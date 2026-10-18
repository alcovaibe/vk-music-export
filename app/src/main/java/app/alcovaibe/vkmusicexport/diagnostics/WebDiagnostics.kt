package app.alcovaibe.vkmusicexport.diagnostics

import android.os.*
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import org.json.JSONObject
import java.util.IdentityHashMap

object WebDiagnostics {
    private data class State(val id: Int, var started: Long = 0, var loading: Boolean = false,
        var longReported: Boolean = false, var pending: Long = 0, var timeoutReported: Boolean = false,
        var generation: Int = 0, var tick: Runnable? = null, var visualPending: Long = 0,
        var visualStarted: Long = 0, var visualTimedOut: Boolean = false)
    private val states = IdentityHashMap<WebView, State>() // main thread only
    private val next = java.util.concurrent.atomic.AtomicInteger()
    private val identities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val identityLimitReported = java.util.concurrent.atomic.AtomicBoolean()
    private val visualIds = java.util.concurrent.atomic.AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private const val marker = "__VKM_DIAG_V1__:"
    private val errorHook = """
        (() => { if(window.__vkmDiagInstalled) return; window.__vkmDiagInstalled=true;
          const send=(kind,name,line,col)=>console.info('$marker'+JSON.stringify({kind,name,line:line||0,col:col||0}));
          const category=e=>['TypeError','SyntaxError','ReferenceError','SecurityError','RangeError','Error'].includes(e&&e.name)?e.name:'Other';
          addEventListener('error', e=>{
            if(e.target && e.target!==window) {
              const tag=e.target.tagName;
              if(['SCRIPT','LINK','IMG','IFRAME'].includes(tag)) {
                console.info('$marker'+JSON.stringify({kind:'resource_error',tag,url:e.target.src||e.target.href||''}));
              }
            } else send('error',category(e.error),e.lineno,e.colno);
          },true);
          addEventListener('unhandledrejection', e=>send('unhandledrejection',category(e.reason),0,0));
        })();
    """.trimIndent()
    fun event(view: WebView, type: String, data: Map<String, Any?> = emptyMap()) {
        Diagnostics.event(type, data + mapOf("window" to (states[view]?.id ?: -1)))
    }
    fun configure(view: WebView, profile: String = "CHROME_COMPAT", role: String = "main") {
        val s = State(next.incrementAndGet()); states[view] = s
        event(view,"webview_created",mapOf("profile" to profile,"role" to role,
            "ua" to Privacy.agent(view.settings.userAgentString),
            "webview_package" to WebView.getCurrentWebViewPackage()?.packageName,
            "webview_version" to WebView.getCurrentWebViewPackage()?.versionName,
            "third_party_cookies" to CookieManager.getInstance().acceptThirdPartyCookies(view),
            "multiple_windows" to view.settings.supportMultipleWindows()))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val m = WebSettingsCompat.getUserAgentMetadata(view.settings)
                m.brandVersionList.forEach { b -> event(view,"configured_hint_brand",mapOf(
                    "brand" to b.brand.takeIf { it in setOf("Chromium","Google Chrome","Android WebView") } ?: "other_brand",
                    "major" to b.majorVersion.filter { it.isDigit() || it == '.' },
                    "full" to b.fullVersion.filter { it.isDigit() || it == '.' })) }
            }.onFailure { event(view,"hint_metadata_read_failed") }
        } else event(view,"hint_metadata_unsupported")
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        event(view,"js_error_hook", mapOf("document_start" to docStart, "fallback" to "page_commit_and_finish"))
        if (docStart) WebViewCompat.addDocumentStartJavaScript(view, errorHook, setOf("*"))
        view.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(v: WebView, renderer: WebViewRenderProcess?) {
                event(v,"renderer_unresponsive",mapOf("detector" to "platform_WebViewRenderProcessClient"))
            }
            override fun onRenderProcessResponsive(v: WebView, renderer: WebViewRenderProcess?) { event(v,"renderer_responsive") }
        })
        val tick = object : Runnable {
            override fun run() {
                if (!states.containsKey(view)) return
                val now = SystemClock.elapsedRealtime()
                if (s.loading && now - s.started >= 30_000 && !s.longReported) {
                    s.longReported = true
                    event(view,"long_load",mapOf("duration_ms" to now-s.started,"detector" to "page_started_without_finished_30s"))
                }
                if (s.pending != 0L && now - s.pending >= 10_000 && !s.timeoutReported) {
                    s.timeoutReported = true
                    event(view,"javascript_no_response",mapOf("duration_ms" to now-s.pending,
                        "detector" to "evaluateJavascript_callback_timeout_10s", "ambiguous_during_navigation_or_background" to true))
                }
                if(s.visualPending != 0L && now-s.visualStarted>=10_000 && !s.visualTimedOut) {
                    s.visualTimedOut=true
                    event(view,"visual_state_timeout",mapOf("request_id" to s.visualPending,"duration_ms" to now-s.visualStarted,
                        "detector" to "postVisualStateCallback_10s"))
                }
                if (view.isShown && s.pending == 0L) probe(view,s)
                main.postDelayed(this,5000)
            }
        }
        s.tick=tick; main.postDelayed(tick,5000)
    }
    private fun probe(view: WebView,s: State) {
        s.pending=SystemClock.elapsedRealtime(); s.timeoutReported=false
        val began=s.pending; val generation=s.generation
        event(view,"javascript_probe_start",mapOf("detector" to "evaluateJavascript"))
        view.evaluateJavascript("""(() => JSON.stringify({ready:document.readyState,ua:navigator.userAgent,
            hints:navigator.userAgentData?navigator.userAgentData.toJSON():null,
            dom:(()=>{
              const b=document.body,r=document.documentElement;
              const count=s=>Math.min(document.querySelectorAll(s).length,10000);
              const visibleCount=s=>Array.from(document.querySelectorAll(s)).slice(0,300).filter(e=>{
                const t=getComputedStyle(e);return e.getClientRects().length>0&&t.visibility==='visible'&&Number(t.opacity)>0;
              }).length;
              const rect=b?b.getBoundingClientRect():null;
              const style=b?getComputedStyle(b):null;
              const rootStyle=r?getComputedStyle(r):null;
              return {body:!!b,children:b?Math.min(b.childElementCount,10000):0,
                inputs:count('input'),buttons:count('button,input[type=submit]'),iframes:count('iframe'),
                visibleInputs:visibleCount('input'),visibleButtons:visibleCount('button,input[type=submit]'),
                bodyWidth:rect?Math.round(rect.width):0,bodyHeight:rect?Math.round(rect.height):0,
                viewportWidth:innerWidth,viewportHeight:innerHeight,
                hidden:document.hidden,bodyDisplay:style?style.display:'',bodyVisibility:style?style.visibility:'',
                bodyOpacity:style?Number(style.opacity):-1,rootDisplay:rootStyle?rootStyle.display:'',
                rootVisibility:rootStyle?rootStyle.visibility:'',rootOpacity:rootStyle?Number(rootStyle.opacity):-1,
                firstContentfulPaint:performance.getEntriesByName('first-contentful-paint').length>0};
            })()}))()""") { raw ->
            if (states[view] !== s || generation != s.generation) return@evaluateJavascript
            s.pending=0
            val elapsed=SystemClock.elapsedRealtime()-began
            nativeSnapshot(view,"javascript_probe")
            runCatching {
                val decoded=org.json.JSONTokener(raw).nextValue() as? String ?: error("No result")
                val j=JSONObject(decoded)
                val h=j.optJSONObject("hints")
                val brands=h?.optJSONArray("brands")
                val safeBrands=mutableListOf<String>()
                if (brands != null) for(i in 0 until minOf(brands.length(),8)) {
                    val b=brands.optJSONObject(i) ?: continue
                    val name=b.optString("brand")
                    if(name in setOf("Chromium","Google Chrome","Android WebView")) safeBrands.add("$name:${b.optString("version").filter{it.isDigit()||it=='.'}.take(32)}")
                }
                event(view,"javascript_probe_result",mapOf("latency_ms" to elapsed,
                    "ready_state" to j.optString("ready").takeIf{it in setOf("loading","interactive","complete")},
                    "ua" to if(j.optString("ua") == view.settings.userAgentString) Privacy.agent(j.optString("ua")) else "[modified-agent-omitted]","hint_brands" to safeBrands.joinToString(","),
                    "hint_mobile" to h?.optBoolean("mobile"),"hint_platform" to h?.optString("platform")?.takeIf{it=="Android"}))
                j.optJSONObject("dom")?.let { d ->
                    val fields=mutableMapOf<String,Any?>()
                    for(key in listOf("body","hidden","firstContentfulPaint")) fields[key]=d.optBoolean(key)
                    for(key in listOf("children","inputs","buttons","iframes","visibleInputs","visibleButtons","bodyWidth","bodyHeight","viewportWidth","viewportHeight"))
                        fields[key]=d.optInt(key).coerceIn(0,100000)
                    for(key in listOf("bodyDisplay","rootDisplay")) fields[key]=d.optString(key).takeIf{it in setOf("none","block","inline","flex","grid","contents","inline-block","inline-flex") } ?: "other"
                    for(key in listOf("bodyVisibility","rootVisibility")) fields[key]=d.optString(key).takeIf{it in setOf("visible","hidden","collapse") } ?: "other"
                    for(key in listOf("bodyOpacity","rootOpacity")) fields[key]=d.optDouble(key,-1.0).takeIf{it.isFinite() && it in 0.0..1.0}
                    event(view,"dom_structure",fields)
                }
            }.onFailure { event(view,"javascript_probe_invalid",mapOf("latency_ms" to elapsed)) }
        }
    }
    fun started(view: WebView,url: String?) {
        states[view]?.apply { started=SystemClock.elapsedRealtime();loading=true;longReported=false;pending=0;visualPending=0;generation++ }
        event(view,"page_started",Privacy.resource(url))
    }
    fun finished(view: WebView,url: String?) {
        states[view]?.loading=false
        event(view,"page_finished",Privacy.resource(url))
        visual(view,"page_finished")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(errorHook,null)
    }
    fun commit(view: WebView,url: String?) {
        event(view,"page_commit_visible",Privacy.resource(url))
        visual(view,"page_commit_visible")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(errorHook,null)
    }
    // Request callback is on an IO thread; caller supplies captured window id, never reads states there.
    fun windowId(view: WebView): Int = states[view]?.id ?: -1
    fun request(id: Int,r: WebResourceRequest, configuredAgent: String) {
        val hints = r.requestHeaders.entries.filter { it.key.lowercase().startsWith("sec-ch-ua") }.associate { (key,value) ->
            val safeKey=key.lowercase().takeIf { it in setOf("sec-ch-ua","sec-ch-ua-mobile","sec-ch-ua-platform","sec-ch-ua-platform-version","sec-ch-ua-full-version","sec-ch-ua-full-version-list","sec-ch-ua-arch","sec-ch-ua-bitness","sec-ch-ua-model","sec-ch-ua-wow64") } ?: "other_hint"
            safeKey to when(safeKey) {
                "sec-ch-ua", "sec-ch-ua-full-version-list" -> Regex("\"(Chromium|Google Chrome|Android WebView)\";v=\"([0-9.]{1,32})\"").findAll(value).joinToString(",") { it.value }
                "sec-ch-ua-mobile", "sec-ch-ua-wow64" -> value.takeIf{it in setOf("?0","?1")} ?: "[omitted]"
                "sec-ch-ua-platform" -> value.takeIf{it=="\"Android\""} ?: "[omitted]"
                "sec-ch-ua-platform-version", "sec-ch-ua-full-version", "sec-ch-ua-bitness" -> value.takeIf{Regex("\"[0-9.]{1,32}\"").matches(it)} ?: "[omitted]"
                "sec-ch-ua-arch" -> value.takeIf{it in setOf("\"arm\"","\"x86\"")} ?: "[omitted]"
                else -> "[omitted]"
            }
        }
        val rawAgent=r.requestHeaders.entries.firstOrNull{it.key.equals("User-Agent",true)}?.value
        val identity=mapOf("hint_headers" to hints.toString(),"ua_header_observed" to (rawAgent != null),
            "ua_header_matches_configured" to (rawAgent==configuredAgent),
            "ua_header" to if(rawAgent==configuredAgent) Privacy.agent(configuredAgent) else "[absent-or-different-agent-omitted]")
        val identityId=Privacy.alias("identity",id.toString()+identity.toString())
        val first=synchronized(identities) { if(identities.contains(identityId)) false else if(identities.size<128) identities.add(identityId) else false }
        if(first) Diagnostics.event("request_identity",identity+mapOf("identity_id" to identityId,"window" to id))
        if(!identities.contains(identityId) && identityLimitReported.compareAndSet(false,true))
            Diagnostics.event("request_identity_limit",mapOf("limit" to 128,"new_identity_details_omitted" to true))
        Diagnostics.event("request_observed",Privacy.resource(r.url.toString())+mapOf("window" to id,
            "method" to r.method.takeIf { it in setOf("GET","POST","HEAD","PUT","OPTIONS","DELETE","PATCH") },
            "main_frame" to r.isForMainFrame,"gesture" to r.hasGesture(),"redirect" to r.isRedirect,"identity_id" to identityId,
            "destination" to r.requestHeaders.entries.firstOrNull{it.key.equals("Sec-Fetch-Dest",true)}?.value?.takeIf{it in setOf("document","iframe","script","style","font","image","empty","worker","serviceworker")}))
    }
    fun console(view: WebView,m: ConsoleMessage) {
        if (m.message().startsWith(marker)) {
            val j=runCatching{JSONObject(m.message().removePrefix(marker))}.getOrNull()
            if(j?.optString("kind")=="resource_error") {
                val tag=j.optString("tag").takeIf{it in setOf("SCRIPT","LINK","IMG","IFRAME")}
                if(tag != null) event(view,"document_resource_error",Privacy.resource(j.optString("url"))+mapOf("tag" to tag))
                return
            }
            val kind=j?.optString("kind")?.takeIf{it in setOf("error","unhandledrejection")}
            val name=j?.optString("name")?.takeIf{it in setOf("TypeError","SyntaxError","ReferenceError","SecurityError","RangeError","Error","Other")}
            if (kind != null && name != null) {
                event(view,"javascript_error",Privacy.resource(m.sourceId(),"source")+mapOf("kind" to kind,"name" to name,"line" to j?.optInt("line"),"column" to j?.optInt("col")))
                return
            }
        }
        event(view,"console",Privacy.resource(m.sourceId(),"source")+mapOf("level" to m.messageLevel().name,"kind" to Privacy.messageKind(m.message()),
            "network_error" to Privacy.networkError(m.message()),"line" to m.lineNumber(),"text_omitted" to true))
    }
    fun nativeSnapshot(view: WebView, reason: String) {
        val counted=view as? DiagnosticWebView
        val last=counted?.diagnosticLastDrawMs ?: 0L
        val rect=android.graphics.Rect();val intersects=view.getGlobalVisibleRect(rect)
        event(view,"native_visual_state",mapOf("reason" to reason,"attached" to view.isAttachedToWindow,
            "shown" to view.isShown,"visibility" to view.visibility,"alpha" to view.alpha,
            "width" to view.width,"height" to view.height,"visible_rect" to intersects,
            "visible_rect_width" to rect.width(),"visible_rect_height" to rect.height(),"window_visibility" to view.windowVisibility,"window_focus" to view.hasWindowFocus(),
            "hardware_accelerated" to view.isHardwareAccelerated,"draw_count" to counted?.diagnosticDrawCount,
            "last_draw_age_ms" to if(last>0) SystemClock.elapsedRealtime()-last else null,
            "scroll_x" to view.scrollX,"scroll_y" to view.scrollY))
    }
    fun visual(view: WebView,reason: String) {
        val s=states[view] ?: return
        nativeSnapshot(view,reason)
        if(s.visualPending != 0L) return
        val id=visualIds.incrementAndGet();val generation=s.generation
        s.visualPending=id;s.visualStarted=SystemClock.elapsedRealtime();s.visualTimedOut=false
        event(view,"visual_state_requested",mapOf("request_id" to id,"reason" to reason))
        view.postVisualStateCallback(id,object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if(states[view] !== s || s.generation!=generation || s.visualPending!=requestId) return
                s.visualPending=0
                event(view,"visual_state_ready",mapOf("request_id" to id,"latency_ms" to SystemClock.elapsedRealtime()-s.visualStarted))
                nativeSnapshot(view,"visual_callback")
                if(view.isAttachedToWindow && view.isShown) view.postInvalidateOnAnimation()
            }
        })
    }
    fun markWhiteScreen(view: WebView) {
        event(view,"white_screen_reported",mapOf("source" to "native_user_button"))
        visual(view,"white_screen_reported")
        val s=states[view] ?: return
        if(s.pending==0L) probe(view,s)
    }
    fun alive(view: WebView): Boolean = states.containsKey(view)
    fun destroyed(view: WebView) {
        val s=states[view] ?: return
        event(view,"webview_destroyed")
        s.tick?.let(main::removeCallbacks);states.remove(view)
    }
}
