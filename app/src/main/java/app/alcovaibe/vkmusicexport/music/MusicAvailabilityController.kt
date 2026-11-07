package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Read-only availability lookup. Its cancellation and busy state are isolated from
 * catalog refreshes, downloads, maintenance, and other ViewModel operations.
 * Generation fences also protect against non-cooperative late network callbacks.
 */
internal class MusicAvailabilityController(
    private val scope: CoroutineScope,
    private val api: MusicApi,
    private val access: suspend ()->MusicAccess,
    private val checked: suspend (MusicAccess)->MusicAccess,
    private val extra: suspend ()->List<NativeTrack>,
    private val blocked: ()->Boolean,
    private val report: (AvailabilityState)->Unit={},
    private val pageDelayMs: Long=400
) {
    private val _state=MutableStateFlow(AvailabilityState())
    val state=_state.asStateFlow()
    private var work: Job?=null
    private var generation=0

    fun show() {if(!blocked())_state.update{it.copy(visible=true)}}

    /** Cancel only this reader; preserve verified partial results for the user. */
    fun pause() {
        generation++
        val wasRunning=_state.value.running
        work?.cancel()
        work=null
        if(wasRunning)_state.update{it.copy(running=false,message="Проверка прервана. Уже полученные результаты показаны.")}
    }

    fun dismiss() {pause();_state.update{it.copy(visible=false)}}

    /** Never reuse old-account results after logout or a new independent login. */
    fun reset() {pause();_state.value=AvailabilityState()}

    private suspend fun active(g: Int) {
        currentCoroutineContext().ensureActive()
        if(g!=generation||blocked())throw CancellationException("Availability read invalidated")
    }

    fun check() {
        if(!_state.value.visible||_state.value.running||blocked())return
        val g=++generation
        _state.value=AvailabilityState(visible=true,running=true)
        work=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val initial=access()
                    suspend fun guard() {active(g);checked(initial);active(g)}
                    guard()
                    val failed=extra()
                    guard()
                    AvailabilityCheck(api,::guard,pageDelayMs).check(initial,failed){done,total,row->
                        if(g==generation)_state.update{
                            it.copy(done=done,total=total,results=if(row==null)it.results else it.results+row)
                        }
                    }
                    guard()
                }
                active(g)
                _state.update{it.copy(message="Проверка завершена. Результат относится к текущему аккаунту и соединению; для загрузки повтори пропущенные треки.")}
                report(_state.value)
            }catch(e: CancellationException) {
                if(g==generation)_state.update{it.copy(message="Проверка прервана. Уже полученные результаты показаны.")}
                throw e
            }catch(e: Exception) {
                if(g==generation)_state.update{it.copy(message="Проверка остановлена: "+((e as? MusicFailure)?.code?.label?:MusicError.STORAGE.label))}
            }finally {
                if(g==generation) {
                    work=null
                    _state.update{it.copy(running=false)}
                }
            }
        }
    }
}
