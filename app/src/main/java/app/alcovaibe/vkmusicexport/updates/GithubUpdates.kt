package app.alcovaibe.vkmusicexport.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

internal interface UpdateService {
    suspend fun latest(token: String): UpdateRelease
    suspend fun download(token: String,release: UpdateRelease,progress: (Long,Long)->Unit): File
    suspend fun verify(file: File,release: UpdateRelease)
}

internal class GithubUpdates(private val context: Context?, private val client: OkHttpClient=newClient()) : UpdateService, AutoCloseable {
    private var highest=0
    private val own=java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    fun purgeOrphans()=synchronized(filesLock) {
        val directory=File((context ?: return@synchronized).cacheDir,"updates")
        owned.removeIf{!it.exists()}
        directory.listFiles()?.filter{it !in owned}?.forEach{it.delete()}
    }
    private suspend fun <T> response(url: String,token: String,accept: String,cleanup: ()->Unit={},handler: (Response)->T): T = suspendCancellableCoroutine { cont ->
        val request=request(url,token,accept)
        val call=client.newCall(request)
        if(accept=="application/json")call.timeout().timeout(15,TimeUnit.SECONDS)
        cont.invokeOnCancellation{call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException){if(cont.isActive)cont.resumeWithException(UpdateProblem("NETWORK"))}
            override fun onResponse(call: Call,response: Response) {
                if(!cont.isActive){response.close();cleanup();return}
                try {val value=response.use(handler);if(cont.isActive)cont.resume(value)}
                catch(e: UpdateProblem){if(cont.isActive)cont.resumeWithException(e)}
                catch (_: Exception){if(cont.isActive)cont.resumeWithException(UpdateProblem("NETWORK"))}
                finally {if(call.isCanceled())cleanup()}
            }
        })
    }
    private fun status(r: Response) {
        if(r.code in listOf(429) || r.code==403&&r.header("X-RateLimit-Remaining")=="0")throw UpdateProblem("RATE_LIMIT")
        if(r.code in listOf(401,403,404))throw UpdateProblem("ACCESS")
        if(r.code!=200)throw UpdateProblem("NETWORK")
    }
    private suspend fun metadata(token: String): ByteArray = response("${UpdateProtocol.API}/latest",token,"application/json") { r ->
        status(r)
        if(r.body?.contentType()?.subtype!="json")throw UpdateProblem("INVALID_RESPONSE")
        bytes(r,262144)
    }
    private suspend fun asset(token: String,a: UpdateAsset,file: File?=null,progress: (Long,Long)->Unit={_,_->}): ByteArray {
        var url=UpdateProtocol.assetUrl(a.id)
        repeat(6) { attempt ->
            var redirect: String?=null
            val data=response(url,token,"application/octet-stream",{file?.delete();Unit}) { r ->
                if(r.code in listOf(301,302,303,307,308)) {
                    if(attempt==5)throw UpdateProblem("UNSAFE_REDIRECT")
                    redirect=UpdateProtocol.redirect(url,r.header("Location") ?: throw UpdateProblem("UNSAFE_REDIRECT"))
                    byteArrayOf()
                }else {
                    status(r)
                    if(file==null)bytes(r,a.size)else {
                        val body=r.body ?: throw UpdateProblem("INVALID_APK")
                        if(body.contentLength()>=0 && body.contentLength()!=a.size)throw UpdateProblem("INVALID_APK")
                        var count=0L;var emitted=0L
                        file.outputStream().use { output ->body.byteStream().use { input ->
                            val buffer=ByteArray(32768)
                            while(true){val n=input.read(buffer);if(n<0)break;count+=n;if(count>a.size)throw UpdateProblem("INVALID_APK")
                                output.write(buffer,0,n);if(count-emitted>=65536){progress(count,a.size);emitted=count}}
                        }}
                        if(count!=a.size)throw UpdateProblem("INVALID_APK")
                        progress(count,a.size);byteArrayOf()
                    }
                }
            }
            val next=redirect
            if(next==null)return data
            url=next
        }
        throw UpdateProblem("UNSAFE_REDIRECT")
    }
    override suspend fun latest(token: String): UpdateRelease = withContext(Dispatchers.IO) {
        val release=withTimeout(30000){val assets=UpdateProtocol.latest(metadata(token));UpdateProtocol.release(asset(token,assets.info),assets)}
        if(release.code<highest)throw UpdateProblem("INVALID_RESPONSE")
        highest=release.code;release
    }
    override suspend fun download(token: String,release: UpdateRelease,progress: (Long,Long)->Unit): File = withContext(Dispatchers.IO) {
        val file=synchronized(filesLock) {
            val directory=File((context ?: throw UpdateProblem("INVALID_APK")).cacheDir,"updates").apply{mkdirs()}
            File.createTempFile("vkm-",".apk",directory).also{owned.add(it);own.add(it)}
        }
        var complete=false
        try {asset(token,release.apk,file,progress);verify(file,release);complete=true;file}
        finally {if(!complete){file.delete();owned.remove(file);own.remove(file)}}
    }
    override suspend fun verify(file: File,release: UpdateRelease) = withContext(Dispatchers.IO) {
        if(!file.isFile || file.length() !in 1..UpdateProtocol.MAX_APK || file.length()!=release.apk.size)throw UpdateProblem("INVALID_APK")
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        if(digest.digest().joinToString(""){"%02x".format(it)}!=release.apk.hash)throw UpdateProblem("INVALID_APK")
        val context=context ?: throw UpdateProblem("INVALID_APK")
        val pm=context.packageManager
        val apk=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES) ?: throw UpdateProblem("INVALID_APK")
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val app=apk.applicationInfo ?: throw UpdateProblem("INVALID_APK")
        if(context.packageName!=UpdateProtocol.PACKAGE)throw UpdateProblem("INVALID_APK")
        UpdateProtocol.identity(release,installed.longVersionCode,Build.VERSION.SDK_INT,apk.packageName,apk.longVersionCode,apk.versionName,
            app.minSdkVersion,app.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0,
            apk.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet().orEmpty(),
            installed.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet().orEmpty())
    }
    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.execute {
            try{client.connectionPool.evictAll();synchronized(filesLock){own.forEach{it.delete();owned.remove(it)};own.clear()}}
            finally{client.dispatcher.executorService.shutdown()}
        }
    }
    companion object {
        private val filesLock=Any()
        private val owned=java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
        fun newClient()=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).build()
        fun request(url: String,token: String,accept: String): Request = Request.Builder().url(url)
            .header("Accept",accept).header("User-Agent","VK-Music-Export-Updates/1")
            .apply{if(UpdateProtocol.authorize(url)){require(UpdateProtocol.validToken(token));header("Authorization","Bearer $token");header("X-GitHub-Api-Version","2022-11-28");header("Cache-Control","no-cache")}}
            .build()
        fun bytes(r: Response,limit: Long): ByteArray {
            val body=r.body ?: throw UpdateProblem("INVALID_RESPONSE")
            if(body.contentLength()>limit)throw UpdateProblem("INVALID_RESPONSE")
            val out=ByteArrayOutputStream()
            body.byteStream().use{input->val buffer=ByteArray(4096);while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit)throw UpdateProblem("INVALID_RESPONSE");out.write(buffer,0,n)}}
            return out.toByteArray()
        }
    }
}
