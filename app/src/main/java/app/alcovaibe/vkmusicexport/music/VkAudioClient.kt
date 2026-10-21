package app.alcovaibe.vkmusicexport.music

import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface MusicApi {
    suspend fun library(access: MusicAccess): NativeProbe
    suspend fun catalog(access: MusicAccess, owner: Long): NativeCatalog
    suspend fun playlist(access: MusicAccess, list: NativePlaylist): NativePage
    suspend fun media(access: MusicAccess, track: NativeTrack): String
    suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
        musicCheck(offset==0,MusicError.INCOMPLETE);return library(access).library
    }
    suspend fun catalogPage(access: MusicAccess,owner: Long,offset: Int): NativeCatalog {
        musicCheck(offset==0,MusicError.INCOMPLETE);return catalog(access,owner)
    }
    suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
        musicCheck(offset==0,MusicError.INCOMPLETE);return playlist(access,list)
    }
}

private object MusicApiPacer {
    private val mutex=Mutex();private var last=0L
    suspend fun awaitSlot()=mutex.withLock {
        val now=System.nanoTime();val wait=350_000_000L-(now-last)
        if(wait>0)delay((wait+999_999)/1_000_000)
        last=System.nanoTime()
    }
}

internal class VkAudioClient(private val client: OkHttpClient = NetworkClients.api(),private val observe:(String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)}) : MusicApi {
    private suspend fun request(access: MusicAccess, method: String, fields: Map<String,String> = emptyMap()): JsonElement {
        musicCheck(NativeProtocol.validAccess(access.token,access.agent),MusicError.INVALID_ACCESS)
        musicCheck(method in NativeProtocol.methods && fields.keys.none { it in setOf("access_token","v","method") })
        val form = FormBody.Builder().add("access_token",access.token).add("v","5.199")
        fields.forEach { (k,v) -> form.add(k,v) }
        val req = Request.Builder().url("https://api.vk.com/method/$method")
            .header("User-Agent",access.agent).post(form.build()).build()
        val step = when(method) {"users.get"->"identity";"audio.get"->"tracks";"audio.getPlaylists"->"catalog";else->"resolve_media"}
        observe("native_music_request",mapOf("stage" to step,"phase" to "start","transport" to "native_https_api"))
        return try {
            MusicApiPacer.awaitSlot()
            val response = client.newCall(req).readBounded(1_048_576)
            observe("native_music_request",mapOf("stage" to step,"phase" to "response","http_status" to response.status,"bytes" to response.bytes.size))
            if(response.status==429)throw MusicFailure(MusicError.RATE_LIMIT)
            if(response.status in setOf(401,403))throw MusicFailure(MusicError.ACCESS)
            musicCheck(response.status==200,MusicError.HTTP)
            withContext(Dispatchers.Default){NativeProtocol.envelope(strictUtf8(response.bytes))}
        } catch(e: CancellationException){throw e}
          catch(e: MusicFailure){observe("native_music_failure",mapOf("stage" to step,"code" to e.code.name,"vk_code" to e.remoteCode,"kind" to e.kind?.name));throw e}
          catch(e: Exception){
              currentCoroutineContext().ensureActive()
              val failure=if(e is IOException)networkFailure(e) else MusicFailure(MusicError.RESPONSE,kind=MusicFailureKind.OTHER)
              observe("native_music_failure",mapOf("stage" to step,"code" to failure.code.name,"kind" to failure.kind?.name))
              throw failure
          }
    }
    override suspend fun library(access: MusicAccess): NativeProbe {
        val owner = NativeProtocol.owner(request(access,"users.get"))
        val page = libraryPage(access,owner,0)
        return NativeProbe(owner,page)
    }
    override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
        musicCheck(offset in 0..1_000_000)
        return NativeProtocol.page(request(access,"audio.get",mapOf("owner_id" to owner.toString(),"count" to NativeProtocol.LIMIT.toString(),"offset" to offset.toString())))
    }
    override suspend fun catalog(access: MusicAccess, owner: Long)=catalogPage(access,owner,0)
    override suspend fun catalogPage(access: MusicAccess,owner: Long,offset: Int): NativeCatalog {
        musicCheck(offset in 0..1_000_000)
        return NativeProtocol.catalog(request(access,"audio.getPlaylists",mapOf("owner_id" to owner.toString(),"count" to NativeProtocol.LIMIT.toString(),"offset" to offset.toString())))
    }
    override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
    override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
        musicCheck(offset in 0..1_000_000)
        val params = mutableMapOf("owner_id" to list.owner.toString(),"playlist_id" to list.id.toString(),"count" to NativeProtocol.LIMIT.toString(),"offset" to offset.toString())
        list.accessKey?.let {params["access_key"]=it}
        return NativeProtocol.page(request(access,"audio.get",params))
    }
    override suspend fun media(access: MusicAccess,track: NativeTrack): String = NativeProtocol.media(
        request(access,"audio.getById",mapOf("audios" to track.id)),track)
}

internal object NetworkClients {
    fun api() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build()
    fun media() = api().newBuilder().callTimeout(2,TimeUnit.MINUTES).build()
}
internal data class BoundedResponse(val status: Int,val bytes: ByteArray,val location: String?,val mediaType: String? = null,
    val charset: String? = null)
internal fun strictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}catch(_: Exception){throw MusicFailure(MusicError.RESPONSE)}

/** Blocking stream IO belongs to Dispatchers.IO; cancellation closes the actual socket too. */
internal suspend fun <T> Call.withResponse(block: suspend (Response)->T): T = withContext(Dispatchers.IO) {
    val call=this@withResponse
    val watcher=launch(start=CoroutineStart.UNDISPATCHED) {try{awaitCancellation()}finally{call.cancel()}}
    try {call.execute().use {currentCoroutineContext().ensureActive();block(it)}}
    finally {watcher.cancel()}
}
internal suspend fun Call.readBounded(max: Int): BoundedResponse = withResponse { r ->
    val body=r.body ?: throw MusicFailure(MusicError.RESPONSE)
    musicCheck(body.contentLength()<=max,MusicError.TOO_LARGE)
    val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
    body.byteStream().use { input ->
        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
            musicCheck(out.size()+n<=max,MusicError.TOO_LARGE);out.write(buffer,0,n)}
    }
    musicCheck(body.contentLength()<0 || out.size().toLong()==body.contentLength(),MusicError.INCOMPLETE)
    BoundedResponse(r.code,out.toByteArray(),r.header("Location"),body.contentType()?.let{it.type+"/"+it.subtype},
        body.contentType()?.charset()?.name())
}
