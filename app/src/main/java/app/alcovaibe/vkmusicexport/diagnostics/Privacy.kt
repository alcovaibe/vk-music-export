package app.alcovaibe.vkmusicexport.diagnostics

import java.net.URI

/** Deny by default: untrusted page text never crosses the journal boundary. */
object Privacy {
    private val hosts = setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru", "id.vk.com", "id.vk.ru",
        "login.vk.com", "login.vk.ru", "oauth.vk.com", "oauth.vk.ru", "music.vk.com", "music.vk.ru",
        "api.vk.com", "api.vk.ru", "login.vkconnect.ru", "id.vkconnect.ru")
    private val segments = setOf("audio", "music", "playlist", "playlists", "auth", "authorize", "login", "oauth",
        "token", "callback", "blank.html", "index.html", "api", "method", "v1", "v2", "v3", "captcha", "verify", "code", "silent_auth", "redirect", "web", "sign_in", "sign_up")
    fun url(raw: String?): String = try {
        val u = URI(raw.orEmpty())
        val scheme = u.scheme?.lowercase()
        if (scheme !in setOf("https", "http")) when (scheme) {
            "about" -> "about:[omitted]"
            "intent", "vk", "vkconnect", "tel", "mailto", "data", "blob", "javascript", "file", "content" -> "$scheme:[omitted]"
            else -> "[other-or-invalid-url]"
        } else {
            val host = u.host?.lowercase()?.takeIf { it in hosts } ?: "[other-host]"
            val path = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.take(8)
                .joinToString("/") { if (it in segments) it else "~" }
            "$scheme://$host/" + path + if (u.rawQuery != null || u.rawFragment != null) "[parameters-omitted]" else ""
        }
    } catch (_: Exception) { "[invalid-url]" }

    // Never preserve arbitrary strings, even a short password that resembles a normal word.
    fun messageKind(raw: String?): String {
        val s = raw.orEmpty()
        return when {
            s.contains("Content Security Policy", true) || s.contains("Content-Security-Policy", true) -> "csp"
            s.contains("net::ERR_") -> "network"
            s.contains("TypeError") -> "TypeError"
            s.contains("SyntaxError") -> "SyntaxError"
            s.contains("ReferenceError") -> "ReferenceError"
            s.contains("SecurityError") -> "SecurityError"
            else -> "unclassified_text_omitted"
        }
    }
    fun agent(raw: String?): String = raw?.takeIf {
        it.length <= 512 && Regex("Mozilla/5\\.0 \\(Linux; (?:U; )?Android [0-9.]+; [A-Za-z0-9 _;./()-]+\\) AppleWebKit/[0-9.]+ \\(KHTML, like Gecko\\) (?:Version/[0-9.]+ )?Chrome/[0-9.]+ Mobile Safari/[0-9.]+").matches(it)
    } ?: "[unexpected-agent-omitted]"
    private val codes = setOf("NOT_STARTED", "NAVIGATION_BLOCKED", "BROWSING", "PAGE_LOAD_FAILED", "TLS_FAILED", "MUSIC_PAGE_READY",
        "USER_ID_NOT_FOUND", "MUSIC_LAYOUT_NOT_FOUND", "SESSION_CHECK_FAILED", "BROWSER_CLOSED", "NOT_MUSIC_PAGE", "PAGE_TOO_LARGE",
        "EXTRACTION_FAILED", "UNSUPPORTED_PAGE", "LOGIN_OR_CAPTCHA", "UNTRUSTED_URL", "WRONG_PLAYLIST_PAGE", "NO_SUPPORTED_ROWS",
        "WAIT_FOR_PAGE", "PLAYLIST_LIST_UNSUPPORTED", "FINISHED_WITH_UNCONFIRMED_COMPLETENESS", "STOPPED", "SCAN_FAILED", "SCANNING")
    fun code(raw: String): String = raw.takeIf { it in codes } ?: "OTHER_CODE_OMITTED"
}
