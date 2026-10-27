package app.alcovaibe.vkmusicexport.music

import android.content.Context
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Native pairing material only; never Chrome's profile. Encrypted with the musical credential. */
internal class WebRenewal(val parent: String,val owner: Long,val cookies: List<String>) {
    override fun toString()="WebRenewal([redacted])"
}
internal enum class SessionStatus { UNKNOWN, VALID, RENEWING, RETRY, LOGIN_REQUIRED }
internal data class MusicSessionState(val expiresAtMs: Long?=null,val renewable: Boolean=false,
    val lastVerifiedAtMs: Long?=null,val status: SessionStatus=SessionStatus.UNKNOWN)

internal object MusicCredentialCodec {
    // Zero/missing is unknown, never a promise of perpetual access. VK web expires can be an epoch or duration.
    fun expiry(data: JsonObject,now: Long): Long? {
        val key=when {"expires_in" in data->"expires_in";"expires" in data->"expires";else->return null}
        val p=data[key] as? JsonPrimitive?:throw MusicFailure(MusicError.RESPONSE)
        val seconds=p.longOrNull
        musicCheck(!p.isString&&seconds!=null&&seconds>=0)
        if(seconds==0L)return null
        return try {
            val value=seconds!!
            if(key=="expires"&&value>=1_000_000_000L)Math.multiplyExact(value,1000)
            else {musicCheck(value<=315_576_000L);Math.addExact(now,Math.multiplyExact(value,1000))}
        }catch(_: ArithmeticException){throw MusicFailure(MusicError.RESPONSE)}
    }
    fun encode(a: MusicAccess): String=buildJsonObject {
        put("token",a.token);put("agent",a.agent);put("credential_id",a.credentialId)
        a.expiresAtMs?.let{put("expires_at_ms",it)};a.lastVerifiedAtMs?.let{put("verified_at_ms",it)}
        put("renewal_blocked",a.renewalBlocked);put("access_rejected",a.rejected)
        a.renewal?.let{g->putJsonObject("web_renewal") {
            put("parent",g.parent);put("owner",g.owner);putJsonArray("cookies"){g.cookies.forEach{add(it)}}
        }}
    }.toString()
    fun decode(raw: String): MusicAccess {
        val o=Json.parseToJsonElement(raw).jsonObject
        fun time(key: String): Long?=o[key]?.let {
            val p=it as? JsonPrimitive;val n=p?.longOrNull
            musicCheck(p!=null&&!p.isString&&n!=null&&n>0,MusicError.STORAGE);n
        }
        val grant=o["web_renewal"]?.jsonObject?.let {
            val parent=it.getValue("parent").jsonPrimitive.content
            val owner=it.getValue("owner").jsonPrimitive.long
            val cookies=it.getValue("cookies").jsonArray.map{v->v.jsonPrimitive.content}
            musicCheck(NativeProtocol.validAccess(parent,NativeProtocol.DEFAULT_AGENT)&&owner>0&&cookies.size<=32&&
                cookies.sumOf{c->c.length}<=20000,MusicError.STORAGE)
            WebRenewal(parent,owner,cookies)
        }
        val a=MusicAccess(o.getValue("token").jsonPrimitive.content,o.getValue("agent").jsonPrimitive.content,
            time("expires_at_ms"),grant,time("verified_at_ms"),o["renewal_blocked"]?.jsonPrimitive?.boolean?:false,
            o["credential_id"]?.jsonPrimitive?.content?:"legacy",persisted=true,rejected=o["access_rejected"]?.jsonPrimitive?.boolean?:false)
        musicCheck(NativeProtocol.validAccess(a.token,a.agent),MusicError.INVALID_ACCESS)
        return a
    }
}

internal interface MusicCredentialStore {
    fun read(): MusicAccess?
    fun replace(expected: MusicAccess,next: MusicAccess): Boolean
}
internal interface MusicRenewer { suspend fun renew(access: MusicAccess): MusicAccess }
internal class MusicReadChanged: MusicFailure(MusicError.ACCESS)

/** Reissues through the very same fixed web_token exchange used during pairing. No guessed OAuth grant. */
internal class VkMusicRenewer(private val base: OkHttpClient=NetworkClients.api(),
    private val wall: ()->Long=System::currentTimeMillis): MusicRenewer {
    override suspend fun renew(access: MusicAccess): MusicAccess {
        val grant=access.renewal?:throw MusicFailure(MusicError.ACCESS)
        val jar=PairingCookieJar()
        val url="https://login.vk.com/?act=web_token".toHttpUrl()
        grant.cookies.forEach{raw->Cookie.parse(url,raw)?.let{jar.saveFromResponse(url,listOf(it))}}
        val client=base.newBuilder().cookieJar(jar).cache(null).followRedirects(false).followSslRedirects(false).build()
        try {
            val form=FormBody.Builder().add("version","1").add("app_id",WebExchangeProtocol.WEB_TOKEN_CLIENT)
                .add("access_token",grant.parent).build()
            val request=Request.Builder().url(url).post(form).header("User-Agent",access.agent)
                .header("Origin","https://id.vk.com").header("Referer","https://id.vk.com/").header("Accept","application/json").build()
            val r=client.newCall(request).readBounded(1_048_576)
            if(r.status==429)throw MusicFailure(MusicError.RATE_LIMIT)
            if(r.status in setOf(401,403))throw MusicFailure(MusicError.ACCESS)
            musicCheck(r.status==200,MusicError.HTTP)
            musicCheck(!jar.limited,MusicError.RESPONSE)
            val fresh=try{WebExchangeProtocol.access(WebExchangeProtocol.response(WebExchangeProtocol.body(r)),wall())}
                catch(e: DeviceAuthFailure){throw MusicFailure(if(e.issue==DeviceAuthIssue.TOKEN_EXCHANGE||e.issue==DeviceAuthIssue.PARTIAL)MusicError.ACCESS else MusicError.RESPONSE)}
            musicCheck(fresh.renewalOwner==grant.owner,MusicError.ACCESS)
            return fresh.withSession(renewal=WebRenewal(grant.parent,grant.owner,jar.snapshot()))
        }catch(e: CancellationException){throw e}
         catch(e: MusicFailure){throw e}
         catch(e: java.io.IOException){throw networkFailure(e)}
        finally{jar.clear()}
    }
}

/** Process-wide lock coalesces UI and UIDT refreshes. CAS prevents sign-out/replacement from resurrecting access. */
internal class MusicSessionManager(private val store: MusicCredentialStore,private val renewer: MusicRenewer,
    private val verify: suspend(MusicAccess)->NativeProbe,private val wall: ()->Long=System::currentTimeMillis,
    private val io: CoroutineDispatcher=Dispatchers.IO,private val event: (String)->Unit={}) {
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(MusicSessionState());val state=mutable.asStateFlow()
    private var attemptedId: String?=null;private var attemptedAt=0L
    private var lastFailure: MusicFailure?=null
    // Read-only operations may follow verified renewals, never an unrelated save/new login.
    private val renewedReads=mutableMapOf<String,String>()
    suspend fun continueRead(supplied: MusicAccess): MusicAccess=mutex.withLock {
        val stored=withContext(io){store.read()}?:throw MusicFailure(MusicError.ACCESS)
        if(stored.credentialId!=supplied.credentialId&&renewedReads[supplied.credentialId]!=stored.credentialId)throw MusicReadChanged()
        resolve(false,null)
    }
    private fun publish(a: MusicAccess?,status: SessionStatus?=null) {
        mutable.value=MusicSessionState(a?.expiresAtMs,a?.renewal!=null&&!a.renewalBlocked,a?.lastVerifiedAtMs,
            status?:when{a==null->SessionStatus.UNKNOWN;a.rejected->SessionStatus.LOGIN_REQUIRED;a.expiresAtMs?.let{it<=wall()}==true->SessionStatus.LOGIN_REQUIRED;
                a.lastVerifiedAtMs!=null->SessionStatus.VALID;else->SessionStatus.UNKNOWN})
    }
    suspend fun reload(){publish(withContext(io){store.read()})}
    suspend fun current(): MusicAccess=mutex.withLock {resolve(false,null)}
    private suspend fun resolve(force: Boolean,failed: MusicAccess?): MusicAccess {
        val old=withContext(io){store.read()}?:run{publish(null);throw MusicFailure(MusicError.ACCESS)}
        // Another request already refreshed the rejected token. Use that result, never refresh twice.
        if(force&&failed!=null&&old.credentialId!=failed.credentialId) {
            musicCheck(failed.renewal?.owner!=null&&failed.renewal.owner==old.renewal?.owner,MusicError.ACCESS)
            return resolve(false,null)
        }
        if(old.rejected){publish(old,SessionStatus.LOGIN_REQUIRED);throw MusicFailure(MusicError.ACCESS)}
        val now=wall();val expired=old.expiresAtMs?.let{it<=now}==true
        val due=force||old.expiresAtMs?.let{it-now<=300_000}==true
        if(!due){publish(old);return old}
        if(old.renewal==null||old.renewalBlocked){
            val value=if(force)old.withSession(rejected=true) else old
            if(force)withContext(io){store.replace(old,value)}
            publish(value,if(expired||force)SessionStatus.LOGIN_REQUIRED else null)
            if(expired||force)throw MusicFailure(MusicError.ACCESS);return old
        }
        if(attemptedId==old.credentialId&&now>=attemptedAt&&now-attemptedAt<300_000){
            publish(old,SessionStatus.RETRY)
            if(expired||force)throw lastFailure?:MusicFailure(MusicError.ACCESS);return old
        }
        attemptedId=old.credentialId;attemptedAt=now
        publish(old,SessionStatus.RENEWING);event("started")
        try {
            val fresh=renewer.renew(old)
            musicCheck(fresh.token!=old.token||(fresh.expiresAtMs?:0)>(old.expiresAtMs?:0),MusicError.ACCESS)
            val probe=verify(fresh)
            musicCheck(probe.owner==old.renewal.owner,MusicError.ACCESS)
            val next=fresh.withSession(verifiedAt=wall())
            currentCoroutineContext().ensureActive()
            if(!withContext(io){store.replace(old,next)})throw MusicFailure(MusicError.ACCESS)
            renewedReads.entries.forEach{if(it.value==old.credentialId)it.setValue(next.credentialId)}
            if(renewedReads.size>=64)renewedReads.clear()
            renewedReads[old.credentialId]=next.credentialId
            publish(next);event("verified");return next
        }catch(e: CancellationException){publish(old);throw e}
         catch(e: Exception){
            val failure=e as? MusicFailure?:MusicFailure(MusicError.RESPONSE)
            // An unusable expired credential must pause the queue, never fail every track as a parser error.
            val retryFailure=if(failure.code in setOf(MusicError.NETWORK,MusicError.HTTP,MusicError.RATE_LIMIT,
                MusicError.TLS,MusicError.TIMEOUT,MusicError.STORAGE))failure else MusicFailure(MusicError.ACCESS)
            lastFailure=retryFailure
            val blocked=failure.code==MusicError.ACCESS||failure.code==MusicError.DENIED
            if(blocked)withContext(io){store.replace(old,old.withSession(blocked=true,rejected=force))}
            val remaining=withContext(io){store.read()}
            if(remaining==null||remaining.credentialId!=old.credentialId){publish(remaining);throw MusicFailure(MusicError.ACCESS)}
            publish(remaining,if(blocked)SessionStatus.LOGIN_REQUIRED else SessionStatus.RETRY)
            event(if(blocked)"reconnect_required" else "retry_later")
            if(expired||force)throw retryFailure
            return remaining
        }
    }
    suspend fun <T> request(supplied: MusicAccess,block: suspend(MusicAccess)->T): T {
        val stored=withContext(io){store.read()}
        // Initial pairing is verified before saving. It must never be substituted by an old account.
        if(!supplied.persisted&&(stored==null||stored.credentialId!=supplied.credentialId))return block(supplied)
        if(stored==null)throw MusicFailure(MusicError.ACCESS)
        if(stored.credentialId!=supplied.credentialId&&stored.token!=supplied.token) {
            musicCheck(supplied.renewal?.owner!=null&&supplied.renewal.owner==stored.renewal?.owner,MusicError.ACCESS)
        }
        val access=current()
        try{return block(access)}catch(e: MusicFailure) {
            if(e.code!=MusicError.ACCESS||e.remoteCode!=null&&e.remoteCode!=5)throw e
            val renewed=mutex.withLock{resolve(true,access)}
            try{return block(renewed)}catch(second: MusicFailure) {
                if(second.code==MusicError.ACCESS&&(second.remoteCode==null||second.remoteCode==5)) {
                    val rejected=renewed.withSession(blocked=true,rejected=true)
                    withContext(io){store.replace(renewed,rejected)}
                    publish(withContext(io){store.read()})
                }
                throw second
            } // Exactly one retry, including a second ACCESS response.
        }
    }
}

internal object MusicSessions {
    private var instance: MusicSessionManager?=null
    @Synchronized fun get(context: Context): MusicSessionManager=instance?:MusicSessionManager(
        MusicAccessStore(context.applicationContext),VkMusicRenewer(),{VkAudioClient().library(it)},
        event={Diagnostics.event("native_access_renewal",mapOf("stage" to it))}).also{instance=it}
}
