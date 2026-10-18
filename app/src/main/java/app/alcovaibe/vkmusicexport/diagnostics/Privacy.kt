package app.alcovaibe.vkmusicexport.diagnostics

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

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

    // Process-only key: stable correlation within a run, no raw host/path or reusable digest on disk.
    private val aliasKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun alias(kind: String, value: String): String {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(aliasKey,"HmacSHA256"))
        return kind+"_"+mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(8).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun resource(raw: String?, field: String = "url"): Map<String, Any?> {
        val safe = url(raw)
        val uri=runCatching { URI(raw.orEmpty()) }.getOrNull()
        if(uri?.scheme?.lowercase() !in setOf("https","http") || uri?.host==null) return mapOf(field to safe)
        val origin="${uri.scheme.lowercase()}://${uri.host.lowercase()}:${uri.port}"
        val prefix=if(field=="url") "" else "${field}_"
        return mapOf(field to safe, "${prefix}origin_id" to alias("host",origin),
            "${prefix}resource_id" to alias("res",origin+uri.rawPath.orEmpty()))
    }
    private val networkErrors=setOf("ERR_BLOCKED_BY_CLIENT","ERR_NAME_NOT_RESOLVED","ERR_CONNECTION_RESET",
        "ERR_CONNECTION_REFUSED","ERR_CONNECTION_CLOSED","ERR_TIMED_OUT","ERR_CONNECTION_TIMED_OUT",
        "ERR_INTERNET_DISCONNECTED","ERR_FAILED","ERR_ABORTED","ERR_CERT_AUTHORITY_INVALID",
        "ERR_CERT_DATE_INVALID","ERR_SSL_PROTOCOL_ERROR","ERR_NETWORK_CHANGED","ERR_HTTP_RESPONSE_CODE_FAILURE")
    fun networkError(raw: String?): String? = Regex("net::(ERR_[A-Z_]+)").find(raw.orEmpty())?.groupValues?.get(1)?.takeIf{it in networkErrors}
    // Never preserve arbitrary strings, even a short password that resembles a normal word.
    fun messageKind(raw: String?): String {
        val s = raw.orEmpty()
        return when {
            s.contains("Content Security Policy", true) || s.contains("Content-Security-Policy", true) -> "csp"
            s.contains("net::ERR_") -> "network"
            s.contains("CORS",true) || s.contains("cross-origin request",true) -> "cors"
            s.contains("Permissions-Policy",true) || s.contains("Unrecognized feature",true) -> "permissions_policy"
            s.contains("Cross-Origin-Opener-Policy",true) -> "cross_origin_opener_policy"
            s.contains("SameSite",true) || s.contains("third-party cookie",true) -> "cookie_policy"
            s.contains("WebAuthn",true) || s.contains("PublicKeyCredential",true) -> "webauthn"
            s.contains("FedCM",true) || s.contains("IdentityCredential",true) -> "fedcm"
            s.contains("Failed to load module",true) || s.contains("MIME type",true) -> "script_module_load"
            s.contains("Failed to load resource",true) -> "resource_load"
            s.contains("passive event listener",true) -> "passive_listener"
            s.contains("preload",true) -> "preload"
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
