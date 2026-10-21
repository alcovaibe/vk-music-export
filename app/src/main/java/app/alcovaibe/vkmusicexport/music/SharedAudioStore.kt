package app.alcovaibe.vkmusicexport.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Own shared audio only. Pending rows are hidden until bytes, digest and index checkpoint exist. */
internal class SharedAudioStore(private val context: Context) {
    private val resolver=context.contentResolver
    private val collection=MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private fun uri(raw: String): Uri {
        val u=Uri.parse(raw)
        musicCheck(u.scheme=="content"&&u.authority=="media"&&u.query==null&&u.fragment==null&&
            u.pathSegments.size==4&&u.pathSegments.take(3)==listOf("external_primary","audio","media")&&
            (u.lastPathSegment?.toLongOrNull()?:0)>0,MusicError.STORAGE)
        return u
    }
    private fun own(raw: String,pending: Boolean): Boolean = runCatching {
        resolver.query(uri(raw),arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use{c->
            c.moveToFirst()&&c.getString(0)==context.packageName&&c.getString(1).startsWith(ExportLayout.ROOT)&&
                c.getInt(2)==(if(pending)1 else 0)
        }?:false
    }.getOrDefault(false)
    fun open(record: PublishedAudio): InputStream {
        musicCheck(own(record.uri,false),MusicError.STORAGE)
        return resolver.openInputStream(uri(record.uri))?:throw MusicFailure(MusicError.STORAGE)
    }
    suspend fun valid(record: PublishedAudio): Boolean {
        if(!own(record.uri,false))return false
        return try {
            val c=currentCoroutineContext();val digest=MessageDigest.getInstance("SHA-256");var count=0L
            open(record).use{input->val buffer=ByteArray(65536);while(true){c.ensureActive();val n=input.read(buffer);if(n<0)break;count+=n
                if(count>record.bytes)return false;digest.update(buffer,0,n)}}
            count==record.bytes&&hex(digest.digest())==record.sha256
        }catch(e: CancellationException){throw e}catch(_: Exception){false}
    }
    fun removePending(raw: String){if(own(raw,true))resolver.delete(uri(raw),null,null)}
    fun removeAbandonedPending() {
        val rows=resolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(context.packageName,ExportLayout.ROOT+"%"),null)?.use{c->buildList{while(c.moveToNext())add(ContentUris.withAppendedId(collection,c.getLong(0)).toString())}}?:emptyList()
        rows.forEach(::removePending)
    }
    suspend fun publish(task: AudioExportTask,result: TransferResult,relative: String=ExportLayout.relative(task),prepared:(PublishedAudio)->Unit): PublishedAudio =
        publish(task,result.media,result.file.length(),null,relative,{FileInputStream(result.file)},prepared)
    suspend fun copy(task: AudioExportTask,record: PublishedAudio,relative: String=ExportLayout.relative(task),prepared:(PublishedAudio)->Unit): PublishedAudio =
        publish(task,ProcessedAudio(record.extension,record.mime,record.durationMs),record.bytes,record.sha256,relative,{open(record)},prepared)
    private suspend fun availableName(task: AudioExportTask,extension: String,relative: String): String {
        var duplicate=1
        while(true) {
            currentCoroutineContext().ensureActive()
            val name=ExportLayout.name(task,extension,duplicate)
            val exists=resolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf(relative,name,context.packageName),null)?.use{it.moveToFirst()}?:throw MusicFailure(MusicError.STORAGE)
            if(!exists)return name
            duplicate++;musicCheck(duplicate<=1_000_000,MusicError.STORAGE)
        }
    }
    private suspend fun publish(task: AudioExportTask,media: ProcessedAudio,size: Long,expectedHash: String?,relative: String,input:()->InputStream,
        prepared:(PublishedAudio)->Unit): PublishedAudio {
        val c=currentCoroutineContext();c.ensureActive()
        musicCheck(size in 1..AudioTransfer.MAX_BYTES&&media.durationMs>0,MusicError.INCOMPLETE)
        musicCheck(relative.startsWith(ExportLayout.ROOT)&&relative.endsWith("/")&&
            relative.removePrefix(ExportLayout.ROOT).dropLast(1).split('/').let{parts->
                parts.size in 1..3&&parts.all{it==ExportLayout.component(it,180)}
            },MusicError.STORAGE)
        val name=availableName(task,media.extension,relative)
        val target=resolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,media.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,relative);put(MediaStore.MediaColumns.IS_PENDING,1)
            put(MediaStore.Audio.Media.TITLE,task.track.title);put(MediaStore.Audio.Media.ARTIST,task.track.artist)
            task.track.album?.let{put(MediaStore.Audio.Media.ALBUM,it.title)}
        })?:throw MusicFailure(MusicError.STORAGE)
        var published=false
        try {
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            val fd=resolver.openFileDescriptor(target,"w")?:throw MusicFailure(MusicError.STORAGE)
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use{out->input().use{source->
                val buffer=ByteArray(65536)
                while(true){c.ensureActive();val n=source.read(buffer);if(n<0)break;bytes+=n
                    musicCheck(bytes<=size,MusicError.INCOMPLETE);out.write(buffer,0,n);digest.update(buffer,0,n)}
                out.flush();out.fd.sync()
            }}
            val hash=hex(digest.digest());musicCheck(bytes==size&&(expectedHash==null||expectedHash==hash),MusicError.INCOMPLETE)
            c.ensureActive()
            val actual=resolver.query(target,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)?.use{
                if(it.moveToFirst())it.getString(0) to it.getString(1)else null
            }?:throw MusicFailure(MusicError.STORAGE)
            val row=PublishedAudio(task.key,task.track,target.toString(),actual.first,actual.second,media.extension,media.mime,media.durationMs,bytes,hash)
            // Checkpoint before publishing: recovery can adopt a finished file after a process kill.
            prepared(row);c.ensureActive()
            musicCheck(resolver.update(target,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1,MusicError.STORAGE)
            published=true
            return row
        }finally{if(!published)runCatching{resolver.delete(target,null,null)}}
    }
    private fun hex(bytes: ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
}
