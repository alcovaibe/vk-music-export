package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLException

/** Native pairing cookies only. Never reads Chrome's profile or writes a cookie to disk. */
internal class PairingCookieJar: CookieJar {
    private val values=mutableListOf<Cookie>()
    @Volatile var limited=false; private set
    @Synchronized override fun saveFromResponse(url: HttpUrl,cookies: List<Cookie>) {
        if(!WebExchangeProtocol.allowed(url))return
        val now=System.currentTimeMillis()
        values.removeAll{it.expiresAt<=now}
        for(cookie in cookies) {
            // Some VK session cookies omit Secure. They are still only stored
            // from HTTPS and only sent to this explicit HTTPS host allowlist.
            if(!cookie.matches(url))continue
            values.removeAll{it.name==cookie.name && it.domain==cookie.domain && it.path==cookie.path}
            if(cookie.expiresAt<=now)continue
            if(cookie.name.length+cookie.value.length>4096 || values.size>=32 ||
                values.sumOf{it.name.length+it.value.length}+cookie.name.length+cookie.value.length>16384) {
                limited=true;continue
            }
            values.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll{it.expiresAt<=System.currentTimeMillis()}
        return if(WebExchangeProtocol.allowed(url))values.filter{it.matches(url)}else emptyList()
    }
    @Synchronized fun clear(){values.clear();limited=false}
    override fun toString()="PairingCookieJar([redacted])"
}

/** Only these first-party hosts may receive native pairing cookies or callback GETs. */
internal object WebExchangeProtocol {
    const val WEB_TOKEN_CLIENT="6287487"
    private val hosts=setOf("vk.com","vk.ru","login.vk.com","login.vk.ru")
    fun allowed(url: HttpUrl)=url.scheme=="https" && url.port==443 && url.host in hosts &&
        url.username.isEmpty() && url.password.isEmpty()
    fun callback(raw: String): HttpUrl {
        val url=DeviceAuthProtocol.browserUrl(raw)
        if(!allowed(url) || url.fragment!=null)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        return url
    }
    fun body(reply: BoundedResponse): String = when(reply.charset?.lowercase()) {
        null,"utf-8" -> strictUtf8(reply.bytes)
        "windows-1251" -> try {
            Charset.forName("windows-1251").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(reply.bytes)).toString()
        }catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)}
        else -> throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
    }
    private fun text(o: JsonObject,key: String): String {
        val value=(o[key] as? JsonPrimitive)?.takeIf{it.isString}?.content
        if(value==null)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        return value
    }
    private fun partial(o: JsonObject) {
        for(key in listOf("is_partial","need_password","is_service","additional_signup_required")) {
            val value=o[key]?:continue
            if(value===JsonNull)continue
            val p=value as? JsonPrimitive
            if(p==null || p.isString)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
            val flag=p.booleanOrNull ?: when(p.intOrNull){0->false;1->true;else->throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)}
            if(flag)throw DeviceAuthFailure(DeviceAuthIssue.PARTIAL)
        }
    }
    /** Login uses a different envelope from api.vk.com. No arbitrary message/field name escapes. */
    fun response(raw: String,shape:(Map<String,Any?>)->Unit={}): JsonObject {
        val metadata=mutableMapOf<String,Any?>("parser" to "vk_login_json_v1","body_kind" to when(raw.firstOrNull{!it.isWhitespace()}) {
            '{'->"json_object_like";'<'->"html_like";null->"empty";else->"other"
        })
        try {
            val root=try{NativeProtocol.boundedObject(raw)}catch(_: MusicFailure){throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)}
            val type=(root["type"] as? JsonPrimitive)?.takeIf{it.isString}?.content
            metadata["result_kind"]=if(type in setOf("okay","error","captcha"))type else "other"
            val data=root["data"] as? JsonObject
            metadata["data_object"]=data!=null
            metadata["access_present"]=data?.containsKey("access_token")==true
            metadata["next_step_present"]=data?.containsKey("next_step_url")==true
            val rt=(data?.get("response_type") as? JsonPrimitive)?.takeIf{it.isString}?.content
            metadata["response_kind"]=when(rt){null->"missing";"auth_token"->"auth_token";else->"other"}
            if(type=="error" || type=="captcha") {
                val code=(root["error_code"] as? JsonPrimitive)?.takeIf{!it.isString}?.intOrNull?.takeIf{it in 0..1000000}
                throw DeviceAuthFailure(DeviceAuthIssue.TOKEN_EXCHANGE,code)
            }
            if(type!="okay" || data==null)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
            partial(root);partial(data)
            return data
        }finally {shape(metadata)}
    }
    class Connected(val access: String,val next: HttpUrl?) {
        override fun toString()="Connected([redacted])"
    }
    fun connected(data: JsonObject): Connected {
        if(text(data,"response_type")!="auth_token")throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        val token=text(data,"access_token")
        if(!NativeProtocol.validAccess(token,NativeProtocol.DEFAULT_AGENT))throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        val next=data["next_step_url"]?.takeUnless{it===JsonNull}?.let {
            val raw=text(data,"next_step_url")
            if(raw.isEmpty())null else callback(raw)
        }
        return Connected(token,next)
    }
    fun access(data: JsonObject): MusicAccess {
        val token=text(data,"access_token")
        if(!NativeProtocol.validAccess(token,NativeProtocol.DEFAULT_AGENT))throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        val id=data["user_id"] as? JsonPrimitive
        if(id==null || id.isString || (id.longOrNull?:0)<=0)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        val expiry=data["expires"] as? JsonPrimitive
        if(expiry!=null && (expiry.isString || (expiry.longOrNull?:-1)<0))throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        return MusicAccess(token,NativeProtocol.DEFAULT_AGENT)
    }
}

/** Web pairing only. Credentials use POST; callback URLs and the native cookie jar stay in memory. */
internal class VkWebTokenExchange(private val baseClient: OkHttpClient,
    private val now: ()->Long,private val observe:(String,Map<String,Any?>)->Unit) {
    private fun deadline(session: DeviceSession){if(now()>=session.expiresAt)throw DeviceAuthFailure(DeviceAuthIssue.EXPIRED)}
    private suspend fun <T> transport(stage: String,block: suspend ()->T): T = try {block()}
        catch(e: CancellationException){throw e}
        catch(e: DeviceAuthFailure){throw e}
        catch(_: SSLException){throw DeviceAuthFailure(DeviceAuthIssue.TLS)}
        catch(e: MusicFailure){
            observe("native_auth_transport_failure",mapOf("stage" to stage,"code" to e.code.name))
            throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
        }
        catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.NETWORK)}

    suspend fun exchange(session: DeviceSession,result: DeviceCheck.ExchangeRequired): MusicAccess {
        val jar=PairingCookieJar()
        val client=baseClient.newBuilder().cookieJar(jar).cache(null).followRedirects(false).followSslRedirects(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
        fun healthy(){if(jar.limited){
            observe("native_auth_exchange_cookie_limit",mapOf("exceeded" to true))
            throw DeviceAuthFailure(DeviceAuthIssue.TOKEN_EXCHANGE)
        }}
        fun request(url: HttpUrl)=Request.Builder().url(url).header("User-Agent",NativeProtocol.DEFAULT_AGENT)
            .header("Origin","https://id.vk.com").header("Referer","https://id.vk.com/").header("Accept","application/json")
        suspend fun post(action: String,stage: String,fields: Map<String,String>): JsonObject = transport(stage) {
            deadline(session);healthy()
            val url=HttpUrl.Builder().scheme("https").host("login.vk.com").addQueryParameter("act",action).build()
            val body=FormBody.Builder().apply{fields.forEach{(k,v)->add(k,v)}}.build()
            observe("native_auth_request",mapOf("stage" to stage,"phase" to "start","protocol" to "vk_web_exchange_v1"))
            val reply=client.newCall(request(url).post(body).build()).readBounded(1_048_576)
            observe("native_auth_http",mapOf("stage" to stage,"http_status" to reply.status,"bytes" to reply.bytes.size,
                "media_type" to when(reply.mediaType){"application/json"->"json";"text/html"->"html";null->"missing";else->"other"}))
            deadline(session);healthy()
            if(reply.status==429)throw DeviceAuthFailure(DeviceAuthIssue.RATE_LIMIT)
            if(reply.status!=200)throw DeviceAuthFailure(DeviceAuthIssue.HTTP)
            withContext(Dispatchers.Default){WebExchangeProtocol.response(WebExchangeProtocol.body(reply)){shape->
                observe("native_auth_response_shape",mapOf("stage" to stage)+shape)
            }}
        }
        try {
            deadline(session)
            val uuid=UUID.randomUUID().toString().replace("-","")
            val connected=WebExchangeProtocol.connected(post("connect_code_auth","web_connect",mapOf(
                "token" to result.token,"uuid" to uuid,"app_id" to DeviceAuthProtocol.WEB_CLIENT,
                "flow_start_state" to "","is_external_carousel" to "","oauth_version" to "","sid" to "",
                "oauth_force_hash" to "0","is_registration" to "0","oauth_response_type" to "silent_token",
                "vkid_oauth_hash" to "","is_oauth_migrated_flow" to "0","oauth_state" to "",
                "to" to Base64.getEncoder().encodeToString("https://vk.com/".toByteArray(Charsets.UTF_8)),"version" to "1")))
            // The callback can mint native web-session cookies. Only GET, exact VK
            // hosts, at most four redirects. Headers only: HTML is not read or parsed.
            var next=connected.next
            val seen=mutableSetOf<HttpUrl>()
            repeat(5) {
                val url=next?:return@repeat
                if(!seen.add(url))throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
                next=transport("web_callback") {
                    deadline(session);healthy()
                    observe("native_auth_request",mapOf("stage" to "web_callback","phase" to "start",
                        "protocol" to "vk_web_exchange_v1","method" to "GET","hop" to it))
                    client.newCall(request(url).get().build()).withResponse { reply->
                        observe("native_auth_http",mapOf("stage" to "web_callback","http_status" to reply.code,
                            "body_observation" to "headers_only","redirect_present" to (reply.header("Location")!=null)))
                        deadline(session);healthy()
                        when(reply.code) {
                            200,204 -> null
                            301,302,303,307,308 -> {
                                val location=reply.header("Location")?:throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
                                val resolved=url.resolve(location)?:throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
                                WebExchangeProtocol.callback(resolved.toString())
                            }
                            429 -> throw DeviceAuthFailure(DeviceAuthIssue.RATE_LIMIT)
                            else -> throw DeviceAuthFailure(DeviceAuthIssue.HTTP)
                        }
                    }
                }
            }
            if(next!=null)throw DeviceAuthFailure(DeviceAuthIssue.RESPONSE)
            val access=WebExchangeProtocol.access(post("web_token","web_token",mapOf(
                "version" to "1","app_id" to WebExchangeProtocol.WEB_TOKEN_CLIENT,"access_token" to connected.access)))
            deadline(session)
            observe("native_auth_exchange_result",mapOf("result" to "api_token_received","music_access" to "not_proven"))
            return access
        }finally {
            jar.clear()
            observe("native_auth_exchange_cleanup",mapOf("native_cookies_cleared" to true,"detector" to "exchange_finally"))
        }
    }
}
