package app.alcovaibe.vkmusicexport

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val pendingImport = MutableStateFlow<ImportPlan?>(null)
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
    fun stageImport(uri: Uri) {
        if(!ready.value || busy.value || pendingImport.value!=null)return
        busy.value=true
        Diagnostics.event("chrome_import_started")
        viewModelScope.launch {
            try {
                val plan=withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(ChromeImport::read) ?: throw ImportFailure("OPEN_FAILED")
                }
                pendingImport.value=plan
                Diagnostics.event("chrome_import_preview",mapOf("entries" to plan.tracks,"playlists" to plan.playlists.size,"reason" to plan.reason))
            }catch(e: Exception) {
                val code=(e as? ImportFailure)?.code ?: "OPEN_FAILED"
                message.value=if(code=="FILE_TOO_LARGE")"Файл больше 10 МБ. Импорт не выполнен." else "Не удалось прочитать файл сборщика. Коллекция сохранена."
                Diagnostics.event("chrome_import_failed",mapOf("code" to code))
            }finally {busy.value=false}
        }
    }
    fun cancelImport() {Diagnostics.button("cancel_import");pendingImport.value=null}
    fun stageNativeSample(source: MusicSource) {
        if(!ready.value||busy.value||pendingImport.value!=null)return
        pendingImport.value=ImportPlan(source,emptyList(),"NATIVE_ACCESS_SAMPLE_FIRST_10",source.entries.size)
        Diagnostics.event("native_sample_preview",mapOf("entries" to source.entries.size,"complete" to false))
    }
    fun confirmImport() {
        val plan=pendingImport.value ?: return
        if(busy.value)return
        busy.value=true
        Diagnostics.button("confirm_import")
        viewModelScope.launch {
            try {
                val next=withContext(Dispatchers.Default){ChromeImport.merge(_snapshot.value,plan)}
                store.save(next);_snapshot.value=next;pendingImport.value=null
                message.value="Импортировано: ${plan.tracks} треков, ${plan.playlists.size} плейлистов в каталоге."
                Diagnostics.event(if(plan.reason=="NATIVE_ACCESS_SAMPLE_FIRST_10")"native_sample_saved" else "chrome_import_saved",mapOf("entries" to plan.tracks,"catalog" to plan.playlists.size))
            }catch (_: Exception) {
                message.value="Не удалось сохранить импорт. Прежняя коллекция сохранена."
                Diagnostics.event("chrome_import_save_failed")
            }finally {busy.value=false}
        }
    }
    fun clearCollection() {
        if (!ready.value || busy.value || pendingImport.value!=null) return
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
