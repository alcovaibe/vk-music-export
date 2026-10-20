package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.net.ssl.SSLException

internal enum class DeviceAuthPhase(val label: String) {
    PREPARING("Создаём одноразовую заявку ВК…"),
    READY("Заявка готова. Открой подтверждение в Chrome."),
    WAITING("Подтверди вход в Chrome и вернись в приложение."),
    CONFIRM_PHONE("ВК ожидает подтверждения входа на телефоне. Заверши его на странице ВК и вернись."),
    VERIFYING("Вход подтверждён. Проверяем доступ к музыке…"),
    CONNECTED("Музыкальный доступ подтверждён и сохранён на устройстве."),
    FAILED("Подключение не завершено.")
}
internal enum class DeviceAuthIssue(val label: String) {
    EXPIRED("Срок одноразовой заявки истёк. Начни вход заново."),
    RESPONSE("ВК изменил или не поддерживает этот протокол входа. Скопируй диагностический лог."),
    PARTIAL("ВК выдал неполную сессию. Этот способ входа пока не поддерживается; доступ не сохранён."),
    UNSUPPORTED_TOKEN("ВК выдал другой тип токена. Его обмен пока не поддерживается; доступ не сохранён."),
    BROWSER("Не удалось открыть Google Chrome. Включи его и повтори."),
    NETWORK("Не удалось связаться с ВК. Проверь сеть и VPN."),
    TLS("Не удалось проверить защищённое соединение с ВК."),
    HTTP("ВК отклонил сетевой запрос входа."),
    RATE_LIMIT("ВК ограничил частоту запросов. Повтори позже."),
    SERVER("ВК отклонил заявку входа. Скопируй диагностический лог."),
    STORAGE("Не удалось сохранить проверенный доступ на устройстве."),
    MUSIC_CHECK("ВК подтвердил вход, но проверка музыки не прошла. Новая сессия не сохранена.")
}
internal class DeviceAuthFailure(val issue: DeviceAuthIssue, val remoteCode: Int? = null,
    val musicError: MusicError? = null): Exception("DEVICE_AUTH_" + issue.name)

/** All reusable credentials and one-time links stay in memory and have redacted toString. */
internal class DeviceBootstrap(val anonymous: String, val verification: String) {
    override fun toString() = "DeviceBootstrap([redacted])"
}
internal class DeviceSession(val anonymous: String, val hash: String, val url: String, val expiresAt: Long) {
    override fun toString() = "DeviceSession([redacted])"
}
internal sealed interface DeviceCheck {
    data class Pending(val confirmPhone: Boolean): DeviceCheck
    class Approved(val access: MusicAccess): DeviceCheck {
        override fun toString() = "Approved([redacted])"
    }
    data object Expired: DeviceCheck
}
internal interface DeviceAuthApi {
    suspend fun begin(deviceName: String): DeviceSession
    suspend fun poll(session: DeviceSession): DeviceCheck
}

/** Experimental VK WEB device pairing, not the official Android client's identity or secret. */
internal object DeviceAuthProtocol {
    const val WEB_CLIENT = "7913379"
    const val INIT_VERSION = "5.276"
    const val PAIR_VERSION = "5.126"
    private val browserHosts = setOf("vk.com","vk.ru","m.vk.com","m.vk.ru","id.vk.com","id.vk.ru",
        "login.vk.com","login.vk.ru","oauth.vk.com","oauth.vk.ru","qr.vk.ru")
    private fun check(value: Boolean) { if(!value)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE) }
    private inline fun <T> parse(block: ()->T): T = try {block()}
        catch(e: DeviceAuthFailure){throw e}
        catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)}
    fun browserUrl(raw: String): HttpUrl = parse {
        check(raw.length in 1..8192 && raw.none{it <= ' ' || it == '\\' || it == '\u007f'})
        val u=raw.toHttpUrlOrNull() ?: throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        check(u.scheme=="https" && u.port==443 && u.username.isEmpty() && u.password.isEmpty() && u.host in browserHosts)
        u
    }
    private fun opaque(value: String) = Regex("[A-Za-z0-9_.=\\-]{8,8192}").matches(value)
    fun anonymous(response: JsonElement): String = parse {
        val token=text(response.jsonObject,"token");check(opaque(token));token
    }
    fun bootstrap(anonymous: String,response: JsonElement): DeviceBootstrap = parse {
        check(opaque(anonymous))
        val verification=text(response.jsonObject,"code_auth_verification_hash")
        check(opaque(verification));DeviceBootstrap(anonymous,verification)
    }
    /** Only fixed classifications and presence flags may cross the journal boundary. */
    fun response(raw: String,shape: (Map<String,Any?>)->Unit = {}): JsonElement {
        val first=raw.firstOrNull{!it.isWhitespace()}
        val metadata=mutableMapOf<String,Any?>("parser" to "vk_auth_json_v2","body_kind" to when(first) {
            '{'->"json_object_like";'['->"json_array_like";'<'->"html_like";null->"empty";else->"other"
        })
        return try {
            val result=NativeProtocol.envelope(raw)
            val o=result as? JsonObject
            metadata.putAll(mapOf("success_envelope" to true,"response_object" to (o!=null),
                "anonymous_present" to (o?.containsKey("token")==true),
                "verification_present" to (o?.containsKey("code_auth_verification_hash")==true),
                "pair_hash_present" to (o?.containsKey("auth_hash")==true),
                "pair_link_present" to (o?.containsKey("auth_url")==true),
                "expiry_present" to (o?.containsKey("expires_in")==true),
                "status_present" to (o?.containsKey("status")==true)))
            shape(metadata);result
        }catch(e: MusicFailure) {
            metadata["success_envelope"]=false;shape(metadata)
            val issue=if(e.code==MusicError.RATE_LIMIT)DeviceAuthIssue.RATE_LIMIT
                else if(e.remoteCode!=null)DeviceAuthIssue.SERVER else DeviceAuthIssue.RESPONSE
            throw DeviceAuthFailure(issue,e.remoteCode)
        }
    }
    private fun text(o: JsonObject,key: String): String {
        val p=o[key] as? JsonPrimitive;check(p?.isString==true);return p!!.content
    }
    private fun number(o: JsonObject,key: String): Long {
        val p=o[key] as? JsonPrimitive;check(p!=null && !p.isString)
        return p!!.longOrNull ?: throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
    }
    private fun flag(o: JsonObject,key: String): Boolean {
        val p=o[key] ?: return false
        if(p===JsonNull)return false
        check(p is JsonPrimitive && !p.isString)
        p as JsonPrimitive
        return p.booleanOrNull ?: when(p.intOrNull){0->false;1->true;else->throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)}
    }
    fun session(response: JsonElement,b: DeviceBootstrap,startedAt: Long,
        wallTimeMs: Long = System.currentTimeMillis()): DeviceSession = parse {
        val o=response.jsonObject
        val hash=text(o,"auth_hash");check(Regex("[A-Za-z0-9_.=\\-]{8,4096}").matches(hash))
        val url=browserUrl(text(o,"auth_url")).toString()
        val expiry=number(o,"expires_in")
        // Current VK responses use Unix seconds despite the field's name. A relative duration is also accepted for compatibility.
        val remaining=if(expiry>=1_000_000_000L) {
            val ms=Math.subtractExact(Math.multiplyExact(expiry,1000),wallTimeMs)
            if(ms<=0)throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)
            check(ms<=86_400_000);ms
        }else {check(expiry in 1..86400);expiry*1000}
        DeviceSession(b.anonymous,hash,url,Math.addExact(startedAt,remaining.coerceAtMost(300_000)))
    }
    fun poll(response: JsonElement): DeviceCheck = parse {
        val o=response.jsonObject
        when(number(o,"status")) {
            0L,1L,200L -> DeviceCheck.Pending(number(o,"status")==1L)
            4L -> DeviceCheck.Expired
            2L -> {
                if(flag(o,"need_password") || flag(o,"is_partial"))throw DeviceAuthFailure(DeviceAuthIssue.PARTIAL)
                val token=(o["access_token"] as? JsonPrimitive)?.takeIf{it.isString}?.content
                if(token==null && o["super_app_token"]!=null && o["super_app_token"]!==JsonNull)
                    throw DeviceAuthFailure(DeviceAuthIssue.UNSUPPORTED_TOKEN)
                check(token!=null && NativeProtocol.validAccess(token,NativeProtocol.DEFAULT_AGENT))
                DeviceCheck.Approved(MusicAccess(token!!,NativeProtocol.DEFAULT_AGENT))
            }
            else -> throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        }
    }
}

internal class VkDeviceAuthClient(private val client: OkHttpClient=NetworkClients.api(),
    private val now: ()->Long={SystemClock.elapsedRealtime()},
    private val wallNow: ()->Long={System.currentTimeMillis()},
    private val observe: (String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)}): DeviceAuthApi {
    private suspend fun read(request: Request,stage: String,max: Int): BoundedResponse = try {
        val r=client.newCall(request).readBounded(max)
        val kind=when(r.mediaType) {
            "application/json"->"json";"text/html","application/xhtml+xml"->"html"
            null->"missing";else->"other"
        }
        observe("native_auth_http",mapOf("stage" to stage,"http_status" to r.status,"bytes" to r.bytes.size,"media_type" to kind))
        if(r.status==429)throw DeviceAuthFailure(DeviceAuthIssue.RATE_LIMIT)
        r
    }catch(e: CancellationException){throw e}
     catch(e: DeviceAuthFailure){throw e}
     catch(_: SSLException){throw DeviceAuthFailure(DeviceAuthIssue.TLS)}
     catch(e: MusicFailure){
         observe("native_auth_transport_failure",mapOf("stage" to stage,"code" to e.code.name))
         throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
     }
     catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.NETWORK)}

    private suspend fun request(method: String,fields: Map<String,String>): JsonElement {
        val stage=when(method) {
            "auth.getAnonymToken"->"anonymous";"auth.getQrAuthData"->"qr_bootstrap"
            "auth.getAuthCode"->"create";"auth.checkAuthCode"->"poll"
            else->throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        }
        val version=if(stage in setOf("anonymous","qr_bootstrap"))DeviceAuthProtocol.INIT_VERSION else DeviceAuthProtocol.PAIR_VERSION
        val form=FormBody.Builder().add("v",version).add("client_id",DeviceAuthProtocol.WEB_CLIENT)
        fields.forEach{(k,v)->form.add(k,v)}
        observe("native_auth_request",mapOf("stage" to stage,"phase" to "start","protocol" to "vk_web_json_v2","api_version" to version))
        val r=read(Request.Builder().url("https://api.vk.com/method/"+method).header("User-Agent",NativeProtocol.DEFAULT_AGENT)
            .header("Origin","https://id.vk.com").header("Referer","https://id.vk.com/").header("Accept","application/json")
            .post(form.build()).build(),stage,1_048_576)
        if(r.status!=200)throw DeviceAuthFailure(DeviceAuthIssue.HTTP)
        return withContext(Dispatchers.Default){
            val raw=try{strictUtf8(r.bytes)}catch(_: MusicFailure){
                observe("native_auth_response_shape",mapOf("stage" to stage,"parser" to "strict_utf8","valid" to false))
                throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
            }
            DeviceAuthProtocol.response(raw){shape->observe("native_auth_response_shape",mapOf("stage" to stage)+shape)}
        }
    }
    override suspend fun begin(deviceName: String): DeviceSession {
        val anonymous=DeviceAuthProtocol.anonymous(request("auth.getAnonymToken",mapOf("access_token" to "")))
        val b=DeviceAuthProtocol.bootstrap(anonymous,request("auth.getQrAuthData",mapOf("access_token" to "",
            "app_id" to DeviceAuthProtocol.WEB_CLIENT,"app_settings" to "","http_origin" to "","http_referer" to "",
            "lang" to "3","oauth_version" to "","origin" to "https://vk.com","scheme" to "vkcom_dark",
            "scope" to "","scope_string" to "","uuid" to "")))
        val r=request("auth.getAuthCode",mapOf("device_name" to deviceName.take(120),"force_regenerate" to "1",
            "auth_code_flow" to "0","anonymous_token" to b.anonymous,"verification_hash" to b.verification,"is_switcher_flow" to ""))
        val started=now()
        val session=DeviceAuthProtocol.session(r,b,started,wallNow())
        val expiry=(r as? JsonObject)?.get("expires_in")?.jsonPrimitive?.longOrNull ?: 0
        observe("native_auth_session",mapOf("phase" to "ready","expiry_format" to
            if(expiry>=1_000_000_000L)"unix_seconds" else "relative_seconds","remaining_ms" to (session.expiresAt-started)))
        return session
    }
    override suspend fun poll(session: DeviceSession): DeviceCheck {
        if(now()>=session.expiresAt)return DeviceCheck.Expired
        val response=request("auth.checkAuthCode",mapOf("auth_hash" to session.hash,"anonymous_token" to session.anonymous,"web_auth" to "1"))
        val o=response as? JsonObject
        val status=(o?.get("status") as? JsonPrimitive)?.takeIf{!it.isString}?.intOrNull?.takeIf{it in -1..10000}
        val kind=when {
            o?.get("access_token") is JsonPrimitive -> "access"
            o?.get("super_app_token") is JsonPrimitive -> "super_app"
            else -> "none"
        }
        observe("native_auth_poll_result",mapOf("status" to status,"token_kind" to kind))
        return DeviceAuthProtocol.poll(response)
    }
}

/** No polling while Chrome/app is backgrounded. Expiration also covers waiting for the user's gesture. */
internal suspend fun awaitDeviceAccess(api: DeviceAuthApi,session: DeviceSession,opened: StateFlow<Boolean>,
    foreground: StateFlow<Boolean>,now: ()->Long,pollMs: Long=5000,onPhase: (DeviceAuthPhase)->Unit): MusicAccess {
    val remaining=session.expiresAt-now()
    if(remaining<=0)throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)
    try {
        return withTimeout(remaining) {
            opened.first{it}
            while(true) {
                foreground.first{it}
                if(now()>=session.expiresAt)throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)
                when(val r=api.poll(session)) {
                    is DeviceCheck.Approved -> {
                        foreground.first{it}
                        return@withTimeout r.access
                    }
                    DeviceCheck.Expired -> throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)
                    is DeviceCheck.Pending -> onPhase(if(r.confirmPhone)DeviceAuthPhase.CONFIRM_PHONE else DeviceAuthPhase.WAITING)
                }
                delay(pollMs)
            }
            @Suppress("UNREACHABLE_CODE") throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        }
    }catch(e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)
    }
}

internal fun openDeviceAuthChrome(context: Context,url: String): Boolean = try {
    val safe=DeviceAuthProtocol.browserUrl(url)
    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(safe.toString())).setPackage("com.android.chrome").apply {
        if(context !is android.app.Activity)addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
}catch(_: Exception){false}
