package app.alcovaibe.vkmusicexport.music

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class CoverImage(val bytes: ByteArray,val mime: String)

/** Only metadata is rewritten. MP3 payload / MP4 mdat bytes are copied verbatim. No codec runs here. */
internal object EmbeddedArtwork {
    private const val MAX_META=8*1024*1024
    private fun check(ok: Boolean)=musicCheck(ok,MusicError.FORMAT)
    private fun int(bytes: ByteArray,offset: Int)=ByteBuffer.wrap(bytes,offset,4).int
    private fun uint(bytes: ByteArray,offset: Int)=int(bytes,offset).toLong() and 0xffffffffL
    private fun sync(bytes: ByteArray,offset: Int): Int {
        check((offset until offset+4).all{bytes[it].toInt() and 128==0})
        return (0..3).fold(0){n,i->(n shl 7) or (bytes[offset+i].toInt() and 127)}
    }
    private fun sync(n: Int)=byteArrayOf((n ushr 21).toByte(),((n ushr 14) and 127).toByte(),((n ushr 7) and 127).toByte(),(n and 127).toByte())
    private data class Tag(val version: Int,val bytes: ByteArray,val end: Long,val cover: Boolean)
    private fun tag(file: RandomAccessFile): Tag {
        file.seek(0);val head=ByteArray(minOf(10,file.length().toInt()));file.readFully(head)
        if(head.size<3||String(head,0,3,Charsets.US_ASCII)!="ID3")return Tag(3,byteArrayOf(),0,false)
        check(head.size==10&&head[3].toInt() in 3..4&&head[4].toInt()==0&&head[5].toInt()==0)
        val size=sync(head,6);check(size<=MAX_META&&size+10L<=file.length())
        val body=ByteArray(size);file.readFully(body);var p=0;var cover=false
        while(p<body.size) {
            if(body[p].toInt()==0){check((p until body.size).all{body[it].toInt()==0});break}
            check(body.size-p>=10)
            val id=String(body,p,4,Charsets.US_ASCII);check(id.all{it in 'A'..'Z'||it in '0'..'9'})
            val length=if(head[3].toInt()==4)sync(body,p+4)else int(body,p+4)
            check(length>=0&&length<=body.size-p-10)
            if(id=="APIC")cover=true
            p+=10+length
        }
        return Tag(head[3].toInt(),body,10L+size,cover)
    }
    private data class Box(val start: Long,val size: Long,val header: Int,val type: String){val end get()=start+size}
    private fun box(file: RandomAccessFile,end: Long): Box {
        val start=file.filePointer;check(end-start>=8)
        val size32=file.readInt().toLong() and 0xffffffffL
        val type=ByteArray(4).also{file.readFully(it)}.toString(Charsets.ISO_8859_1)
        val header=if(size32==1L)16 else 8
        val size=when(size32){0L->end-start;1L->{check(end-start>=16);file.readLong()};else->size32}
        check(size>=header&&size<=end-start)
        return Box(start,size,header,type)
    }
    private data class Atom(val type: String,val data: ByteArray) {
        fun encoded(): ByteArray=ByteBuffer.allocate(8+data.size).putInt(8+data.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(data).array()
    }
    private fun atoms(data: ByteArray,offset: Int=0): List<Atom> {
        val result=mutableListOf<Atom>();var p=offset
        while(p<data.size) {
            check(data.size-p>=8);val size=uint(data,p);check(size in 8..(data.size-p).toLong())
            result.add(Atom(String(data,p+4,4,Charsets.ISO_8859_1),data.copyOfRange(p+8,p+size.toInt())))
            p+=size.toInt();check(result.size<=100000)
        }
        return result
    }
    private fun encode(items: List<Atom>): ByteArray=ByteArrayOutputStream().also{out->items.forEach{out.write(it.encoded())}}.toByteArray()
    private fun one(items: List<Atom>,type: String): Atom? {check(items.count{it.type==type}<=1);return items.firstOrNull{it.type==type}}
    private fun containsCover(moov: ByteArray): Boolean {
        val udta=one(atoms(moov),"udta")?:return false
        val meta=one(atoms(udta.data),"meta")?:return false
        check(meta.data.size>=4&&meta.data.take(4).all{it.toInt()==0})
        val ilst=one(atoms(meta.data,4),"ilst")?:return false
        return atoms(ilst.data).any{it.type=="covr"}
    }
    private fun addCover(moov: ByteArray,image: CoverImage): ByteArray {
        fun replace(items: List<Atom>,type: String,updated: Atom)=items.filterNot{it.type==type}+updated
        val root=atoms(moov);val user=one(root,"udta")?.let{atoms(it.data)}?:emptyList()
        val meta=one(user,"meta")
        val metadata=if(meta==null)listOf(Atom("hdlr",ByteBuffer.allocate(25).putLong(0).put("mdir".toByteArray()).put("appl".toByteArray()).putLong(0).put(0).array()))else {
            check(meta.data.size>=4&&meta.data.take(4).all{it.toInt()==0});atoms(meta.data,4)
        }
        val old=one(metadata,"ilst")?.let{atoms(it.data)}?:emptyList()
        val kind=if(image.mime=="image/jpeg")13 else 14
        val cover=Atom("covr",Atom("data",ByteBuffer.allocate(8+image.bytes.size).putInt(kind).putInt(0).put(image.bytes).array()).encoded())
        val ilst=Atom("ilst",encode(old+cover))
        val nextMeta=Atom("meta",ByteArray(4)+encode(replace(metadata,"ilst",ilst)))
        return encode(replace(root,"udta",Atom("udta",encode(replace(user,"meta",nextMeta)))))
    }
    private val containers=setOf("trak","mdia","minf","stbl")
    private fun offsets(data: ByteArray,oldMoov: Box,delta: Long,fileSize: Long,depth: Int=0): ByteArray {
        check(depth<=8)
        return encode(atoms(data).map{atom->when {
            atom.type in containers->atom.copy(data=offsets(atom.data,oldMoov,delta,fileSize,depth+1))
            atom.type in setOf("stco","co64")->{
                val b=atom.data.copyOf();check(b.size>=8);val count=uint(b,4);val width=if(atom.type=="stco")4 else 8
                check(count<=1000000&&b.size.toLong()==8+count*width)
                repeat(count.toInt()){i->
                    val p=8+i*width;val previous=if(width==4)uint(b,p)else ByteBuffer.wrap(b,p,8).long
                    check(previous in 0 until fileSize&&previous !in oldMoov.start until oldMoov.end)
                    val next=previous+if(previous>=oldMoov.end)delta else 0
                    check(next>=0&&(width==8||next<=0xffffffffL))
                    if(width==4)ByteBuffer.wrap(b,p,4).putInt(next.toInt())else ByteBuffer.wrap(b,p,8).putLong(next)
                };atom.copy(data=b)
            }
            else->atom
        }})
    }
    private fun roots(file: RandomAccessFile): List<Box> {
        val result=mutableListOf<Box>();file.seek(0)
        while(file.filePointer<file.length()){val b=box(file,file.length());check(b.type !in setOf("moof","mfra"));result.add(b);file.seek(b.end);check(result.size<=10000)}
        check(result.count{it.type=="moov"}==1&&result.any{it.type=="mdat"})
        return result
    }
    private fun moov(file: RandomAccessFile,root: List<Box>): Pair<Box,ByteArray> {
        val b=root.single{it.type=="moov"};check(b.size<=MAX_META)
        file.seek(b.start+b.header);return b to ByteArray((b.size-b.header).toInt()).also{file.readFully(it)}
    }
    fun hasCover(input: File,extension: String): Boolean=RandomAccessFile(input,"r").use{file->when(extension){
        "mp3"->tag(file).cover
        "m4a"->containsCover(moov(file,roots(file)).second)
        else->throw MusicFailure(MusicError.FORMAT)
    }}
    private fun copy(file: RandomAccessFile,out: OutputStream,start: Long,end: Long,active:()->Unit) {
        file.seek(start);var left=end-start;val buffer=ByteArray(65536)
        while(left>0){active();val n=file.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0);out.write(buffer,0,n);left-=n}
    }
    fun write(input: File,output: File,extension: String,image: CoverImage,active:()->Unit={}) {
        check(input.canonicalPath!=output.canonicalPath&&image.mime in setOf("image/jpeg","image/png")&&image.bytes.size in 1..2*1024*1024)
        try{RandomAccessFile(input,"r").use{file->FileOutputStream(output).use{out->
            if(extension=="mp3") {
                val old=tag(file);check(!old.cover)
                val pic=byteArrayOf(0)+image.mime.toByteArray(Charsets.US_ASCII)+byteArrayOf(0,3,0)+image.bytes
                val frame="APIC".toByteArray()+if(old.version==4)sync(pic.size)else ByteBuffer.allocate(4).putInt(pic.size).array()
                val body=frame+byteArrayOf(0,0)+pic+old.bytes;check(body.size<=MAX_META)
                out.write(byteArrayOf(73,68,51,old.version.toByte(),0,0)+sync(body.size));out.write(body)
                copy(file,out,old.end,file.length(),active)
            }else {
                check(extension=="m4a")
                val roots=roots(file);val (old,bytes)=moov(file,roots);check(!containsCover(bytes))
                val added=addCover(bytes,image);val delta=added.size+8L-old.size
                val updated=Atom("moov",offsets(added,old,delta,file.length())).encoded()
                roots.forEach{if(it==old)out.write(updated)else copy(file,out,it.start,it.end,active)}
            }
            active();out.flush();out.fd.sync()
        }}}catch(e: Exception){output.delete();throw e}
        check(hasCover(output,extension)&&payloadDigest(input,extension,active)==payloadDigest(output,extension,active))
    }
    fun payloadDigest(input: File,extension: String,active:()->Unit={}): String=RandomAccessFile(input,"r").use{file->
        val digest=MessageDigest.getInstance("SHA-256")
        val out=object: OutputStream(){override fun write(b: Int){digest.update(b.toByte())};override fun write(b: ByteArray,off: Int,len: Int){digest.update(b,off,len)}}
        if(extension=="mp3")copy(file,out,tag(file).end,file.length(),active)
        else {check(extension=="m4a");roots(file).filter{it.type=="mdat"}.forEach{copy(file,out,it.start+it.header,it.end,active)}}
        digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
