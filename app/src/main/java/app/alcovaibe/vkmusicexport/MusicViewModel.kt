package app.alcovaibe.vkmusicexport

import android.app.Application
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.*
import app.alcovaibe.vkmusicexport.diagnostics.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.vk.PagePolicy
import app.alcovaibe.vkmusicexport.vk.BrowserProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONTokener
import java.time.Instant
import kotlin.coroutines.resume

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _snapshot = MutableStateFlow(CollectionSnapshot())
    val snapshot = _snapshot.asStateFlow()
    val message = MutableStateFlow("Войдите в ВК и откройте свою музыку")
    val running = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val currentPage = MutableStateFlow("")
    val pageLoading = MutableStateFlow(false)
    val lastCode = MutableStateFlow("NOT_STARTED")
    private var web: WebView? = null
    private var scanJob: Job? = null
    private var loadWaiter: CompletableDeferred<Unit>? = null
    private val extractor by lazy { application.assets.open("vk-extractor.js").bufferedReader().use { it.readText() } }
    private val mobileUi by lazy { application.assets.open("vk-mobile-ui.js").bufferedReader().use { it.readText() } }
    val popup = MutableStateFlow<WebView?>(null)
    val browserGeneration = MutableStateFlow(0)
    private val browserPreferences=application.getSharedPreferences("browser-profile",android.content.Context.MODE_PRIVATE)
    val browserProfile=MutableStateFlow(runCatching { BrowserProfile.Mode.valueOf(browserPreferences.getString("mode",null).orEmpty()) }.getOrDefault(BrowserProfile.Mode.CHROME_COMPAT))
    private var openAfterAttach=false
    val connected = MutableStateFlow(false)
    val browserError = MutableStateFlow<String?>(null)
    private var sessionCheck: Job? = null

    init {
        listOf("loading" to pageLoading, "connected" to connected, "running" to running, "ready" to ready, "parser_code" to lastCode).forEach { (name, flow) ->
            viewModelScope.launch { flow.collect { value -> Diagnostics.event("state_changed", mapOf("state" to name, "value" to (if (value is String) Privacy.code(value) else value))) } }
        }
        viewModelScope.launch {
            try {
                val loaded = store.read()
                _snapshot.value = loaded.copy(sources = loaded.sources.map {
                    if (it.status == ScanStatus.SCANNING) it.copy(status = ScanStatus.INTERRUPTED, reason = "PROCESS_STOPPED") else it
                })
            } catch (_: Exception) {
                message.value = "Не удалось прочитать сохранённую коллекцию. Файл оставлен на устройстве."
            } finally { ready.value = true }
        }
    }

    // The Activity owns the WebView. Navigation between app screens never destroys it.
    fun attach(view: WebView) {
        web = view
        configure(view)
        if(openAfterAttach) {
            openAfterAttach=false
            view.post { if(web===view) {
                WebDiagnostics.event(view,"app_load_url",Privacy.resource("https://vk.ru/")+mapOf("reason" to "profile_retry"))
                view.loadUrl("https://vk.ru/")
            } }
        }
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        BrowserProfile.apply(view,browserProfile.value)
        CookieManager.getInstance().setAcceptCookie(true)
        // VK ID and VK are different sites. Both windows need the same cookie policy.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        WebDiagnostics.configure(view,browserProfile.value.name,if(view===web) "main" else "additional")
        val windowId = WebDiagnostics.windowId(view)
        val configuredAgent=view.settings.userAgentString
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                WebDiagnostics.request(windowId, request, configuredAgent)
                return null
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                WebDiagnostics.event(v, "http_error", Privacy.resource(request.url.toString())+mapOf("status" to response.statusCode, "main_frame" to request.isForMainFrame))
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                WebDiagnostics.event(v,"history_updated",Privacy.resource(url)+mapOf("reload" to isReload))
            }
            override fun onFormResubmission(v: WebView, dontResend: Message, resend: Message) {
                WebDiagnostics.event(v,"form_resubmission",mapOf("decision" to "platform_default_do_not_resend"))
                super.onFormResubmission(v,dontResend,resend)
            }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                WebDiagnostics.event(v,"renderer_gone",mapOf("crashed" to detail.didCrash(),"priority" to detail.rendererPriorityAtExit(),"decision" to "remove_destroy_keep_native_diagnostics"))
                sessionCheck?.cancel()
                stop()
                if (v === popup.value) popup.value = null
                if (v === web) { web = null; browserGeneration.value++ }
                loadWaiter?.completeExceptionally(ScanFailure("BROWSER_CLOSED"))
                connected.value = false; pageLoading.value = false
                browserError.value = "Процесс страницы завершился. Скопируйте лог; затем обновите страницу."
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                WebDiagnostics.destroyed(v)
                v.destroy()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                WebDiagnostics.event(view,"navigation_decision",Privacy.resource(request.url.toString())+mapOf("main_frame" to request.isForMainFrame,"gesture" to request.hasGesture(),"redirect" to request.isRedirect,
                    "method" to request.method.takeIf { it in setOf("GET","POST","HEAD") },
                    "allowed" to (!request.isForMainFrame || PagePolicy.allowed(request.url.toString()))))
                if (!request.isForMainFrame) return false
                val allowed = PagePolicy.allowed(request.url.toString())
                if (!allowed && view === activeWeb()) {
                    pageLoading.value = false
                    lastCode.value = "NAVIGATION_BLOCKED"
                    browserError.value = "Этот переход не поддерживается. Выберите вход по номеру телефона на сайте ВК."
                }
                return !allowed
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                WebDiagnostics.started(view,url)
                if (view === activeWeb()) {
                    pageLoading.value = true
                    browserError.value = null
                    currentPage.value = PagePolicy.publicUrl(url.orEmpty())
                }
                if (view === web && !running.value) {
                    sessionCheck?.cancel()
                    connected.value = false
                    lastCode.value = "BROWSING"
                }
            }
            override fun onPageCommitVisible(view: WebView, url: String?) {
                WebDiagnostics.commit(view,url)
                applyMobileUi(view, url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                WebDiagnostics.finished(view,url)
                applyMobileUi(view, url)
                if (view === activeWeb()) {
                    pageLoading.value = false
                    currentPage.value = PagePolicy.publicUrl(url.orEmpty())
                }
                CookieManager.getInstance().flush()
                if (view === popup.value && PagePolicy.musicPage(url.orEmpty())) {
                    closePopup()
                    web?.loadUrl("https://vk.ru/audio")
                }
                if (view === web) {
                    loadWaiter?.complete(Unit)
                    if (!running.value && PagePolicy.musicPage(url.orEmpty())) verifySession()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                WebDiagnostics.event(view,"network_error",Privacy.resource(request.url.toString())+mapOf("code" to error.errorCode,"main_frame" to request.isForMainFrame))
                if (request.isForMainFrame) {
                    pageLoading.value = false
                    lastCode.value = "PAGE_LOAD_FAILED"
                    browserError.value = "Страница не загрузилась. Проверьте подключение и нажмите «Обновить»."
                    if (view === web) loadWaiter?.completeExceptionally(ScanFailure("PAGE_LOAD_FAILED"))
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                WebDiagnostics.event(view,"tls_error",Privacy.resource(error.url)+mapOf("primary_error" to error.primaryError,"decision" to "cancel"))
                handler.cancel()
                pageLoading.value = false
                lastCode.value = "TLS_FAILED"
                browserError.value = "Не удалось проверить защищённое соединение ВК."
                if (view === web) loadWaiter?.completeExceptionally(ScanFailure("TLS_FAILED"))
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, progress: Int) { WebDiagnostics.event(v,"load_progress",mapOf("percent" to progress)) }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean { WebDiagnostics.console(view,m); return true }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                WebDiagnostics.event(view,"window_create_requested",mapOf("dialog" to isDialog,"gesture" to isUserGesture,
                    "existing_popup" to (popup.value != null),"parser_running" to running.value,
                    "accepted" to (isUserGesture && popup.value == null && !running.value)))
                if (!isUserGesture || popup.value != null || running.value) return false
                val child = DiagnosticWebView(view.context)
                configure(child)
                popup.value = child
                currentPage.value = ""
                pageLoading.value = true
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                WebDiagnostics.event(window,"window_close_requested")
                if (window === popup.value) closePopup()
            }
        }
    }

    private fun applyMobileUi(view: WebView, url: String?) {
        if (PagePolicy.vkWebsite(url.orEmpty())) view.evaluateJavascript(mobileUi, null)
    }

    fun activeWeb(): WebView? = popup.value ?: web

    fun closePopup() {
        val child = popup.value ?: return
        WebDiagnostics.event(child,"popup_closed")
        popup.value = null
        child.stopLoading()
        // AndroidView.onRelease destroys the child after it leaves the view hierarchy.
        currentPage.value = PagePolicy.publicUrl(web?.url.orEmpty())
        pageLoading.value = web?.progress?.let { it < 100 } ?: false
        verifySession()
    }

    fun detach(view: WebView) {
        if (!WebDiagnostics.alive(view)) return
        stop()
        sessionCheck?.cancel()
        closePopup()
        sessionCheck?.cancel()
        if (web === view) {
            web = null
            connected.value = false
            currentPage.value = ""
            pageLoading.value = false
        }
        loadWaiter?.cancel()
        loadWaiter = null
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        WebDiagnostics.destroyed(view)
        view.destroy()
    }

    fun markWhiteScreen() {
        Diagnostics.button("mark_white_screen")
        activeWeb()?.let(WebDiagnostics::markWhiteScreen)
    }
    fun retryWithProfile(mode: BrowserProfile.Mode): Boolean {
        if(running.value) return false
        Diagnostics.button("retry_browser_profile")
        Diagnostics.event("browser_profile_change",mapOf("previous" to browserProfile.value.name,"next" to mode.name,
            "cookies_cleared" to false,"restart_at" to "vk_home"))
        sessionCheck?.cancel()
        popup.value?.let { child -> popup.value=null; child.stopLoading() }
        web?.stopLoading()
        browserProfile.value=mode
        browserPreferences.edit().putString("mode",mode.name).apply()
        connected.value=false;pageLoading.value=false;currentPage.value="";browserError.value=null
        openAfterAttach=true
        browserGeneration.value++
        return true
    }
    fun openLogin() { Diagnostics.action("openLogin");
        if (running.value) return
        if (web?.url == null) web?.loadUrl("https://vk.ru/")
    }
    fun openMusic() { Diagnostics.action("openMusic"); if (!running.value) { closePopup(); web?.loadUrl("https://vk.ru/audio") } }
    fun openPlaylists() { Diagnostics.action("openPlaylists"); if (!running.value) { closePopup(); web?.loadUrl("https://vk.ru/audio?section=playlists") } }
    fun reload() { Diagnostics.action("reload"); if (!running.value) activeWeb()?.let {
        if(it.url==null || it.url=="about:blank") it.loadUrl("https://vk.ru/") else it.reload()
    } }
    fun back(): Boolean { Diagnostics.action("back");
        val view = activeWeb() ?: return false
        if (running.value) return false
        if (view.canGoBack()) { view.goBack(); return true }
        if (popup.value != null) { closePopup(); return true }
        return false
    }

    /** Only reads the music-page identity and DOM scope, never credentials or cookies. */
    fun verifySession() { Diagnostics.action("verifySession");
        if (running.value || popup.value != null) return
        sessionCheck?.cancel()
        sessionCheck = viewModelScope.launch {
            repeat(4) { attempt ->
                delay(if (attempt == 0) 400 else 900)
                try {
                    val page = sample(false)
                    connected.value = page.userId != null && (page.scoped || page.playlistsScoped)
                    lastCode.value = when {
                        connected.value -> "MUSIC_PAGE_READY"
                        page.userId == null -> "USER_ID_NOT_FOUND"
                        else -> "MUSIC_LAYOUT_NOT_FOUND"
                    }
                    if (connected.value) {
                        message.value = "ВК подключён. Можно собрать коллекцию."
                        return@launch
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    connected.value = false
                    lastCode.value = (error as? ScanFailure)?.code ?: "SESSION_CHECK_FAILED"
                }
            }
            message.value = "Список музыки не распознан. Откройте «Мою музыку» и повторите проверку."
        }
    }
    fun stop() { Diagnostics.action("stop"); scanJob?.cancel() }

    private suspend fun sample(advance: Boolean = true): PageSample {
        val view = web ?: throw ScanFailure("BROWSER_CLOSED")
        if (!PagePolicy.musicPage(view.url.orEmpty())) throw ScanFailure("NOT_MUSIC_PAGE")
        Diagnostics.event("parser_sample_started", mapOf("advance" to advance))
        val raw = try { withTimeout(15_000) {
            suspendCancellableCoroutine<String> { continuation ->
                view.evaluateJavascript(if (advance) extractor else extractor.replace("})(true);", "})(false);")) { result -> if (continuation.isActive) continuation.resume(result ?: "null") }
            }
        }
        } catch (e: TimeoutCancellationException) {
            Diagnostics.event("parser_javascript_timeout",mapOf("detector" to "evaluateJavascript_callback_timeout_15s")); throw e
        }
        if (raw.length > 4_000_000) throw ScanFailure("PAGE_TOO_LARGE")
        val decoded = JSONTokener(raw).nextValue() as? String ?: throw ScanFailure("EXTRACTION_FAILED")
        val page = try { store.json.decodeFromString<PageSample>(decoded) } catch (_: Exception) { throw ScanFailure("UNSUPPORTED_PAGE") }
        page.error?.let { throw ScanFailure(it) }
        Diagnostics.event("parser_sample_result",mapOf("tracks" to page.tracks.size,"scoped" to page.scoped,"playlists_scoped" to page.playlistsScoped,"has_user_id" to (page.userId != null)))
        return page
    }

    private suspend fun load(url: String) {
        if (!PagePolicy.allowed(url)) throw ScanFailure("UNTRUSTED_URL")
        val view = web ?: throw ScanFailure("BROWSER_CLOSED")
        val waiter = CompletableDeferred<Unit>()
        loadWaiter = waiter
        try {
            WebDiagnostics.event(view,"app_load_url",Privacy.resource(url))
            view.loadUrl(url)
            withTimeout(40_000) { waiter.await() }
            delay(1200)
        } finally { if (loadWaiter === waiter) loadWaiter = null }
    }

    private suspend fun update(source: MusicSource) {
        val previous = _snapshot.value
        val sources = if (previous.sources.any { it.id == source.id }) previous.sources.map { if (it.id == source.id) source else it }
            else previous.sources + source
        val next = previous.copy(sources = sources, updatedAt = Instant.now().toString(), playlistDiscoveryConfirmed = false)
        store.save(next)
        _snapshot.value = next
    }

    private suspend fun scanSource(id: String, title: String, url: String) {
        val entries = linkedMapOf<String, Entry>()
        var source = MusicSource(id, title, PagePolicy.publicUrl(url), status = ScanStatus.SCANNING, reason = "SCANNING")
        var idle = 0
        var lastBottom = false
        var unparsed = false
        var scoped = true
        var ambiguous = false
        try {
            repeat(2500) {
                currentCoroutineContext().ensureActive()
                val page = sample()
                if (id.startsWith("playlist:") && page.sourceId != id) throw ScanFailure("WRONG_PLAYLIST_PAGE")
                val oldSize = entries.size
                page.tracks.forEach { row ->
                    if (row.key.length <= 4000 && row.title.length <= 2000 && row.artist.length <= 2000) {
                        val track = Track(row.vkId?.takeIf { Regex("-?\\d+_\\d+").matches(it) }, row.title, row.artist)
                        if (entries[row.key]?.track?.let { it != track } == true) unparsed = true
                        entries.putIfAbsent(row.key, Entry(row.key, track, entries.size + 1))
                    } else unparsed = true
                }
                unparsed = unparsed || page.unparsedRows > 0
                scoped = scoped && page.scoped
                ambiguous = ambiguous || page.ambiguousPositions
                idle = if (entries.size == oldSize) idle + 1 else 0
                lastBottom = page.atBottom
                source = source.copy(entries = entries.values.toList(), expectedCount = page.expectedCount ?: source.expectedCount,
                    updatedAt = Instant.now().toString())
                update(source)
                message.value = "$title: собрано ${entries.size}"
                if ((idle >= 7 && page.atBottom) || idle >= 25) {
                    if (entries.isEmpty() && source.expectedCount != 0) throw ScanFailure("NO_SUPPORTED_ROWS")
                    val countMatched = source.expectedCount != null && source.expectedCount == entries.size && scoped && !unparsed && !ambiguous
                    val reason = when {
                        unparsed -> "UNPARSED_ROWS"
                        !scoped -> "SOURCE_SCOPE_UNCONFIRMED"
                        source.expectedCount != null && source.expectedCount != entries.size -> "COUNT_MISMATCH"
                        !lastBottom -> "LOAD_STALLED"
                        ambiguous -> "POSITIONS_UNCONFIRMED"
                        countMatched -> "COUNT_MATCHED_CONTENT_NOT_VERIFIED"
                        else -> "END_NOT_VERIFIED"
                    }
                    val partial = unparsed || (source.expectedCount != null && source.expectedCount != entries.size) || !lastBottom
                    update(source.copy(status = if (countMatched) ScanStatus.COUNT_MATCHED else if (partial) ScanStatus.PARTIAL else ScanStatus.UNCONFIRMED, reason = reason))
                    return
                }
                delay(700)
            }
            update(source.copy(status = ScanStatus.PARTIAL, reason = "SCAN_LIMIT"))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { update(source.copy(status = ScanStatus.INTERRUPTED, reason = "STOPPED")) }
            throw cancelled
        } catch (error: Exception) {
            update(source.copy(status = ScanStatus.ERROR, reason = (error as? ScanFailure)?.code ?: "SOURCE_FAILED"))
            throw error
        }
    }

    fun scan(all: Boolean) {
        Diagnostics.action(if (all) "scan_all" else "scan_current")
        if (running.value || !ready.value || web == null) return
        running.value = true
        lastCode.value = "SCANNING"
        scanJob = viewModelScope.launch {
            val links = linkedMapOf<String, PlaylistLink>()
            try {
                if (pageLoading.value) throw ScanFailure("WAIT_FOR_PAGE")
                val initial = sample(false)
                if (!all) {
                    val id = initial.sourceId ?: "my-music"
                    val url = if (initial.sourceId == null) "https://vk.ru/audios${initial.userId ?: throw ScanFailure("USER_ID_NOT_FOUND")}" else web?.url.orEmpty()
                    load(url)
                    scanSource(id, initial.sourceTitle?.takeIf { it.isNotBlank() } ?: if (initial.sourceId != null) "Плейлист" else "Моя музыка", url)
                } else {
                    val userId = initial.userId ?: throw ScanFailure("USER_ID_NOT_FOUND")
                    val own = "https://vk.ru/audios$userId"
                    load(own)
                    sample(false)
                    // A new full pass replaces the previous run, including stale/removed playlists.
                    store.save(CollectionSnapshot())
                    _snapshot.value = CollectionSnapshot()
                    scanSource("my-music", "Моя музыка", own)
                    load("https://vk.ru/audio?section=playlists")
                    if (!sample(false).playlistsScoped) throw ScanFailure("PLAYLIST_LIST_UNSUPPORTED")
                    var idle = 0
                    for (round in 0 until 400) {
                        if (idle >= 8) break
                        val page = sample()
                        if (!page.playlistsScoped) throw ScanFailure("PLAYLIST_LIST_UNSUPPORTED")
                        val before = links.size
                        page.playlists.filter { PagePolicy.allowed(it.url) && it.id.startsWith("playlist:") }.forEach { links.putIfAbsent(it.id, it) }
                        idle = if (links.size == before && page.atBottom) idle + 1 else 0
                        message.value = "Поиск плейлистов: найдено ${links.size}"
                        delay(700)
                    }
                    val queue = links.values.take(1000).toList()
                    queue.forEachIndexed { index, playlist ->
                        currentCoroutineContext().ensureActive()
                        message.value = "Плейлист ${index + 1} из ${queue.size}: ${playlist.title}"
                        try {
                            load(playlist.url)
                            scanSource(playlist.id, playlist.title.take(300), playlist.url)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (error is ScanFailure && error.code in setOf("NOT_MUSIC_PAGE", "LOGIN_OR_CAPTCHA", "BROWSER_CLOSED", "TLS_FAILED")) throw error
                            if (_snapshot.value.sources.none { it.id == playlist.id }) update(MusicSource(playlist.id, playlist.title.take(300), PagePolicy.publicUrl(playlist.url), status = ScanStatus.ERROR, reason = "PLAYLIST_LOAD_FAILED"))
                        }
                    }
                }
                message.value = "Сбор завершён. Проверьте статусы полноты в коллекции."
                lastCode.value = "FINISHED_WITH_UNCONFIRMED_COMPLETENESS"
            } catch (cancelled: CancellationException) {
                message.value = "Сбор остановлен. Полученные результаты сохранены."
                lastCode.value = "STOPPED"
            } catch (error: Exception) {
                lastCode.value = (error as? ScanFailure)?.code ?: "SCAN_FAILED"
                if (lastCode.value in setOf("NOT_MUSIC_PAGE", "LOGIN_OR_CAPTCHA")) connected.value = false
                message.value = when ((error as? ScanFailure)?.code) {
                    "NOT_MUSIC_PAGE", "LOGIN_OR_CAPTCHA" -> "Войдите в ВК и откройте свою музыку. При необходимости пройдите проверку на странице."
                    "USER_ID_NOT_FOUND" -> "Не удалось определить владельца. Откройте «Мою музыку»; можно собрать текущий список отдельно."
                    "WAIT_FOR_PAGE" -> "Дождитесь загрузки страницы и повторите сбор."
                    "PLAYLIST_LIST_UNSUPPORTED" -> "Музыка сохранена, но список плейлистов не распознан. Откройте нужный плейлист вручную и выберите «Текущий список»."
                    else -> "Сбор прерван: ${(error as? ScanFailure)?.code ?: "SCAN_FAILED"}. Сохранённые результаты доступны."
                }
            } finally { running.value = false; scanJob = null }
        }
    }

    fun clearCollection() { Diagnostics.action("clearCollection");
        if (running.value) return
        viewModelScope.launch { try { val empty = CollectionSnapshot(); store.save(empty); _snapshot.value = empty; message.value = "Коллекция очищена" }
            catch (_: Exception) { message.value = "Не удалось очистить коллекцию" } }
    }
    fun logout() { Diagnostics.action("logout");
        if (running.value) return
        sessionCheck?.cancel()
        connected.value = false
        closePopup()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); web?.loadUrl("https://vk.ru/") }
        sessionCheck?.cancel()
        android.webkit.WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.clearHistory()
        message.value = "Сессия ВК очищена. Сохранённая коллекция остаётся на телефоне."
    }
    private class ScanFailure(val code: String) : Exception(code)
}
