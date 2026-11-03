package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal data class FriendFolderMove(val before: String,val after: String)
internal data class FriendFolderMigrationResult(val moved: Int,val skipped: Int,val failures: Int,val playlists: Int)

/**
 * Idempotent migration of owned VK friend exports. VK IDs remain in ExportSource
 * and the SQLite directory identity; only user-visible MediaStore paths change.
 * Durable before/after checkpoints survive process death between a physical move
 * and SQLite/M3U8 repair. Never uses filesystem renames or broad storage access.
 */
internal class FriendFolderMigration(private val context: Context,private val store: AudioExportStore,
    private val shared: SharedAudioStore=SharedAudioStore(context)) {
    private val json=Json{encodeDefaults=true}
    private val markerPrefix="friend-folder-migration:"

    private fun legacyFolders(path: String,source: ExportSource?): List<String>? {
        val friendId=ExportLayout.friendId(source)?:return null
        if(!path.startsWith(ExportLayout.ROOT)||!path.endsWith("/"))return null
        val parts=path.removePrefix(ExportLayout.ROOT).dropLast(1).split('/')
        if(parts.size !in 2..3||parts.first()!="Друзья"||
            !parts[1].endsWith(" [$friendId]")||
            parts.any{it!=ExportLayout.component(it,180)})return null
        return parts
    }
    private fun marker(key: String)=markerPrefix+key
    private fun planned(task: AudioExportTask,path: String): FriendFolderMove? {
        if(legacyFolders(path,task.source)==null)return null
        val after=store.directory(task)
        return if(after==path)null else FriendFolderMove(path,after)
    }
    private fun record(marker: String,move: FriendFolderMove) {
        val existing=store.checkpoint(marker)
        if(existing==null)store.checkpoint(marker,json.encodeToString(move))
        else musicCheck(json.decodeFromString<FriendFolderMove>(existing)==move,MusicError.STORAGE)
    }
    suspend fun run(): FriendFolderMigrationResult {
        var moved=0;var skipped=0;var failed=0
        // Existing task metadata covers even empty playlists (no saved audio files).
        for((task,_) in store.taskRows()) {
            currentCoroutineContext().ensureActive()
            val folder=ExportLayout.ROOT+task.folders.joinToString("/")+"/"
            val next=planned(task,folder)?:continue
            record(marker(task.key),next)
        }
        val groups=store.savedFiles().groupBy{it.uri}
        for((_,aliases) in groups) {
            currentCoroutineContext().ensureActive()
            val row=aliases.first()
            val source=row.source
            val friend=ExportLayout.friendId(source)
            val parts=legacyFolders(row.relativePath,source)
            if(parts==null)continue
            // A physical file reused by an unrelated source must not silently move.
            if(aliases.any{ExportLayout.friendId(it.source)!=friend||
                    it.source?.account!=source?.account||it.relativePath!=row.relativePath}) {
                skipped++;continue
            }
            try {
                val task=AudioExportTask(row.taskKey,row.track,parts,1,source,row.occurrence)
                val target=planned(task,row.relativePath)?:continue
                record(marker(row.taskKey),target)
                val actual=shared.moveVerified(row,target.after)
                if(actual!=row)store.relinked(row,actual)
                moved++
            }catch(e: CancellationException){throw e}
             catch(_: Exception){failed++}
        }
        val checkpoints=store.checkpoints(markerPrefix)
        val mappings=linkedMapOf<String,String>()
        for((_,payload) in checkpoints) {
            val entry=runCatching{json.decodeFromString<FriendFolderMove>(payload)}.getOrNull()
            if(entry==null){failed++;continue}
            if(mappings.putIfAbsent(entry.before,entry.after)?.let{it!=entry.after}==true)failed++
        }
        var playlists=0
        if(failed==0&&mappings.isNotEmpty()) {
            try {
                playlists=SharedPlaylistStore(context,store).migrateFriendDirectories(mappings)
                checkpoints.forEach{store.removeCheckpoint(it.first)}
            }catch(e: CancellationException){throw e}
             catch(_: Exception){failed++} // Durable checkpoints remain for the next launch.
        }
        return FriendFolderMigrationResult(moved,skipped,failed,playlists)
    }
}
