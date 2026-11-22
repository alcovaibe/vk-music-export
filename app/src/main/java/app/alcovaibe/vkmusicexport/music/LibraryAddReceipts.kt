package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pending record means the request may already have reached VK. */
internal class LibraryAddReceipts(private val context: Context) {
    private fun prefix(account: Long) = "library-add:$account:"
    private fun key(account: Long, sourceId: String) = prefix(account) + sourceId

    suspend fun read(account: Long, sourceId: String) = withContext(Dispatchers.IO) {
        AudioExportStore(context).use { it.checkpoint(key(account, sourceId)) }
    }

    suspend fun save(account: Long, sourceId: String, libraryId: String) = withContext(Dispatchers.IO) {
        AudioExportStore(context).use { it.checkpoint(key(account, sourceId), libraryId) }
    }

    suspend fun clear(account: Long, sourceId: String) = withContext(Dispatchers.IO) {
        AudioExportStore(context).use { it.removeCheckpoint(key(account, sourceId)) }
    }

    suspend fun confirmed(account: Long) = withContext(Dispatchers.IO) {
        val prefix = prefix(account)
        AudioExportStore(context).use { store ->
            store.checkpoints(prefix).filter { (_, id) -> id != PENDING }
                .associate { (name, id) -> name.removePrefix(prefix) to id }
        }
    }

    suspend fun removed(account: Long, libraryId: String) = withContext(Dispatchers.IO) {
        AudioExportStore(context).use { store ->
            store.checkpoints(prefix(account)).filter { (_, id) -> id == libraryId }
                .forEach { (name, _) -> store.removeCheckpoint(name) }
        }
    }

    suspend fun <T> serialized(write: suspend () -> T): T = writes.withLock { write() }

    companion object {
        const val PENDING = "pending"
        private val writes = Mutex()
    }
}

internal data class LibraryAddition(val track: NativeTrack, val account: Long, val session: String)
