package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import okhttp3.*
import java.io.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

internal data class AudioProgress(val stage: String,val bytes: Long=0,val segment: Int=0,val segments: Int=0)
internal data class ProcessedAudio(val extension: String,val mime: String,val durationMs: Long)
internal fun interface AudioProcessor {
    suspend fun process(input: File,output: File,track: NativeTrack,hlsSeconds: Double?): ProcessedAudio
}
internal class TransferResult(val file: File,val media: ProcessedAudio)
internal class AudioTransfer(private val client: OkHttpClient = NetworkClients.media(),private val processor: AudioProcessor = AndroidAudioProcessor(),
    private val observe:(String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)}) {
    companion object {const val MAX_BYTES=100L*1024*1024;const val MAX_SEGMENT=16*1024*1024}
    private class Resource(val url: String,val bytes: Long)
    private suspend fun resource(url: String,max: Long,consume: suspend (Response)->Long): Resource {
        var next=MediaAddress.require(url).toString()
        repeat(6) { attempt ->
            val request=Request.Builder().url(next).header("User-Agent","VK-Music-Export-Transfer/1").get().build()
            var location: String?=null;var size=0L
            client.newCall(request).withResponse { r ->
                observe("native_audio_response",mapOf("http_status" to r.code,"transport" to "native_https_cdn","declared_bytes" to (r.body?.contentLength()?:-1)))
                if(r.code in setOf(301,302,303,307,308)) {
                    musicCheck(attempt<5,MusicError.HTTP)
                    location=MediaAddress.resolve(next,r.header("Location")?:throw MusicFailure(MusicError.HTTP))
                }else {
                    musicCheck(r.code==200,if(r.code==429)MusicError.RATE_LIMIT else MusicError.HTTP)
                    val body=r.body?:throw MusicFailure(MusicError.HTTP)
                    musicCheck(body.contentLength()<=max,MusicError.TOO_LARGE)
                    size=consume(r)
                    musicCheck(size<=max,MusicError.TOO_LARGE)
                    musicCheck(body.contentLength()<0||body.contentLength()==size,MusicError.INCOMPLETE)
                }
            }
            if(location==null)return Resource(next,size)
            next=location!!
        }
        throw MusicFailure(MusicError.HTTP)
    }
    private suspend fun bytes(url: String,max: Int): Pair<String,ByteArray> {
        val out=ByteArrayOutputStream()
        val resource=resource(url,max.toLong()){r->copy(r.body!!.byteStream(),out,max.toLong())}
        return resource.url to out.toByteArray()
    }
    private suspend fun copy(input: InputStream,out: OutputStream,max: Long,onBytes:(Long)->Unit={}): Long {
        val buffer=ByteArray(32768);var total=0L
        input.use {while(true){currentCoroutineContext().ensureActive();val n=it.read(buffer);if(n<0)break
            total+=n;musicCheck(total<=max,MusicError.TOO_LARGE);out.write(buffer,0,n);onBytes(total)}}
        return total
    }
    suspend fun download(url: String,track: NativeTrack,directory: File,progress:(AudioProgress)->Unit): TransferResult = withContext(Dispatchers.IO) {
        musicCheck(track.duration>0&&!track.restricted,MusicError.UNAVAILABLE)
        directory.mkdirs();val id=UUID.randomUUID().toString()
        val raw=File(directory,"$id.media");val converted=File(directory,"$id.output")
        try {
            withTimeout(10*60*1000L) {
                progress(AudioProgress("receiving"))
                var manifest=false
                val first=FileOutputStream(raw).use { out ->
                    resource(url,MAX_BYTES){r->
                        manifest=r.body!!.contentType()?.toString()?.contains("mpegurl",ignoreCase=true)==true
                        copy(r.body!!.byteStream(),out,if(manifest)524288 else MAX_BYTES){n->progress(AudioProgress("receiving",n))}
                    }.also{out.fd.sync()}
                }
                val prefix=raw.inputStream().use{it.readNBytes(16)}.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                manifest=manifest||prefix.trimStart().startsWith("#EXTM3U")
                var hlsSeconds: Double?=null
                if(manifest) {
                    musicCheck(raw.length()<=524288,MusicError.TOO_LARGE)
                    var base=first.url;var text=strictUtf8(raw.readBytes());var plan: HlsPlan?=null
                    repeat(4) {
                        if(plan==null) {
                            val p=HlsPlaylist.parse(base,text)
                            if(p.redirect==null)plan=p else {val b=bytes(p.redirect,524288);base=b.first;text=strictUtf8(b.second)}
                        }
                    }
                    val media=plan?:throw MusicFailure(MusicError.HLS);hlsSeconds=media.duration
                    var total=0L
                    val keys=mutableMapOf<String,ByteArray>()
                    FileOutputStream(raw,false).use { output ->
                        media.init?.let { init ->
                            val data=bytes(init,MAX_SEGMENT).second;total+=data.size;output.write(data)
                        }
                        for((i,segment) in media.segments.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val encrypted=bytes(segment.url,MAX_SEGMENT).second
                            val data=if(segment.key==null)encrypted else {
                                val key=keys[segment.key.url]?:bytes(segment.key.url,16).second.also{
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
                    }
                }
                musicCheck(raw.length()>0,MusicError.INCOMPLETE)
                progress(AudioProgress("verifying",raw.length()))
                val media=processor.process(raw,converted,track,hlsSeconds)
                currentCoroutineContext().ensureActive()
                musicCheck(converted.length() in 1..MAX_BYTES,MusicError.INCOMPLETE)
                TransferResult(converted,media)
            }
        }catch(_: TimeoutCancellationException){converted.delete();throw MusicFailure(MusicError.NETWORK)}
         catch(e: CancellationException){converted.delete();throw e}
         catch(e: MusicFailure){converted.delete();throw e}
         catch(_: SSLException){converted.delete();throw MusicFailure(MusicError.TLS)}
         catch(_: IOException){converted.delete();throw MusicFailure(MusicError.NETWORK)}
         catch(_: Exception){converted.delete();throw MusicFailure(MusicError.FORMAT)}
         finally {raw.delete()}
    }
}
