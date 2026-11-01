package app.alcovaibe.vkmusicexport.music

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded InputStream reading without Java 9 library APIs (available on Android only from API 33). */
internal fun InputStream.readBoundedBytes(limit: Int): ByteArray {
    require(limit>=0)
    val result=ByteArrayOutputStream(minOf(limit,8192))
    val buffer=ByteArray(8192)
    var remaining=limit
    while(remaining>0) {
        val n=read(buffer,0,minOf(buffer.size,remaining))
        if(n<0)break
        if(n==0) {
            val b=read()
            if(b<0)break
            result.write(b)
            remaining--
        }else {
            result.write(buffer,0,n)
            remaining-=n
        }
    }
    return result.toByteArray()
}
