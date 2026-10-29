package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import okhttp3.*
import java.io.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

internal data class AudioProgress(val stage: String,val bytes: Long=0,val segment: Int=0,val segments: Int=0)
internal data class ProcessedAudio(val extension: String,val mime: String,val durationMs: Long)
internal fun interface AudioProcessor {
    suspend fun process(input: File,output: File,track: NativeTrack,hlsSeconds: Double?): ProcessedAudio
}
internal class TransferResult(val file: File,val media: ProcessedAudio,val coverEmbedded: Boolean=false)
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioTransfer(private val client: OkHttpClient = NetworkClients.media(),private val processor: AudioProcessor = AndroidAudioProcessor(),
    private val timeoutMs: Long=10*60*1000L,
    private val observe:(String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)}) {
    companion object {const val MAX_BYTES=100L*1024*1024;const val MAX_SEGMENT=16*1024*1024}
    private class Trace(val id: String,val observe:(String,Map<String,Any?>)->Unit) {
        var stage="preparing";private var requests=0
        fun stage(value: String){stage=value;event("native_audio_stage",emptyMap())}
        fun request(kind: String,hop: Int): Map<String,Any?> = mapOf("request_seq" to ++requests,"resource_kind" to kind,"redirect_hop" to hop)
        fun event(type: String,fields: Map<String,Any?>){observe(type,mapOf("transfer_id" to id,"stage" to stage)+fields)}
    }
    // Socket reads are classified at resource(); local opens, writes, syncs and closes here.
    private inline fun <T> storage(block:()->T): T = try{block()}
        catch(_: IOException){throw MusicFailure(MusicError.STORAGE,kind=MusicFailureKind.IO)}
    private class Resource(val url: String,val bytes: Long)
    private suspend fun resource(url: String,max: Long,trace: Trace,kind: String,consume: suspend (Response)->Long): Resource {
        var next=MediaAddress.require(url).toString()
        repeat(6) { attempt ->
            val request=Request.Builder().url(next).header("User-Agent","VK-Music-Export-Transfer/1").get().build()
            var location: String?=null;var size=0L
            val metadata=trace.request(kind,attempt)
            trace.event("native_audio_request",metadata)
            try {client.newCall(request).withResponse { r ->
                val mime=r.body?.contentType()
                val mediaKind=when{mime==null->"missing";mime.toString().contains("mpegurl",true)->"hls";mime.type=="audio"->"audio";else->"other"}
                trace.event("native_audio_response",metadata+mapOf("http_status" to r.code,"transport" to "native_https_cdn","declared_bytes" to (r.body?.contentLength()?:-1),"media_kind" to mediaKind))
                if(r.code in setOf(301,302,303,307,308)) {
                    musicCheck(attempt<5,MusicError.HTTP)
                    location=MediaAddress.resolve(next,r.header("Location")?:throw MusicFailure(MusicError.HTTP))
                }else {
                    if(r.code!=200)throw MediaHttpFailure(r.code)
                    val body=r.body?:throw MusicFailure(MusicError.HTTP)
                    musicCheck(body.contentLength()<=max,MusicError.TOO_LARGE)
                    size=consume(r)
                    musicCheck(size<=max,MusicError.TOO_LARGE)
                    musicCheck(body.contentLength()<0||body.contentLength()==size,MusicError.INCOMPLETE)
                }
            }}catch(e: IOException){currentCoroutineContext().ensureActive();throw networkFailure(e)}
            trace.event("native_audio_resource_complete",metadata+mapOf("received_bytes" to size,"redirect" to (location!=null)))
            if(location==null)return Resource(next,size)
            next=location!!
        }
        throw MusicFailure(MusicError.HTTP)
    }
    private suspend fun bytes(url: String,max: Int,trace: Trace,kind: String): Pair<String,ByteArray> {
        val out=ByteArrayOutputStream()
        val resource=resource(url,max.toLong(),trace,kind){r->copy(r.body!!.byteStream(),out,max.toLong())}
        return resource.url to out.toByteArray()
    }
    private suspend fun copy(input: InputStream,out: OutputStream,max: Long,onBytes:(Long)->Unit={}): Long {
        val buffer=ByteArray(32768);var total=0L
        input.use {while(true){currentCoroutineContext().ensureActive();val n=it.read(buffer);if(n<0)break
            total+=n;musicCheck(total<=max,MusicError.TOO_LARGE);storage{out.write(buffer,0,n)};onBytes(total)}}
        return total
    }
    suspend fun download(url: String,track: NativeTrack,directory: File,progress:(AudioProgress)->Unit): TransferResult = withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString();val trace=Trace(id,observe)
        val raw=File(directory,"$id.media");val converted=File(directory,"$id.output")
        try {
            trace.stage("preparing")
            musicCheck(track.duration>0&&!track.restricted,MusicError.UNAVAILABLE)
            musicCheck(directory.isDirectory||directory.mkdirs(),MusicError.STORAGE)
            withTimeoutOrNull(timeoutMs) {
                trace.stage("receiving")
                progress(AudioProgress("receiving"))
                var manifest=false
                val first=storage{FileOutputStream(raw).use { out ->
                    resource(url,MAX_BYTES,trace,"initial"){r->
                        manifest=r.body!!.contentType()?.toString()?.contains("mpegurl",ignoreCase=true)==true
                        copy(r.body!!.byteStream(),out,if(manifest)524288 else MAX_BYTES){n->progress(AudioProgress("receiving",n))}
                    }.also{out.fd.sync()}
                }}
                val prefix=storage{raw.inputStream().use{it.readNBytes(16)}}.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                manifest=manifest||prefix.trimStart().startsWith("#EXTM3U")
                var hlsSeconds: Double?=null
                if(manifest) {
                    trace.stage("manifest")
                    progress(AudioProgress("manifest",raw.length()))
                    musicCheck(raw.length()<=524288,MusicError.TOO_LARGE)
                    var base=first.url;var text=strictUtf8(storage{raw.readBytes()});var plan: HlsPlan?=null
                    repeat(4) {
                        if(plan==null) {
                            val p=HlsPlaylist.parse(base,text)
                            if(p.redirect==null)plan=p else {val b=bytes(p.redirect,524288,trace,"manifest");base=b.first;text=strictUtf8(b.second)}
                        }
                    }
                    val media=plan?:throw MusicFailure(MusicError.HLS);hlsSeconds=media.duration
                    trace.stage("segments")
                    progress(AudioProgress("segments",segments=media.segments.size))
                    trace.event("native_audio_hls_plan",mapOf("segments" to media.segments.size,"init_present" to (media.init!=null)))
                    var total=0L
                    val keys=mutableMapOf<String,ByteArray>()
                    storage{FileOutputStream(raw,false).use { output ->
                        media.init?.let { init ->
                            val data=bytes(init,MAX_SEGMENT,trace,"init").second;total+=data.size;output.write(data)
                        }
                        for((i,segment) in media.segments.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val encrypted=bytes(segment.url,MAX_SEGMENT,trace,"segment").second
                            val data=if(segment.key==null)encrypted else {
                                val key=keys[segment.key.url]?:bytes(segment.key.url,16,trace,"key").second.also{
                                    musicCheck(it.size==16&&keys.size<64,MusicError.HLS);keys[segment.key.url]=it
                                }
                                try {
                                    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                                        init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(segment.key.iv));doFinal(encrypted)
                                    }
                                }catch(_: Exception){throw MusicFailure(MusicError.INCOMPLETE)}
                            }
                            total+=data.size;musicCheck(total<=MAX_BYTES,MusicError.TOO_LARGE)
                            output.write(data);progress(AudioProgress("segments",total,i+1,media.segments.size))
                        }
                        output.fd.sync()
                    }}
                }
                musicCheck(raw.length()>0,MusicError.INCOMPLETE)
                trace.stage("verifying")
                progress(AudioProgress("verifying",raw.length()))
                // Media3 ParserException extends IOException. Local parsing is never a network request.
                val media=try{processor.process(raw,converted,track,hlsSeconds)}
                    catch(e: CancellationException){throw e}
                    catch(e: MusicFailure){throw e}
                    catch(_: ParserException){throw MusicFailure(MusicError.FORMAT,kind=MusicFailureKind.MEDIA_PARSE)}
                    catch(_: EOFException){throw MusicFailure(MusicError.INCOMPLETE,kind=MusicFailureKind.EOF)}
                    catch(_: IOException){throw MusicFailure(MusicError.STORAGE,kind=MusicFailureKind.IO)}
                currentCoroutineContext().ensureActive()
                musicCheck(converted.length() in 1..MAX_BYTES,MusicError.INCOMPLETE)
                trace.stage("verified")
                TransferResult(converted,media)
            }?:throw MusicFailure(MusicError.TIMEOUT,kind=MusicFailureKind.TIMEOUT)
        }catch(e: CancellationException){converted.delete();trace.event("native_audio_cancelled",emptyMap());throw e}
         catch(e: MusicFailure){converted.delete();trace.event("native_audio_failure",mapOf("code" to e.code.name,"kind" to e.kind?.name));throw e}
         catch(_: IOException){converted.delete();trace.event("native_audio_failure",mapOf("code" to "STORAGE","kind" to "IO"));throw MusicFailure(MusicError.STORAGE,kind=MusicFailureKind.IO)}
         catch(_: Exception){converted.delete();trace.event("native_audio_failure",mapOf("code" to "FORMAT","kind" to "OTHER"));throw MusicFailure(MusicError.FORMAT,kind=MusicFailureKind.OTHER)}
         finally {raw.delete()}
    }
}
