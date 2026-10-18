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
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.vk.PagePolicy
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
    private val popups = mutableSetOf<WebView>()

    init {
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

    fun attach(view: WebView) {
        web = view
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = PagePolicy.allowed(request.url.toString())
                if (!allowed && request.isForMainFrame) message.value = "Внешний переход заблокирован. Используйте вход на странице ВК."
                return !allowed
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageLoading.value = true
                currentPage.value = PagePolicy.publicUrl(url.orEmpty())
            }
            override fun onPageFinished(view: WebView, url: String?) {
                pageLoading.value = false
                currentPage.value = PagePolicy.publicUrl(url.orEmpty())
                CookieManager.getInstance().flush()
                loadWaiter?.complete(Unit)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageLoading.value = false
                    message.value = "Ошибка загрузки страницы (${error.errorCode})"
                    loadWaiter?.completeExceptionally(ScanFailure("PAGE_LOAD_FAILED"))
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                message.value = "Не удалось проверить защищённое соединение ВК"
                loadWaiter?.completeExceptionally(ScanFailure("TLS_FAILED"))
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val child = WebView(view.context)
                popups.add(child)
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(popup: WebView, request: WebResourceRequest): Boolean {
                        if (PagePolicy.allowed(request.url.toString())) view.loadUrl(request.url.toString())
                        else message.value = "Внешнее окно входа заблокировано"
                        popups.remove(popup)
                        popup.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
        }
        view.loadUrl("https://vk.ru/audio")
    }

    fun detach(view: WebView) {
        stop()
        if (web === view) web = null
        loadWaiter?.cancel()
        loadWaiter = null
        popups.forEach { it.destroy() }
        popups.clear()
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    fun openMusic() { if (!running.value) web?.loadUrl("https://vk.ru/audio") }
    fun openPlaylists() { if (!running.value) web?.loadUrl("https://vk.ru/audio?section=playlists") }
    fun reload() { if (!running.value) web?.reload() }
    fun back(): Boolean {
        val view = web ?: return false
        if (running.value || !view.canGoBack()) return false
        view.goBack(); return true
    }
    fun desktop(enabled: Boolean) {
        if (running.value) return
        web?.apply {
            val defaultAgent = WebSettings.getDefaultUserAgent(context)
            settings.userAgentString = if (enabled) defaultAgent.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "") else defaultAgent
            reload()
        }
    }
    fun stop() { scanJob?.cancel() }

    private suspend fun sample(advance: Boolean = true): PageSample {
        val view = web ?: throw ScanFailure("BROWSER_CLOSED")
        if (!PagePolicy.musicPage(view.url.orEmpty())) throw ScanFailure("NOT_MUSIC_PAGE")
        val raw = withTimeout(15_000) {
            suspendCancellableCoroutine<String> { continuation ->
                view.evaluateJavascript(if (advance) extractor else extractor.replace("})(true);", "})(false);")) { result -> if (continuation.isActive) continuation.resume(result ?: "null") }
            }
        }
        if (raw.length > 4_000_000) throw ScanFailure("PAGE_TOO_LARGE")
        val decoded = JSONTokener(raw).nextValue() as? String ?: throw ScanFailure("EXTRACTION_FAILED")
        val page = try { store.json.decodeFromString<PageSample>(decoded) } catch (_: Exception) { throw ScanFailure("UNSUPPORTED_PAGE") }
        page.error?.let { throw ScanFailure(it) }
        return page
    }

    private suspend fun load(url: String) {
        if (!PagePolicy.allowed(url)) throw ScanFailure("UNTRUSTED_URL")
        val view = web ?: throw ScanFailure("BROWSER_CLOSED")
        val waiter = CompletableDeferred<Unit>()
        loadWaiter = waiter
        try {
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

    private suspend fun scanSource(id: String, title: String, url: String, links: LinkedHashMap<String, PlaylistLink>) {
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
                    scanSource(id, initial.sourceTitle?.takeIf { it.isNotBlank() } ?: if (initial.sourceId != null) "Плейлист" else "Моя музыка", url, links)
                } else {
                    val userId = initial.userId ?: throw ScanFailure("USER_ID_NOT_FOUND")
                    val own = "https://vk.ru/audios$userId"
                    load(own)
                    sample(false)
                    // A new full pass replaces the previous run, including stale/removed playlists.
                    store.save(CollectionSnapshot())
                    _snapshot.value = CollectionSnapshot()
                    scanSource("my-music", "Моя музыка", own, links)
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
                            scanSource(playlist.id, playlist.title.take(300), playlist.url, links)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
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
                message.value = when ((error as? ScanFailure)?.code) {
                    "NOT_MUSIC_PAGE", "LOGIN_OR_CAPTCHA" -> "Войдите в ВК и откройте свою музыку. При необходимости пройдите проверку на странице."
                    "USER_ID_NOT_FOUND" -> "Не удалось определить владельца. Откройте «Мою музыку» или попробуйте версию для ПК; можно собрать текущий список отдельно."
                    "WAIT_FOR_PAGE" -> "Дождитесь загрузки страницы и повторите сбор."
                    "PLAYLIST_LIST_UNSUPPORTED" -> "Музыка сохранена, но список плейлистов не распознан. Откройте нужный плейлист вручную и выберите «Текущий список»; попробуйте также версию для ПК."
                    else -> "Сбор прерван: ${(error as? ScanFailure)?.code ?: "SCAN_FAILED"}. Сохранённые результаты доступны."
                }
            } finally { running.value = false; scanJob = null }
        }
    }

    fun clearCollection() {
        if (running.value) return
        viewModelScope.launch { try { val empty = CollectionSnapshot(); store.save(empty); _snapshot.value = empty; message.value = "Коллекция очищена" }
            catch (_: Exception) { message.value = "Не удалось очистить коллекцию" } }
    }
    fun diagnosticText(): String = buildString {
        appendLine("VK Music Export 0.1.0")
        appendLine("Android API: ${android.os.Build.VERSION.SDK_INT}")
        appendLine("WebView: ${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}")
        appendLine("Result: ${lastCode.value}")
        appendLine("Sources: ${_snapshot.value.sources.size}; entries: ${_snapshot.value.entriesCount}")
        _snapshot.value.sources.groupingBy { it.reason }.eachCount().forEach { (reason, count) -> appendLine("$reason: $count") }
    }
    fun logout() {
        if (running.value) return
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); web?.loadUrl("https://vk.ru/audio") }
        android.webkit.WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.clearHistory()
        message.value = "Сессия ВК очищена. Сохранённая коллекция остаётся на телефоне."
    }
    private class ScanFailure(val code: String) : Exception(code)
}
