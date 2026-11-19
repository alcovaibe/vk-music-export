package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Retains confirmed card names and IDs for the same VK login. Photos refresh with the network list. */
internal class ArtistSubscriptionsStore(private val context: Context) {
    @Serializable private data class Card(val id: String, val name: String)
    @Serializable private data class Snapshot(val session: String, val cards: List<Card>)

    suspend fun read(owner: Long, session: String): List<MusicArtist>? = withContext(Dispatchers.IO) {
        AudioExportStore(context).use { store ->
            store.checkpoint("artist-subscriptions:$owner")
                ?.let { Json.decodeFromString<Snapshot>(it) }
                ?.takeIf { it.session == session }
                ?.cards?.map { MusicArtist(it.id, it.name) }
        }
    }

    suspend fun write(owner: Long, session: String, artists: List<MusicArtist>) = withContext(Dispatchers.IO) {
        val snapshot = Snapshot(session, artists.map { Card(it.id, it.name) })
        val payload = Json.encodeToString(snapshot)
        if (payload.toByteArray().size <= 512 * 1024) {
            AudioExportStore(context).use { it.checkpoint("artist-subscriptions:$owner", payload) }
        }
    }
}
