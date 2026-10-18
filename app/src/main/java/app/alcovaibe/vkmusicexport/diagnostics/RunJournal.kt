package app.alcovaibe.vkmusicexport.diagnostics

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Single writer only. Two bounded NDJSON segments per run; exports recover complete records. */
class RunJournal(private val root: File, val runId: String = "${System.currentTimeMillis()}-${UUID.randomUUID()}",
                 private val segmentLimit: Long = 1_500_000, private val maxRuns: Int = 5,
                 private val monotonicNanos: () -> Long = System::nanoTime) {
    private val directory = File(root, runId)
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
        root.listFiles()?.filter { it.isDirectory && it.name != runId }?.sortedByDescending { it.name }
            ?.drop(maxRuns - 1)?.forEach { old ->
                val oldBytes = old.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (old.deleteRecursively()) append(buildJsonObject {
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
    }
    private fun open() { stream = FileOutputStream(File(directory, "$part.ndjson"), true); bytes = File(directory, "$part.ndjson").length() }
    fun sync() { stream?.fd?.sync() }
    fun previous(): String? = root.listFiles()?.filter { it.isDirectory && it.name != runId }?.maxByOrNull { it.name }?.name
    fun export(id: String = runId): String {
        require(id == runId || id == previous())
        val records = mutableListOf<JsonElement>()
        var damaged = 0
        val parts = File(root, id).listFiles()?.filter { it.extension == "ndjson" }?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: -1 }.orEmpty()
        parts.forEach { file ->
            file.bufferedReader().useLines { lines -> lines.forEach { line ->
                // A killed write can leave an incomplete final line. Never insert raw bytes into JSON.
                val parsed = runCatching { Json.parseToJsonElement(line) }.getOrNull()
                if (parsed is JsonObject) records.add(parsed) else damaged++
            } }
        }
        return buildJsonObject {
            put("schema", 1); put("run_id", id); put("exported_at", Instant.now().toString())
            put("recovery_invalid_records", damaged)
            put("history_truncated", parts.firstOrNull()?.nameWithoutExtension?.toIntOrNull()?.let { it > 0 } ?: false)
            put("observability", "WebView callbacks are partial metadata, not a network trace. POST navigation, later redirect hops, service workers, blob/javascript requests and successful responses may be absent. Absence of an event does not prove absence of a request. Free-form page messages/stacks are omitted; JavaScript errors are categories only. Process kill can lose queued or unsynced events; no shutdown event is not proof of a crash.")
            put("events", JsonArray(records))
        }.toString()
    }
    fun close() { sync(); stream?.close(); stream = null }
}
