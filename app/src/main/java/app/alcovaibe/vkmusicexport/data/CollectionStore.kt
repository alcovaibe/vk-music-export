package app.alcovaibe.vkmusicexport.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class CollectionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "collection.json"))
    private val lock = Mutex()
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun read(): CollectionSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.baseFile.exists()) return@withLock CollectionSnapshot()
            json.decodeFromString<CollectionSnapshot>(file.openRead().bufferedReader().use { it.readText() })
        }
    }

    suspend fun save(snapshot: CollectionSnapshot) = withContext(Dispatchers.IO) {
        lock.withLock {
            val output = file.startWrite()
            try {
                output.write(json.encodeToString(CollectionSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
