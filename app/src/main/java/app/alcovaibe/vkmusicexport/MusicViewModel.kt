package app.alcovaibe.vkmusicexport

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Saved metadata is independent of authorization, the audio queue and diagnostic JSON. */
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _snapshot = MutableStateFlow(CollectionSnapshot())
    val snapshot = _snapshot.asStateFlow()
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val pendingCollection = MutableStateFlow<MusicSource?>(null)
    init {
        viewModelScope.launch {
            try {
                val loaded=store.read()
                // Preserve the file; an old interrupted scan must not appear to still be running.
                _snapshot.value = loaded.copy(sources=loaded.sources.map {
                    if(it.status==ScanStatus.SCANNING) it.copy(status=ScanStatus.INTERRUPTED,reason="PROCESS_STOPPED") else it
                })
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
    fun cancelCollectionChange() {Diagnostics.button("cancel_collection_change");pendingCollection.value=null}
    fun stageNativePage(source: MusicSource) {
        if(!ready.value||busy.value||pendingCollection.value!=null)return
        pendingCollection.value=source
        Diagnostics.event("native_page_preview",mapOf("entries" to source.entries.size,"count_matched" to (source.status==ScanStatus.COUNT_MATCHED)))
    }
    fun confirmCollectionChange() {
        val source=pendingCollection.value?:return
        if(busy.value)return
        busy.value=true
        Diagnostics.button("confirm_collection_change")
        viewModelScope.launch {
            try {
                val next=withContext(Dispatchers.Default){CollectionMerge.replaceSource(_snapshot.value,source)}
                store.save(next);_snapshot.value=next;pendingCollection.value=null
                message.value="В коллекции сохранено позиций: ${source.entries.size}."
                Diagnostics.event("native_page_saved",mapOf("entries" to source.entries.size))
            }catch(_: Exception) {
                message.value="Не удалось сохранить список. Прежняя коллекция сохранена."
                Diagnostics.event("native_page_save_failed")
            }finally{busy.value=false}
        }
    }
    fun clearCollection() {
        if (!ready.value || busy.value || pendingCollection.value!=null) return
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
