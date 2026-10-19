package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChromeImportTest {
    private fun fixture()=ChromeCapture("vk_music_chrome_export",1,
        ChromeSource("my-music","Моя музыка",listOf(PageTrack("pos:0","Песня 🎵","Артист","1_1"),PageTrack("pos:1","Песня 🎵","Артист","1_1")),2),
        listOf(ChromePlaylist("playlist:-123:5","Для дороги")),CaptureReport("BOTTOM_STABLE",9,0,false,true))
    private fun parse(p: ChromeCapture)=ChromeImport.parse(Json.encodeToString(p))
    @Test fun repeatedTracksAndCatalogArePreservedWithoutClaimingGlobalCompleteness() {
        val plan=parse(fixture());val source=plan.source!!;assertEquals(2,source.entries.size);assertEquals(ScanStatus.COUNT_MATCHED,source.status)
        val next=ChromeImport.merge(CollectionSnapshot(),plan);assertEquals(2,next.sources.size);assertEquals(1,next.uniqueTracks().size)
        assertFalse(next.playlistDiscoveryConfirmed);assertEquals("NOT_SCANNED",next.sources.last().reason)
        assertEquals("https://vk.ru/music/playlist/-123_5",next.sources.last().url)
    }
    @Test fun importIsIdempotentAndOnlyReplacesReviewedSource() {
        val plan=parse(fixture());val first=ChromeImport.merge(CollectionSnapshot(),plan)
        val playlist=first.sources.last().copy(entries=listOf(Entry("keep",Track(title="Saved",artist="Artist"),1)))
        val original=first.copy(sources=listOf(first.sources.first(),playlist))
        val again=ChromeImport.merge(original,plan);assertEquals(original.sources,again.sources)
    }
    @Test fun countMismatchGapsStopsAndUnparsedRowsRemainUnconfirmed() {
        val p=fixture()
        assertEquals(ScanStatus.UNCONFIRMED,parse(p.copy(source=p.source.copy(expectedCount=9))).source!!.status)
        assertEquals(ScanStatus.PARTIAL,parse(p.copy(source=p.source.copy(tracks=listOf(p.source.tracks[1].copy(key="pos:5"))))).source!!.status)
        assertEquals(ScanStatus.PARTIAL,parse(p.copy(capture=p.capture.copy(reason="USER_STOPPED"))).source!!.status)
        assertEquals(ScanStatus.PARTIAL,parse(p.copy(capture=p.capture.copy(unparsedRows=1))).source!!.status)
    }
    @Test fun unknownPositionsNeverBecomeConfirmed() {
        val p=fixture();val plan=parse(p.copy(source=p.source.copy(tracks=p.source.tracks.mapIndexed{i,t->t.copy(key="capture:$i")}),capture=p.capture.copy(ambiguousPositions=true)))
        val source=plan.source!!;assertEquals(ScanStatus.UNCONFIRMED,source.status);assertEquals(2,source.entries.size)
    }
    @Test fun catalogOnlyNeverEmptiesAnExistingMusicSource() {
        val p=fixture();val old=ChromeImport.merge(CollectionSnapshot(),parse(p));val catalog=parse(p.copy(source=p.source.copy(tracks=emptyList())))
        assertNull(catalog.source);assertEquals(old.sources,ChromeImport.merge(old,catalog).sources)
    }
    private fun rejected(raw: String) {try {ChromeImport.parse(raw);fail("Accepted invalid capture")}catch(_:ImportFailure){}}
    @Test fun unknownFieldsAndWrongProtocolAreRejected() {
        rejected(Json.encodeToString(fixture()).replace("\"kind\":","\"cookie\":\"PRIVATE\",\"kind\":"))
        rejected(Json.encodeToString(fixture().copy(kind="other")))
        rejected(Json.encodeToString(fixture().copy(schemaVersion=2)))
    }
    @Test fun invalidIdentifiersKeysPositionsAndNamesAreRejected() {
        val p=fixture();listOf(p.copy(source=p.source.copy(id="https://evil.example/?token=PRIVATE")),
            p.copy(source=p.source.copy(tracks=listOf(p.source.tracks.first(),p.source.tracks.first()))),
            p.copy(source=p.source.copy(tracks=p.source.tracks.reversed())),
            p.copy(source=p.source.copy(title="bad\u0000name")),
            p.copy(source=p.source.copy(tracks=listOf(p.source.tracks.first().copy(vkId="token=PRIVATE")))))
            .forEach{rejected(Json.encodeToString(it))}
    }
    @Test fun interruptedTruncatedDeepAndOversizedFilesCannotReplaceCollection() {
        rejected(Json.encodeToString(fixture()).dropLast(20));rejected("[".repeat(40)+"]".repeat(40))
        try {ChromeImport.read(object:java.io.InputStream(){var n=0;override fun read():Int=if(n++<=ChromeImport.MAX_BYTES)32 else -1});fail()}catch(e:ImportFailure){assertEquals("FILE_TOO_LARGE",e.code)}
    }
}
