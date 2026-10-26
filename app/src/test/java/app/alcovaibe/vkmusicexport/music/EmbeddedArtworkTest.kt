package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class EmbeddedArtworkTest {
    private val image=CoverImage(byteArrayOf(1,2,3,4),"image/jpeg")
    private fun box(type: String,data: ByteArray)=ByteBuffer.allocate(8+data.size).putInt(8+data.size).put(type.toByteArray()).put(data).array()
    private fun temporary(block:(File)->Unit){val dir=kotlin.io.path.createTempDirectory("artwork-test").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    @Test fun mp3KeepsExistingTagsAndEveryAudioByteIncludingId3v1()=temporary{dir->
        val frame="TIT2".toByteArray()+ByteBuffer.allocate(4).putInt(6).array()+byteArrayOf(0,0,0)+"Title".toByteArray()
        val body=frame+ByteArray(10)
        val original=byteArrayOf(73,68,51,3,0,0,0,0,0,body.size.toByte())+body+ByteArray(4096){it.toByte()}+"TAG".toByteArray()
        val src=File(dir,"a.mp3").apply{writeBytes(original)};val out=File(dir,"out.mp3")
        EmbeddedArtwork.write(src,out,"mp3",image)
        assertTrue(EmbeddedArtwork.hasCover(out,"mp3"));assertArrayEquals(original,src.readBytes())
        assertEquals(EmbeddedArtwork.payloadDigest(src,"mp3"),EmbeddedArtwork.payloadDigest(out,"mp3"))
        assertTrue(out.readBytes().toString(Charsets.ISO_8859_1).contains("Title"))
        assertThrows(MusicFailure::class.java){EmbeddedArtwork.write(out,File(dir,"twice.mp3"),"mp3",image)}
        // ID3v2.4 uses synchsafe frame lengths and must stay v2.4.
        val v4=File(dir,"v4.mp3").apply{writeBytes(byteArrayOf(73,68,51,4,0,0,0,0,0,0)+ByteArray(1024){7})}
        EmbeddedArtwork.write(v4,out,"mp3",image);assertEquals(4,out.readBytes()[3].toInt())
    }
    @Test fun m4aRetainsMdatAndAdjustsChunkOffsetsWhenMoovPrecedesAudio()=temporary{dir->
        for(first in listOf(true,false))for(wide in listOf(true,false)) {
            val ftyp=box("ftyp","M4A ".toByteArray()+ByteArray(8));val payload=ByteArray(1024){it.toByte()}
            fun moov(offset: Long): ByteArray {
                val entry=if(wide)ByteBuffer.allocate(8).putLong(offset).array()else ByteBuffer.allocate(4).putInt(offset.toInt()).array()
                val chunks=box(if(wide)"co64" else "stco",ByteArray(4)+byteArrayOf(0,0,0,1)+entry)
                return box("moov",box("trak",box("mdia",box("minf",box("stbl",chunks)))))
            }
            val position=ftyp.size+(if(first)moov(0).size else 0)+8L
            val src=File(dir,"in.m4a").apply{writeBytes(ftyp+(if(first)moov(position) else byteArrayOf())+box("mdat",payload)+(if(first)byteArrayOf()else moov(position)))}
            val out=File(dir,"out.m4a");EmbeddedArtwork.write(src,out,"m4a",image)
            assertTrue(EmbeddedArtwork.hasCover(out,"m4a"));assertEquals(EmbeddedArtwork.payloadDigest(src,"m4a"),EmbeddedArtwork.payloadDigest(out,"m4a"))
            val bytes=out.readBytes();val key=if(wide)"co64" else "stco"
            val p=bytes.toString(Charsets.ISO_8859_1).indexOf(key)+12
            val offset=if(wide)ByteBuffer.wrap(bytes,p,8).long else ByteBuffer.wrap(bytes,p,4).int.toLong()
            assertArrayEquals(payload,bytes.copyOfRange(offset.toInt(),offset.toInt()+payload.size))
        }
    }
    @Test fun malformedMetadataCannotOverwriteInputAndUnsafeImageAddressesAreRejected()=temporary{dir->
        val src=File(dir,"broken.m4a").apply{writeBytes(byteArrayOf(0,0,0,127)+"moov".toByteArray())}
        val before=src.readBytes()
        assertThrows(MusicFailure::class.java){EmbeddedArtwork.write(src,File(dir,"out"),"m4a",image)}
        assertArrayEquals(before,src.readBytes())
        for(url in listOf("http://userapi.com/x","https://userapi.com.evil.test/x","https://127.0.0.1/x","https://u:p@userapi.com/x","https://userapi.com:444/x"))assertThrows(MusicFailure::class.java){ArtworkAddress.require(url)}
        assertEquals("sun9.userapi.com",ArtworkAddress.require("https://sun9.userapi.com/a.jpg").host)
    }
    @Test fun coverComesOnlyFromTheExactAudioOrAlbumAndNeverFromAPlaylist() {
        val expected=NativeTrack("12_34","CI","Artist",1)
        fun response(extra: String)=Json.parseToJsonElement("""[{"owner_id":12,"id":34,"title":"CI","artist":"Artist","duration":1,$extra}]""")
        val extra=""""thumb":{"photo_600":"https://sun9.userapi.com/track.jpg"},"album":{"id":4,"title":"Album","thumb":{"photo_1200":"https://sun9.userapi.com/album.jpg"}}"""
        assertEquals("https://sun9.userapi.com/track.jpg",NativeProtocol.artwork(response(extra),expected))
        assertNull(NativeProtocol.artwork(response(""""playlist":{"thumb":{"photo_600":"https://sun9.userapi.com/wrong.jpg"}}"""),expected))
        assertThrows(MusicFailure::class.java){NativeProtocol.artwork(response(extra),expected.copy(id="12_35"))}
        assertEquals(MusicError.MEDIA_URL,assertThrows(MusicFailure::class.java){NativeProtocol.artwork(response(""""thumb":{"photo_600":"https://evil.test/x"}"""),expected)}.code)
    }
}
