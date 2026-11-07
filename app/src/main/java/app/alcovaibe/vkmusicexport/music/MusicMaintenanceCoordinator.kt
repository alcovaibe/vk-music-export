package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Retained batch use cases. All writes still enter AudioExportRuntime.editSaved. */
internal class MusicMaintenanceCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<NativeMusicState>,
    private val api: MusicApi,
    private val operation: (String,suspend(Int)->Unit)->Unit,
    private val active: suspend(Int)->Unit,
    private val savedAccount: suspend()->Long?,
    private val currentAccess: suspend()->MusicAccess,
    private val continueRead: suspend(MusicAccess)->MusicAccess,
    private val maintenanceBeforeItem: suspend(String,Int)->Unit
) {
    private val _artwork=MaintenanceRuntime.artwork
    private val _trackMetadata=MaintenanceRuntime.metadata
    fun showTrackMetadata() {
        if(!_state.value.initialized||_state.value.busy)return
        _trackMetadata.update{it.copy(visible=true)}
        scope.launch(Dispatchers.IO) {
            try {
                val owner=savedAccount()?:return@launch
                val saved=AudioExportStore(application).use{it.metadataRunState(owner)}?:return@launch
                MaintenanceRuntime.updateMetadata(owner){if(it.visible&&!it.running&&!_state.value.busy)saved.copy(visible=true)else it}
            }catch(_: Exception){_trackMetadata.update{it.copy(message="Не удалось прочитать сохранённую проверку.")}}
        }
    }
    fun dismissTrackMetadata(){_trackMetadata.update{it.copy(visible=false)}}
    fun updateSavedTrackMetadata() {
        if(!_trackMetadata.value.visible||_trackMetadata.value.running)return
        operation("track_metadata"){g->
            _trackMetadata.update{it.copy(running=true,message=null)}
            try {
                withContext(Dispatchers.IO) {
                    AudioExportRuntime.editSaved(application,maintenance=true) {
                        AudioExportStore(application).use{store->
                            val shared=SharedAudioStore(application)
                            savedAccount()?.let{owner->store.metadataRun(owner)}?.let{saved->
                                MaintenanceRuntime.updateMetadata(saved.owner){saved.state(it.visible,running=true).copy(message=null)}
                            }
                            ArtworkRepair(application,store,shared).recover()
                            val initial=currentAccess()
                            val owner=api.library(initial).owner
                            suspend fun checkedAccess(): MusicAccess {
                                active(g)
                                val current=continueRead(initial)
                                active(g);musicCheck(current.accountId==null||current.accountId==owner,MusicError.ACCOUNT_CHANGED)
                                return current
                            }
                            suspend fun guard(){checkedAccess();Unit}
                            guard()
                            val repair=TrackMetadataRepair(application,store,shared,beforeWrite=::guard)
                            val files=store.savedFiles()
                            val pending=store.metadataRun(owner)?.takeIf{it.unfinished}?:TrackMetadataRun.start(owner,files)
                            val batch=TrackMetadataBatch(api,::checkedAccess,find=store::metadataTarget,
                                verify=shared::requireValid,update=repair::update,recover=repair::recover,save={store.metadataRun(it)},checkpoint=store::metadataProgress,
                                metadataSource={store.metadataReplacement(owner,it)?.referenceId},beforeItem={maintenanceBeforeItem("track_metadata",it)},
                                progress={snapshot,name->
                                    MaintenanceRuntime.updateMetadata(owner){snapshot.state(it.visible,running=true).copy(name=name,message=null)}
                                    MaintenanceRuntime.checkpoint();AudioExportRuntime.refresh()
                                })
                            try {
                                val result=batch.run(pending)
                                MaintenanceRuntime.updateMetadata(owner){result.state(it.visible,running=true)}
                            }finally {
                                withContext(NonCancellable+Dispatchers.IO) {
                                    SharedPlaylistStore(application,store).repairLocations()
                                    AudioExportRuntime.refresh()
                                }
                            }
                        }
                    }
                }
            }catch(e: CancellationException){_trackMetadata.update{it.copy(message="Проверка приостановлена. Нажми «Продолжить», чтобы вернуться к необработанной записи.")};throw e}
             catch(e: Exception) {
                MaintenanceRuntime.markPaused()
                val code=(e as? MusicFailure)?.code
                _trackMetadata.update{it.copy(message=when {
                    e is MusicReadChanged->"Доступ ВК изменился. Сохранённую проверку можно продолжить после входа в тот же аккаунт."
                    code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS)->"ВК требует повторный вход. Подключись заново и нажми «Продолжить». Готовые позиции сохранены."
                    else->(code?.label?:"Обработка остановлена.")+" Прогресс сохранён; проверку можно продолжить."
                })}
            }finally {
                _trackMetadata.update{it.copy(running=false,name=null)}
                Diagnostics.event("audio_track_metadata",mapOf("updated" to _trackMetadata.value.updated,"unavailable" to _trackMetadata.value.unavailable,
                    "skipped" to _trackMetadata.value.skipped,"failed" to _trackMetadata.value.failed))
            }
        }
    }
    fun showArtwork(){if(_state.value.initialized&&!_state.value.busy)_artwork.update{it.copy(visible=true)}}
    fun dismissArtwork(){_artwork.update{it.copy(visible=false)}}
    fun addSavedArtwork() {
        if(!_artwork.value.visible||_artwork.value.running)return
        operation("artwork"){g->
            val diagnostic=ArtworkDiagnostics{type,fields->Diagnostics.event(type,fields)}
            var outcome="STOPPED"
            if(MaintenanceRuntime.isResuming("artwork"))_artwork.update{it.copy(visible=true,running=true,message=null,current=null)}
            else _artwork.value=ArtworkState(visible=true,running=true)
            try {
                withContext(Dispatchers.IO) {
                    AudioExportRuntime.editSaved(application,maintenance=true) {
                        AudioExportStore(application).use{store->
                            val shared=SharedAudioStore(application)
                            var lease: MusicAccess?=null
                            suspend fun checkedAccess(): MusicAccess {
                                active(g)
                                val first=lease?:throw MusicFailure(MusicError.ACCESS)
                                return continueRead(first).also{active(g)}
                            }
                            val repair=ArtworkRepair(application,store,shared,onStep={diagnostic.step=it},beforeWrite={checkedAccess();Unit})
                            diagnostic.step=ArtworkStep.RECOVERY;repair.recover()
                            val saved=store.savedFiles()
                            val ready=saved.filter{it.coverEmbedded}.map{it.uri}.toSet()
                            val files=saved.filter{it.uri !in ready}.distinctBy{it.uri}
                            if(files.isNotEmpty())lease=currentAccess()
                            val batch=MaintenanceRuntime.batch("artwork",lease?.accountId?:0,files)
                            if(batch.resumed)_artwork.update{it.copy(total=batch.rows.size)}
                            else _artwork.value=ArtworkState(visible=_artwork.value.visible,running=true,total=batch.rows.size,readySkipped=ready.size)
                            MaintenanceRuntime.checkpoint()
                            val source=ArtworkLoader(api,onAttempt=diagnostic::candidate,onStep={diagnostic.step=it})
                            for((index,original) in batch.rows.withIndex()) {
                                if(index<batch.next)continue
                                val row=store.existing(original.taskKey)?:original
                                active(g);maintenanceBeforeItem("artwork",index);diagnostic.item=index+1;diagnostic.step=ArtworkStep.VERIFY_FILE
                                _artwork.update{it.copy(current="${row.track.artist} — ${row.track.displayTitle}")}
                                _artwork.update{it.copy(issues=it.issues.filterNot{issue->issue.item==index+1})}
                                try {
                                    musicCheck(row.uri==original.uri&&row.track.id==original.track.id,MusicError.INCOMPLETE)
                                    val changed=repair.add(row,source,::checkedAccess)
                                    val marked=store.existing(row.taskKey)?.coverEmbedded==true
                                    _artwork.update{when{changed->it.copy(added=it.added+1);marked->it.copy(existing=it.existing+1);else->it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.displayTitle,ArtworkSkipReason.NO_COVER))}}
                                    diagnostic.result(if(changed)"ADDED" else if(marked)"EXISTING" else "NO_COVER")
                                }catch(e: CancellationException){throw e}
                                 catch(e: Exception) {
                                    val unavailable=(e as? MusicFailure)?.code==MusicError.UNAVAILABLE&&diagnostic.step==ArtworkStep.RESOLVE_COVER
                                    if(unavailable) {
                                        _artwork.update{it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.displayTitle,ArtworkSkipReason.TRACK_UNAVAILABLE,row.taskKey))};diagnostic.result("UNAVAILABLE",e)
                                    }else {
                                        val issue=artworkIssue(index+1,row.displayName,diagnostic.step,e,row.track.artist,row.track.displayTitle)
                                        _artwork.update{it.copy(issues=it.issues+issue)}
                                        diagnostic.result("FAILED",e)
                                        try{repair.recover()}catch(recovery: Exception){diagnostic.step=ArtworkStep.RECOVERY;throw recovery}
                                        if((e as? MusicFailure)?.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.DENIED,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.RATE_LIMIT,MusicError.STORAGE,MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT)||e is java.io.IOException)throw e
                                        _artwork.update{it.copy(failed=it.failed+1)}
                                    }
                                }
                                _artwork.update{it.copy(processed=index+1)}
                                MaintenanceRuntime.advance(index+1);AudioExportRuntime.refresh()
                            }
                        }
                    }
                }
                active(g);outcome="COMPLETED"
                _artwork.update{it.copy(message="Обработка завершена. Музыка повторно не скачивалась.")}
            }catch(e: CancellationException){outcome="INTERRUPTED";_artwork.update{it.copy(message="Обработка прервана. Продолжи обработку из приложения. Готовые обложки и прогресс сохранены.")};throw e}
             catch(e: Exception){MaintenanceRuntime.markPaused();diagnostic.result("STOPPED",e);_artwork.update{it.copy(message="Обработка остановлена (${(e as? MusicFailure)?.code?.name?:"STORAGE"}). Уже готовые файлы сохранены; при незавершённой записи резервная копия остаётся до восстановления.")}}
            finally{_artwork.update{it.copy(running=false,current=null,resumable=MaintenanceRuntime.isResuming("artwork"))};diagnostic.summary(outcome,_artwork.value)}
        }
    }
}
