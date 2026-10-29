package app.alcovaibe.vkmusicexport

import android.content.ContentUris
import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadContinuityDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val access=MusicAccess("a".repeat(64),"Synthetic")
    private fun track(n: Int)=NativeTrack("12_$n","Synthetic continuity song","Synthetic artist",1)
    private fun context(dir: File)=object: ContextWrapper(app) {
        override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
    }
    private val noApi=object: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("Unexpected library request")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Unexpected catalogue")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Unexpected playlist")
        override suspend fun media(access: MusicAccess,track: NativeTrack)="https://audio.userapi.com/${track.id}"
    }
    private fun transfer(status:(String)->Int={200},request:(String)->Unit={}): AudioTransfer {
        val bytes=instrumentation.context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
        val client=NetworkClients.media().newBuilder().addInterceptor{chain->
            val id=chain.request().url.encodedPath.removePrefix("/");request(id)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status(id))
                .message("Synthetic").body(bytes.toResponseBody()).build()
        }.build()
        return AudioTransfer(client,observe={_,_->})
    }
    private fun cleanup(directory: String) {
        val collection=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids=app.contentResolver.query(collection,arrayOf("_id"),"relative_path=?",arrayOf(directory),null)?.use{c->
            buildList{while(c.moveToNext())add(c.getLong(0))}
        }.orEmpty()
        ids.forEach{app.contentResolver.delete(ContentUris.withAppendedId(collection,it),null,null)}
    }

    @Test fun playlistContinuesAfterUnavailableAndMediaHttpErrorsAndPersistsFinalFailures()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"queue-continuity").apply{deleteRecursively();mkdirs()};val context=context(dir)
        var store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val rows=(1..6).map(::track)
        val playlist=NativePlaylist(12,123,"CI continuity ${UUID.randomUUID()}",6,null,null,null,null,PlaylistKind.PLAYLIST)
        val tasks=ExportLayout.playlist(NativePage(rows,6),playlist)
        val requests=mutableListOf<String>();val resolutions=mutableListOf<String>()
        val api=object: MusicApi by noApi {
            override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio {
                resolutions.add(track.id)
                if(track.id=="12_2")throw TrackUnavailable(TrackAccessIssue.RESTRICTED)
                return ResolvedAudio(track,media(access,track))
            }
        }
        val directory=store.directory(tasks.first())
        try {
            store.replace(tasks);store.status("QUEUED")
            AudioExportEngine(store,shared,File(dir,"tmp"),api,
                transfer(status={when(it){"12_3"->403;"12_5"->503;else->200}},request={requests.add(it)}),
                credential={access},observe={_,_->}).run{}
            val result=store.snapshot()
            assertEquals("FINISHED_WITH_ERRORS",result.status);assertFalse(result.unfinished)
            assertEquals(3,result.done);assertEquals(3,result.failed);assertEquals(0,result.pending)
            assertEquals(listOf("12_2","12_3","12_5"),result.failures.map{it.task.track.id})
            assertEquals(listOf(MusicError.UNAVAILABLE,MusicError.UNAVAILABLE,MusicError.HTTP),result.failures.map{it.error})
            assertEquals(3,requests.count{it=="12_3"});assertEquals(3,requests.count{it=="12_5"})
            assertEquals(1,resolutions.count{it=="12_2"})
            for(i in listOf(0,3,5))assertTrue(shared.valid(store.existing(tasks[i].key)!!))
            assertTrue(File(dir,"tmp").listFiles().orEmpty().isEmpty())
            store.close();store=AudioExportStore(context)
            assertEquals(result.failures,store.snapshot().failures);assertEquals("FINISHED_WITH_ERRORS",store.snapshot().status)
        }finally{store.close();cleanup(directory);dir.deleteRecursively()}
    }

    @Test fun expiredMediaUrlIsRefreshedButRealAccountFailureKeepsLaterItemsPending()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"queue-refresh-account").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val source=ExportSource(0,"legacy","continuity-refresh")
        val folder="CI refresh ${UUID.randomUUID()}"
        val tasks=(1..3).map{ExportLayout.task(track(it),listOf("Плейлисты",folder),it,source)}
        var requests=0;var resolutions=0
        val api=object: MusicApi by noApi {
            override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio {
                if(track.id=="12_2")throw MusicFailure(MusicError.ACCESS,5)
                resolutions++;return ResolvedAudio(track,media(access,track))
            }
        }
        val directory=store.directory(tasks.first())
        try {
            store.replace(tasks);store.status("QUEUED")
            AudioExportEngine(store,shared,File(dir,"tmp"),api,
                transfer(status={if(requests==1)403 else 200},request={requests++}),credential={access},observe={_,_->}).run{}
            val result=store.snapshot()
            assertEquals("PAUSED",result.status);assertEquals(MusicError.ACCESS,result.error)
            assertEquals(1,result.done);assertEquals(0,result.failed);assertEquals(2,result.pending)
            assertEquals(2,requests);assertEquals(2,resolutions);assertTrue(shared.valid(store.existing(tasks[0].key)!!))
            assertNull(store.existing(tasks[2].key))
        }finally{store.close();cleanup(directory);dir.deleteRecursively()}
    }

    @Test fun changedRecordInvalidatesOnlyItsReceiptAndOtherLibraryEditsKeepCompletedChecks() {
        val dir=File(app.cacheDir,"receipts-per-record").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val tracks=(1..3).map(::track);val signature=DuplicateSignature("mp3","a".repeat(64))
        val audio=File(context.cacheDir,"verified.output").apply{writeBytes(ByteArray(16))}
        try {
            var cache=DuplicateScanCache(context);cache.begin(12,tracks)
            tracks.forEach{cache.save(it,signature,audio)}
            cache=DuplicateScanCache(context);cache.begin(12,listOf(track(4))+tracks.reversed())
            tracks.forEach{assertEquals(signature,cache.get(it))};assertNull(cache.get(track(4)));assertEquals(3,cache.count())
            val edited=tracks[0].copy(duration=2)
            cache.begin(12,listOf(edited,tracks[1],track(4)))
            assertEquals(1,cache.count());assertNull(cache.get(tracks[0]));assertNull(cache.get(edited))
            assertNull(cache.get(tracks[2]));assertEquals(signature,cache.get(tracks[1]))
        }finally{dir.deleteRecursively()}
    }

    @Test fun changingLibraryFinishesTaskOnceAndExplicitNextSearchDownloadsOnlyNewCandidates()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"finite-duplicate-task").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val rows=(1..3).map(::track).toMutableList();var reads=0;var downloads=0
        val credentials=object: MusicCredentialStore {
            override fun read()=access
            override fun replace(expected: MusicAccess,next: MusicAccess)=false
        }
        val api=object: MusicApi by noApi {
            override suspend fun library(access: MusicAccess): NativeProbe {
                reads++;if(reads==2){rows.reverse();rows.add(track(4))}
                return NativeProbe(12,NativePage(rows.toList(),rows.size))
            }
        }
        fun task()=DuplicateCheckTask(context,api,credentials,null,hasherFactory={cache,progress->
            DuplicateAudioHasher(context,api,transfer(request={downloads++}),cache,progress)
        })
        try {
            val first=task();val id=first.prepare();withTimeout(30000){first.execute(id)}
            assertEquals(DuplicatePhase.READY,first.state.value.phase);assertTrue(first.state.value.report!!.libraryChanged)
            assertEquals(3,downloads);assertEquals(2,reads);assertEquals(2,first.state.value.found)
            first.execute(id);assertEquals(3,downloads);assertEquals(2,reads)
            val next=task();val nextId=next.prepare();withTimeout(30000){next.execute(nextId)}
            assertEquals(DuplicatePhase.READY,next.state.value.phase);assertEquals(4,downloads);assertEquals(4,reads)
            assertEquals(3,next.state.value.progress.reused);assertEquals(3,next.state.value.found)
            val again=task();withTimeout(30000){again.execute(again.prepare())}
            assertEquals(DuplicatePhase.READY,again.state.value.phase);assertEquals(4,downloads);assertEquals(6,reads)
            assertEquals(4,again.state.value.progress.reused);assertNull(again.state.value.consent)
        }finally{dir.deleteRecursively()}
    }
}
