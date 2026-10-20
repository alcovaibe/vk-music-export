package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class HlsPlaylistTest {
    private val base="https://audio.userapi.com/path/index.m3u8?secret=SIGNATURE"
    @Test fun finitePlaylistPreservesRelativeUrlsDurationSequenceAndKeyRotation() {
        val p=HlsPlaylist.parse(base,"""#EXTM3U
            #EXT-X-MEDIA-SEQUENCE:257
            #EXT-X-KEY:METHOD=AES-128,URI="key"
            #EXTINF:1.25,
            a.ts?sig=ONE
            #EXT-X-KEY:METHOD=AES-128,URI="key2",IV=0x2
            #EXTINF:2.5,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        assertEquals(3.75,p.duration,0.0);assertEquals(2,p.segments.size)
        assertEquals("https://audio.userapi.com/path/a.ts?sig=ONE",p.segments[0].url)
        assertArrayEquals(HlsPlaylist.sequenceIv(257),p.segments[0].key!!.iv)
        assertEquals(2,p.segments[1].key!!.iv.last().toInt());assertFalse(p.toString().contains("SIGNATURE"))
    }
    @Test fun masterSelectsExplicitAudioAndOtherwiseHighestBandwidthVariant() {
        val p=HlsPlaylist.parse(base,"""#EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="audio, one",DEFAULT=YES,URI="audio/list"
            #EXT-X-STREAM-INF:BANDWIDTH=123,AUDIO="g"
            video/list
        """.trimIndent())
        assertEquals("https://audio.userapi.com/path/audio/list",p.redirect)
        assertTrue(HlsPlaylist.parse(base,"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na\n#EXT-X-STREAM-INF:BANDWIDTH=2\nb").redirect!!.endsWith("/b"))
    }
    @Test fun partialLiveGappedRangeDrmAndDiscontinuousPlaylistsAreNotSuccessfulFiles() {
        val valid="#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST"
        val invalid=listOf(valid.replace("#EXT-X-ENDLIST",""),valid.replace("#EXTINF:1,","#EXT-X-GAP\n#EXTINF:1,"),
            valid.replace("#EXTINF:1,","#EXT-X-BYTERANGE:10@0\n#EXTINF:1,"),valid.replace("#EXTINF:1,","#EXT-X-DISCONTINUITY\n#EXTINF:1,"),
            valid.replace("#EXTINF:1,","#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,"),
            valid.replace("#EXTINF:1,","#EXT-X-KEY:METHOD=AES-128,URI=\"key\",KEYFORMAT=\"com.drm\"\n#EXTINF:1,"),
            valid.replace("#EXTINF:1,","#EXTINF:NaN,"),valid+"\nmore.ts",valid.replace("a.ts","https://evil.test/part"))
        invalid.forEach{assertThrows(MusicFailure::class.java){HlsPlaylist.parse(base,it)}}
    }
    @Test fun mapMustBeUnencryptedAndStableBeforeTheFirstSegment() {
        val p=HlsPlaylist.parse(base,"#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST")
        assertTrue(p.init!!.endsWith("init.mp4"))
        assertThrows(MusicFailure::class.java){HlsPlaylist.parse(base,"#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init\"\n#EXTINF:1,\na\n#EXT-X-ENDLIST")}
    }
}
