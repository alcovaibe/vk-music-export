package app.alcovaibe.vkmusicexport.diagnostics

import android.app.*
import android.content.Context
import android.os.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

object Diagnostics {
    private data class Item(val json: String? = null, val action: (() -> Unit)? = null)
    private val queue = ArrayBlockingQueue<Item>(1024)
    private val sequence = AtomicLong()
    private val lost = AtomicLong()
    private val totalLost = AtomicLong()
    private val ioFailures = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private val monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diagnostics-watchdog").apply { isDaemon = true } }
    private var journal: RunJournal? = null
    private lateinit var app: Context
    @Volatile private var enabled = false
    @Volatile private var ack = SystemClock.elapsedRealtime()
    @Volatile private var foreground = false
    @Volatile private var writerAlive = false
    private var lastStall = false
    fun start(context: Context) {
        if (enabled) return
        app = context; enabled = true
        Thread({
            writerAlive = true
            try { journal = RunJournal(File(app.noBackupFilesDir, "diagnostics"), monotonicNanos = SystemClock::elapsedRealtimeNanos) } catch (_: Exception) { ioFailures.incrementAndGet() }
            var lastSync = SystemClock.elapsedRealtime()
            while (true) {
                val item = queue.poll(500, TimeUnit.MILLISECONDS)
                try {
                    item?.json?.let { journal?.append(it) }
                    item?.action?.invoke()
                    if (SystemClock.elapsedRealtime() - lastSync >= 1000) {
                        journal?.sync(); lastSync = SystemClock.elapsedRealtime()
                    }
                } catch (_: Exception) { ioFailures.incrementAndGet() }
            }
        }, "diagnostics-writer").apply { isDaemon = true; start() }
        event("process_attach", mapOf("pid" to Process.myPid(), "api" to Build.VERSION.SDK_INT,
            "android" to Build.VERSION.RELEASE, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL))
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Exception message/cause may contain credentials. Only class and app code locations.
            event("uncaught_exception", mapOf("kind" to Privacy.messageKind(error.javaClass.simpleName),
                "main_thread" to (thread === Looper.getMainLooper().thread),
                "app_frames" to error.stackTrace.filter { it.className.startsWith("app.alcovaibe.vkmusicexport.") }
                    .take(12).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }))
            val done = CountDownLatch(1)
            queue.offer(Item(action = { try { journal?.sync() } finally { done.countDown() } }))
            done.await(750, TimeUnit.MILLISECONDS)
            previousHandler?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
        }
        var healthTick = 0
        monitor.scheduleAtFixedRate({
            if (++healthTick % 10 == 0 || lost.get() > 0) event("journal_health", mapOf("dropped_total" to totalLost.get(),"io_failures" to ioFailures.get()))
            val lag = SystemClock.elapsedRealtime() - ack
            val stalled = foreground && lag > 5000
            if (stalled && !lastStall) event("main_thread_stall", mapOf("lag_ms" to lag,
                "detector" to "background_elapsedRealtime_vs_main_handler_ack", "not_platform_anr" to true))
            if (!stalled && lastStall) event("main_thread_recovered", mapOf("lag_ms" to lag))
            lastStall = stalled
            // One outstanding ping; a blocked main thread cannot accumulate runnable objects.
            if (lag < 2500) main.post { ack = SystemClock.elapsedRealtime() }
        }, 1, 1, TimeUnit.SECONDS)
    }
    fun foreground(value: Boolean) { foreground = value; ack = SystemClock.elapsedRealtime(); main.post { ack = SystemClock.elapsedRealtime() } }
    @Synchronized fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        fun record(name: String, data: Map<String, Any?>): Item {
            val seq = sequence.incrementAndGet()
            return Item(buildJsonObject {
                put("capture_seq", seq); put("time", Instant.now().toString()); put("wall_ms", System.currentTimeMillis())
                put("elapsed_ns", SystemClock.elapsedRealtimeNanos()); put("type", name)
                put("data", buildJsonObject { data.forEach { (k,v) -> when (v) {
                    is Boolean -> put(k,v); is Number -> put(k,v); null -> put(k, JsonNull)
                    else -> put(k,v.toString().take(4096))
                } } })
            }.toString())
        }
        val dropped = lost.getAndSet(0)
        if (dropped > 0 && !queue.offer(record("queue_loss", mapOf("count" to dropped)))) lost.addAndGet(dropped)
        if (!queue.offer(record(type, fields))) { lost.incrementAndGet(); totalLost.incrementAndGet() }
    }
    fun button(name: String) = event("native_button", mapOf("action" to name))
    fun export(previous: Boolean = false): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        val future = CompletableFuture<String>()
        check(queue.offer(Item(action = {
            try {
                val j = checkNotNull(journal) { "Journal unavailable" }; j.sync()
                val id = if (previous) j.previous() ?: error("No previous run") else j.runId
                val result = Json.parseToJsonElement(j.export(id)).jsonObject.toMutableMap()
                result["writer_health"] = buildJsonObject {
                    put("pending_loss", lost.get()); put("current_process_total_dropped", totalLost.get())
                    put("current_process_io_failures", ioFailures.get()); put("writer_alive", writerAlive)
                    put("health_scope", "exporting process; prior runs only have persisted loss markers")
                }
                future.complete(JsonObject(result).toString())
            } catch (e: Exception) { future.completeExceptionally(e) }
        }), 3, TimeUnit.SECONDS)) { "Journal busy" }
        return future.get(10, TimeUnit.SECONDS)
    }
    fun shareFile(json: String): File {
        val dir = File(app.cacheDir, "diagnostic-export").apply { mkdirs() }
        // Single bounded export file, <= ~3.1 MB; retained logs <=15 MB total.
        return File(dir, "vk-music-diagnostics.json").apply { writeText(json) }
    }
}
