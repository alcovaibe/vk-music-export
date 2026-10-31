package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class TrackMetadataDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val dir=File(app.cacheDir,name).apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}} to dir
    }
    private fun track(n: Int)=NativeTrack("12_${99600+n}","Synthetic song $n","CI artist",1)
    private fun task(n: Int)=ExportLayout.task(track(n),listOf("Моя музыка","Metadata CI $n"),1,ExportSource(12,"library","my-$n"))
    private fun fixture(dir: File,ext: String)=File(dir,"input.$ext").apply{inst.context.assets.open("native-audio/tone.$ext").use{writeBytes(it.readBytes())}}
    private suspend fun publish(dir: File,shared: SharedAudioStore,store: AudioExportStore,task: AudioExportTask,ext: String): PublishedAudio {
        val output=File(dir,"out.$ext");val media=AndroidAudioProcessor().process(fixture(dir,ext),output,task.track,null)
        return shared.publish(task,TransferResult(output,media)){store.prepared(task,it)}.also{store.done(task,it)}
    }
    private fun playlist(store: AudioExportStore,source: ExportSource): String {
        val uri=Json.parseToJsonElement(store.checkpoint("m3u:"+source.key)!!).jsonObject.getValue("uri").jsonPrimitive.content
        return app.contentResolver.openInputStream(Uri.parse(uri))!!.use{it.readBytes().toString(Charsets.UTF_8)}
    }
    private fun clean(store: AudioExportStore,rows: List<PublishedAudio>) {
        rows.distinctBy{it.uri}.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
        for((_,value)in store.checkpoints("m3u:")) {
            val uri=Json.parseToJsonElement(value).jsonObject.getValue("uri").jsonPrimitive.content
            app.contentResolver.delete(Uri.parse(uri),null,null)
        }
    }
    @Test fun newDownloadsUseResolvedVersionsInNamesTagsAndPlaylistsForBothCodecs()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("metadata-download");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val tasks=listOf(task(1),task(2));store.replace(tasks,account=12)
            val client=NetworkClients.media().newBuilder().addInterceptor{chain->
                val ext=if(chain.request().url.encodedPath.endsWith("mp3"))"mp3"else "m4a"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body(fixture(dir,ext).readBytes().toResponseBody((if(ext=="mp3")"audio/mpeg"else "audio/mp4").toMediaType())).build()
            }.build()
            val api=object: MusicApi {
                override suspend fun library(access: MusicAccess): NativeProbe=error("No real VK")
                override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog")
                override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
                override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Use resolved metadata")
                override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track.copy(subtitle="Cover sped up"),
                    "https://audio.userapi.com/tone."+if(track.id.endsWith("1"))"mp3"else "m4a")
            }
            val engine=AudioExportEngine(store,shared,File(dir,"transfer"),api,AudioTransfer(client,observe={_,_->}),
                credential={MusicAccess("a".repeat(64),"CI/1")},artwork=ArtworkSource{_,_->null},observe={_,_->})
            engine.recover();store.status("QUEUED");engine.run{}
            tasks.forEach{task->
                val row=store.existing(task.key)?:error("Missing download");rows.add(row)
                assertTrue(row.displayName.contains("(Cover sped up)"));assertTrue(shared.valid(row))
                MediaMetadataRetriever().use{it.setDataSource(app,Uri.parse(row.uri));assertEquals(task.track.title+" (Cover sped up)",it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))}
                assertTrue(playlist(store,task.source!!).contains(task.track.title+" (Cover sped up)"))
            }
        }finally{clean(store,rows);store.close();dir.deleteRecursively()}
    }
    @Test fun retrofitUpdatesSharedMembershipsOldPlaylistsAndBothCodecsWithoutChangingAudioOrCover()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("metadata-retrofit");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val tasks=listOf(task(3),task(4))
            val aliases=tasks.mapIndexed{i,t->ExportLayout.task(t.track,listOf("Плейлисты","Metadata CI alias $i"),1,ExportSource(12,"catalog","playlist:12:${900+i}"))}
            store.replace(tasks+aliases,account=12)
            for((i,ext)in listOf("mp3","m4a").withIndex()) {
                val row=publish(dir,shared,store,tasks[i],ext);rows.add(row)
                val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
                val image=try{CoverImage(ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray(),"image/png")}finally{bitmap.recycle()}
                ArtworkRepair(context,store,shared).add(row,ArtworkSource{_,_->image}){MusicAccess("a".repeat(64),"CI/1")}
                store.done(aliases[i],store.link(aliases[i],store.existing(row.taskKey)!!))
            }
            SharedPlaylistStore(context,store).publishAll()
            store.replace(emptyList(),account=12);store.clearRecentFiles()
            for(row in rows) {
                val before=store.existing(row.taskKey)!!;val original=File(dir,"before.${row.extension}");shared.copyTo(before,original)
                val fingerprint=EmbeddedArtwork.audioFingerprint(original,row.extension)
                assertTrue(TrackMetadataRepair(context,store,shared).update(before,before.track.copy(subtitle="hehehe Cover sped up")))
                val after=store.existing(row.taskKey)!!
                assertEquals(row.uri,after.uri);assertEquals(row.relativePath,after.relativePath);assertTrue(shared.valid(after))
                val output=File(dir,"after.${row.extension}");shared.copyTo(after,output)
                assertEquals(fingerprint,EmbeddedArtwork.audioFingerprint(output,row.extension));assertTrue(EmbeddedArtwork.hasCover(output,row.extension))
                assertTrue(store.savedFiles().filter{it.uri==row.uri}.all{it.track.subtitle=="hehehe Cover sped up"&&it.sha256==after.sha256})
                assertFalse(TrackMetadataRepair(context,store,shared).update(after,after.track))
            }
            SharedPlaylistStore(context,store).repairLocations()
            for(t in tasks+aliases)assertTrue(playlist(store,t.source!!).contains("(hehehe Cover sped up)"))
            assertEquals(0,store.snapshot().total);assertTrue(store.snapshot().recent.isEmpty())
        }finally{clean(store,rows);store.close();dir.deleteRecursively()}
    }
    @Test fun tornWriteRestoresTheOriginalFileAndACompletedWriteCanBeRecovered()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("metadata-recovery");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val task=task(5);store.replace(listOf(task),account=12);val row=publish(dir,shared,store,task,"mp3");rows.add(row)
            val fresh=row.track.copy(subtitle="slowed")
            val broken=TrackMetadataRepair(context,store,shared,replace={record,_->
                val fragment=File(dir,"partial").apply{writeBytes(byteArrayOf(1,2,3))};shared.overwriteMetadata(record,fragment);throw IOException("Synthetic torn write")
            })
            try{broken.update(row,fresh);fail("Must restore") }catch(_: IOException){}
            assertEquals(row,store.existing(row.taskKey));assertTrue(shared.valid(row))
            val interrupted=TrackMetadataRepair(context,store,shared,replace={record,file->shared.overwriteMetadata(record,file);throw IOException("Synthetic lost completion")})
            try{interrupted.update(row,fresh);fail("Injected failure") }catch(_: IOException){}
            val after=store.existing(row.taskKey)!!;assertEquals("slowed",after.track.subtitle);assertTrue(shared.valid(after))
            assertEquals("slowed",store.taskRows().single().first.track.subtitle)
            TrackMetadataRepair(context,store,shared).recover();assertEquals(after,store.existing(row.taskKey))
            val next=after.track.copy(subtitle="slowed + reverb")
            var interruptedAfterCommit=false
            try {
                TrackMetadataRepair(context,store,shared,afterIndex={throw AssertionError("Synthetic process death after DB commit")}).update(after,next)
            }catch(_: AssertionError){interruptedAfterCommit=true}
            assertTrue(interruptedAfterCommit)
            val journal=File(context.noBackupFilesDir,"track-metadata-repair/transaction.json")
            assertTrue(journal.exists())
            val committed=store.existing(row.taskKey)!!
            TrackMetadataRepair(context,store,shared).recover()
            assertEquals(committed,store.existing(row.taskKey));assertTrue(shared.valid(committed))
            assertFalse(journal.exists())
        }finally{clean(store,rows);store.close();dir.deleteRecursively()}
    }
    @Test fun foreignIdChangedFileAndUnverifiedLocalReplacementAreNotEdited()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("metadata-no-guess");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val task=task(6);store.replace(listOf(task),account=12);val row=publish(dir,shared,store,task,"mp3");rows.add(row)
            val repair=TrackMetadataRepair(context,store,shared)
            try{repair.update(row,row.track.copy(id="12_999",subtitle="sped up"));fail("Wrong ID") }catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
            store.imported(row.copy(catalogueVerified=false))
            try{repair.update(store.existing(row.taskKey)!!,row.track.copy(subtitle="sped up"));fail("Unverified audio") }catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
            store.imported(row)
            val modified=File(dir,"modified").apply{writeBytes(byteArrayOf(7,8,9))};shared.overwrite(row,modified)
            try{repair.update(row,row.track.copy(subtitle="sped up"));fail("Changed file") }catch(_: SavedFileFailure){}
            assertEquals(row,store.existing(row.taskKey))
            assertArrayEquals(byteArrayOf(7,8,9),app.contentResolver.openInputStream(Uri.parse(row.uri))!!.use{it.readBytes()})
        }finally{clean(store,rows);store.close();dir.deleteRecursively()}
    }
    @Test fun cancellationBeforeWritingLeavesBytesNamesAndIndexUntouchedAndCollisionsKeepBothFiles()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("metadata-collision");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val task=task(7);store.replace(listOf(task),account=12);val row=publish(dir,shared,store,task,"mp3");rows.add(row)
            val fresh=row.track.copy(subtitle="sped up")
            try{TrackMetadataRepair(context,store,shared,beforeWrite={throw CancellationException()}).update(row,fresh);fail("Cancelled") }catch(_: CancellationException){}
            assertEquals(row,store.existing(row.taskKey));assertTrue(shared.valid(row))
            val otherTask=task.copy(key="synthetic-other",track=fresh.copy(id="12_99888"))
            val other=publish(dir,shared,store,otherTask,"mp3");rows.add(other)
            assertTrue(TrackMetadataRepair(context,store,shared).update(row,fresh))
            val after=store.existing(row.taskKey)!!
            assertTrue(after.displayName.endsWith("(sped up) (2).mp3"));assertNotEquals(other.displayName,after.displayName)
            assertTrue(shared.valid(after));assertTrue(shared.valid(other))
        }finally{clean(store,rows);store.close();dir.deleteRecursively()}
    }
}

@RunWith(AndroidJUnit4::class)
class TrackMetadataUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun resultsDistinguishUpdatedCurrentUnavailableSkippedAndFailedFiles() {
        awaitTestWindowFocus(ui.activity)
        ui.setContent{MusicTheme{TrackMetadataSheet(TrackMetadataState(visible=true,total=5,processed=5,updated=1,current=1,
            unavailable=1,skipped=1,failed=1,message="Проверка завершена.",issues=listOf(TrackMetadataIssue("Synthetic (2).mp3","ВК не вернул запись по сохранённому ID."))),{}, {}, {})}}
        ui.onNodeWithTag("track_metadata_sheet").assertIsDisplayed()
        ui.onNodeWithTag("track_metadata_count").performScrollTo().assertTextEquals("Обработано: 5/5")
        ui.onNodeWithTag("track_metadata_issue_0").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("track_metadata_start").assertIsEnabled()
    }
}
