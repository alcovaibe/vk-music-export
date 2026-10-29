package app.alcovaibe.vkmusicexport

import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaFinalizationDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private val json=Json{encodeDefaults=true}
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val root=File(app.cacheDir,"finalization-$name-${UUID.randomUUID()}").apply{mkdirs()}
        return object: ContextWrapper(app) {
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply{mkdirs()}
        } to root
    }
    private fun fixture(root: File)=File(root,"tone.mp3").apply {
        instrumentation.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}
    }
    private fun task()=ExportLayout.task(NativeTrack("12_98991","Finalize ${UUID.randomUUID()}","CI",1),listOf("CI finalization"),1)
    private fun rename(row: PublishedAudio,name: String) {
        assertEquals(1,app.contentResolver.update(Uri.parse(row.uri),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
        },null,null))
    }
    private fun erase(row: PublishedAudio?) {row?.let{runCatching{app.contentResolver.delete(Uri.parse(it.uri),null,null)}}}

    @Test fun publishingUsesFinalProviderLocationAndRefreshesTheDurableCheckpoint()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,root)=isolated("publish");val shared=SharedAudioStore(context)
        val checkpoints=mutableListOf<PublishedAudio>();var row: PublishedAudio?=null
        val name="Finalized-${UUID.randomUUID()}.mp3"
        try {
            row=shared.publish(task(),TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200))){pending->
                checkpoints.add(pending)
                // Force a metadata change between the pending checkpoint and finalization,
                // just as MediaProvider does when resolving a pre-existing file collision.
                if(checkpoints.size==1)rename(pending,name)
            }
            assertEquals(name,row!!.displayName)
            assertEquals(row,checkpoints.last())
            assertNotEquals(checkpoints.first().displayName,row!!.displayName)
            shared.requireValid(row!!)
        }finally{erase(row?:checkpoints.firstOrNull());root.deleteRecursively()}
    }

    @Test fun unfinishedQueuePublicationAdoptsVerifiedFinalMetadataAfterRestart()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,root)=isolated("queue");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val task=task();var row: PublishedAudio?=null;val name="Recovered-${UUID.randomUUID()}.mp3"
        try {
            store.replace(listOf(task));store.status("PAUSED")
            row=shared.publish(task,TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(task,it)}
            rename(row!!,name)
            assertEquals(SavedFileReason.LOCATION_CHANGED,shared.inspect(row!!).reason)
            AudioExportEngine(store,shared,File(root,"tmp"),credential={error("No network credentials in recovery")}).recover()
            val saved=store.existing(task.key)!!
            assertEquals(row!!.uri,saved.uri);assertEquals(name,saved.displayName)
            assertEquals(1,store.snapshot().done);assertTrue(store.prepared().isEmpty());shared.requireValid(saved)
        }finally{erase(row);store.close();root.deleteRecursively()}
    }

    @Test fun unfinishedLegacyPublicationReusesTheSameVerifiedUriAfterProviderRename()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,root)=isolated("legacy");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val input=fixture(root);var row: PublishedAudio?=null
        try {
            val original=File(context.filesDir,"native-audio/retired.mp3").apply{parentFile!!.mkdirs();input.copyTo(this)}
            val hash=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
            val track=task().track
            File(context.filesDir,"native-audio-index.json").writeText("""[{"fileName":"retired.mp3","track":${json.encodeToString(track)},"extension":"mp3","mime":"audio/mpeg","durationMs":1200,"bytes":${input.length()},"sha256":"$hash"}]""")
            val task=ExportLayout.task(track,listOf("Восстановленные","Из старой версии"),1,ExportSource(0,"legacy_archive","retired.mp3:$hash"))
            val checkpoint="legacy-import:"+task.key
            row=shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200)),store.directory(task)){store.checkpoint(checkpoint,json.encodeToString(it))}
            val name="Legacy-finalized-${UUID.randomUUID()}.mp3";rename(row!!,name)
            LegacyArchiveMigration(context,store,shared).migrate()
            val saved=store.savedFiles().single()
            assertEquals(row!!.uri,saved.uri);assertEquals(name,saved.displayName);shared.requireValid(saved)
            assertEquals(saved,json.decodeFromString<PublishedAudio>(store.checkpoint(checkpoint)!!))
            assertArrayEquals(input.readBytes(),original.readBytes())
        }finally{erase(row);store.savedFiles().forEach(::erase);store.close();root.deleteRecursively()}
    }

    @Test fun changedBytesAreNeverAdoptedAsACompletedPublication()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,root)=isolated("changed-bytes");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val task=task();var row: PublishedAudio?=null
        try {
            store.replace(listOf(task));store.status("PAUSED")
            row=shared.publish(task,TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(task,it)}
            val changed=File(root,"tone.mp3").readBytes()
            changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte()
            app.contentResolver.openOutputStream(Uri.parse(row!!.uri),"wt")!!.use{it.write(changed)}
            assertEquals(SavedFileReason.HASH_CHANGED,shared.inspect(row!!).reason)
            assertNull(shared.completed(row!!))
            AudioExportEngine(store,shared,File(root,"tmp"),credential={error("No network credentials in recovery")}).recover()
            assertEquals(0,store.snapshot().done);assertEquals(1,store.snapshot().pending)
            assertTrue(store.prepared().isEmpty());assertNull(store.existing(task.key))
        }finally{erase(row);store.close();root.deleteRecursively()}
    }
}
