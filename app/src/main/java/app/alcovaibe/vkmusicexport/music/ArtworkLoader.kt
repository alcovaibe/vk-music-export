package app.alcovaibe.vkmusicexport.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/** Separate from audio URLs. No credential, cookie or API User-Agent is sent to an image host. */
internal object ArtworkAddress {
    fun require(raw: String): HttpUrl {
        musicCheck(raw.length in 1..8192&&raw.none{it<=' '||it=='\\'||it=='\u007f'},MusicError.MEDIA_URL)
        val u=raw.toHttpUrlOrNull()?:throw MusicFailure(MusicError.MEDIA_URL)
        musicCheck(u.scheme=="https"&&u.port==443&&u.username.isEmpty()&&u.password.isEmpty()&&u.fragment==null&&
            setOf("userapi.com","vk-cdn.net","vkcdn.net","vkuserphoto.ru").any{u.host==it||u.host.endsWith(".$it")},MusicError.MEDIA_URL)
        return u
    }
}
internal fun interface ArtworkSource {suspend fun load(access: MusicAccess,track: NativeTrack): CoverImage?}
internal class ArtworkLoader(private val api: MusicApi,private val client: OkHttpClient=NetworkClients.api(),private val onStep:(ArtworkStep)->Unit={}): ArtworkSource {
    // Small in-memory cache only; signed URLs and API response bodies never enter SQLite or logs.
    private val cache=object: LinkedHashMap<String,CoverImage>(8,.75f,true){override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,CoverImage>?)=size>4}
    override suspend fun load(access: MusicAccess,track: NativeTrack): CoverImage?=withContext(Dispatchers.IO) {
        onStep(ArtworkStep.RESOLVE_COVER)
        val raw=api.artwork(access,track)?:return@withContext null
        val first=ArtworkAddress.require(raw).toString()
        cache[first]?.let{return@withContext it}
        withTimeoutOrNull(20_000) {
            var url=first
            repeat(4){hop->
                onStep(ArtworkStep.FETCH_COVER)
                val req=Request.Builder().url(url).header("User-Agent","VK-Music-Export-Artwork/1").get().build()
                val response=client.newCall(req).readBounded(2*1024*1024)
                if(response.status in setOf(301,302,303,307,308)) {
                    if(hop>=3)throw ArtworkFetchFailure(response.status,ArtworkFetchReason.REDIRECT_LIMIT)
                    val location=response.location?:throw ArtworkFetchFailure(response.status,ArtworkFetchReason.REDIRECT_LOCATION_MISSING)
                    url=try{ArtworkAddress.require(ArtworkAddress.require(url).resolve(location)?.toString()?:throw MusicFailure(MusicError.MEDIA_URL)).toString()}
                        catch(_: MusicFailure){throw ArtworkFetchFailure(response.status,ArtworkFetchReason.REDIRECT_UNSAFE,MusicError.MEDIA_URL)}
                }else {
                    if(response.status!=200)throw ArtworkFetchFailure(response.status,ArtworkFetchReason.HTTP_STATUS)
                    onStep(ArtworkStep.DECODE_COVER)
                    val image=decode(response.bytes);cache[first]=image;return@withTimeoutOrNull image
                }
            }
            throw MusicFailure(MusicError.HTTP)
        }?:throw MusicFailure(MusicError.TIMEOUT)
    }
    companion object {
        fun decode(bytes: ByteArray): CoverImage {
            musicCheck(bytes.size in 1..2*1024*1024,MusicError.FORMAT)
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            musicCheck(options.outWidth in 1..4096&&options.outHeight in 1..4096&&options.outWidth.toLong()*options.outHeight<=16_000_000&&
                options.outMimeType in setOf("image/jpeg","image/png","image/webp"),MusicError.FORMAT)
            options.inJustDecodeBounds=false;options.inSampleSize=1
            while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:throw MusicFailure(MusicError.FORMAT)
            return try{val out=ByteArrayOutputStream();musicCheck(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out),MusicError.FORMAT);CoverImage(out.toByteArray(),"image/jpeg")}finally{bitmap.recycle()}
        }
    }
}
