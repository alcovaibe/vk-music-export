package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LegacyCleanupDeviceTest {
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val base=File(target.cacheDir,name).apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(base,"files").apply{mkdirs()}
            override fun getCacheDir()=File(base,"cache").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(base,"private").apply{mkdirs()}
        }
        return context to base
    }
    @Test fun cleanupRetainsLegacyAudioAndIndexesAndKeepsPublicMusicQueueCredentialsCollectionAndJournals()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("legacy-cleanup-boundaries")
        val store=AudioExportStore(context);var published: PublishedAudio?=null
        fun write(parent: File,name: String,value: String)=File(parent,name).apply{parentFile!!.mkdirs();writeText(value)}
        try {
            write(context.filesDir,"native-audio/old.mp3","RETIRED")
            write(context.cacheDir,"native-audio-transfer/partial.media","RETIRED")
            listOf("native-audio-index.json","native-audio-index.json.bak","native-audio-index.json.new").forEach{write(context.filesDir,it,"RETIRED")}
            val kept=listOf(write(context.filesDir,"collection.json","COLLECTION"),
                write(context.noBackupFilesDir,"native-music-access.bin","MUSIC_SECRET_ENVELOPE"),
                write(context.noBackupFilesDir,"update-access.bin","UPDATER_SECRET_ENVELOPE"),
                write(context.noBackupFilesDir,"diagnostics/sentinel.ndjson","DIAGNOSTIC_RUN"),
                write(context.cacheDir,"audio-export-transfer/active.media","CURRENT_TRANSFER"))
            val before=kept.map{it.readBytes()}
            val tasks=(1..2).map{ExportLayout.task(NativeTrack("12_${99800+it}","Synthetic migration $it","CI",1),listOf("Моя музыка","CI cleanup"),it)}
            store.replace(tasks);store.status("PAUSED")
            val input=File(base,"tone.mp3").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val shared=SharedAudioStore(context)
            published=shared.publish(tasks[0],TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(tasks[0],it)}
            store.done(tasks[0],published!!)
            val operations=NativeOperationJournal(context);operations.begin("pending-native-operation")
            assertEquals(1,LegacyAudioCleanup(context).run())
            assertEquals(0,LegacyAudioCleanup(context).run())
            assertEquals("RETIRED",File(context.filesDir,"native-audio/old.mp3").readText())
            assertFalse(File(context.cacheDir,"native-audio-transfer").exists())
            assertEquals("RETIRED",File(context.filesDir,"native-audio-index.json").readText())
            kept.forEachIndexed{i,file->assertArrayEquals(before[i],file.readBytes())}
            assertEquals(1,store.snapshot().pending);assertEquals(1,store.snapshot().done);assertEquals("PAUSED",store.snapshot().status)
            assertTrue(shared.valid(published!!));assertTrue(operations.recover())
        }finally{published?.let{context.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();base.deleteRecursively()}
    }
    @Test fun legacySymlinkIsPreservedAndNeverFollowedIntoCurrentTransferDirectory() {
        val (context,base)=isolated("legacy-cleanup-links")
        try {
            val active=File(context.cacheDir,"audio-export-transfer/active.media").apply{parentFile!!.mkdirs();writeText("KEEP")}
            val link=File(context.filesDir,"native-audio").toPath()
            Files.createSymbolicLink(link,active.parentFile!!.toPath())
            assertEquals(0,LegacyAudioCleanup(context).run())
            assertEquals("KEEP",active.readText());assertTrue(Files.isSymbolicLink(link))
            assertTrue(LegacyArchiveMigration.list(context).isEmpty())
        }finally{base.deleteRecursively()}
    }
}
