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
import kotlinx.serialization.json.*
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
            assertTrue(store.snapshot().failures.all{it.details?.accessReason=="RESTRICTED"})
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
            assertEquals(2,store.savedFiles().size)
            assertEquals(setOf(replacement.uri),store.savedFiles().map{it.uri}.toSet())
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

    private fun memberships(title: String,row: NativeTrack): List<AudioExportTask> =
        tasks(title,listOf(row),PlaylistKind.ALBUM,id=930)+tasks(title,listOf(row,row),id=931)+
            listOf(ExportLayout.task(row,listOf("Моя музыка",title),1,ExportSource(0,"library","my")))
    private fun playlistUri(store: AudioExportStore,task: AudioExportTask)=Uri.parse(Json.parseToJsonElement(
        store.checkpoint("m3u:"+task.source!!.key)!!).jsonObject.getValue("uri").jsonPrimitive.content)
    private fun playlistText(store: AudioExportStore,task: AudioExportTask)=app.contentResolver
        .openInputStream(playlistUri(store,task))!!.bufferedReader().use{it.readText()}

    @Test fun albumPlaylistLibraryAndRepeatedPositionsShareOneUriWithoutVkOrMediaRequests()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            val queue=memberships("CI shared ${UUID.randomUUID()}",track(21))
            queue.forEach{directories.add(store.directory(it))};store.replace(queue)
            val first=publish(dir,store,shared,queue.first(),dir.name);store.clearRecentFiles()
            run(dir,store,shared)
            assertEquals(4,store.snapshot().done);assertEquals("FINISHED",store.snapshot().status)
            assertEquals(setOf(first.uri),store.savedFiles().map{it.uri}.toSet())
            assertEquals(queue.map{it.source},queue.map{store.existing(it.key)!!.source})
            assertEquals(listOf(1,2),queue.filter{it.source==queue[1].source}.map{store.existing(it.key)!!.occurrence})
            assertTrue(store.snapshot().recent.isEmpty())
            val playlistDir=store.directory(queue[1])
            val playlistUri=app.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf("_id"),"relative_path=? AND _display_name=?",arrayOf(playlistDir,"_Порядок.m3u8"),null)!!.use{c->
                    assertTrue(c.moveToFirst());ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),c.getLong(0))
                }
            val body=app.contentResolver.openInputStream(playlistUri)!!.bufferedReader().use{it.readText()}
            val entries=body.lineSequence().filter{it.isNotBlank()&&!it.startsWith("#")}.toList()
            assertEquals(2,entries.size);assertEquals(entries[0],entries[1]);assertTrue(entries[0].contains("../"))
            assertTrue(body.contains("#VKME-SAVED:2"));assertFalse(body.contains("#VKME-INCOMPLETE"))
            // An already verified local recording remains usable even when VK now restricts the item.
            val restricted=queue.last().copy(track=queue.last().track.copy(restricted=true))
            store.replace(listOf(restricted));run(dir,store,shared)
            assertEquals(first.uri,store.existing(restricted.key)!!.uri);assertEquals(1,store.snapshot().done)
        }
    }

    @Test fun sharedArtworkAndMoveUpdateAllReferencesAtomicallyAcrossDatabaseReopen()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{context,dir,store,shared,directories->
            val queue=memberships("CI shared move ${UUID.randomUUID()}",track(22))
            queue.forEach{directories.add(store.directory(it))};store.replace(queue)
            val first=publish(dir,store,shared,queue.first(),dir.name);run(dir,store,shared);store.clearRecentFiles()
            val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
            val image=try{bitmap.eraseColor(android.graphics.Color.MAGENTA)
                val out=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out)
                CoverImage(out.toByteArray(),"image/jpeg")
            }finally{bitmap.recycle()}
            assertTrue(ArtworkRepair(context,store,shared).add(first,ArtworkSource{_,_->image}){access})
            val tagged=store.existing(first.taskKey)!!
            assertNotEquals(first.sha256,tagged.sha256);assertEquals(4,store.snapshot().covered)
            store.savedFiles().forEach{assertEquals(tagged.sha256,it.sha256);assertTrue(it.coverEmbedded);assertTrue(shared.valid(it))}
            val destination=ExportLayout.ROOT+"Плейлисты/CI moved shared ${UUID.randomUUID()}/";directories.add(destination)
            val oldPlaylist=playlistText(store,queue[1])
            // The former album/playlist must be repaired even after another selection replaces the queue.
            store.replace(listOf(queue.last()))
            val replacement=shared.copy(queue.first(),tagged,destination){}
            app.contentResolver.delete(Uri.parse(tagged.uri),null,null)
            run(dir,store,shared)
            assertEquals(setOf(replacement.uri),store.savedFiles().map{it.uri}.toSet())
            assertTrue(store.snapshot().recent.isEmpty());assertEquals(1,store.snapshot().covered)
            val repaired=playlistText(store,queue[1]);assertNotEquals(oldPlaylist,repaired)
            assertTrue(repaired.contains("CI moved shared"));assertEquals(2,repaired.lineSequence().count{it.startsWith("#EXTINF:")})
            assertTrue(store.checkpoints("m3u-relocation:").isEmpty())
            AudioExportStore(context).use{reopened->
                assertEquals(1,reopened.snapshot().done);reopened.savedFiles().forEach{assertTrue(shared.valid(it))}
                reopened.forgetFile(reopened.existing(queue[1].key)!!)
                assertEquals(3,reopened.savedFiles().size);assertTrue(shared.valid(reopened.existing(queue.last().key)!!))
            }
        }
    }

    @Test fun missingSharedFileIsDownloadedOnceForAllMemberships()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{_,dir,store,shared,directories->
            val queue=memberships("CI shared missing ${UUID.randomUUID()}",track(23))
            queue.forEach{directories.add(store.directory(it))};store.replace(queue)
            val first=publish(dir,store,shared,queue.first(),dir.name);run(dir,store,shared)
            app.contentResolver.delete(Uri.parse(first.uri),null,null)
            val requests=mutableListOf<String>()
            val api=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,"https://audio.userapi.com/${track.id}")
            }
            run(dir,store,shared,api,transfer{requests.add(it)})
            assertEquals(listOf(track(23).id),requests);assertEquals(4,store.snapshot().done)
            assertEquals(1,store.savedFiles().map{it.uri}.distinct().size)
            assertNotEquals(first.uri,store.savedFiles().first().uri)
        }
    }

    @Test fun manualRecoveryShowsReferenceCountAndReplacesAllLinksWithoutDeletingEitherFile()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{context,dir,store,shared,directories->
            val queue=memberships("CI shared selection ${UUID.randomUUID()}",track(24))
            queue.forEach{directories.add(store.directory(it))};store.replace(queue)
            val first=publish(dir,store,shared,queue.first(),dir.name);run(dir,store,shared);store.clearRecentFiles()
            val recovery=SavedFileRecovery(context,store,shared)
            val editedUri=playlistUri(store,queue[1]);val userPlaylist=playlistText(store,queue[1])+"# User edit\n"
            app.contentResolver.openOutputStream(editedUri,"wt")!!.use{it.write(userPlaylist.toByteArray())}
            val preview=recovery.preview(first,Uri.parse(first.uri));assertEquals(4,preview.references)
            val after=recovery.confirm(preview);directories.add(after.relativePath)
            assertNotEquals(first.uri,after.uri);assertEquals(first.sha256,after.sha256)
            assertTrue(shared.valid(first));store.savedFiles().forEach{assertEquals(after.uri,it.uri);assertTrue(shared.valid(it))}
            assertEquals(4,store.snapshot().done);assertTrue(store.snapshot().recent.isEmpty())
            try{SharedPlaylistStore(context,store).repairLocations();fail("An edited playlist must be reported, not overwritten")}
            catch(e: MusicFailure){assertEquals(MusicError.PLAYLIST_CHANGED,e.code)}
            assertEquals(userPlaylist,app.contentResolver.openInputStream(editedUri)!!.bufferedReader().use{it.readText()})
            assertTrue(playlistText(store,queue.first()).contains("Восстановленные"))
            assertTrue(store.checkpoints("m3u-relocation:").isNotEmpty())
        }
    }

    @Test fun equalTitlesWithDifferentIdsAndCatalogueDurationMismatchNeverAutoMerge()=runBlocking<Unit>(Dispatchers.IO) {
        scenario{context,dir,store,shared,directories->
            val row=track(25);val queue=tasks("CI shared identity ${UUID.randomUUID()}",listOf(row,row.copy(id="12_99025")))
            directories.add(store.directory(queue.first()));store.replace(queue)
            val first=publish(dir,store,shared,queue.first(),dir.name)
            val requests=mutableListOf<String>()
            val api=object: MusicApi by noApi {
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,"https://audio.userapi.com/${track.id}")
            }
            run(dir,store,shared,api,transfer{requests.add(it)})
            assertEquals(listOf("12_99025"),requests);assertEquals(2,store.savedFiles().map{it.uri}.distinct().size)
            val wrongDuration=queue.first().copy(track=row.copy(duration=30));store.replace(listOf(wrongDuration));requests.clear()
            run(dir,store,shared,api,transfer{requests.add(it)})
            assertEquals(0,store.snapshot().done);assertEquals(MusicError.INCOMPLETE,store.snapshot().failures.single().error)
            val details=store.snapshot().failures.single().details!!
            assertEquals(30,details.expectedSeconds);assertTrue(details.actualMs!! in 1000L..2500L)
            AudioExportStore(context).use{reopened->assertEquals(details,reopened.snapshot().failures.single().details)}
            assertEquals(listOf(row.id),requests);assertTrue(shared.valid(first))
        }
    }
}
