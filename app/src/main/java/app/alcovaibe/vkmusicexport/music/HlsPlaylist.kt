package app.alcovaibe.vkmusicexport.music

import java.math.BigInteger

internal class HlsKey(val url: String,val iv: ByteArray) {
    override fun toString()="HlsKey([redacted])"
}
internal class HlsSegment(val url: String,val duration: Double,val key: HlsKey?) {
    override fun toString()="HlsSegment([redacted])"
}
internal class HlsPlan(val redirect: String? = null,val init: String? = null,val segments: List<HlsSegment> = emptyList()) {
    val duration get() = segments.sumOf {it.duration}
    override fun toString()="HlsPlan([redacted])"
}

/** Deliberate finite VOD subset. Unsupported range/delta/discontinuous/encrypted-map forms fail closed. */
internal object HlsPlaylist {
    private fun check(ok: Boolean) = musicCheck(ok,MusicError.HLS)
    private fun attrs(raw: String): Map<String,String> {
        val out=linkedMapOf<String,String>();var i=0
        while(i<raw.length) {
            val start=i;while(i<raw.length&&raw[i]!='=')i++
            check(i<raw.length);val name=raw.substring(start,i);check(Regex("[A-Z0-9-]+").matches(name));i++
            val value: String
            if(i<raw.length&&raw[i]=='"') {
                i++;val s=i;while(i<raw.length&&raw[i]!='"')i++;check(i<raw.length)
                value=raw.substring(s,i);i++
            }else {val s=i;while(i<raw.length&&raw[i]!=',')i++;value=raw.substring(s,i)}
            check(value.isNotEmpty()&&name !in out);out[name]=value
            if(i<raw.length){check(raw[i]==',');i++;check(i<raw.length)}
        }
        return out
    }
    fun parse(base: String,raw: String): HlsPlan {
        check(raw.length<=524288)
        val lines=raw.removePrefix("\uFEFF").lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList()
        check(lines.size in 2..20000&&lines.first()=="#EXTM3U")
        check(lines.none{it.startsWith("#EXT-X-SESSION-KEY:")})
        if(lines.any{it.startsWith("#EXT-X-STREAM-INF:")}) {
            check(lines.none{it.startsWith("#EXTINF:")})
            val audios=lines.filter{it.startsWith("#EXT-X-MEDIA:")}.map{attrs(it.substringAfter(':'))}
                .filter{it["TYPE"]=="AUDIO"&&it["URI"]!=null}
            if(audios.isNotEmpty()) {
                val selected=audios.firstOrNull{it["DEFAULT"]=="YES"}?:audios.first()
                return HlsPlan(redirect=MediaAddress.resolve(base,selected.getValue("URI")))
            }
            val variants=mutableListOf<Pair<Long,String>>()
            for(i in 1 until lines.size)if(lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                val a=attrs(lines[i].substringAfter(':'));val bw=a["BANDWIDTH"]?.toLongOrNull()
                check(bw!=null&&bw>0&&i+1<lines.size&&!lines[i+1].startsWith('#')&&a["VIDEO"]==null)
                variants.add(bw!! to MediaAddress.resolve(base,lines[i+1]));check(variants.size<=16)
            }
            check(variants.isNotEmpty())
            return HlsPlan(redirect=variants.maxBy{it.first}.second)
        }
        var sequence=0L;var sequenceSeen=false;var duration: Double?=null;var init: String?=null
        var keyUrl: String?=null;var explicitIv: ByteArray?=null;var ended=false;var total=0.0
        val segments=mutableListOf<HlsSegment>()
        for(line in lines.drop(1))when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:")-> {
                check(!sequenceSeen&&segments.isEmpty());sequenceSeen=true
                sequence=line.substringAfter(':').toLongOrNull()?:throw MusicFailure(MusicError.HLS);check(sequence>=0)
            }
            line.startsWith("#EXTINF:")-> {
                check(!ended&&duration==null)
                duration=line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                check(duration!=null&&duration.isFinite()&&duration>0&&duration<=3600)
            }
            line.startsWith("#EXT-X-KEY:")-> {
                val a=attrs(line.substringAfter(':'))
                when(a["METHOD"]) {
                    "NONE"->{keyUrl=null;explicitIv=null;check(a.size==1)}
                    "AES-128"->{
                        check(a["KEYFORMAT"] in setOf(null,"identity")&&a["KEYFORMATVERSIONS"] in setOf(null,"1"))
                        keyUrl=MediaAddress.resolve(base,a["URI"]?:throw MusicFailure(MusicError.HLS))
                        explicitIv=a["IV"]?.let {
                            check(Regex("0[xX][0-9a-fA-F]{1,32}").matches(it));hexIv(it.substring(2))
                        }
                    }
                    else->throw MusicFailure(MusicError.HLS)
                }
            }
            line.startsWith("#EXT-X-MAP:")-> {
                check(segments.isEmpty()&&init==null&&keyUrl==null)
                val a=attrs(line.substringAfter(':'));check(a.keys==setOf("URI"))
                init=MediaAddress.resolve(base,a.getValue("URI"))
            }
            line=="#EXT-X-ENDLIST"->{check(!ended&&duration==null);ended=true}
            line.startsWith("#EXT-X-BYTERANGE:")||line.startsWith("#EXT-X-DISCONTINUITY")||
                line.startsWith("#EXT-X-GAP")||line.startsWith("#EXT-X-SKIP")||line.startsWith("#EXT-X-PART")||
                line.startsWith("#EXT-X-PRELOAD-HINT")->throw MusicFailure(MusicError.HLS)
            line.startsWith('#')->Unit
            else->{
                check(!ended&&duration!=null&&segments.size<2000)
                val n=sequence+segments.size;check(n>=sequence)
                val key=keyUrl?.let {HlsKey(it,explicitIv?.copyOf()?:sequenceIv(n))}
                val seconds=duration?:throw MusicFailure(MusicError.HLS)
                total+=seconds;check(total<=86400)
                segments.add(HlsSegment(MediaAddress.resolve(base,line),seconds,key));duration=null
            }
        }
        check(ended&&duration==null&&segments.isNotEmpty())
        return HlsPlan(init=init,segments=segments)
    }
    internal fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { out ->
        check(sequence>=0);var n=sequence;for(i in 15 downTo 8){out[i]=(n and 255).toByte();n=n ushr 8}
    }
    private fun hexIv(raw: String): ByteArray {
        val bytes=BigInteger(raw,16).toByteArray();return ByteArray(16).also{out->
            val src=if(bytes.size>16)bytes.copyOfRange(bytes.size-16,bytes.size)else bytes
            System.arraycopy(src,0,out,16-src.size,src.size)
        }
    }
}
