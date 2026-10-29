package app.alcovaibe.vkmusicexport

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

private fun duplicateTrack(n: Int)=NativeTrack("12_$n","Synthetic song","Synthetic artist",1)
private fun duplicateApi()=object: MusicApi {
    override suspend fun library(access: MusicAccess): NativeProbe=error("No real VK")
    override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No real VK")
    override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No real VK")
    override suspend fun media(access: MusicAccess,track: NativeTrack)="https://audio.userapi.com/${track.id}.mp3"
}

@RunWith(AndroidJUnit4::class)
class DuplicatesUiDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun everyTargetRequiresSeparateConfirmationAndCancelDoesNotDelete() {
        val keep=duplicateTrack(1);val signature=DuplicateSignature("mp3","a".repeat(64))
        val report=DuplicateReport(12,"synthetic",3,3,listOf(MusicDuplicate(keep,duplicateTrack(2),signature),MusicDuplicate(keep,duplicateTrack(3),signature)),emptyList())
        var state by mutableStateOf(DuplicatesState(true,DuplicatePhase.READY,"synthetic",report))
        val sent=mutableListOf<String>()
        ui.setContent{MusicTheme{DuplicatesSheet(state,{},onRequest={state=state.copy(consent=DuplicateConsent(12,"synthetic",it))},
            onConfirm={val target=state.consent!!.duplicate.remove.id;sent.add(target)
                state=state.copy(consent=null,report=state.report!!.let{r->r.copy(duplicates=r.duplicates.map{if(it.remove.id==target)it.copy(status=DuplicateStatus.DELETED)else it})})},
            onCancelConfirm={state=state.copy(consent=null)},onSkip={},onStop={},onDismiss={})}}
        fun request(id: Int){ui.onNodeWithTag("duplicates_list").performScrollToNode(hasTestTag("request_duplicate_12_$id"));ui.onNodeWithTag("request_duplicate_12_$id").performClick()}
        request(2);ui.onNodeWithTag("confirm_duplicate").assertIsDisplayed()
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        val image=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots/duplicate-confirmation.png").apply{parentFile!!.mkdirs()}
        image.outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        for(command in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${image.absolutePath} /data/local/tmp/vk-music-export-screenshots/duplicate-confirmation.png"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        ui.runOnIdle{assertTrue(sent.isEmpty());assertEquals(keep,state.consent!!.duplicate.keep)}
        ui.onNodeWithText("Отмена").performClick();ui.runOnIdle{assertTrue(sent.isEmpty())}
        request(2);ui.onNodeWithTag("confirm_duplicate").performClick()
        ui.runOnIdle{assertEquals(listOf("12_2"),sent);assertEquals(1,state.remaining)}
        ui.onNodeWithTag("confirm_duplicate").assertDoesNotExist()
        request(3);ui.runOnIdle{assertEquals(listOf("12_2"),sent);assertEquals(keep,state.consent!!.duplicate.keep)}
        ui.onNodeWithTag("confirm_duplicate").performClick()
        ui.runOnIdle{assertEquals(listOf("12_2","12_3"),sent);assertEquals(0,state.remaining);assertEquals(2,state.removed)}
    }
    @Test fun pausedScanShowsSavedChecksAndExplicitResumeWithoutAnyDeletion() {
        var resumed=0;var deletes=0
        val state=DuplicatesState(visible=true,phase=DuplicatePhase.BLOCKED,savedChecks=7,
            progress=DuplicateProgress("",reused=3),message="Проверка приостановлена. Готовые проверки сохранены.")
        ui.setContent{MusicTheme{DuplicatesSheet(state,{resumed++},{deletes++},{deletes++},{},{},{},{})}}
        ui.onNodeWithTag("duplicates_checkpoint").assertTextEquals("Сохранено проверок: 7 · использовано повторно: 3")
        ui.onNodeWithTag("confirm_duplicate").assertDoesNotExist()
        ui.onNodeWithText("Продолжить проверку").performClick()
        ui.runOnIdle{assertEquals(1,resumed);assertEquals(0,deletes)}
    }
    @Test fun cleanupButtonFollowsDownloadAllAndExistsOnlyInMyMusic() {
        var state by mutableStateOf(NativeMusicState(initialized=true,probe=NativeProbe(12,NativePage(emptyList(),0))))
        var opened=0
        ui.setContent{MusicTheme{Surface{Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            MusicCatalogPanel(state,true,onSection={state=state.copy(catalogSection=it)},onSelect={},onCatalogPage={},onRefresh={},
                onLibrary={},onCategory={},onClassify={_,_->},onDuplicates={opened++})
        }}}}
        val all=ui.onNodeWithText("Скачать всю мою музыку").fetchSemanticsNode().boundsInRoot
        val duplicates=ui.onNodeWithTag("clean_duplicates").fetchSemanticsNode().boundsInRoot
        val albums=ui.onNodeWithText("Скачать мою музыку и группы по альбомам").fetchSemanticsNode().boundsInRoot
        assertTrue(all.bottom<=duplicates.top);assertTrue(duplicates.bottom<=albums.top)
        ui.onNodeWithTag("clean_duplicates").performClick();ui.runOnIdle{assertEquals(1,opened)}
        ui.onNodeWithTag("catalog_tab_PLAYLISTS").performClick();ui.onNodeWithTag("clean_duplicates").assertDoesNotExist()
        ui.onNodeWithTag("catalog_tab_ALBUMS").performClick();ui.onNodeWithTag("clean_duplicates").assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
class DuplicateHashDeviceTest {
    @Test fun temporaryAudioHashIgnoresCoverAndRemovesTemporaryCopiesWithoutChangingInputs()=runBlocking<Unit>(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        val dir=File(app.cacheDir,"duplicate-hash-test").apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(app){override fun getCacheDir()=dir}
        try {
            val plain=File(dir,"plain.mp3").apply{instrumentation.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
            val image=try{ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray()}finally{bitmap.recycle()}
            val covered=File(dir,"covered.mp3");EmbeddedArtwork.write(plain,covered,"mp3",CoverImage(image,"image/jpeg"))
            val before=listOf(fileSha256(plain),fileSha256(covered));assertNotEquals(before[0],before[1])
            val client=NetworkClients.media().newBuilder().addInterceptor{chain->
                val bytes=(if(chain.request().url.encodedPath.endsWith("12_1.mp3"))plain else covered).readBytes()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .header("Content-Type","audio/mpeg").body(bytes.toResponseBody()).build()
            }.build()
            DuplicateAudioHasher(context,duplicateApi(),AudioTransfer(client,observe={_,_->})).use{hasher->
                val access=MusicAccess("a".repeat(64),"Synthetic")
                val a=hasher.signature(access,duplicateTrack(1));val b=hasher.signature(access,duplicateTrack(2))
                assertEquals("mp3",a.codec);assertEquals(64,a.sha256.length);assertEquals(a,b)
                assertTrue(File(dir,"duplicate-check").listFiles().orEmpty().isEmpty())
            }
            assertFalse(File(dir,"duplicate-check").exists());assertEquals(before,listOf(fileSha256(plain),fileSha256(covered)))
        }finally{dir.deleteRecursively()}
    }
}

@RunWith(AndroidJUnit4::class)
class ArtworkRetryDeviceTest {
    @Test fun readyCoverIsExcludedBeforeReadingFileOrCallingVkEvenWhenFileIsMissing()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val real=instrumentation.targetContext.applicationContext as Application
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(real)}
        val dir=File(real.cacheDir,"artwork-ready-skip").apply{deleteRecursively();mkdirs()}
        val application=object: Application(){
            init{attachBaseContext(real)}
            override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
            override fun getFilesDir()=File(dir,"files").apply{mkdirs()}
            override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        }
        val task=ExportLayout.task(duplicateTrack(999999),listOf("Моя музыка"),1)
        val row=PublishedAudio(task.key,task.track,"content://media/external_primary/audio/media/9223372036854775806",
            "Synthetic.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",1000,100,"a".repeat(64),coverEmbedded=true)
        withContext(Dispatchers.IO){AudioExportStore(application).use{it.done(task,row)}}
        val holder=ViewModelStore();lateinit var model: NativeMusicViewModel
        try {
            instrumentation.runOnMainSync{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(application,duplicateApi()) as T
            })[NativeMusicViewModel::class.java]}
            suspend fun await(predicate:()->Boolean){withTimeout(15000){while(!predicate())delay(25)}}
            await{model.state.value.initialized}
            instrumentation.runOnMainSync{model.showArtwork();model.addSavedArtwork()}
            await{!model.state.value.busy&&model.artwork.value.message!=null}
            val result=model.artwork.value
            assertEquals(1,result.readySkipped);assertEquals(0,result.total);assertEquals(0,result.processed);assertEquals(0,result.failed)
            withContext(Dispatchers.IO){AudioExportStore(application).use{assertEquals(row,it.savedFiles().single())}}
        }finally{instrumentation.runOnMainSync{holder.clear()};dir.deleteRecursively()}
    }
}

@RunWith(AndroidJUnit4::class)
class DuplicateResumeDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun context(dir: File)=object: ContextWrapper(app) {
        override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
    }
    private val access=MusicAccess("a".repeat(64),"Synthetic")
    private fun client(onRequest: ()->Unit={}): OkHttpClient=NetworkClients.media().newBuilder().addInterceptor{chain->
        onRequest()
        val data=instrumentation.context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
            .header("Content-Type","audio/mpeg").body(data.toResponseBody()).build()
    }.build()
    @Test fun accessFailureResumesAfterRecreationWithoutRedownloadingCompletedTracksButRemovalIsFresh()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"duplicate-resume-access").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val tracks=(1..3).map(::duplicateTrack);val calls=mutableListOf<String>();var reject=true;var downloads=0;var deletes=0
        val api=object: MusicApi by duplicateApi() {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(tracks,tracks.size))
            override suspend fun resolve(access: MusicAccess,track: NativeTrack)=ResolvedAudio(track,media(access,track))
            override suspend fun media(access: MusicAccess,track: NativeTrack): String {
                calls.add(track.id)
                if(track==tracks[1]&&reject)throw MusicFailure(MusicError.ACCESS,5)
                return duplicateApi().media(access,track)
            }
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack){deletes++}
        }
        try {
            val cache=DuplicateScanCache(context)
            DuplicateAudioHasher(context,api,AudioTransfer(client{downloads++},observe={_,_->}),cache).use{hasher->
                try{MusicDuplicates(api,hasher,pageDelayMs=0).scan(access);fail("Expected access failure")}
                catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
            }
            assertEquals(1,cache.count());assertEquals(1,downloads)
            assertTrue(File(context.noBackupFilesDir,"duplicate-scan").listFiles().orEmpty().none{it.extension in setOf("mp3","m4a")})
            assertFalse(File(context.cacheDir,"duplicate-check").exists())
            // Simulate a new process/object and reclamation of optional temporary audio.
            File(context.noBackupFilesDir,"duplicate-scan").listFiles().orEmpty().filter{it.extension=="mp3"}.forEach{it.delete()}
            reject=false;var reused=0
            DuplicateAudioHasher(context,api,AudioTransfer(client{downloads++},observe={_,_->}),DuplicateScanCache(context)).use{hasher->
                val engine=MusicDuplicates(api,hasher,progress={reused=maxOf(reused,it.reused)},pageDelayMs=0)
                val report=engine.scan(access)
                assertEquals(3,report.checked);assertEquals(2,report.duplicates.size);assertEquals(1,reused)
                assertEquals(listOf("12_1","12_2","12_2","12_3"),calls);assertEquals(3,downloads);assertEquals(0,deletes)
                engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.first()))
                assertEquals(listOf("12_1","12_2"),calls.takeLast(2));assertEquals(5,downloads);assertEquals(1,deletes)
            }
        }finally{dir.deleteRecursively()}
    }
    @Test fun checkpointIsBoundToAccountSnapshotAndExpiryAndCorruptionNeverCreatesMatches() {
        val dir=File(app.cacheDir,"duplicate-resume-binding").apply{deleteRecursively();mkdirs()};val context=context(dir)
        var now=1_800_000_000_000L;val tracks=(1..2).map(::duplicateTrack);val signature=DuplicateSignature("mp3","a".repeat(64))
        fun save(cache: DuplicateScanCache,track: NativeTrack){cache.save(track,signature,File(context.cacheDir,"verified.output").apply{writeBytes(ByteArray(16))})}
        try {
            var cache=DuplicateScanCache(context,{now},audioLimit=8)
            cache.begin(12,tracks);save(cache,tracks[0]);assertEquals(signature,cache.get(tracks[0]))
            assertTrue(File(context.noBackupFilesDir,"duplicate-scan").listFiles().orEmpty().none{it.extension=="mp3"})
            cache=DuplicateScanCache(context,{now});cache.begin(12,tracks);assertEquals(signature,cache.get(tracks[0]))
            cache.begin(13,tracks);assertEquals(0,cache.count());assertNull(cache.get(tracks[0]))
            cache.begin(12,tracks);save(cache,tracks[0]);cache.begin(12,tracks.reversed());assertEquals(0,cache.count())
            save(cache,tracks[0]);now+=24L*60*60*1000
            cache=DuplicateScanCache(context,{now});cache.begin(12,tracks);assertEquals(0,cache.count())
            save(cache,tracks[0]);File(context.noBackupFilesDir,"duplicate-scan/checks.json").writeText("{broken")
            cache=DuplicateScanCache(context,{now});cache.begin(12,tracks);assertEquals(0,cache.count());assertNull(cache.get(tracks[0]))
            save(cache,tracks[0]);cache.clear();assertFalse(File(context.noBackupFilesDir,"duplicate-scan").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun cancellationDuringProcessingDoesNotCheckpointPartialTrackOrLeaveTransferFiles()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"duplicate-resume-cancel").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val tracks=(1..2).map(::duplicateTrack);val cache=DuplicateScanCache(context);val started=CompletableDeferred<Unit>();var downloads=0
        try {
            val processor=AudioProcessor{_,_,_,_->started.complete(Unit);awaitCancellation()}
            DuplicateAudioHasher(context,duplicateApi(),AudioTransfer(client{downloads++},processor,observe={_,_->}),cache).use{hasher->
                hasher.beginScan(12,tracks)
                val job=launch{hasher.signature(access,tracks[0])}
                withTimeout(15000){started.await()};job.cancelAndJoin()
                assertEquals(0,cache.count());assertNull(cache.get(tracks[0]))
                assertTrue(File(context.cacheDir,"duplicate-check").listFiles().orEmpty().isEmpty())
            }
            DuplicateAudioHasher(context,duplicateApi(),AudioTransfer(client{downloads++},observe={_,_->}),DuplicateScanCache(context)).use{hasher->
                hasher.beginScan(12,tracks);assertNull(hasher.cached(tracks[0]));hasher.signature(access,tracks[0])
            }
            assertEquals(2,downloads);assertEquals(1,DuplicateScanCache(context).count())
        }finally{dir.deleteRecursively()}
    }
}
