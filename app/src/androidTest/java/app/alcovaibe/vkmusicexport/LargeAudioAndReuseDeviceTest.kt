package app.alcovaibe.vkmusicexport

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LargeAudioAndReuseDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val access=MusicAccess("a".repeat(64),"Synthetic")
    private val noApi=object: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("Unexpected library request")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Unexpected catalogue request")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Unexpected playlist request")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Saved audio must not be downloaded")
    }
    private val tone get()=instrumentation.context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
    private fun track(n: Int)=NativeTrack("12_${88000+n}","Reuse song $n","CI",1)
    private fun tasks(title: String,rows: List<NativeTrack>,kind: PlaylistKind=PlaylistKind.PLAYLIST,id: Long=123)=
        ExportLayout.playlist(NativePage(rows,rows.size),NativePlaylist(12,id,title,rows.size,null,null,null,null,kind))
    private suspend fun scenario(block:suspend(ContextWrapper,File,AudioExportStore,SharedAudioStore,MutableSet<String>)->Unit) {
        val dir=File(app.cacheDir,"large-reuse-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object: ContextWrapper(app) {
            override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
        }
        val store=AudioExportStore(context);val shared=SharedAudioStore(context);val directories=mutableSetOf<String>()
        try {block(context,dir,store,shared,directories)}finally {
            store.close()
            val collection=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            for(path in directories) {
                val ids=app.contentResolver.query(collection,arrayOf("_id"),"relative_path=?",arrayOf(path),null)?.use{c->
                    buildList{while(c.moveToNext())add(c.getLong(0))}
                }.orEmpty()
                ids.forEach{app.contentResolver.delete(ContentUris.withAppendedId(collection,it),null,null)}
            }
            dir.deleteRecursively()
        }
    }
    private suspend fun publish(dir: File,store: AudioExportStore,shared: SharedAudioStore,task: AudioExportTask,marker: String): PublishedAudio {
        val input=File(dir,"fixture.mp3").apply{writeBytes(tone);appendBytes("TAG".toByteArray()+marker.toByteArray().copyOf(125))}
        return shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200)),store.directory(task)){}.also{store.done(task,it)}
    }
    private fun transfer(request:(String)->Unit)=AudioTransfer(NetworkClients.media().newBuilder().addInterceptor{chain->
        request(chain.request().url.encodedPath.removePrefix("/"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
            .body(tone.toResponseBody()).build()
    }.build(),observe={_,_->})
    private suspend fun run(dir: File,store: AudioExportStore,shared: SharedAudioStore,api: MusicApi=noApi,transfer: AudioTransfer=transfer{error("Unexpected media request")}) {
        store.status("QUEUED")
        AudioExportEngine(store,shared,File(dir,"tmp"),api,transfer,credential={access},observe={_,_->}).run{}
    }

    @Test fun mp3LargerThan100MiBPassesRealParserAndPublishesWithOriginalAudio()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            // A real MPEG-1 Layer III frame, streamed 260000 times (~108 MB / 113 minutes).
            // No full-file byte array, mock processor, sparse output or network account is used.
            val frame=tone.takeLast(418).toByteArray()
            assertEquals(255,frame[0].toInt() and 255);assertEquals(0xfb,frame[1].toInt() and 255)
            val count=260000L;val size=frame.size*count
            val row=track(1).copy(duration=(count*1152/44100).toInt())
            val task=tasks("CI large ${UUID.randomUUID()}",listOf(row)).single()
            directories.add(store.directory(task));store.replace(listOf(task))
            var requests=0
            val chunk=ByteArray(frame.size*32){frame[it%frame.size]}
            val client=NetworkClients.media().newBuilder().addInterceptor{chain->
                requests++
                val body=object: ResponseBody() {
                    private val data=object: Source {
                        var remaining=size
                        override fun read(sink: Buffer,byteCount: Long): Long {
                            if(remaining==0L)return -1
                            val n=minOf(remaining,byteCount,chunk.size.toLong()).toInt()
                            // Reads need not align to a frame boundary.
                            val offset=((size-remaining)%chunk.size).toInt()
                            val first=minOf(n,chunk.size-offset);sink.write(chunk,offset,first)
                            if(first<n)sink.write(chunk,0,n-first)
                            remaining-=n;return n.toLong()
                        }
                        override fun timeout()=Timeout.NONE
                        override fun close()=Unit
                    }.buffer()
                    override fun contentType()="audio/mpeg".toMediaType()
                    override fun contentLength()=size
                    override fun source()=data
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body).build()
            }.build()
            assertEquals(0,client.callTimeoutMillis);assertTrue(client.readTimeoutMillis>0)
            val api=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,"https://audio.userapi.com/large")
            }
            run(dir,store,shared,api,AudioTransfer(client,observe={_,_->}))
            assertEquals("FINISHED",store.snapshot().status);assertEquals(1,requests)
            val saved=store.existing(task.key)!!
            assertTrue(saved.bytes>100L*1024*1024);assertTrue(shared.valid(saved))
            assertTrue(AudioCompleteness.matchesCatalogue(saved.durationMs,row.duration))
            assertTrue(File(dir,"tmp").listFiles().orEmpty().isEmpty())
            run(dir,store,shared) // Repeating a finished run must not issue any VK/media request.
            assertEquals(saved.uri,store.existing(task.key)!!.uri)
        }
    }

    @Test fun retryAfterReopenDownloadsOnlyFailuresAndNewSelectionSkipsAllSavedTracks()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{context,dir,store,shared,directories->
            val rows=(1..6).map(::track);val title="CI retry ${UUID.randomUUID()}";val tasks=tasks(title,rows)
            directories.add(store.directory(tasks.first()));store.replace(tasks)
            val requests=mutableListOf<String>()
            val api=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio {
                    if(track.id in setOf(rows[1].id,rows[4].id))throw TrackUnavailable(TrackAccessIssue.RESTRICTED)
                    return ResolvedAudio(track,"https://audio.userapi.com/${track.id}")
                }
            }
            run(dir,store,shared,api,transfer{requests.add(it)})
            assertEquals(4,store.snapshot().done);assertEquals(2,store.snapshot().failed)
            val originals=store.savedFiles().associate{it.taskKey to it.uri};requests.clear()
            val nextApi=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,"https://audio.userapi.com/${track.id}")
            }
            AudioExportStore(context).use{reopened->
                reopened.retryFailed();run(dir,reopened,shared,nextApi,transfer{requests.add(it)})
                assertEquals(6,reopened.snapshot().done);assertEquals(0,reopened.snapshot().failed)
                assertEquals(setOf(rows[1].id,rows[4].id),requests.toSet());assertEquals(2,requests.size)
                originals.forEach{(key,uri)->assertEquals(uri,reopened.existing(key)!!.uri)}
                val again=tasks(title,rows.reversed()+track(7));reopened.replace(again);requests.clear()
                run(dir,reopened,shared,nextApi,transfer{requests.add(it)})
                assertEquals(listOf(track(7).id),requests);assertEquals(7,reopened.snapshot().done)
            }
        }
    }

    @Test fun renamedAlbumFileAndMoveWithNewMediaIdAreReusedWithoutVkRequests()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            val title="CI moved ${UUID.randomUUID()}";val task=tasks(title,listOf(track(8)),PlaylistKind.ALBUM).single()
            directories.add(store.directory(task));store.replace(listOf(task))
            val before=publish(dir,store,shared,task,dir.name)
            val destination=ExportLayout.ROOT+"Плейлисты/"+title+"/";directories.add(destination)
            app.contentResolver.update(Uri.parse(before.uri),ContentValues().apply{
                put(MediaStore.MediaColumns.RELATIVE_PATH,destination);put(MediaStore.MediaColumns.DISPLAY_NAME,"Moved.mp3")
            },null,null)
            run(dir,store,shared)
            val moved=store.existing(task.key)!!
            assertEquals(before.uri,moved.uri);assertEquals(destination,moved.relativePath);assertTrue(shared.valid(moved))
            // A file manager may implement a move as copy + delete, assigning a new MediaStore ID.
            val copy=shared.copy(task,moved,destination){}
            app.contentResolver.delete(Uri.parse(moved.uri),null,null)
            store.replace(listOf(task));run(dir,store,shared)
            val recovered=store.existing(task.key)!!
            assertEquals(copy.uri,recovered.uri);assertEquals(before.sha256,recovered.sha256);assertTrue(shared.valid(recovered))
            assertEquals("FINISHED",store.snapshot().status)
        }
    }

    @Test fun playlistReplacementFromKnownAlbumIsAdoptedWithoutAnotherDownloadOrCopy()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            val title="CI replacement ${UUID.randomUUID()}";val row=track(9)
            val album=tasks(title,listOf(row),PlaylistKind.ALBUM,id=901).single()
            val albumFile=publish(dir,store,shared,album,dir.name+"album")
            val playlist=tasks(title,listOf(row),id=902).single();store.replace(listOf(playlist))
            val playlistFile=publish(dir,store,shared,playlist,dir.name+"playlist")
            directories.add(albumFile.relativePath);directories.add(playlistFile.relativePath)
            assertNotEquals(albumFile.sha256,playlistFile.sha256)
            app.contentResolver.delete(Uri.parse(playlistFile.uri),null,null)
            val replacement=shared.copy(playlist,albumFile,playlistFile.relativePath){}
            app.contentResolver.delete(Uri.parse(albumFile.uri),null,null)
            store.retryFailed();run(dir,store,shared)
            val adopted=store.existing(playlist.key)!!
            assertEquals(replacement.uri,adopted.uri);assertEquals(albumFile.sha256,adopted.sha256)
            assertTrue(shared.valid(adopted));assertEquals("FINISHED",store.snapshot().status)
            assertEquals(1,store.savedFiles().size)
        }
    }

    @Test fun missingOrModifiedSavedFilesAreDownloadedAgainWhileIntactFilesKeepTheirUris()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            val tasks=tasks("CI changed ${UUID.randomUUID()}",(10..12).map(::track));store.replace(tasks)
            val files=tasks.map{publish(dir,store,shared,it,dir.name+it.track.id)}
            directories.add(files.first().relativePath)
            app.contentResolver.delete(Uri.parse(files[0].uri),null,null)
            app.contentResolver.openOutputStream(Uri.parse(files[1].uri),"wt")!!.use{it.write("USER MODIFIED".toByteArray())}
            val requests=mutableListOf<String>()
            val api=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,"https://audio.userapi.com/${track.id}")
            }
            run(dir,store,shared,api,transfer{requests.add(it)})
            assertEquals(tasks.take(2).map{it.track.id},requests)
            assertEquals(files[2].uri,store.existing(tasks[2].key)!!.uri)
            assertNotEquals(files[1].uri,store.existing(tasks[1].key)!!.uri)
            assertEquals("USER MODIFIED",app.contentResolver.openInputStream(Uri.parse(files[1].uri))!!.bufferedReader().use{it.readText()})
            assertEquals("FINISHED",store.snapshot().status)
        }
    }
}
