package app.alcovaibe.vkmusicexport.diagnostics

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Single writer only. Two bounded NDJSON segments per run; exports recover complete records. */
class RunJournal(private val root: File, runId: String? = null,
                 private val segmentLimit: Long = 1_500_000, private val maxRuns: Int = 5,
                 private val monotonicNanos: () -> Long = System::nanoTime) {
    val runId: String = runId ?: run {
        val ordinal=(root.listFiles()?.filter{it.isDirectory}?.mapNotNull{it.name.substringBefore('-').toLongOrNull()}?.maxOrNull() ?: 0L)+1
        "${ordinal.toString().padStart(20,'0')}-${UUID.randomUUID()}"
    }
    private val directory = File(root, this.runId)
    private var part = 0
    private var stream: FileOutputStream? = null
    private var bytes = 0L
    private var evicted = 0L
    private var seq = 0L
    private fun stamped(json: String): ByteArray {
        val fields=Json.parseToJsonElement(json).jsonObject.toMutableMap()
        fields["seq"]=JsonPrimitive(++seq); fields["run_id"]=JsonPrimitive(runId)
        fields.putIfAbsent("time",JsonPrimitive(Instant.now().toString()))
        fields.putIfAbsent("wall_ms",JsonPrimitive(System.currentTimeMillis()))
        fields.putIfAbsent("elapsed_ns",JsonPrimitive(monotonicNanos()))
        return (JsonObject(fields).toString()+"\n").toByteArray(Charsets.UTF_8)
    }
    init {
        root.mkdirs(); directory.mkdirs()
        root.listFiles()?.filter { it.isDirectory && it.name != this.runId }?.sortedByDescending { it.name }
            ?.drop(maxRuns - 1)?.forEach { old ->
                val oldBytes = old.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                check(old.deleteRecursively()) { "Run rotation failed" }
                append(buildJsonObject {
                    put("type", "run_rotation"); put("removed_bytes", oldBytes); put("time", Instant.now().toString())
                }.toString())
            }
    }
    fun append(json: String) {
        require(json.toByteArray().size <= 24_000)
        if (stream == null) open()
        if (bytes + json.toByteArray().size + 2048 > segmentLimit) {
            sync(); stream?.close(); stream = null; part++
            val obsolete = File(directory, "${part - 2}.ndjson")
            if (obsolete.exists()) { evicted += obsolete.length(); check(obsolete.delete()) }
            open()
            val marker = stamped(buildJsonObject {
                put("type", "segment_rotation"); put("evicted_bytes_total", evicted)
                put("time", Instant.now().toString()); put("history_truncated", evicted > 0)
            }.toString())
            stream!!.write(marker); bytes += marker.size
        }
        val record=stamped(json)
        stream!!.write(record); bytes += record.size
        val type=Json.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
        if(type in setOf("process_attach","application_create","browser_provider","webview_created","configured_hint_brand","hint_metadata_unsupported","js_error_hook")) {
            val metadata=File(directory,"startup.meta")
            if(metadata.length()+record.size<=32_000) FileOutputStream(metadata,true).use{it.write(record);it.fd.sync()}
        }
    }
    private fun open() { stream = FileOutputStream(File(directory, "$part.ndjson"), true); bytes = File(directory, "$part.ndjson").length() }
    fun sync() { stream?.fd?.sync() }
    fun previous(): String? = root.listFiles()?.filter { it.isDirectory && it.name != this.runId }?.maxByOrNull { it.name }?.name
    /**
     * Stream validated NDJSON records directly to the destination. In particular,
     * never materialize a JsonArray of the entire journal: Android 10 can give
     * instrumentation/app processes a 16 MiB heap, far below the temporary memory
     * needed for both the parsed tree and its full JSON serialization.
     *
     * Only valid complete JSON objects are included. The exported schema and
     * recovery counters remain the same as with the in-memory implementation.
     */
    fun writeExport(writer: java.io.Writer, id: String = runId, writerHealth: JsonObject? = null) {
        require(id == runId || id == previous())
        var damaged = 0
        val directory = File(root, id)
        val parts = directory.listFiles()?.filter { it.extension == "ndjson" }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: -1 }.orEmpty()

        fun writeRecords(files: List<File>) {
            var first = true
            files.forEach { file ->
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // A killed write may leave an incomplete last record.
                        val parsed = runCatching { Json.parseToJsonElement(line) }.getOrNull()
                        if (parsed is JsonObject) {
                            if (!first) writer.append(',')
                            writer.append(parsed.toString())
                            first = false
                        } else damaged++
                    }
                }
            }
        }

        writer.append("{\"schema\":1,\"startup_snapshot\":[")
        writeRecords(listOf(File(directory, "startup.meta")).filter { it.isFile })
        writer.append("],\"run_id\":").append(JsonPrimitive(id).toString())
        writer.append(",\"exported_at\":").append(JsonPrimitive(Instant.now().toString()).toString())
        writer.append(",\"history_truncated\":")
            .append((parts.firstOrNull()?.nameWithoutExtension?.toIntOrNull()?.let { it > 0 } ?: false).toString())
        writer.append(",\"observability\":")
            .append(JsonPrimitive("Chrome is external: the app observes intent dispatch and native lifecycle, not Chrome page loads, DOM, console, UA/Client Hints, cookies or requests. Native VK API and experimental device-pairing events contain partial metadata from the app's own HTTPS clients; they are not a complete network trace. Browser dispatch, returning to the app and pairing approval alone do not prove music access. CONNECTED requires a successful native music probe and encrypted credential storage. Retained earlier runs may contain partial WebView callbacks. Missing events do not prove absence of requests; process kill can lose queued or unsynced events. No shutdown event is not proof of a crash.").toString())
        writer.append(",\"events\":[")
        writeRecords(parts)
        writer.append("],\"recovery_invalid_records\":").append(damaged.toString())
        if (writerHealth != null) {
            writer.append(",\"writer_health\":").append(writerHealth.toString())
        }
        writer.append('}')
    }

    /** Compatibility API for smaller reports and existing callers. */
    fun export(id: String = runId): String =
        java.io.StringWriter().also { writeExport(it, id) }.toString()

    fun close() { sync(); stream?.close(); stream = null }
}
