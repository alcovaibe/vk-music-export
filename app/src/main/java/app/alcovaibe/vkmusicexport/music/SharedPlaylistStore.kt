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
    private suspend fun valid(row: Playlist): Boolean {
        if(!owned(row,false))return false
        return try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0
            resolver.openInputStream(target(row.uri))?.use{input->
                val b=ByteArray(16384)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break
                    count+=n;if(count>row.bytes)return false;digest.update(b,0,n)}
            }?:return false
            count==row.bytes&&hex(digest.digest())==row.sha256
        }catch(e: kotlinx.coroutines.CancellationException){throw e}catch(_: Exception){false}
    }
    private fun row(key: String)=store.checkpoint(key)?.takeIf{it.isNotEmpty()}?.let{runCatching{json.decodeFromString<Playlist>(it)}.getOrNull()}
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
            val digest=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val key="m3u:"+identity;val pendingKey="m3u-pending:"+identity
            val previous=row(key)
            if(previous!=null&&previous.sha256==digest&&valid(previous))continue
            val prepared=row(pendingKey)
            if(prepared!=null&&prepared.sha256==digest&&valid(prepared)) {
                store.checkpoint(key,json.encodeToString(prepared));store.checkpoint(pendingKey,"")
                if(previous!=null&&previous.uri!=prepared.uri&&valid(previous))resolver.delete(target(previous.uri),null,null)
                continue
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
                store.checkpoint(pendingKey,json.encodeToString(next))
                currentCoroutineContext().ensureActive()
                musicCheck(resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1,MusicError.STORAGE)
                published=true
                musicCheck(valid(next),MusicError.STORAGE)
                store.checkpoint(key,json.encodeToString(next));store.checkpoint(pendingKey,"")
                if(previous!=null&&previous.uri!=next.uri&&valid(previous))resolver.delete(target(previous.uri),null,null)
            } finally {if(!published)runCatching{resolver.delete(uri,null,null)}}
        }
        store.clearSourceFailure(ExportSource(store.account(),"playlist_file","all"))
    }
    private fun hex(data: ByteArray)=data.joinToString(""){"%02x".format(it.toInt() and 255)}
}
