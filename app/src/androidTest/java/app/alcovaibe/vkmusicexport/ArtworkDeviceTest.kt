package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
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
class ArtworkDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val access=MusicAccess("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val dir=File(app.cacheDir,name).apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}} to dir
    }
    private fun fixture(dir: File,ext: String)=File(dir,"original.$ext").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.$ext").use{writeBytes(it.readBytes())}}
    private fun image(): CoverImage {
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.MAGENTA)}
        return try{val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);CoverImage(out.toByteArray(),"image/jpeg")}finally{bitmap.recycle()}
    }
    private fun track(id: Int=1)=NativeTrack("12_${99800+id}","Artwork CI $id","CI",1)
    private fun picture(row: PublishedAudio) {
        MediaMetadataRetriever().use{m->
            m.setDataSource(app,Uri.parse(row.uri));assertTrue((m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)>0)
            assertNotNull(m.embeddedPicture)
        }
    }
    private fun api()=object: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("No real VK")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No real VK")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No real VK")
        override suspend fun media(access: MusicAccess,track: NativeTrack)="https://audio.userapi.com/"+if(track.id.endsWith("1"))"tone.mp3"else "tone.m4a"
        override suspend fun artwork(access: MusicAccess,track: NativeTrack)="https://sun9.userapi.com/cover.jpg"
    }
    @Test fun newDownloadsEmbedRealJpegInMp3AndM4aAndImageFailureKeepsAudio()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,dir)=isolated("artwork-download");val rows=mutableListOf<PublishedAudio>()
        val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        try {
            val tasks=(1..2).map{ExportLayout.task(track(it),listOf("Моя музыка","Artwork download CI"),it)}
            val client=NetworkClients.api().newBuilder().addInterceptor{chain->
                val ext=if(chain.request().url.encodedPath.endsWith("mp3"))"mp3"else "m4a"
                val bytes=fixture(dir,ext).readBytes()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body(bytes.toResponseBody((if(ext=="mp3")"audio/mpeg"else "audio/mp4").toMediaType())).build()
            }.build()
            val engine=AudioExportEngine(store,shared,File(dir,"transfer"),api(),AudioTransfer(client,observe={_,_->}),
                credential={access},artwork=ArtworkSource{_,_->image()},observe={_,_->})
            engine.recover();store.replace(tasks);store.status("QUEUED");engine.run{}
            tasks.forEach{rows.add(store.existing(it.key)?:error("missing audio"))}
            assertEquals(2,store.snapshot().done);assertEquals(2,store.snapshot().covered)
            rows.forEach{assertTrue(it.coverEmbedded);assertTrue(shared.valid(it));picture(it)}
            val original=fixture(dir,"mp3");val before=fileSha256(original)
            val result=DownloadArtwork(ArtworkSource{_,_->throw MusicFailure(MusicError.HTTP)}).add(TransferResult(original,ProcessedAudio("mp3","audio/mpeg",1200)),track()){access}
            assertFalse(result.coverEmbedded);assertEquals(before,fileSha256(result.file))
        }finally{rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
    @Test fun retrofitKeepsUriNamesAudioBytesAndClearedHistoryAndSkipsExistingCovers()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,dir)=isolated("artwork-retrofit");val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try {
            val tasks=(1..2).map{ExportLayout.task(track(it),listOf("Моя музыка","Artwork retrofit CI"),it)};store.replace(tasks)
            for((i,ext)in listOf("mp3","m4a").withIndex()) {
                val input=fixture(dir,ext);val task=tasks[i]
                // The CDN fixture is fragmented MP4. Old app exports are normal MediaMuxer M4A.
                val exported=File(dir,"exported.$ext")
                val media=AndroidAudioProcessor().process(input,exported,task.track,null)
                val row=shared.publish(task,TransferResult(exported,media)){store.prepared(task,it)}
                rows.add(row);store.done(task,row)
            }
            store.clearRecentFiles();var requests=0
            val repair=ArtworkRepair(context,store,shared)
            assertFalse(repair.add(rows[0],ArtworkSource{_,_->null}){access});assertTrue(shared.valid(rows[0]))
            for(row in rows) {
                val before=File(dir,"before.${row.extension}");shared.copyTo(row,before)
                val payload=EmbeddedArtwork.payloadDigest(before,row.extension)
                assertTrue(repair.add(row,ArtworkSource{_,_->requests++;image()}){access})
                val after=store.existing(row.taskKey)!!;assertEquals(row.uri,after.uri);assertEquals(row.displayName,after.displayName);assertEquals(row.relativePath,after.relativePath)
                assertTrue(shared.valid(after));picture(after)
                val copy=File(dir,"after.${row.extension}");shared.copyTo(after,copy)
                assertEquals(payload,EmbeddedArtwork.payloadDigest(copy,row.extension))
                assertFalse(repair.add(after,ArtworkSource{_,_->error("must skip existing cover")}){access})
                assertTrue(store.snapshot().recent.isEmpty())
            }
            assertEquals(2,requests);assertEquals(2,store.snapshot().covered)
            val changed=store.existing(rows.first().taskKey)!!
            app.contentResolver.openOutputStream(Uri.parse(changed.uri),"wt")!!.use{it.write(byteArrayOf(9,8,7))}
            try{repair.add(changed,ArtworkSource{_,_->error("modified file must not reach VK")}){access};fail("modified file")}
            catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
            assertArrayEquals(byteArrayOf(9,8,7),app.contentResolver.openInputStream(Uri.parse(changed.uri))!!.use{it.readBytes()})
        }finally{rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
    private class ProcessDeath: Error()
    @Test fun interruptedPartialWriteRestoresBackupAndCompletedWriteAdoptsNewHash()=runBlocking<Unit>(Dispatchers.IO) {
        for(mode in listOf("partial","complete","io-error")) {
            val (context,dir)=isolated("artwork-recovery-$mode");var store=AudioExportStore(context);val shared=SharedAudioStore(context);var row: PublishedAudio?=null
            try {
                val task=ExportLayout.task(track(3),listOf("Моя музыка","Artwork recovery CI"),1);store.replace(listOf(task))
                val input=fixture(dir,"mp3")
                val saved=shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(task,it)};row=saved;store.done(task,saved)
                val repair=ArtworkRepair(context,store,shared){record,file->
                    if(mode=="complete")shared.overwrite(record,file)
                    else app.contentResolver.openOutputStream(Uri.parse(record.uri),"wt")!!.use{it.write(byteArrayOf(1,2,3))}
                    if(mode=="io-error")throw IOException("Synthetic local write error")else throw ProcessDeath()
                }
                try{repair.add(saved,ArtworkSource{_,_->image()}){access};fail("simulated interruption")}
                catch(_: ProcessDeath){}catch(e: IOException){assertEquals("io-error",mode)}
                store.close();store=AudioExportStore(context)
                ArtworkRepair(context,store,shared).recover()
                val actual=store.existing(task.key)!!;assertTrue(shared.valid(actual));assertEquals(saved.uri,actual.uri)
                if(mode=="complete"){assertTrue(actual.coverEmbedded);assertNotEquals(saved.sha256,actual.sha256);picture(actual)}
                else {assertEquals(saved,actual);assertEquals(saved.sha256,actual.sha256)}
                assertFalse(File(context.noBackupFilesDir,"artwork-repair/transaction.json").exists())
                assertFalse(File(context.noBackupFilesDir,"artwork-repair/original.audio").exists())
            }finally{row?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
        }
    }
    @Test fun imageTransportHasNoApiCredentialsAndRejectsRedirectsAndInvalidPictures()=runBlocking<Unit>(Dispatchers.IO) {
        var requests=0
        val bytes=image().bytes
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val req=chain.request();requests++
            assertNull(req.header("Authorization"));assertNull(req.header("Cookie"));assertFalse(req.url.toString().contains(access.token));assertNotEquals(access.agent,req.header("User-Agent"))
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val loader=ArtworkLoader(api(),client)
        assertNotNull(loader.load(access,track()));assertNotNull(loader.load(access,track()));assertEquals(1,requests)
        val redirect=NetworkClients.api().newBuilder().addInterceptor{chain->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Synthetic").header("Location","https://evil.test/cover").body(byteArrayOf().toResponseBody()).build()
        }.build()
        try{ArtworkLoader(api(),redirect).load(access,track());fail("unsafe redirect")}catch(e: MusicFailure){assertEquals(MusicError.MEDIA_URL,e.code)}
        assertThrows(MusicFailure::class.java){ArtworkLoader.decode(byteArrayOf(1,2,3))}
        val huge=Bitmap.createBitmap(4097,1,Bitmap.Config.ARGB_8888)
        val out=ByteArrayOutputStream();huge.compress(Bitmap.CompressFormat.PNG,100,out);huge.recycle()
        assertThrows(MusicFailure::class.java){ArtworkLoader.decode(out.toByteArray())}
    }
}

@RunWith(AndroidJUnit4::class)
class ArtworkUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun editingStartsOnlyOnExplicitClickAndProgressCanBeDismissedWithoutStopping() {
        var state by mutableStateOf(ArtworkState(visible=true));var starts=0;var stops=0
        awaitTestWindowFocus(ui.activity)
        ui.setContent{MusicTheme{if(state.visible)ArtworkSheet(state,
            onStart={starts++;state=state.copy(running=true,total=10,processed=3,added=2,missing=1)},
            onStop={stops++;state=state.copy(running=false,message="Прервано")},onDismiss={state=state.copy(visible=false)})}}
        ui.onNodeWithTag("artwork_sheet").assertIsDisplayed();assertEquals(0,starts)
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
        ui.onNodeWithTag("artwork_start").assertIsDisplayed()
        val inst=InstrumentationRegistry.getInstrumentation();inst.waitForIdleSync();inst.uiAutomation.waitForIdle(300,5000)
        val bitmap=inst.uiAutomation.takeScreenshot()
        val file=File(File(inst.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()},"saved-artwork.png")
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/saved-artwork.png").forEach{
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(it)).use{input->input.readBytes()}}
        ui.onNodeWithTag("artwork_start").performClick();assertEquals(1,starts)
        ui.onNodeWithText("Обработано: 3/10").assertIsDisplayed()
        ui.onNodeWithText("Свернуть").performClick();ui.runOnIdle{assertEquals(0,stops);assertTrue(state.running)}
        ui.runOnIdle{state=state.copy(visible=true)}
        ui.onNodeWithTag("artwork_stop").performClick();assertEquals(1,stops)
        ui.onNodeWithTag("artwork_result").assertIsDisplayed()
    }
}
