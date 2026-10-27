package app.alcovaibe.vkmusicexport

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

private val lookupAccess=MusicAccess("a".repeat(64),"Synthetic agent")
private fun lookupTrack(n: Int=1)=NativeTrack("12_${99700+n}","Saved synthetic $n","CI artist",1)
private fun lookupMatch(t: NativeTrack)=ArtworkMatch(t.copy(id="56_77",title="Found synthetic"),listOf(ArtworkCandidate("https://sun9.userapi.com/synthetic",ArtworkOrigin.ALBUM)))
private fun lookupImage(): CoverImage {
    val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)}
    return try{CoverImage(ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray(),"image/jpeg")}finally{bitmap.recycle()}
}
private fun lookupApi(match: ArtworkMatch)=object: MusicApi {
    override suspend fun library(access: MusicAccess): NativeProbe=error("No library lookup")
    override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog lookup")
    override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist lookup")
    override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No music download")
    override suspend fun artworkCandidates(access: MusicAccess,track: NativeTrack): List<ArtworkCandidate> =throw MusicFailure(MusicError.UNAVAILABLE)
    override suspend fun searchArtwork(access: MusicAccess,query: String,offset: Int)=ArtworkMatches(listOf(match),1,offset)
}

@RunWith(AndroidJUnit4::class)
class ArtworkLookupUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun searchPreviewAndBackNeverWriteUntilSeparateConfirmation() {
        val target=PublishedAudio("synthetic",lookupTrack(),"content://media/external_primary/audio/media/1","Saved.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",1200,100,"b".repeat(64))
        val match=lookupMatch(target.track);var searches=0;var previews=0;var writes=0
        var state by mutableStateOf(ArtworkLookupState(true,"synthetic",target=target,query="CI artist song"))
        awaitTestWindowFocus(ui.activity)
        ui.setContent{MusicTheme{ArtworkLookupSheet(state,onQuery={state=state.copy(query=it,results=null,choice=null)},
            onSearch={searches++;state=state.copy(results=ArtworkMatches(listOf(match),1))},
            onPreview={previews++;state=state.copy(phase=LookupPhase.CONFIRM,choice=ArtworkChoice(target,lookupAccess.credentialId,it,lookupImage(),Long.MAX_VALUE))},
            onConfirm={writes++;state=state.copy(phase=LookupPhase.DONE,choice=null,message="Done")},
            onBack={state=state.copy(phase=LookupPhase.READY,choice=null)},onStop={},onDismiss={})}}
        ui.runOnIdle{assertEquals(0,searches);assertEquals(0,previews);assertEquals(0,writes)}
        ui.onNodeWithTag("artwork_lookup_search").performScrollTo().performClick()
        ui.onNodeWithTag("artwork_lookup_list").performScrollToNode(hasTestTag("preview_artwork_56_77"))
        ui.onNodeWithTag("preview_artwork_56_77").performClick()
        ui.onNodeWithTag("artwork_lookup_list").performScrollToNode(hasTestTag("artwork_lookup_image"))
        ui.onNodeWithTag("artwork_lookup_image").assertIsDisplayed();ui.onNodeWithTag("confirm_artwork_choice").assertIsDisplayed()
        ui.runOnIdle{assertEquals(1,searches);assertEquals(1,previews);assertEquals(0,writes)}
        ui.waitForIdle()
        val inst=InstrumentationRegistry.getInstrumentation();val bitmap=inst.uiAutomation.takeScreenshot()
        val file=File(inst.targetContext.getExternalFilesDir(null),"screenshots/artwork-choice.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        for(command in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/artwork-choice.png"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        ui.onNodeWithTag("cancel_artwork_choice").performClick();ui.runOnIdle{assertEquals(0,writes)}
        ui.onNodeWithTag("artwork_lookup_list").performScrollToNode(hasTestTag("preview_artwork_56_77"))
        ui.onNodeWithTag("preview_artwork_56_77").performClick();ui.onNodeWithTag("confirm_artwork_choice").performClick()
        ui.runOnIdle{assertEquals(1,writes)}
    }
    @Test fun entryPointBelongsToMissingSavedIdAndCarriesExactTaskKey() {
        val skips=listOf(ArtworkSkip(1,"Artist","No cover",ArtworkSkipReason.NO_COVER,"no-cover"),
            ArtworkSkip(2,"Artist","Old ID",ArtworkSkipReason.TRACK_UNAVAILABLE,"exact-task"))
        var found=""
        awaitTestWindowFocus(ui.activity)
        ui.setContent{MusicTheme{ArtworkSheet(ArtworkState(visible=true,skipped=skips),{}, {}, {},onFind={found=it})}}
        ui.onNodeWithTag("find_artwork_1").assertDoesNotExist()
        ui.onNodeWithTag("artwork_results").performScrollToNode(hasTestTag("find_artwork_2"))
        ui.onNodeWithTag("find_artwork_2").performClick();ui.runOnIdle{assertEquals("exact-task",found)}
    }
}

@RunWith(AndroidJUnit4::class)
class ArtworkLookupStorageDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val dir=File(app.cacheDir,name).apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}} to dir
    }
    private suspend fun publish(context: android.content.Context,dir: File,store: AudioExportStore,n: Int,ext: String): PublishedAudio {
        val task=ExportLayout.task(lookupTrack(n),listOf("Моя музыка","Artwork lookup CI"),n)
        val input=File(dir,"input.$ext").apply{inst.context.assets.open("native-audio/tone.$ext").use{writeBytes(it.readBytes())}}
        val output=File(dir,"exported.$ext");val media=AndroidAudioProcessor().process(input,output,task.track,null)
        return SharedAudioStore(context).publish(task,TransferResult(output,media)){}.also{store.done(task,it)}
    }
    @Test fun confirmedAlternateRecordEmbedsCoverInMp3AndM4aWithoutReplacingIdentityOrAudio()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("lookup-codecs");val rows=mutableListOf<PublishedAudio>();val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        try {
            for((i,ext)in listOf("mp3","m4a").withIndex()) {
                val row=publish(context,dir,store,i+1,ext);rows.add(row);store.clearRecentFiles()
                val before=File(dir,"before.$ext");shared.copyTo(row,before);val payload=EmbeddedArtwork.payloadDigest(before,ext)
                val match=lookupMatch(row.track);val api=lookupApi(match);val repair=ArtworkRepair(context,store,shared)
                try{repair.add(row,ArtworkSource{_,_->throw MusicFailure(MusicError.UNAVAILABLE)}){lookupAccess};fail("Old ID must be unavailable")}
                catch(e: MusicFailure){assertEquals(MusicError.UNAVAILABLE,e.code)}
                val service=ArtworkLookup(api,{ArtworkSource{_,_->lookupImage()}})
                val candidate=service.find(lookupAccess,"CI song").matches.single();val choice=service.preview(lookupAccess,row,candidate)
                assertEquals(row,store.existing(row.taskKey));assertTrue(shared.valid(row))
                assertTrue(service.apply(lookupAccess,choice){target,image->repair.add(target,ArtworkSource{_,_->image}){lookupAccess}})
                val after=store.existing(row.taskKey)!!;assertTrue(after.coverEmbedded);assertEquals(row.track,after.track)
                assertEquals(row.uri,after.uri);assertEquals(row.displayName,after.displayName);assertEquals(row.relativePath,after.relativePath)
                assertNotEquals(row.sha256,after.sha256);assertTrue(shared.valid(after));assertTrue(store.snapshot().recent.isEmpty())
                val copy=File(dir,"after.$ext");shared.copyTo(after,copy);assertEquals(payload,EmbeddedArtwork.payloadDigest(copy,ext))
                MediaMetadataRetriever().use{it.setDataSource(app,Uri.parse(row.uri));assertNotNull(it.embeddedPicture)}
            }
        }finally{rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
    @Test fun lastMomentCancellationRestoresJournalAndLeavesOriginalBytesAndIndex()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("lookup-guard");val store=AudioExportStore(context);val shared=SharedAudioStore(context);var row: PublishedAudio?=null
        try {
            val saved=publish(context,dir,store,3,"mp3");row=saved
            val repair=ArtworkRepair(context,store,shared,beforeWrite={throw CancellationException("Synthetic account/background change")})
            try{repair.add(saved,ArtworkSource{_,_->lookupImage()}){lookupAccess};fail("Guard must reject")}
            catch(_: CancellationException){}
            assertEquals(saved,store.existing(saved.taskKey));assertTrue(shared.valid(saved))
            assertFalse(File(context.noBackupFilesDir,"artwork-repair/transaction.json").exists())
        }finally{row?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
}

@RunWith(AndroidJUnit4::class)
class ArtworkLookupViewModelDeviceTest {
    @Test fun backgroundDropsPreviewAndFreshExplicitChoiceUpdatesOnlyTheSavedFile()=runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val real=inst.targetContext.applicationContext as Application
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(real);AudioExportRuntime.clear(real)}
        val dir=File(real.cacheDir,"lookup-viewmodel").apply{deleteRecursively();mkdirs()}
        val app=object: Application(){init{attachBaseContext(real)}
            override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
            override fun getFilesDir()=File(dir,"files").apply{mkdirs()}
            override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        }
        val store=AudioExportStore(app);val holder=ViewModelStore();var saved: PublishedAudio?=null;lateinit var model: NativeMusicViewModel
        suspend fun await(predicate:()->Boolean){withTimeout(15000){while(!predicate())delay(25)}}
        try {
            val row=withContext(Dispatchers.IO){
                val task=ExportLayout.task(lookupTrack(4),listOf("Моя музыка","Artwork lookup VM CI"),1)
                val input=File(dir,"input.mp3").apply{inst.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
                SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}.also{store.done(task,it)}
            };saved=row;val match=lookupMatch(row.track)
            withContext(Dispatchers.IO){MusicAccessStore(app).save(lookupAccess)}
            inst.runOnMainSync{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=
                    NativeMusicViewModel(app,lookupApi(match),lookupImages={ArtworkSource{_,_->lookupImage()}}) as T
            })[NativeMusicViewModel::class.java]}
            await{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.showArtwork();model.addSavedArtwork()}
            await{!model.state.value.busy&&model.artwork.value.unavailable==1}
            suspend fun preview(){
                inst.runOnMainSync{model.showArtworkLookup(row.taskKey)};await{!model.state.value.busy&&model.artworkLookup.value.target!=null}
                inst.runOnMainSync{model.findArtworkReplacement()};await{!model.state.value.busy&&model.artworkLookup.value.results!=null}
                inst.runOnMainSync{model.previewArtworkReplacement(model.artworkLookup.value.results!!.matches.single())}
                await{!model.state.value.busy&&model.artworkLookup.value.phase==LookupPhase.CONFIRM}
            }
            preview();inst.runOnMainSync{model.stopInBackground();model.confirmArtworkReplacement()}
            assertNull(model.artworkLookup.value.choice);assertTrue(SharedAudioStore(app).valid(row));assertEquals(row,store.existing(row.taskKey))
            inst.runOnMainSync{model.setForeground(true)};preview()
            inst.runOnMainSync{model.confirmArtworkReplacement()};await{!model.state.value.busy&&model.artworkLookup.value.phase==LookupPhase.DONE}
            assertTrue(store.existing(row.taskKey)!!.coverEmbedded);assertEquals(row.track,store.existing(row.taskKey)!!.track)
            assertEquals(0,model.artwork.value.unavailable);assertEquals(1,model.artwork.value.added)
        }finally{inst.runOnMainSync{holder.clear()};saved?.let{real.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
}
