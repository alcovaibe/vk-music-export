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
        var generation: Int = 0, var tick: Runnable? = null)
    private val states = IdentityHashMap<WebView, State>() // main thread only
    private val next = java.util.concurrent.atomic.AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private const val marker = "__VKM_DIAG_V1__:"
    private val errorHook = """
        (() => { if(window.__vkmDiagInstalled) return; window.__vkmDiagInstalled=true;
          const send=(kind,name,line,col)=>console.info('$marker'+JSON.stringify({kind,name,line:line||0,col:col||0}));
          const category=e=>['TypeError','SyntaxError','ReferenceError','SecurityError','RangeError','Error'].includes(e&&e.name)?e.name:'Other';
          addEventListener('error', e=>send('error',category(e.error),e.lineno,e.colno));
          addEventListener('unhandledrejection', e=>send('unhandledrejection',category(e.reason),0,0));
        })();
    """.trimIndent()
    fun event(view: WebView, type: String, data: Map<String, Any?> = emptyMap()) {
        Diagnostics.event(type, data + mapOf("window" to (states[view]?.id ?: -1)))
    }
    fun configure(view: WebView) {
        val s = State(next.incrementAndGet()); states[view] = s
        event(view,"webview_created",mapOf("role" to if(s.id == 1) "main" else "additional",
            "ua" to Privacy.agent(view.settings.userAgentString),
            "webview_package" to WebView.getCurrentWebViewPackage()?.packageName,
            "webview_version" to WebView.getCurrentWebViewPackage()?.versionName,
            "third_party_cookies" to CookieManager.getInstance().acceptThirdPartyCookies(view),
            "multiple_windows" to view.settings.supportMultipleWindows()))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val m = WebSettingsCompat.getUserAgentMetadata(view.settings)
                m.brandVersionList.forEach { b -> event(view,"configured_hint_brand",mapOf(
                    "brand" to b.brand.takeIf { it in setOf("Chromium","Google Chrome","Android WebView") }.orEmpty(),
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
            hints:navigator.userAgentData?navigator.userAgentData.toJSON():null}))()""") { raw ->
            if (states[view] !== s || generation != s.generation) return@evaluateJavascript
            s.pending=0
            val elapsed=SystemClock.elapsedRealtime()-began
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
            }.onFailure { event(view,"javascript_probe_invalid",mapOf("latency_ms" to elapsed)) }
        }
    }
    fun started(view: WebView,url: String?) {
        states[view]?.apply { started=SystemClock.elapsedRealtime();loading=true;longReported=false;pending=0;generation++ }
        event(view,"page_started",mapOf("url" to Privacy.url(url)))
    }
    fun finished(view: WebView,url: String?) {
        states[view]?.loading=false
        event(view,"page_finished",mapOf("url" to Privacy.url(url)))
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(errorHook,null)
    }
    fun commit(view: WebView,url: String?) {
        event(view,"page_commit_visible",mapOf("url" to Privacy.url(url)))
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(errorHook,null)
    }
    // Request callback is on an IO thread; caller supplies captured window id, never reads states there.
    fun windowId(view: WebView): Int = states[view]?.id ?: -1
    fun request(id: Int,r: WebResourceRequest) {
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
        Diagnostics.event("request_observed",mapOf("window" to id,"url" to Privacy.url(r.url.toString()),
            "method" to r.method.takeIf { it in setOf("GET","POST","HEAD","PUT","OPTIONS","DELETE","PATCH") },
            "main_frame" to r.isForMainFrame,"gesture" to r.hasGesture(),"redirect" to r.isRedirect,
            "hint_headers" to hints.toString(),"ua_header" to r.requestHeaders.entries.firstOrNull{it.key.equals("User-Agent",true)}?.value?.let(Privacy::agent)))
    }
    fun console(view: WebView,m: ConsoleMessage) {
        if (m.message().startsWith(marker)) {
            val j=runCatching{JSONObject(m.message().removePrefix(marker))}.getOrNull()
            val kind=j?.optString("kind")?.takeIf{it in setOf("error","unhandledrejection")}
            val name=j?.optString("name")?.takeIf{it in setOf("TypeError","SyntaxError","ReferenceError","SecurityError","RangeError","Error","Other")}
            if (kind != null && name != null) {
                event(view,"javascript_error",mapOf("kind" to kind,"name" to name,"line" to j?.optInt("line"),"column" to j?.optInt("col"),"source" to Privacy.url(m.sourceId())))
                return
            }
        }
        event(view,"console",mapOf("level" to m.messageLevel().name,"kind" to Privacy.messageKind(m.message()),
            "source" to Privacy.url(m.sourceId()),"line" to m.lineNumber(),"text_omitted" to true))
    }
    fun alive(view: WebView): Boolean = states.containsKey(view)
    fun destroyed(view: WebView) {
        val s=states[view] ?: return
        event(view,"webview_destroyed")
        s.tick?.let(main::removeCallbacks);states.remove(view)
    }
}
