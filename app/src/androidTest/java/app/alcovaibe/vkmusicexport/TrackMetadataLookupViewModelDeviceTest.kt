package app.alcovaibe.vkmusicexport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrackMetadataLookupViewModelDeviceTest {
    @Test fun unavailableReportOpensLookupBackgroundInvalidatesChoiceAndConfirmedSourceIsRemembered()=runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val real=inst.targetContext.applicationContext as Application
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(real);AudioExportRuntime.clear(real)}
        val dir=File(real.cacheDir,"metadata-lookup-vm").apply{deleteRecursively();mkdirs()}
        val app=object: Application(){init{attachBaseContext(real)}
            override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
            override fun getFilesDir()=File(dir,"files").apply{mkdirs()}
            override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        }
        val store=AudioExportStore(app);val holder=ViewModelStore();var saved: PublishedAudio?=null;lateinit var model: NativeMusicViewModel
        suspend fun until(check:()->Boolean){withTimeout(15_000){while(!check())delay(25)}}
        try {
            val row=withContext(Dispatchers.IO){
                val track=NativeTrack("12_99801","Миражи","whiteSpot",1)
                val task=ExportLayout.task(track,listOf("Моя музыка","Metadata lookup VM CI"),1,ExportSource(12,"library","my"))
                val input=File(dir,"input.mp3").apply{inst.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
                val output=File(dir,"tagged.mp3");val processed=AndroidAudioProcessor().process(input,output,track,null)
                SharedAudioStore(app).publish(task,TransferResult(output,processed)){}.also{store.done(task,it)}
            };saved=row
            val selected=row.track.copy(id="56_99802",subtitle="hehehe Cover sped up")
            val api=object: MusicApi {
                override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
                override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog")
                override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
                override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
                override suspend fun metadata(access: MusicAccess,id: String)=selected.takeIf{it.id==id}
                override suspend fun searchMetadata(access: MusicAccess,query: String,offset: Int)=MetadataMatches(listOf(selected),1,offset)
            }
            withContext(Dispatchers.IO){MusicAccessStore(app).save(MusicAccess("a".repeat(64),"CI/1",accountId=12))}
            inst.runOnMainSync{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api) as T
            })[NativeMusicViewModel::class.java]}
            until{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.showTrackMetadata();model.updateSavedTrackMetadata()}
            until{!model.state.value.busy&&model.trackMetadata.value.unavailable==1}
            val target=model.trackMetadata.value.issues.single().target!!
            suspend fun preview(){
                inst.runOnMainSync{model.showMetadataLookup(target)};until{!model.state.value.busy&&model.metadataLookup.value.target!=null}
                inst.runOnMainSync{model.findMetadataReplacement()};until{!model.state.value.busy&&model.metadataLookup.value.results!=null}
                inst.runOnMainSync{model.previewMetadataReplacement(selected)};until{!model.state.value.busy&&model.metadataLookup.value.phase==LookupPhase.CONFIRM}
            }
            preview();inst.runOnMainSync{model.stopInBackground();model.confirmMetadataReplacement()}
            assertNull(model.metadataLookup.value.choice);assertEquals(row,store.existing(row.taskKey))
            inst.runOnMainSync{model.setForeground(true)};preview()
            inst.runOnMainSync{model.confirmMetadataReplacement()};until{!model.state.value.busy&&model.metadataLookup.value.phase==LookupPhase.DONE}
            val after=store.existing(row.taskKey)!!
            assertEquals(row.track.copy(subtitle=selected.subtitle),after.track);assertTrue(SharedAudioStore(app).valid(after))
            assertEquals(0,model.trackMetadata.value.unavailable);assertEquals(1,model.trackMetadata.value.updated)
            assertTrue(store.metadataRun(12)!!.issues.single().resolved)
            assertEquals(selected.id,store.metadataReplacement(12,after)!!.referenceId)
            inst.runOnMainSync{model.dismissMetadataLookup();model.updateSavedTrackMetadata()}
            until{!model.state.value.busy&&model.trackMetadata.value.current==1}
            assertEquals(0,model.trackMetadata.value.unavailable)
        }finally{inst.runOnMainSync{holder.clear()};saved?.let{real.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
}
