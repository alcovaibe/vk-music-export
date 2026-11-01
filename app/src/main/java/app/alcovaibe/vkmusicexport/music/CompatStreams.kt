package app.alcovaibe.vkmusicexport.music

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * InputStream.readNBytes(int) is absent on Android 10. Read no more than the
 * caller's strict input limit, preserving EOF/short-read semantics without
 * buffering untrusted files indefinitely or changing their size checks.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit>=0)
    val output=ByteArrayOutputStream(minOf(limit,8192))
    val buffer=ByteArray(minOf(limit.coerceAtLeast(1),8192))
    var remaining=limit
    while(remaining>0) {
        val n=read(buffer,0,minOf(buffer.size,remaining))
        if(n<0)break
        if(n==0) {
            val one=read()
            if(one<0)break
            output.write(one)
            remaining--
        }else{
            output.write(buffer,0,n)
            remaining-=n
        }
    }
    return output.toByteArray()
}
