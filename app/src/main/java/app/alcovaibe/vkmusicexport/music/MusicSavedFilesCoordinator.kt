package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Recovery/forget actions share the retained file state and the sole file writer. */
internal class MusicSavedFilesCoordinator(
    private val application: Application,
    private val _state: MutableStateFlow<NativeMusicState>,
    private val operation: (String,suspend(Int)->Unit)->Unit,
    private val active: suspend(Int)->Unit,
    private val maintenanceBeforeItem: suspend(String,Int)->Unit
) {
    private val _savedFiles=MaintenanceRuntime.files
    fun showSavedFiles(){if(_state.value.initialized&&!_state.value.busy)_savedFiles.update{it.copy(visible=true)}}
    fun dismissSavedFiles(){_savedFiles.update{it.copy(visible=false)}}
    fun selectSavedFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(selecting=row)}}
    fun cancelSavedFileSelection(){_savedFiles.update{it.copy(selecting=null)}}
    fun backSavedFiles() {
        if(_state.value.busy)return
        _savedFiles.update{it.copy(preview=null,forget=null,selecting=null)}
    }
    fun requestForgetFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(forget=row)}}
    private fun savedFileWork(background: Boolean=false,block: suspend(Int,AudioExportStore,SharedAudioStore,SavedFileRecovery)->Unit) {
        operation(if(background)"saved_files_background" else "saved_files"){g->
            _savedFiles.update{it.copy(running=true,message=null)}
            try {
                withContext(Dispatchers.IO){AudioExportRuntime.editSaved(application,maintenance=background){
                    AudioExportStore(application).use{store->
                        val shared=SharedAudioStore(application);val recovery=SavedFileRecovery(application,store,shared)
                        ArtworkRepair(application,store,shared).recover();recovery.recover()
                        block(g,store,shared,recovery)
                        SharedPlaylistStore(application,store).repairLocations();AudioExportRuntime.refresh()
                    }
                }}
            }catch(e: CancellationException){_savedFiles.update{it.copy(message="Проверка прервана. Восстановленные связи сохранены.")};throw e}
             catch(e: Exception){
                if(background)MaintenanceRuntime.markPaused()
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                _savedFiles.update{it.copy(message=(e as? SavedFileFailure)?.reason?.label?:code.label)}
                Diagnostics.event("audio_file_recovery",mapOf("outcome" to "FAILED","code" to code.name,"file_reason" to (e as? SavedFileFailure)?.reason?.name))
            }finally{_savedFiles.update{it.copy(running=false,resumable=MaintenanceRuntime.isResuming("saved_files_background"))}}
        }
    }
    fun checkSavedFiles(search: Boolean=MaintenanceRuntime.savedSearch()) {
        savedFileWork(background=true){g,store,shared,recovery->
            val batch=MaintenanceRuntime.batch("saved_files_background",0,store.savedFiles().distinctBy{it.uri},search)
            var restored=if(batch.resumed)_savedFiles.value.restored else 0
            if(!batch.resumed)_savedFiles.update{it.copy(processed=0,total=batch.rows.size,problems=emptyList(),preview=null,forget=null,restored=0)}
            batch.rows.forEachIndexed{index,original->
                if(index<batch.next)return@forEachIndexed
                val row=store.existing(original.taskKey)?:original
                active(g);maintenanceBeforeItem("saved_files_background",index)
                var reason=shared.inspect(row).reason
                var searchReason: SavedFileReason?=null
                if(reason!=SavedFileReason.OK&&search) {
                    val result=recovery.search(row)
                    if(result==SavedFileReason.OK){restored++;reason=SavedFileReason.OK}else searchReason=result
                }
                Diagnostics.event("audio_file_check",mapOf("item" to index+1,"reason" to reason.name,"search_reason" to searchReason?.name,"search" to search))
                _savedFiles.update{it.copy(processed=index+1,restored=restored,
                    problems=if(reason==SavedFileReason.OK)it.problems else it.problems+SavedFileProblem(row,reason,searchReason))}
                MaintenanceRuntime.advance(index+1)
            }
            _savedFiles.update{it.copy(message="Проверка завершена. Файлы не изменялись.")}
        }
    }
    fun previewSavedFile(uri: android.net.Uri) {
        val before=_savedFiles.value.selecting?:return
        _savedFiles.update{it.copy(selecting=null)}
        savedFileWork{g,_,_,recovery->
            val preview=recovery.preview(before,uri);active(g)
            _savedFiles.update{it.copy(preview=preview,forget=null)}
        }
    }
    fun confirmSavedFile() {
        val preview=_savedFiles.value.preview?:return
        savedFileWork{g,_,_,recovery->
            active(g);recovery.confirm(preview)
            _savedFiles.update{it.copy(preview=null,restored=it.restored+1,problems=it.problems.filterNot{p->p.row.taskKey==preview.before.taskKey},
                message="Копия восстановлена в Music/VK Music Export/Восстановленные. Исходный файл сохранён.")}
            Diagnostics.event("audio_file_recovery",mapOf("outcome" to "IMPORTED","exact" to preview.exact))
        }
    }
    fun confirmForgetFile() {
        val row=_savedFiles.value.forget?:return
        savedFileWork{g,store,_,_->
            active(g);store.forgetFile(row)
            _savedFiles.update{it.copy(forget=null,problems=it.problems.filterNot{p->p.row.taskKey==row.taskKey},message="Запись убрана. Файлы на телефоне и музыка ВК сохранены.")}
            Diagnostics.event("audio_file_recovery",mapOf("outcome" to "FORGOTTEN"))
        }
    }
}
