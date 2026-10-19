package app.alcovaibe.vkmusicexport.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

@Serializable data class ChromeCapture(val kind: String, val schemaVersion: Int, val source: ChromeSource,
    val playlists: List<ChromePlaylist> = emptyList(), val capture: CaptureReport)
@Serializable data class ChromeSource(val id: String, val title: String, val tracks: List<PageTrack>, val expectedCount: Int? = null)
@Serializable data class ChromePlaylist(val id: String, val title: String)
@Serializable data class CaptureReport(val reason: String, val samples: Int, val unparsedRows: Int,
    val ambiguousPositions: Boolean, val startedAtTop: Boolean)
class ImportFailure(val code: String) : Exception(code)
data class ImportPlan(val source: MusicSource?, val playlists: List<MusicSource>, val reason: String, val tracks: Int)

object ChromeImport {
    const val MAX_BYTES=10_000_000
    private val json=Json { ignoreUnknownKeys=false }
    private val playlist=Regex("playlist:(-?[0-9]{1,20}):([0-9]{1,20})")
    private val reasons=setOf("BOTTOM_STABLE","USER_STOPPED","SAMPLE_LIMIT","ENTRY_LIMIT","PAGE_CHANGED","LOGIN_OR_CAPTCHA",
        "NOT_MUSIC_PAGE","UNSUPPORTED_LAYOUT","NO_SUPPORTED_ROWS","LIST_CHANGED","POSITION_MODE_CHANGED",
        "VIRTUALIZED_WITHOUT_POSITIONS","SCRIPT_ERROR","SCROLL_STALLED","METADATA_LIMIT","CATALOG_LIMIT")
    private fun check(value: Boolean) { if(!value) throw ImportFailure("INVALID_CAPTURE") }
    private fun text(s: String): String {
        check(s.isNotBlank() && s.length<=500 && s.none {it<' ' || it=='\u007f'})
        return s.trim()
    }
    fun canonical(id: String): String {
        if(id=="my-music")return "https://vk.ru/audio"
        val m=playlist.matchEntire(id) ?: throw ImportFailure("INVALID_CAPTURE")
        return "https://vk.ru/music/playlist/${m.groupValues[1]}_${m.groupValues[2]}"
    }
    fun read(input: InputStream): ImportPlan {
        val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
        while(true) {val n=input.read(buffer);if(n<0)break;if(out.size()+n>MAX_BYTES)throw ImportFailure("FILE_TOO_LARGE");out.write(buffer,0,n)}
        val raw=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString() } catch (_: Exception) { throw ImportFailure("INVALID_CAPTURE") }
        return parse(raw)
    }
    fun parse(raw: String): ImportPlan {
        if(raw.toByteArray(Charsets.UTF_8).size>MAX_BYTES)throw ImportFailure("FILE_TOO_LARGE")
        // Bound nesting before invoking the serializer; file contents are never logged.
        var depth=0;var quoted=false;var escaped=false
        for(c in raw) {
            if(quoted) {if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else if(c=='"')quoted=true else if(c=='{' || c=='[') {depth++;check(depth<=32)} else if(c=='}' || c==']')depth--
        }
        val p=try {json.decodeFromString<ChromeCapture>(raw.removePrefix("\uFEFF"))}catch (_: Exception){throw ImportFailure("INVALID_CAPTURE")}
        check(p.kind=="vk_music_chrome_export" && p.schemaVersion==1)
        val r=p.capture;check(r.reason in reasons && r.samples in 1..600 && r.unparsedRows in 0..1000000)
        check(p.source.tracks.size<=10000 && p.playlists.size<=1000)
        check(p.source.expectedCount==null || p.source.expectedCount in 0..1000000)
        val id=p.source.id;val url=canonical(id);val title=text(p.source.title)
        val keys=HashSet<String>();var positioned=true;val positions=mutableListOf<Int>()
        val entries=p.source.tracks.mapIndexed { index,t ->
            check(Regex("(?:pos|capture):[0-9]{1,9}").matches(t.key) && keys.add(t.key))
            check(t.vkId==null || Regex("-?[0-9]{1,20}_[0-9]{1,20}").matches(t.vkId))
            if(t.key.startsWith("pos:"))positions.add(t.key.substringAfter(':').toInt())else positioned=false
            Entry(t.key,Track(t.vkId,text(t.title),text(t.artist)),index+1)
        }
        check(positions.isEmpty() || positioned)
        check(positions.zipWithNext().all{(a,b)->b>a})
        val contiguous=positions.isNotEmpty() && positions.first() in 0..1 && positions.zipWithNext().all{(a,b)->b==a+1}
        val matched=r.reason=="BOTTOM_STABLE" && r.startedAtTop && !r.ambiguousPositions && positioned && contiguous &&
            r.unparsedRows==0 && p.source.expectedCount==entries.size
        val status=when { matched->ScanStatus.COUNT_MATCHED
            r.reason!="BOTTOM_STABLE" || r.unparsedRows>0 || !r.startedAtTop || (positioned && !contiguous)->ScanStatus.PARTIAL
            else->ScanStatus.UNCONFIRMED }
        val source=entries.takeIf{it.isNotEmpty()}?.let {MusicSource(id,title,url,it,p.source.expectedCount,status,
            if(r.ambiguousPositions && r.reason=="BOTTOM_STABLE")"AMBIGUOUS_POSITIONS" else r.reason)}
        val catalogIds=HashSet<String>()
        val catalog=p.playlists.map {
            check(playlist.matches(it.id) && catalogIds.add(it.id))
            MusicSource(it.id,text(it.title),canonical(it.id),status=ScanStatus.PARTIAL,reason="NOT_SCANNED")
        }
        check(source!=null || catalog.isNotEmpty())
        return ImportPlan(source,catalog,r.reason,entries.size)
    }
    fun merge(current: CollectionSnapshot, plan: ImportPlan): CollectionSnapshot {
        val sources=linkedMapOf<String,MusicSource>();current.sources.forEach{sources[it.id]=it}
        plan.playlists.forEach{sources.putIfAbsent(it.id,it)}
        plan.source?.let{sources[it.id]=it}
        return current.copy(updatedAt=Instant.now().toString(),sources=sources.values.toList(),playlistDiscoveryConfirmed=false)
    }
}
