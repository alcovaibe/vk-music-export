package app.alcovaibe.vkmusicexport

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.ContextWrapper

@RunWith(AndroidJUnit4::class)
class NativeAudioDeviceTest {
    @Test fun operationJournalRecoversInterruptedOwnerAndIgnoresStaleCompletion() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val base=File(target.cacheDir,"native-operation-test").apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(target){override fun getNoBackupFilesDir()=base}
        try {
            val journal=NativeOperationJournal(context);assertFalse(journal.recover())
            journal.begin("old-operation");journal.begin("new-operation");journal.finish("old-operation","completed")
            assertTrue(NativeOperationJournal(context).recover());assertFalse(journal.recover())
            journal.begin("completed-operation");journal.finish("completed-operation","completed")
            assertFalse(NativeOperationJournal(context).recover())
        }finally{base.deleteRecursively()}
    }
    @Test fun actualMedia3ExtractionOfMp3AndAacFromDirectAndTransportStreamsProducesDecodableAudio()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val dir=File(context.cacheDir,"native-audio-device-test").apply{mkdirs()}
        try {
            for((fixture,extension,mime) in listOf(Triple("tone.mp3","mp3","audio/mpeg"),Triple("tone-mp3.ts","mp3","audio/mpeg"),
                Triple("tone-aac.ts","m4a","audio/mp4a-latm"),Triple("tone.m4a","m4a","audio/mp4a-latm"))) {
                val input=File(dir,"input").apply{instrumentation.context.assets.open("native-audio/$fixture").use{writeBytes(it.readBytes())}}
                val output=File(dir,"output")
                val result=AndroidAudioProcessor().process(input,output,NativeTrack("12_34","Синтетический сигнал","CI",1),1.2)
                assertEquals(fixture,extension,result.extension);assertTrue(result.durationMs in 1000..1500)
                assertTrue(output.length()>1000);decode(output,mime)
                output.delete()
            }
        }finally{dir.deleteRecursively()}
    }
    private fun decode(file: File,expectedMime: String) {
        val extractor=MediaExtractor();var decoder: MediaCodec?=null
        try {
            extractor.setDataSource(file.absolutePath);assertEquals(1,extractor.trackCount)
            val format=extractor.getTrackFormat(0);assertEquals(expectedMime,format.getString(MediaFormat.KEY_MIME));extractor.selectTrack(0)
            decoder=MediaCodec.createDecoderByType(expectedMime);decoder.configure(format,null,null,0);decoder.start()
            val info=MediaCodec.BufferInfo();var endedInput=false;var endedOutput=false;var pcm=0L;var loops=0
            while(!endedOutput&&loops++<500) {
                if(!endedInput) {
                    val index=decoder.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val buffer=decoder.getInputBuffer(index)!!;val n=extractor.readSampleData(buffer,0)
                        if(n<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);endedInput=true}
                        else{decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val out=decoder.dequeueOutputBuffer(info,10000)
                if(out>=0){pcm+=info.size;endedOutput=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(out,false)}
            }
            assertTrue("Decoder must reach end of stream",endedOutput);assertTrue("Actual PCM must be produced",pcm>10000)
        }finally{runCatching{decoder?.stop()};decoder?.release();extractor.release()}
    }
    @Test fun independentKeystoreEnvelopeTamperDetectionAndRetiredProviderRemoval() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val vault=MusicAccessStore(context)
        val token="vk1.a."+"SYNTHETIC_SECRET_".repeat(8)
        try {
            vault.delete();vault.save(MusicAccess(token,"Synthetic Client/1",expiresAtMs=1_900_000_000_000,
                renewal=WebRenewal("vk1.a."+"PARENT_SECRET_".repeat(8),12,listOf("native_session=COOKIE_SECRET; path=/; domain=vk.com; secure"))))
            val file=File(context.noBackupFilesDir,"native-music-access.bin")
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token));assertEquals(token,MusicAccessStore(context).read()!!.token)
            assertEquals(1_900_000_000_000,MusicAccessStore(context).read()!!.expiresAtMs)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("COOKIE_SECRET"))
            assertTrue(MusicAccessStore(context).read()!!.renewal!!.parent.contains("PARENT_SECRET"))
            val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
            assertThrows(Exception::class.java){vault.read()}
            vault.save(MusicAccess(token,"Synthetic Client/1"));assertFalse(Diagnostics.export().contains(token))
        }finally{vault.delete()}
        val flags=android.content.pm.PackageManager.ComponentInfoFlags.of(0)
        assertNull(context.packageManager.resolveContentProvider("${context.packageName}.audio",flags))
        assertNotNull(context.packageManager.resolveContentProvider("${context.packageName}.diagnostics",flags))
        assertNotNull(context.packageManager.resolveContentProvider("${context.packageName}.updates",flags))
    }
}

@RunWith(AndroidJUnit4::class)
class NativeMusicUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun nativePanelRetainsProtectedAccessAndRejectsWrongServiceCredentialWithoutWritingIt() {
        val collection=ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes()
        ui.onNodeWithText("VK Music").performScrollTo().performClick()
        ui.onNodeWithText("Настроить доступ ВК").performScrollTo().performClick()
        ui.onNodeWithTag("native_vk_token").performTextInput("github_pat_"+"WRONG_SERVICE_".repeat(8))
        ui.onNodeWithText("Сохранить и проверить ВК").performScrollTo().performClick()
        ui.onNodeWithText(MusicError.INVALID_ACCESS.label).assertExists()
        assertFalse(File(ui.activity.noBackupFilesDir,"native-music-access.bin").exists())
        ui.onNodeWithText("Отмена").performScrollTo().performClick();ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasText("Закрыть"));ui.onNodeWithText("Закрыть").performClick()
        assertArrayEquals(collection,ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes())
    }
}
