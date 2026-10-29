package app.alcovaibe.vkmusicexport.music

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.TsExtractor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer

/** Media3 detects the actual audio PID/codec; no hard-coded TS PID and no lossy re-encoding. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AndroidAudioProcessor : AudioProcessor {
    override suspend fun process(input: File,output: File,track: NativeTrack,hlsSeconds: Double?): ProcessedAudio {
        val context=currentCoroutineContext()
        val candidates=DefaultExtractorsFactory().setTsExtractorMode(TsExtractor.MODE_HLS).createExtractors()
        RandomAccessFile(input,"r").use { file ->
            val reader=DataReader{buffer,offset,length->file.read(buffer,offset,length)}
            val extractor=candidates.firstOrNull { candidate ->
                context.ensureActive();file.seek(0)
                try {candidate.sniff(DefaultExtractorInput(reader,0,input.length()))}catch(_: IOException){false}
            }?:throw MusicFailure(MusicError.FORMAT)
            candidates.filter{it!==extractor}.forEach{it.release()}
            val audio=AudioOutput(output,track){context.ensureActive()}
            val outputs=mutableMapOf<Int,TrackOutput>()
            extractor.init(object: ExtractorOutput {
                override fun track(id: Int,type: Int): TrackOutput = outputs.getOrPut(id) {
                    when(type) {
                        C.TRACK_TYPE_AUDIO->{musicCheck(outputs.values.none{it===audio},MusicError.FORMAT);audio}
                        C.TRACK_TYPE_VIDEO->throw MusicFailure(MusicError.FORMAT)
                        else->DiscardingTrackOutput()
                    }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            try {
                file.seek(0);var source=DefaultExtractorInput(reader,0,input.length());val seek=PositionHolder();var seeks=0
                while(true) {
                    context.ensureActive()
                    when(extractor.read(source,seek)) {
                        Extractor.RESULT_END_OF_INPUT->break
                        Extractor.RESULT_SEEK->{
                            musicCheck(++seeks<=32&&seek.position in 0..input.length(),MusicError.FORMAT)
                            file.seek(seek.position);source=DefaultExtractorInput(reader,seek.position,input.length())
                        }
                    }
                }
                val result=audio.finish()
                AudioCompleteness.requireComplete(result.durationMs, track.duration, hlsSeconds)
                if (result.extension == "m4a") {
                    val tagged=File(output.parentFile,output.name+".metadata")
                    try {
                        EmbeddedArtwork.writeTextMetadata(output,tagged,track){context.ensureActive()}
                        context.ensureActive()
                        musicCheck(tagged.renameTo(output),MusicError.STORAGE)
                    } finally { tagged.delete() }
                }
                return result
            }finally {extractor.release();audio.close()}
        }
    }

    private class AudioOutput(private val output: File,private val track: NativeTrack,private val active:()->Unit): TrackOutput,Closeable {
        private var format: Format?=null
        private var mp3: FileOutputStream?=null
        private var muxer: MediaMuxer?=null
        private var muxerTrack=-1
        private var pending=ByteArrayOutputStream()
        private var samples=0L;private var firstUs=-1L;private var lastUs=-1L;private var frameUs=0L
        private var finished=false
        override fun format(format: Format) {
            val previous=this.format
            musicCheck(format.sampleMimeType in setOf("audio/mpeg","audio/mp4a-latm")&&format.sampleRate in 1..384000&&format.channelCount in 1..8,MusicError.FORMAT)
            if(previous!=null) {
                musicCheck(previous.sampleMimeType==format.sampleMimeType&&previous.sampleRate==format.sampleRate&&previous.channelCount==format.channelCount&&
                    previous.initializationData.size==format.initializationData.size&&previous.initializationData.indices.all{previous.initializationData[it].contentEquals(format.initializationData[it])},MusicError.FORMAT)
                return
            }
            this.format=format
            if(format.sampleMimeType=="audio/mpeg") {
                mp3=FileOutputStream(output).also{it.write(id3(track))}
            }else {
                val media=MediaFormat.createAudioFormat("audio/mp4a-latm",format.sampleRate,format.channelCount)
                format.initializationData.forEachIndexed{i,data->media.setByteBuffer("csd-$i",ByteBuffer.wrap(data))}
                muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                    muxerTrack=it.addTrack(media);it.start()
                }
            }
        }
        override fun sampleData(input: DataReader,length: Int,allowEndOfInput: Boolean,sampleDataPart: Int): Int {
            active();musicCheck(sampleDataPart==TrackOutput.SAMPLE_DATA_PART_MAIN,MusicError.FORMAT)
            val buffer=ByteArray(minOf(length,65536));val n=input.read(buffer,0,buffer.size)
            if(n<0){if(allowEndOfInput)return -1;throw EOFException()}
            append(buffer,0,n);return n
        }
        override fun sampleData(data: ParsableByteArray,length: Int,sampleDataPart: Int) {
            active();musicCheck(sampleDataPart==TrackOutput.SAMPLE_DATA_PART_MAIN&&length>=0&&length<=4*1024*1024,MusicError.FORMAT)
            val bytes=ByteArray(length);data.readBytes(bytes,0,length);append(bytes,0,length)
        }
        private fun append(bytes: ByteArray,offset: Int,length: Int) {
            musicCheck(pending.size()+length<=4*1024*1024,MusicError.FORMAT);pending.write(bytes,offset,length)
        }
        override fun sampleMetadata(timeUs: Long,flags: Int,size: Int,offset: Int,cryptoData: TrackOutput.CryptoData?) {
            active();val fmt=format?:throw MusicFailure(MusicError.FORMAT)
            musicCheck(cryptoData==null&&flags and C.BUFFER_FLAG_ENCRYPTED==0&&size>0&&offset>=0&&size+offset<=pending.size()&&timeUs>=0,MusicError.FORMAT)
            val all=pending.toByteArray();val start=all.size-offset-size
            musicCheck(start==0,MusicError.FORMAT)
            if(firstUs<0)firstUs=timeUs
            musicCheck(lastUs<0||timeUs>=lastUs,MusicError.INCOMPLETE)
            lastUs=timeUs;samples++
            frameUs=if(fmt.sampleMimeType=="audio/mpeg") {
                musicCheck(size>=4&&(all[0].toInt() and 255)==255&&(all[1].toInt() and 224)==224,MusicError.FORMAT)
                val version=(all[1].toInt() ushr 3) and 3
                musicCheck(version!=1&&((all[1].toInt() ushr 1) and 3)==1,MusicError.FORMAT)
                (if(version==3)1152L else 576L)*1000000/fmt.sampleRate
            }else 1024L*1000000/fmt.sampleRate
            if(mp3!=null)mp3!!.write(all,start,size) else muxer!!.writeSampleData(muxerTrack,ByteBuffer.wrap(all,start,size),MediaCodec.BufferInfo().apply {
                set(start,size,timeUs-firstUs,if(flags and C.BUFFER_FLAG_KEY_FRAME!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            })
            pending=ByteArrayOutputStream().also{if(offset>0)it.write(all,all.size-offset,offset)}
        }
        fun finish(): ProcessedAudio {
            musicCheck(samples>0&&pending.size()==0&&firstUs>=0&&lastUs>=firstUs,MusicError.INCOMPLETE)
            val isMp3=mp3!=null
            mp3?.let{it.flush();it.fd.sync();it.close();mp3=null}
            muxer?.let{it.stop();it.release();muxer=null}
            RandomAccessFile(output,"rw").use{it.fd.sync()};finished=true
            return ProcessedAudio(if(isMp3)"mp3" else "m4a",if(isMp3)"audio/mpeg" else "audio/mp4",(lastUs-firstUs+frameUs)/1000)
        }
        override fun close() {
            runCatching{mp3?.close()};mp3=null
            runCatching{muxer?.release()};muxer=null
            if(!finished)output.delete()
        }
    }
    private companion object {
        fun id3(track: NativeTrack): ByteArray {
            val frames=ByteArrayOutputStream()
            fun frame(name: String,value: String) {
                val bytes=byteArrayOf(1)+value.toByteArray(Charsets.UTF_16)
                frames.write(name.toByteArray(Charsets.US_ASCII))
                frames.write(ByteBuffer.allocate(4).putInt(bytes.size).array());frames.write(byteArrayOf(0,0));frames.write(bytes)
            }
            frame("TIT2",track.title);frame("TPE1",track.artist);track.album?.let{frame("TALB",it.title)}
            val n=frames.size();val size=byteArrayOf(((n ushr 21) and 127).toByte(),((n ushr 14) and 127).toByte(),((n ushr 7) and 127).toByte(),(n and 127).toByte())
            return byteArrayOf(73,68,51,3,0,0)+size+frames.toByteArray()
        }
    }
}
