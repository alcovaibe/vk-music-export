package app.alcovaibe.vkmusicexport.export

import app.alcovaibe.vkmusicexport.data.CollectionSnapshot
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    TXT("txt", "text/plain"), CSV("csv", "text/csv"), JSON("json", "application/json"), ZIP("zip", "application/zip")
}

object Exporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private fun oneLine(value: String) = value.replace(Regex("[\\r\\n]+"), " ").trim()
    private fun line(artist: String, title: String) = "${oneLine(artist)} — ${oneLine(title)}"
    private fun cell(value: String) = "\"${value.replace("\"", "\"\"")}\""
    fun safeName(value: String): String = value.replace(Regex("[\\x00-\\x1f\\\\/:*?\"<>|]"), "_")
        .trim(' ', '.').take(70).ifBlank { "Плейлист" }

    fun text(snapshot: CollectionSnapshot): String = snapshot.uniqueTracks().joinToString("\n") { line(it.artist, it.title) }
    fun csv(snapshot: CollectionSnapshot): String = buildString {
        append("source_id,playlist,position,vk_id,artist,title,status\r\n")
        snapshot.sources.forEach { source -> source.entries.forEach { entry ->
            append(listOf(source.id, source.title, entry.position.toString(), entry.track.vkId.orEmpty(),
                entry.track.artist, entry.track.title, source.status.name).joinToString(",", transform = ::cell))
            append("\r\n")
        } }
    }
    fun report(snapshot: CollectionSnapshot): String = buildString {
        appendLine("VK Music Export")
        appendLine("Время: ${snapshot.updatedAt}")
        appendLine("Полнота обнаружения плейлистов: ${if (snapshot.playlistDiscoveryConfirmed) "подтверждена" else "не подтверждена"}")
        appendLine("Источников: ${snapshot.sources.size}; позиций: ${snapshot.entriesCount}; записей в общем списке: ${snapshot.uniqueTracks().size}")
        appendLine("Совпадение количества не гарантирует неизменность коллекции во время сбора.")
        snapshot.sources.forEach { appendLine("${oneLine(it.title)}: ${it.entries.size}/${it.expectedCount ?: "?"}; ${it.status.label}; ${it.reason}") }
    }

    /** Caller owns output. ZIP.close() finishes the archive even if there are no tracks. */
    fun write(snapshot: CollectionSnapshot, format: ExportFormat, output: OutputStream) {
        when (format) {
            ExportFormat.TXT -> output.write(("# ${report(snapshot).replace("\n", "\n# ")}\n${text(snapshot)}").toByteArray(Charsets.UTF_8))
            ExportFormat.CSV -> output.write(csv(snapshot).toByteArray(Charsets.UTF_8))
            ExportFormat.JSON -> output.write(json.encodeToString(CollectionSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
            ExportFormat.ZIP -> ZipOutputStream(output, Charsets.UTF_8).use { zip ->
                fun add(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                add("all-tracks.txt", text(snapshot))
                add("collection.csv", csv(snapshot))
                add("collection.json", json.encodeToString(CollectionSnapshot.serializer(), snapshot))
                add("report.txt", report(snapshot))
                snapshot.sources.forEachIndexed { index, source ->
                    val content = source.entries.joinToString("\n") { line(it.track.artist, it.track.title) }
                    add("playlists/${index + 1}-${safeName(source.title)}.txt", content)
                }
            }
        }
    }
}
