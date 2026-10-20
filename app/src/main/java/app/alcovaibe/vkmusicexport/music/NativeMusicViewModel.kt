package app.alcovaibe.vkmusicexport.music

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.data.MusicSource
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

private object NativeMusicGate {val mutex=Mutex()}

internal data class NativeMusicState(val initialized: Boolean=false,val configured: Boolean=false,val busy: Boolean=false,
    val stage: String="",val error: MusicError?=null,val message: String?=null,val probe: NativeProbe?=null,
    val catalog: NativeCatalog?=null,val catalogError: MusicError?=null,val page: NativePage?=null,val selected: NativePlaylist?=null,
    val progress: AudioProgress?=null,val saved: List<SavedAudio> = emptyList())

internal class NativeMusicViewModel(application: Application): AndroidViewModel(application) {
    private val vault=MusicAccessStore(application)
    private val files=NativeFiles(application)
    private val api: MusicApi=VkAudioClient()
    private val transfer=AudioTransfer()
    private val _state=MutableStateFlow(NativeMusicState());val state=_state.asStateFlow()
    private var work: Job?=null
    @Volatile private var generation=0
    init {
        viewModelScope.launch {
            try {
                val loaded=withContext(Dispatchers.IO){
                    val interrupted=if(NativeMusicGate.mutex.tryLock())try{files.recover()}finally{NativeMusicGate.mutex.unlock()}else false
                    Triple(vault.read()!=null,files.read(),interrupted)
                }
                _state.value=NativeMusicState(initialized=true,configured=loaded.first,saved=loaded.second,
                    message=if(loaded.third)"Предыдущая операция прервана. Готовые файлы сохранены; проверку можно повторить." else null)
            }catch(_: Exception){_state.value=NativeMusicState(initialized=true,error=MusicError.STORAGE)}
        }
    }
    private fun run(stage: String,block: suspend (Int)->Unit) {
        if(!_state.value.initialized||_state.value.busy)return
        if(!NativeMusicGate.mutex.tryLock()){_state.update{it.copy(error=MusicError.BUSY)};return}
        val g=++generation;val id=UUID.randomUUID().toString()
        _state.update{it.copy(busy=true,stage=stage,error=null,message=null,progress=null)}
        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "start"))
        work=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var outcome="failed"
            try {
                withContext(Dispatchers.IO){files.begin(id)}
                block(g);active(g);outcome="completed"
                Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "completed"))
            }catch(e: CancellationException){outcome="interrupted";throw e}
             catch(e: Exception){
                if(g==generation) {
                    val error=(e as? MusicFailure)?.code?:MusicError.STORAGE
                    _state.update{it.copy(error=error)}
                    Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "failed","code" to error.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
                }
            }finally {
                withContext(NonCancellable+Dispatchers.IO){runCatching{files.finish(id,outcome)}}
                NativeMusicGate.mutex.unlock()
                if(g==generation)_state.update{it.copy(busy=false,stage="",progress=null)}
            }
        }
    }
    private suspend fun active(g: Int){currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()}
    private suspend fun access(): MusicAccess=withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}
    fun configure(token: String,agent: String) {
        Diagnostics.button("native_music_save_access")
        if(!NativeProtocol.validAccess(token,agent)){_state.update{it.copy(error=MusicError.INVALID_ACCESS)};return}
        run("configure") {g->
            withContext(Dispatchers.IO){vault.save(MusicAccess(token,agent))};active(g)
            _state.value=NativeMusicState(initialized=true,configured=true,busy=true,stage="configure",saved=_state.value.saved)
            probe(g)
        }
    }
    fun checkAccess(){Diagnostics.button("native_music_probe");run("probe"){probe(it)}}
    private suspend fun probe(g: Int) {
        _state.update{it.copy(probe=null,page=null,selected=null,catalog=null,catalogError=null)}
        val access=access();val probe=api.library(access);active(g)
        _state.update{it.copy(probe=probe,page=probe.library,selected=null,catalog=null,catalogError=null)}
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to probe.library.tracks.size,"expected_count" to probe.library.count,"sample_limit" to 10,"complete" to false))
        try {
            val catalog=api.catalog(access,probe.owner);active(g)
            _state.update{it.copy(catalog=catalog,message="Музыкальный доступ ответил. Показаны первые 10 позиций и первые 10 подборок; это проверочная выборка.")}
            Diagnostics.event("native_music_sample",mapOf("stage" to "catalog","entries" to catalog.lists.size,"expected_count" to catalog.count,"complete" to false))
        }catch(e: CancellationException){throw e}
         catch(e: Exception){active(g);val error=(e as? MusicFailure)?.code?:MusicError.RESPONSE;_state.update{it.copy(catalogError=error)}}
    }
    fun select(list: NativePlaylist?) {
        Diagnostics.button(if(list==null)"native_music_library" else "native_music_playlist")
        val probe=_state.value.probe?:return
        if(list==null){if(!_state.value.busy)_state.update{it.copy(page=probe.library,selected=null)};return}
        run("playlist") {g->
            val page=api.playlist(access(),list);active(g);_state.update{it.copy(page=page,selected=list)}
            Diagnostics.event("native_music_sample",mapOf("stage" to "playlist","entries" to page.tracks.size,"expected_count" to page.count,"complete" to false))
        }
    }
    fun sample(): MusicSource? {
        val s=_state.value;if(s.busy)return null
        return s.page?.let{NativeProtocol.sampleSource(it,s.probe?.owner?:return null,s.selected)}
    }
    fun download(track: NativeTrack) {
        Diagnostics.button("native_music_download_one")
        run("download") {g->
            val url=api.media(access(),track);active(g)
            var result: TransferResult?=null
            try {
                var lastProgress=0L;var lastStage=""
                result=transfer.download(url,track,files.temporary){p->
                    val now=System.nanoTime()
                    if(g==generation&&(p.stage!=lastStage||now-lastProgress>=200_000_000||p.segments>0&&p.segment==p.segments)) {
                        lastProgress=now;lastStage=p.stage;_state.update{it.copy(progress=p)}
                    }
                }
                active(g);val context=currentCoroutineContext()
                val row=withContext(Dispatchers.IO){files.commit(result!!,track){context.ensureActive();if(g!=generation)throw CancellationException()}}
                result=null;active(g)
                val saved=withContext(Dispatchers.IO){files.read()};active(g)
                _state.update{it.copy(saved=saved,message="Файл проверен и сохранён в приложении: ${row.extension.uppercase()}. Его можно открыть или сохранить в выбранную папку.")}
                Diagnostics.event("native_audio_saved",mapOf("format" to row.extension,"bytes" to row.bytes,"duration_ms" to row.durationMs,"detector" to "media3_encoded_audio_samples_and_duration"))
            }finally{withContext(NonCancellable+Dispatchers.IO){result?.file?.delete()}}
        }
    }
    fun cancel()=stop("native_stop_button")
    fun stopForWindow()=stop("native_window_closed")
    fun stopInBackground()=stop("activity_onStop")
    private fun stop(detector: String) {
        if(!_state.value.busy)return
        if(detector=="native_stop_button")Diagnostics.button("native_music_cancel")
        Diagnostics.event("native_music_cancel",mapOf("detector" to detector))
        generation++;work?.cancel()
        _state.update{it.copy(busy=false,stage="",progress=null,message="Операция остановлена. Готовые файлы сохранены.")}
    }
    fun forget() {
        if(_state.value.busy)return
        Diagnostics.button("native_music_forget_access")
        run("forget") {g->
            withContext(Dispatchers.IO){vault.delete()};active(g)
            _state.value=NativeMusicState(initialized=true,busy=true,stage="forget",saved=_state.value.saved,message="Доступ ВК удалён с устройства.")
        }
    }
    fun saveFile(row: SavedAudio,uri: Uri) {
        Diagnostics.button("native_music_export_audio")
        run("export_audio") {g->
            val context=currentCoroutineContext()
            withContext(Dispatchers.IO) {
                val resolver=getApplication<Application>().contentResolver
                var complete=false
                try {
                    val f=files.verified(row){context.ensureActive()}
                    resolver.openOutputStream(uri,"wt")?.use{out->
                        f.inputStream().use{input->val buffer=ByteArray(32768);while(true){context.ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}}
                    }?:throw MusicFailure(MusicError.STORAGE)
                    context.ensureActive();complete=true
                }finally{if(!complete)runCatching{DocumentsContract.deleteDocument(resolver,uri)}}
            };active(g);_state.update{it.copy(message="Аудиофайл сохранён в выбранное место.")}
        }
    }
    suspend fun verifiedFile(row: SavedAudio)=withContext(Dispatchers.IO){val c=currentCoroutineContext();files.verified(row){c.ensureActive()}}
    override fun onCleared(){work?.cancel();super.onCleared()}
}
