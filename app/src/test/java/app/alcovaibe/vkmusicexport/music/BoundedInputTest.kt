package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedInputTest {
    @Test fun returnsExactlyRequestedBytesWithoutReadingNext() {
        val source=ByteArrayInputStream(byteArrayOf(1,2,3,4,5))
        assertArrayEquals(byteArrayOf(1,2,3),source.readBoundedBytes(3))
        assertEquals(4,source.read())
    }
    @Test fun shortStreamsAndEmptyBoundsAreSupported() {
        assertArrayEquals(byteArrayOf(1,2),ByteArrayInputStream(byteArrayOf(1,2)).readBoundedBytes(10))
        assertArrayEquals(byteArrayOf(),ByteArrayInputStream(byteArrayOf(1,2)).readBoundedBytes(0))
    }
    @Test fun zeroLengthReadsStillMakeProgressWithoutInfiniteLoop() {
        val source=object: InputStream() {
            private val input=ByteArrayInputStream(byteArrayOf(10,20,30))
            private var first=true
            override fun read(): Int=input.read()
            override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                if(first){first=false;return 0}
                return input.read(bytes,offset,length)
            }
        }
        assertArrayEquals(byteArrayOf(10,20,30),source.readBoundedBytes(4))
    }
    @Test(expected=IllegalArgumentException::class)
    fun negativeBoundIsRejected() {ByteArrayInputStream(byteArrayOf(1)).readBoundedBytes(-1)}
}
