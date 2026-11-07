package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class DownloadsModelTest {
    @Test fun byteCountAloneNeverBecomesAnInventedPercentage() {
        assertNull(downloadFraction(AudioProgress("download",bytes=9_000_000)))
        assertNull(downloadFraction(AudioProgress("verifying",bytes=9_000_000)))
        assertNull(downloadFraction(AudioProgress("segments",segment=2,segments=0)))
        assertNull(downloadFraction(null))
        assertEquals(.5f,downloadFraction(AudioProgress("segments",segment=2,segments=4))!!,0f)
        assertEquals(1f,downloadFraction(AudioProgress("segments",segment=10,segments=4))!!,0f)
    }
    @Test fun finishedWithProblemsIsNeverPresentedAsCompleteSuccess() {
        assertEquals("Готово с пропусками",downloadStatus(ExportSnapshot(status="FINISHED",failed=1)))
        assertEquals("Готово с пропусками",downloadStatus(ExportSnapshot(status="FINISHED",sourceIssues=1)))
        assertEquals("Готово",downloadStatus(ExportSnapshot(status="FINISHED")))
        assertEquals("Очередь скачивания пуста",downloadStatus(ExportSnapshot()))
    }
}
