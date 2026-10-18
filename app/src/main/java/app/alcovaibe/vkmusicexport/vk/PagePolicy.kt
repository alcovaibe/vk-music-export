package app.alcovaibe.vkmusicexport.vk

import java.net.URI
import java.util.Locale

object PagePolicy {
    private val musicHosts = setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru", "music.vk.com", "music.vk.ru")
    private val authHosts = setOf("id.vk.com", "id.vk.ru", "login.vk.com", "login.vk.ru", "oauth.vk.com", "oauth.vk.ru")

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT) in musicHosts + authHosts
    }.getOrDefault(false)

    fun musicPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        allowed(url) && uri.host.lowercase(Locale.ROOT) in musicHosts &&
            Regex("^/(audio(?:s[0-9]+)?|music)(/|$)").containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)

    /** Export no query parameters, fragments or private playlist access keys. */
    fun publicUrl(url: String): String = runCatching {
        if (!allowed(url)) return ""
        val uri = URI(url)
        val path = uri.path.orEmpty().replace(Regex("(/music/playlist/-?[0-9]+_[0-9]+)_[^/]+"), "$1")
        URI("https", null, uri.host, -1, path, null, null).toASCIIString()
    }.getOrDefault("")
}
