package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArtworkDiagnosticsTest {
    @Test fun itemAndInterruptedSummaryRetainOnlyCodesAndCounters() {
        val records=mutableListOf<Pair<String,Map<String,Any?>>>()
        val report=ArtworkDiagnostics{type,fields->records.add(type to fields)}
        report.item=21;report.step=ArtworkStep.DECODE_COVER
        report.result("FAILED",IllegalStateException("secret token URL filename"))
        val state=ArtworkState(total=100,processed=21,added=18,existing=1,missing=1,failed=1)
        report.summary("INTERRUPTED",state)
        assertEquals("audio_artwork_item",records[0].first)
        assertEquals(21,records[0].second["item"]);assertEquals("DECODE_COVER",records[0].second["stage"])
        assertEquals("audio_artwork_summary",records[1].first);assertEquals("INTERRUPTED",records[1].second["outcome"])
        assertEquals(21,records[1].second["processed"]);assertEquals(1,records[1].second["failed"])
        assertEquals(records[0].second["operation_id"],records[1].second["operation_id"])
        assertFalse(records.toString().contains("secret"));assertFalse(records.toString().contains("filename"))
    }
    @Test fun fileFailuresHaveSpecificPrivateReasonsAndRemainIncompleteForQueuePolicy() {
        val records=mutableListOf<Map<String,Any?>>()
        val report=ArtworkDiagnostics{_,fields->records.add(fields)}
        report.item=17;report.step=ArtworkStep.VERIFY_FILE
        for(reason in listOf(SavedFileReason.MISSING,SavedFileReason.NO_ACCESS,SavedFileReason.SIZE_CHANGED,SavedFileReason.HASH_CHANGED)) {
            val error=SavedFileFailure(reason)
            assertEquals(MusicError.INCOMPLETE,error.code)
            val issue=artworkIssue(17,"private name",ArtworkStep.VERIFY_FILE,error)
            assertTrue(issue.reason.startsWith(reason.label))
            report.result("FAILED",error)
            assertEquals(reason.name,records.last()["file_reason"])
            assertEquals("INCOMPLETE",records.last()["code"])
        }
        assertFalse(records.toString().contains("private name"))
    }
    @Test fun noCoverAndUnavailableHaveSeparateCountsAndHttpIsNeverReportedAsMissingCover() {
        val skips=listOf(ArtworkSkip(1,"private artist","private title",ArtworkSkipReason.NO_COVER),
            ArtworkSkip(2,"other artist","other title",ArtworkSkipReason.TRACK_UNAVAILABLE))
        val state=ArtworkState(total=3,processed=3,missing=2,failed=1,skipped=skips)
        assertEquals(1,state.noCover);assertEquals(1,state.unavailable)
        val records=mutableListOf<Map<String,Any?>>()
        val diagnostic=ArtworkDiagnostics{_,fields->records.add(fields)}
        diagnostic.step=ArtworkStep.FETCH_COVER
        diagnostic.result("FAILED",ArtworkFetchFailure(404,ArtworkFetchReason.HTTP_STATUS))
        diagnostic.summary("COMPLETED",state)
        assertEquals(404,records.first()["http_status"]);assertEquals("HTTP_STATUS",records.first()["fetch_reason"])
        assertEquals(1,records.last()["no_cover"]);assertEquals(1,records.last()["unavailable"])
        assertFalse(records.toString().contains("private"));assertFalse(records.toString().contains("artist"))
        val issue=artworkIssue(3,"name",ArtworkStep.FETCH_COVER,ArtworkFetchFailure(403,ArtworkFetchReason.HTTP_STATUS))
        assertTrue(issue.reason.contains("403"));assertEquals(MusicError.HTTP,issue.code)
    }
    @Test fun emptyVkAudioIsUnavailableButMalformedOrMismatchedResponsesRemainErrors() {
        val track=NativeTrack("12_34","CI","Artist",1)
        val unavailable=assertThrows(MusicFailure::class.java){NativeProtocol.artwork(Json.parseToJsonElement("[]"),track)}
        assertEquals(MusicError.UNAVAILABLE,unavailable.code)
        val malformed=assertThrows(MusicFailure::class.java){NativeProtocol.artwork(Json.parseToJsonElement("{}"),track)}
        assertEquals(MusicError.RESPONSE,malformed.code)
        val file=artworkIssue(1,"name",ArtworkStep.VERIFY_FILE,MusicFailure(MusicError.INCOMPLETE))
        val image=artworkIssue(2,"name",ArtworkStep.DECODE_COVER,MusicFailure(MusicError.FORMAT))
        val tags=artworkIssue(3,"name",ArtworkStep.READ_TAGS,MusicFailure(MusicError.FORMAT))
        assertTrue(file.reason.contains("изменён"));assertNotEquals(image.reason,tags.reason)
    }
}
