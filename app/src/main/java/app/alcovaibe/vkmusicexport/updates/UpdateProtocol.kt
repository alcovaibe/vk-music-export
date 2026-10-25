package app.alcovaibe.vkmusicexport.updates

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal class UpdateProblem(val code: String) : IOException(code)
internal data class UpdateAsset(val size: Long, val hash: String)
internal data class UpdateRelease(val name: String, val code: Int, val minSdk: Int, val apk: UpdateAsset)

internal object UpdateProtocol {
    const val PACKAGE = "app.alcovaibe.vkmusicexport"
    const val MANIFEST = "https://raw.githubusercontent.com/alcovaibe/vk-music-export/main/docs/updates/android.json"
    const val RELEASES = "https://github.com/alcovaibe/vk-music-export/releases"
    const val MAX_MANIFEST = 16384L
    const val MAX_APK = 100L * 1024 * 1024
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun json(bytes: ByteArray): JsonObject = try {
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) { throw UpdateProblem("INVALID_RESPONSE") }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content ?: throw UpdateProblem("INVALID_RESPONSE")
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeIf {!it.isString}?.longOrNull ?: throw UpdateProblem("INVALID_RESPONSE")
    fun latest(bytes: ByteArray): UpdateRelease {
        if(bytes.isEmpty() || bytes.size>MAX_MANIFEST)throw UpdateProblem("INVALID_RESPONSE")
        val o=json(bytes)
        if(o.keys!=setOf("schemaVersion","platform","channel","applicationId","release") ||
            o.number("schemaVersion")!=1L || o.string("platform")!="android" || o.string("channel")!="stable" ||
            o.string("applicationId")!=PACKAGE)throw UpdateProblem("INVALID_RESPONSE")
        val r=o["release"] as? JsonObject ?: throw UpdateProblem("INVALID_RESPONSE")
        if(r.keys!=setOf("versionName","versionCode","minSdk","tag","apk","size","sha256"))throw UpdateProblem("INVALID_RESPONSE")
        val name=r.string("versionName")
        val code=r.number("versionCode").takeIf{it in 1..2_100_000_000}?.toInt() ?: throw UpdateProblem("INVALID_RESPONSE")
        val sdk=r.number("minSdk").takeIf{it in 1..10000}?.toInt() ?: throw UpdateProblem("INVALID_RESPONSE")
        val size=r.number("size");val hash=r.string("sha256")
        if(!Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){1,3}").matches(name) ||
            r.string("tag")!="v$name-build.$code" || r.string("apk")!="VK-Music-Export-$name-$code.apk" ||
            size !in 1..MAX_APK || !Regex("[a-f0-9]{64}").matches(hash))throw UpdateProblem("INVALID_RESPONSE")
        return UpdateRelease(name,code,sdk,UpdateAsset(size,hash))
    }
    fun downloadUrl(release: UpdateRelease): String =
        "$RELEASES/download/v${release.name}-build.${release.code}/VK-Music-Export-${release.name}-${release.code}.apk"
    fun redirect(from: String, location: String): String = try {
        if(location.any{it.code<=32 || it.code>=127 || it=='\\'})throw UpdateProblem("UNSAFE_REDIRECT")
        val u=URI(from).resolve(location)
        if(u.scheme!="https" || u.host !in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com") ||
            u.rawUserInfo!=null || u.rawFragment!=null || u.port !in listOf(-1,443))throw UpdateProblem("UNSAFE_REDIRECT")
        u.toASCIIString()
    }catch (_: Exception){throw UpdateProblem("UNSAFE_REDIRECT")}
    fun identity(release: UpdateRelease, installedCode: Long, sdk: Int, packageName: String?, code: Long, name: String?, minSdk: Int,
                 debug: Boolean, signers: Set<String>, installedSigners: Set<String>) {
        if(packageName!=PACKAGE || code!=release.code.toLong() || code<=installedCode || name!=release.name || minSdk!=release.minSdk || minSdk>sdk ||
            debug || installedSigners.isEmpty() || signers!=installedSigners)throw UpdateProblem("INVALID_APK")
    }
}
