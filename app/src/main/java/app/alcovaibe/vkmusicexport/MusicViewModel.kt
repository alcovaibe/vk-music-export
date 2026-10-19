package app.alcovaibe.vkmusicexport

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Collection ownership stays native. Chrome auth state is deliberately unknown to this app. */
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _snapshot = MutableStateFlow(CollectionSnapshot())
    val snapshot = _snapshot.asStateFlow()
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    init {
        viewModelScope.launch {
            try {
                _snapshot.value = store.read()
                Diagnostics.event("collection_loaded", mapOf("sources" to _snapshot.value.sources.size,"entries" to _snapshot.value.entriesCount))
            } catch (_: Exception) {
                message.value = "Не удалось прочитать сохранённую коллекцию. Файл оставлен на устройстве."
                Diagnostics.event("collection_read_failed")
            } finally {
                ready.value = true
                Diagnostics.event("state_changed",mapOf("state" to "ready","value" to true))
            }
        }
    }
    fun clearCollection() {
        if (!ready.value || busy.value) return
        Diagnostics.action("clearCollection")
        busy.value=true
        viewModelScope.launch {
            try {
                val empty=CollectionSnapshot();store.save(empty);_snapshot.value=empty
                message.value="Коллекция очищена"
                Diagnostics.event("collection_cleared")
            } catch (_: Exception) {
                message.value="Не удалось очистить коллекцию"
                Diagnostics.event("collection_clear_failed")
            } finally { busy.value=false }
        }
    }
}
