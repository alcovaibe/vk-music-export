package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@androidx.annotation.OptIn(UnstableApi::class)
class AudioTransferTest {
    private val track=NativeTrack("12_34","Название","Исполнитель",2)
    private fun client(route:(Request)->Pair<Int,ByteArray>,requests: MutableList<Request>)=NetworkClients.media().newBuilder().addInterceptor {chain->
        val r=chain.request();requests.add(r);val response=route(r)
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(response.first).message("Synthetic").body(response.second.toResponseBody("application/octet-stream".toMediaType())).build()
    }.build()
    @Test fun completeEncryptedHlsUsesAllSegmentsOriginalIvAndNeverSendsApiSecrets()=runBlocking {
        val directory=Files.createTempDirectory("audio-transfer").toFile();val requests=mutableListOf<Request>()
        val key=ByteArray(16){it.toByte()};val partA="FIRST".toByteArray();val partB="SECOND".toByteArray()
        fun encrypt(bytes: ByteArray,n: Long)=Cipher.getInstance("AES/CBC/PKCS5Padding").run{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(HlsPlaylist.sequenceIv(n)));doFinal(bytes)}
        val routes=mapOf("/list" to "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:257\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na\n#EXTINF:1,\nb\n#EXT-X-ENDLIST".toByteArray(),
            "/key" to key,"/a" to encrypt(partA,257),"/b" to encrypt(partB,258))
        var called=false
        val processor=AudioProcessor{input,output,_,duration->called=true;assertEquals(2.0,duration!!,0.0);assertArrayEquals(partA+partB,input.readBytes());output.writeBytes("VERIFIED".toByteArray());ProcessedAudio("mp3","audio/mpeg",2000)}
        try {
            val result=AudioTransfer(client({200 to routes.getValue(it.url.encodedPath)},requests),processor){_,_->}
                .download("https://audio.userapi.com/list?signature=PRIVATE",track,directory){}
            assertTrue(called);assertEquals("VERIFIED",result.file.readText());assertEquals(4,requests.size)
            requests.forEach{r->assertNull(r.header("Authorization"));assertNull(r.header("Cookie"));assertNull(r.header("Referer"));assertEquals("VK-Music-Export-Transfer/1",r.header("User-Agent"))}
            assertEquals(1,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun failedSegmentAbortsAndRemovesPartialFilesWithoutInvokingProcessor()=runBlocking {
        val directory=Files.createTempDirectory("audio-missing").toFile();var processed=false
        val requests=mutableListOf<Request>()
        val c=client({when(it.url.encodedPath){"/list"->200 to "#EXTM3U\n#EXTINF:1,\na\n#EXTINF:1,\nb\n#EXT-X-ENDLIST".toByteArray();"/a"->200 to "PART".toByteArray();else->503 to "PRIVATE_ERROR".toByteArray()}},requests)
        try {
            try {AudioTransfer(c,AudioProcessor{_,_,_,_->processed=true;error("must not run")}){_,_->}.download("https://a.userapi.com/list",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.HTTP,e.code);assertFalse(e.toString().contains("PRIVATE"))}
            assertFalse(processed);assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun cancellationDuringStreamingRemovesTemporaryAndNoReadyResultIsReturned()=runBlocking {
        val directory=Files.createTempDirectory("audio-cancel").toFile();val started=CompletableDeferred<Unit>()
        val c=NetworkClients.media().newBuilder().addInterceptor {chain->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(ByteArray(1024*1024).toResponseBody()).build()
        }.build()
        try {
            val operation=async(Dispatchers.Default){AudioTransfer(c,AudioProcessor{_,_,_,_->error("must not run")}){_,_->}
                .download("https://a.userapi.com/f",track,directory){if(it.bytes>0){started.complete(Unit);Thread.sleep(10)}}}
            started.await();operation.cancelAndJoin();assertTrue(operation.isCancelled);assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun unknownFormatsAndFailedDurationValidationNeverLeaveAnOutput()=runBlocking {
        val directory=Files.createTempDirectory("audio-invalid").toFile();val requests=mutableListOf<Request>()
        val c=client({200 to "NOT_AUDIO".toByteArray()},requests)
        try {
            try {AudioTransfer(c,AudioProcessor{_,output,_,_->output.writeText("PARTIAL");throw MusicFailure(MusicError.INCOMPLETE)}){_,_->}
                .download("https://a.userapi.com/f",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
            assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun mediaParserIOExceptionAfterSuccessfulResponseIsFormatAndNeverLeaksItsMessage()=runBlocking {
        val directory=Files.createTempDirectory("audio-parse-io").toFile()
        val events=mutableListOf<Pair<String,Map<String,Any?>>>();val requests=mutableListOf<Request>()
        try {
            val transfer=AudioTransfer(client({200 to "ENCODED_AUDIO".toByteArray()},requests),AudioProcessor{_,output,_,_->
                output.writeText("PARTIAL")
                throw ParserException.createForMalformedContainer("PRIVATE_TOKEN https://a.userapi.com/PRIVATE_PATH?signature=PRIVATE",null)
            }){type,fields->events.add(type to fields)}
            try{transfer.download("https://a.userapi.com/PRIVATE_PATH?signature=PRIVATE",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.FORMAT,e.code);assertEquals(MusicFailureKind.MEDIA_PARSE,e.kind);assertNull(e.cause)}
            assertTrue(events.any{it.first=="native_audio_response"&&it.second["http_status"]==200})
            val failure=events.single{it.first=="native_audio_failure"}.second
            assertEquals("verifying",failure["stage"]);assertEquals("MEDIA_PARSE",failure["kind"])
            assertFalse(events.toString().contains("PRIVATE"));assertFalse(events.toString().contains("https://"))
            assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun localProcessorIoAndPrematureEndHaveDifferentCodesAndAlwaysRemovePartialFiles()=runBlocking {
        val directory=Files.createTempDirectory("audio-local-io").toFile()
        try {
            for((problem,expected) in listOf(IOException("PRIVATE_DISK_PATH") to MusicError.STORAGE,EOFException("PRIVATE_MEDIA") to MusicError.INCOMPLETE)) {
                val events=mutableListOf<Pair<String,Map<String,Any?>>>()
                val transfer=AudioTransfer(client({200 to "AUDIO".toByteArray()},mutableListOf()),AudioProcessor{_,output,_,_->output.writeText("PARTIAL");throw problem}){t,f->events.add(t to f)}
                try{transfer.download("https://a.userapi.com/f",track,directory){};fail()}
                catch(e: MusicFailure){assertEquals(expected,e.code)}
                assertEquals("verifying",events.single{it.first=="native_audio_failure"}.second["stage"])
                assertFalse(events.toString().contains("PRIVATE"));assertEquals(0,directory.listFiles()!!.size)
            }
        }finally{directory.deleteRecursively()}
    }
    @Test fun dnsFailureBeforeHeadersIsNetworkAndOnlyItsFixedCategoryIsRecorded()=runBlocking {
        val directory=Files.createTempDirectory("audio-dns").toFile();val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val c=NetworkClients.media().newBuilder().addInterceptor{throw UnknownHostException("PRIVATE_HOST_AND_TOKEN")}.build()
        try {
            try{AudioTransfer(c,AudioProcessor{_,_,_,_->error("must not run")}){t,f->events.add(t to f)}.download("https://a.userapi.com/f",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code);assertEquals(MusicFailureKind.DNS,e.kind)}
            assertTrue(events.any{it.first=="native_audio_request"});assertFalse(events.any{it.first=="native_audio_response"})
            assertEquals("receiving",events.single{it.first=="native_audio_failure"}.second["stage"])
            assertFalse(events.toString().contains("PRIVATE"));assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun socketFailureWhileReadingBodyStaysNetworkAndDoesNotInvokeLocalProcessor()=runBlocking {
        val directory=Files.createTempDirectory("audio-body-io").toFile();val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        var processed=false
        val c=NetworkClients.media().newBuilder().addInterceptor {chain->
            val body=object: ResponseBody() {
                override fun contentType()="audio/mpeg".toMediaType()
                override fun contentLength()=1024L
                override fun source()=object: Source {
                    override fun read(sink: Buffer,byteCount: Long): Long=throw SocketException("PRIVATE_CDN_ERROR")
                    override fun timeout()=Timeout.NONE
                    override fun close()=Unit
                }.buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body).build()
        }.build()
        try {
            try{AudioTransfer(c,AudioProcessor{_,_,_,_->processed=true;error("must not run")}){t,f->events.add(t to f)}.download("https://a.userapi.com/f",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code);assertEquals(MusicFailureKind.SOCKET,e.kind)}
            assertFalse(processed);assertTrue(events.any{it.first=="native_audio_response"})
            assertFalse(events.any{it.first=="native_audio_resource_complete"})
            assertFalse(events.toString().contains("PRIVATE"));assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
    @Test fun unusableTemporaryDirectoryIsStorageWithoutAnyNetworkRequest()=runBlocking {
        val parent=Files.createTempDirectory("audio-directory").toFile();val directory=java.io.File(parent,"not-a-directory").apply{writeText("keep")}
        val requests=mutableListOf<Request>();val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        try {
            try{AudioTransfer(client({200 to byteArrayOf()},requests),AudioProcessor{_,_,_,_->error("must not run")}){t,f->events.add(t to f)}.download("https://a.userapi.com/f",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertTrue(requests.isEmpty());assertEquals("keep",directory.readText())
            assertEquals("preparing",events.single{it.first=="native_audio_failure"}.second["stage"])
        }finally{parent.deleteRecursively()}
    }
    @Test fun transferDeadlineIsTimeoutWhileParentTimeoutRemainsCancellation()=runBlocking {
        val directory=Files.createTempDirectory("audio-deadline").toFile();val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val c=client({200 to "AUDIO".toByteArray()},mutableListOf())
        val processor=AudioProcessor{_,output,_,_->output.writeText("PARTIAL");awaitCancellation()}
        try {
            try{AudioTransfer(c,processor,timeoutMs=200){t,f->events.add(t to f)}.download("https://a.userapi.com/f",track,directory){};fail()}
            catch(e: MusicFailure){assertEquals(MusicError.TIMEOUT,e.code)}
            assertEquals(0,directory.listFiles()!!.size);events.clear()
            try{withTimeout(200){AudioTransfer(c,processor){t,f->events.add(t to f)}.download("https://a.userapi.com/f",track,directory){}};fail()}
            catch(_: TimeoutCancellationException){/* Parent cancellation must not become a network failure. */}
            assertTrue(events.any{it.first=="native_audio_cancelled"});assertFalse(events.any{it.first=="native_audio_failure"})
            assertEquals(0,directory.listFiles()!!.size)
        }finally{directory.deleteRecursively()}
    }
}
