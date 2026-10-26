package app.alcovaibe.vkmusicexport

import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedFileRecoveryDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(dir: File)=File(dir,"fixture.mp3").apply{
        InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}
        appendBytes("TAG".toByteArray()+dir.name.toByteArray().copyOf(125))
    }
    private suspend fun scenario(name: String,block: suspend(ContextWrapper,File,AudioExportStore,SharedAudioStore,MutableList<PublishedAudio>)->Unit) {
        val dir=File(app.cacheDir,name).apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(app){override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}}
        val store=AudioExportStore(context);val shared=SharedAudioStore(context);val rows=mutableListOf<PublishedAudio>()
        try{block(context,dir,store,shared,rows)}finally{
            rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()
        }
    }
    private fun task(n: Int)=ExportLayout.task(NativeTrack("12_${99700+n}","Recovery CI $n","CI",1),listOf("Моя музыка","Recovery CI $n"),1)
    private suspend fun publish(store: AudioExportStore,shared: SharedAudioStore,dir: File,task: AudioExportTask): PublishedAudio {
        val row=shared.publish(task,TransferResult(fixture(dir),ProcessedAudio("mp3","audio/mpeg",1200))){ }
        store.done(task,row)
        return row
    }
    @Test fun inspectionSeparatesMovesMissingSizeAndHashWithoutWriting()=runBlocking<Unit>(Dispatchers.IO) {
        scenario("recovery-reasons"){_,dir,store,shared,rows->
            val row=publish(store,shared,dir,task(1));rows.add(row)
            assertEquals(SavedFileReason.OK,shared.inspect(row).reason)
            assertEquals(SavedFileReason.SIZE_CHANGED,shared.inspect(row.copy(bytes=row.bytes+1)).reason)
            assertEquals(SavedFileReason.HASH_CHANGED,shared.inspect(row.copy(sha256="0".repeat(64))).reason)
            app.contentResolver.update(Uri.parse(row.uri),ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"renamed-recovery.mp3")},null,null)
            assertEquals(SavedFileReason.LOCATION_CHANGED,shared.inspect(row).reason)
            assertFalse(shared.valid(row));assertEquals(row,store.existing(row.taskKey))
            app.contentResolver.delete(Uri.parse(row.uri),null,null);rows.remove(row)
            assertEquals(SavedFileReason.MISSING,shared.inspect(row).reason)
        }
    }
    @Test fun exactMovedFileRelinksAndSurvivesDatabaseReopenWithoutRevivingHistory()=runBlocking<Unit>(Dispatchers.IO) {
        scenario("recovery-move"){context,dir,store,shared,rows->
            val task=task(2);store.replace(listOf(task));val row=publish(store,shared,dir,task);rows.add(row);store.clearRecentFiles()
            app.contentResolver.update(Uri.parse(row.uri),ContentValues().apply{
                put(MediaStore.MediaColumns.DISPLAY_NAME,"Moved.mp3");put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/Recovery moved CI/")
            },null,null)
            assertEquals(SavedFileReason.OUTSIDE_FOLDER,shared.inspect(row).reason)
            assertEquals(SavedFileReason.OK,SavedFileRecovery(context,store,shared).search(row))
            val after=store.existing(row.taskKey)!!
            assertEquals(row.uri,after.uri);assertEquals(row.sha256,after.sha256);assertTrue(after.recoveredLocation)
            assertEquals("Music/Recovery moved CI/",after.relativePath);assertTrue(shared.valid(after));assertTrue(store.snapshot().recent.isEmpty())
            AudioExportStore(context).use{reopened->assertEquals(after,reopened.existing(row.taskKey));assertEquals(1,reopened.snapshot().done)}
            assertThrows(MusicFailure::class.java){store.relinked(row,row)}
        }
    }
    @Test fun multipleIdenticalCopiesNeverChooseByNameAndForgetChangesOnlyIndex()=runBlocking<Unit>(Dispatchers.IO) {
        scenario("recovery-ambiguous"){context,dir,store,shared,rows->
            // Byte identity deliberately equal, distinct task names are never used to choose a match.
            val first=publish(store,shared,dir,task(3));rows.add(first)
            val second=publish(store,shared,dir,task(4));rows.add(second)
            val unavailable=first.copy(uri="content://media/external_primary/audio/media/9223372036854775806")
            store.relinked(first,unavailable)
            assertEquals(SavedFileReason.AMBIGUOUS,SavedFileRecovery(context,store,shared).search(unavailable))
            assertEquals(unavailable,store.existing(first.taskKey))
            val snapshot=store.snapshot();store.forgetFile(unavailable)
            assertNull(store.existing(first.taskKey));assertTrue(shared.valid(first));assertTrue(shared.valid(second))
            assertEquals(snapshot.total,store.snapshot().total);assertEquals(snapshot.done,store.snapshot().done)
            assertTrue(store.snapshot().recent.none{it.taskKey==first.taskKey})
        }
    }
    @Test fun manualSelectionRequiresConfirmationCopiesBytesAndPreservesSourceAndClearedHistory()=runBlocking<Unit>(Dispatchers.IO) {
        scenario("recovery-manual"){context,dir,store,shared,rows->
            val row=publish(store,shared,dir,task(5));rows.add(row);store.clearRecentFiles()
            val recovery=SavedFileRecovery(context,store,shared)
            val preview=recovery.preview(row,Uri.parse(row.uri))
            assertTrue(preview.exact);assertEquals(row,store.existing(row.taskKey));assertEquals(1,store.savedFiles().size)
            val after=recovery.confirm(preview);rows.add(after)
            assertNotEquals(row.uri,after.uri);assertEquals(row.sha256,after.sha256);assertTrue(shared.valid(row));assertTrue(shared.valid(after))
            assertEquals(ExportLayout.ROOT+"Восстановленные/",after.relativePath);assertTrue(store.snapshot().recent.isEmpty())
            assertFalse(File(context.noBackupFilesDir,"saved-file-recovery/transaction.json").exists())
            // A changed source remains untouched. A fresh preview is required for any further confirmation.
            assertThrows(MusicFailure::class.java){store.forgetFile(row)}
            assertEquals(after,store.existing(row.taskKey))
        }
    }
    private class ProcessDeath: Error()
    @Test fun interruptedImportAdoptsPublishedCopyButCannotReviveForgottenRecord()=runBlocking<Unit>(Dispatchers.IO) {
        for(forget in listOf(false,true))scenario("recovery-import-interrupted-$forget"){context,dir,store,shared,rows->
            val row=publish(store,shared,dir,task(7));rows.add(row);store.clearRecentFiles()
            val recovery=SavedFileRecovery(context,store,shared){throw ProcessDeath()}
            val preview=recovery.preview(row,Uri.parse(row.uri))
            try{recovery.confirm(preview);fail("process death")}catch(_: ProcessDeath){}
            assertEquals(row,store.existing(row.taskKey));assertTrue(shared.valid(row))
            val journal=File(context.noBackupFilesDir,"saved-file-recovery/transaction.json")
            val json=kotlinx.serialization.json.Json
            val after=json.decodeFromString<PublishedAudio>(json.parseToJsonElement(journal.readText()).jsonObject.getValue("after").toString())
            rows.add(after)
            if(forget)store.forgetFile(row)
            SavedFileRecovery(context,store,shared).recover()
            if(forget)assertNull(store.existing(row.taskKey))else assertEquals(after,store.existing(row.taskKey))
            assertNotEquals(row.uri,after.uri);assertTrue(shared.valid(after));assertTrue(shared.valid(row))
            assertEquals(row.sha256,after.sha256);assertTrue(store.snapshot().recent.isEmpty());assertFalse(journal.exists())
        }
    }
    @Test fun modifiedTagsNeedExplicitConfirmationAndAudioMismatchIsRejected()=runBlocking<Unit>(Dispatchers.IO) {
        scenario("recovery-changed"){context,dir,store,shared,rows->
            val row=publish(store,shared,dir,task(6));rows.add(row)
            // Valid MP3 with changed bytes (ID3v1 tag) must never be accepted by automatic search.
            app.contentResolver.openOutputStream(Uri.parse(row.uri),"wa")!!.use{it.write("TAG".toByteArray()+ByteArray(125))}
            val recovery=SavedFileRecovery(context,store,shared)
            assertEquals(SavedFileReason.NOT_FOUND,recovery.search(row))
            val preview=recovery.preview(row,Uri.parse(row.uri));assertFalse(preview.exact);assertEquals(row,store.existing(row.taskKey))
            val source=app.contentResolver.openInputStream(Uri.parse(row.uri))!!.use{it.readBytes()}
            val after=recovery.confirm(preview);rows.add(after);assertTrue(shared.valid(after))
            assertArrayEquals(source,app.contentResolver.openInputStream(Uri.parse(row.uri))!!.use{it.readBytes()})
            app.contentResolver.openOutputStream(Uri.parse(row.uri),"wt")!!.use{it.write(byteArrayOf(1,2,3))}
            try{recovery.preview(after,Uri.parse(row.uri));fail("invalid audio must not be accepted")}catch(_: MusicFailure){}
            assertEquals(after,store.existing(row.taskKey))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class SavedFilesUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun staleEntryNeedsConfirmationAndShowsDifferentReasonsAndExplicitActions() {
        val row=PublishedAudio("ci",NativeTrack("12_34","CI","Artist",1),"content://media/external_primary/audio/media/1","CI.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",1000,12,"0".repeat(64))
        var state by mutableStateOf(SavedFilesState(visible=true,problems=listOf(SavedFileProblem(row,SavedFileReason.HASH_CHANGED))))
        var forgotten=0;var selected=0
        awaitTestWindowFocus(ui.activity)
        ui.setContent{MusicTheme{SavedFilesSheet(state,{}, {},{selected++},{},{state=state.copy(forget=it)},
            {forgotten++;state=state.copy(forget=null,problems=emptyList(),message="Запись убрана")},{state=state.copy(forget=null)},{},{})}}
        ui.onNodeWithText(SavedFileReason.HASH_CHANGED.label).assertIsDisplayed()
        ui.onNodeWithText("Выбрать файл").performClick();assertEquals(1,selected);assertEquals(0,forgotten)
        ui.onNodeWithText("Убрать запись").performClick();assertEquals(0,forgotten)
        ui.onNodeWithTag("confirm_forget_file").performScrollTo().performClick();assertEquals(1,forgotten)
        ui.onNodeWithTag("saved_files_result").assertIsDisplayed()
    }
}
