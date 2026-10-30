package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class AudioStorageTest {
    @Test fun storageBudgetAcceptsLargeAudioAndRejectsInsufficientSpaceOrOverflow() {
        val directory=object: File("synthetic") {override fun getUsableSpace()=1024L*1024*1024}
        AudioStorage.requireSpace(directory,200L*1024*1024,3)
        for((bytes,copies)in listOf(400L*1024*1024 to 3,Long.MAX_VALUE to 2,-1L to 1)) {
            val failure=assertThrows(MusicFailure::class.java){AudioStorage.requireSpace(directory,bytes,copies)}
            assertEquals(MusicError.STORAGE,failure.code)
        }
    }

    @Test fun mp3MetadataInspectionDoesNotNarrowLargeFileLengthToInt() {
        val file=Files.createTempFile("large-mp3-header",".mp3").toFile()
        try {
            RandomAccessFile(file,"rw").use{it.setLength(Int.MAX_VALUE.toLong()+4096)}
            assertFalse(EmbeddedArtwork.hasCover(file,"mp3"))
        }finally{file.delete()}
    }
}
