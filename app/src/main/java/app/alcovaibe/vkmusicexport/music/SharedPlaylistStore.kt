package app.alcovaibe.vkmusicexport.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.Locale

@Serializable internal data class PlaylistRelocation(val beforePath: String,val afterPath: String,
    val durationMs: Long,val sources: List<String>)

/** Plain portable M3U8 files; does not use the deprecated Android playlist database. */
internal class SharedPlaylistStore(private val context: Context,private val store: AudioExportStore) {
    @Serializable private data class Playlist(val uri: String,val directory: String,val bytes: Int,val sha256: String)
    private val resolver=context.contentResolver
    private val collection=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val json=Json{encodeDefaults=true}
    private fun target(raw: String): Uri {
        val uri=Uri.parse(raw)
        musicCheck(uri.scheme=="content"&&uri.authority=="media"&&uri.query==null&&uri.fragment==null&&
            uri.pathSegments.size==3&&uri.pathSegments.take(2)==listOf("external_primary","file")&&
            (uri.lastPathSegment?.toLongOrNull()?:0)>0,MusicError.STORAGE)
        return uri
    }
    private fun owned(row: Playlist,pending: Boolean): Boolean=runCatching {
        resolver.query(target(row.uri),arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use{c->
            c.moveToFirst()&&c.getString(0)==context.packageName&&c.getString(1)==row.directory&&
                row.directory.startsWith(ExportLayout.ROOT)&&c.getInt(2)==(if(pending)1 else 0)
        }?:false
    }.getOrDefault(false)
    private suspend fun read(row: Playlist): ByteArray? {
        if(row.bytes !in 1..4*1024*1024||!owned(row,false))return null
        return try {
            val digest=MessageDigest.getInstance("SHA-256");val output=ByteArrayOutputStream(row.bytes)
            resolver.openInputStream(target(row.uri))?.use{input->
                val b=ByteArray(16384)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break
                    if(output.size()+n>row.bytes)return null;digest.update(b,0,n);output.write(b,0,n)}
            }?:return null
            if(output.size()==row.bytes&&hex(digest.digest())==row.sha256)output.toByteArray()else null
        }catch(e: kotlinx.coroutines.CancellationException){throw e}catch(_: Exception){null}
    }
    private suspend fun valid(row: Playlist)=read(row)!=null
    private fun row(key: String)=store.checkpoint(key)?.takeIf{it.isNotEmpty()}?.let{runCatching{json.decodeFromString<Playlist>(it)}.getOrNull()}
    suspend fun matches(source: ExportSource,tasks: List<AudioExportTask>,files: List<PublishedAudio>): Boolean {
        val stored=row("m3u:"+source.key)?:return false
        val current=read(stored)?:return false
        val expected=M3uPlaylist.render(stored.directory,tasks,files.associateBy{it.taskKey}).toByteArray(Charsets.UTF_8)
        return current.contentEquals(expected)
    }
    suspend fun publishAll() {
        val rows=store.taskRows()
        val groups=rows.groupBy{it.first.source?.key?:it.first.folders.joinToString("/")}
        for((identity,entries)in groups) {
            currentCoroutineContext().ensureActive()
            val tasks=entries.map{it.first}
            val directory=store.directory(tasks.first())
            val files=linkedMapOf<String,PublishedAudio>()
            val shared=SharedAudioStore(context)
            for((task,state)in entries)if(state=="DONE")store.existing(task.key)?.let{if(shared.valid(it))files[task.key]=it}
            val bytes=M3uPlaylist.render(directory,tasks,files).toByteArray(Charsets.UTF_8)
            publish("m3u:"+identity,directory,bytes)
        }
        repairLocations()
        store.clearSourceFailure(ExportSource(store.account(),"playlist_file","all"))
    }
    /** Durable moves also repair playlists belonging to earlier queues. Edited files stay untouched. */
    suspend fun repairLocations() {
        var failed=false
        for((marker,payload)in store.checkpoints("m3u-relocation:")) {
            val move=json.decodeFromString<PlaylistRelocation>(payload)
            var complete=true
            for((key,_)in store.checkpoints("m3u:")) {
                currentCoroutineContext().ensureActive()
                val previous=row(key)?:continue
                val bytes=read(previous)
                if(bytes==null) {
                    if(move.sources.isEmpty()||key.removePrefix("m3u:") in move.sources)complete=false
                    continue
                }
                val before=M3uPlaylist.reference(previous.directory,move.beforePath)
                val after=M3uPlaylist.reference(previous.directory,move.afterPath)
                val lines=bytes.toString(Charsets.UTF_8).lines().toMutableList()
                var changed=false
                for(index in lines.indices)if(lines[index]==before) {
                    lines[index]=after;changed=true
                    if(index>0&&lines[index-1].startsWith("#EXTINF:"))lines[index-1]=
                        "#EXTINF:"+String.format(Locale.ROOT,"%.3f",move.durationMs/1000.0)+","+lines[index-1].substringAfter(',')
                }
                if(changed)publish(key,previous.directory,lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            }
            if(complete)store.removeCheckpoint(marker)else failed=true
        }
        musicCheck(!failed,MusicError.PLAYLIST_CHANGED)
    }
    private suspend fun publish(key: String,directory: String,bytes: ByteArray) {
        musicCheck(bytes.size in 1..4*1024*1024,MusicError.TOO_LARGE)
        val digest=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val pendingKey="m3u-pending:"+key.removePrefix("m3u:")
        val previous=row(key)
        if(previous!=null&&previous.sha256==digest&&valid(previous))return
        val prepared=row(pendingKey)
        if(prepared!=null&&prepared.sha256==digest&&valid(prepared)) {
            store.checkpoint(key,json.encodeToString(prepared));store.checkpoint(pendingKey,"")
            if(previous!=null&&previous.uri!=prepared.uri&&valid(previous))resolver.delete(target(previous.uri),null,null)
            return
        }
        if(prepared!=null&&owned(prepared,true))resolver.delete(target(prepared.uri),null,null)
        val uri=resolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"_Порядок.m3u8")
            put(MediaStore.MediaColumns.MIME_TYPE,"audio/x-mpegurl")
            put(MediaStore.MediaColumns.RELATIVE_PATH,directory);put(MediaStore.MediaColumns.IS_PENDING,1)
        })?:throw MusicFailure(MusicError.STORAGE)
        var published=false
        try {
            val fd=resolver.openFileDescriptor(uri,"w")?:throw MusicFailure(MusicError.STORAGE)
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use{out->
                currentCoroutineContext().ensureActive();out.write(bytes);out.flush();out.fd.sync()
            }
            val next=Playlist(uri.toString(),directory,bytes.size,digest)
            store.checkpoint(pendingKey,json.encodeToString(next));currentCoroutineContext().ensureActive()
            musicCheck(resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1,MusicError.STORAGE)
            published=true;musicCheck(valid(next),MusicError.STORAGE)
            store.checkpoint(key,json.encodeToString(next));store.checkpoint(pendingKey,"")
            if(previous!=null&&previous.uri!=next.uri&&valid(previous))resolver.delete(target(previous.uri),null,null)
        }finally{if(!published)runCatching{resolver.delete(uri,null,null)}}
    }
    private fun hex(data: ByteArray)=data.joinToString(""){"%02x".format(it.toInt() and 255)}
}
