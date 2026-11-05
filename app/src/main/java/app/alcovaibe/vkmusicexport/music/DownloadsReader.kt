package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DownloadsSelection(val tab: DownloadsTab=DownloadsTab.QUEUE,val offset: Int=0,val refresh: Long=0)
internal data class DownloadsPageState(val page: ExportQueuePage?=null,val loading: Boolean=false,val error: Boolean=false)
/** Byte progress does not invalidate the list. A replaced queue cannot receive an older page. */
internal class DownloadsReader(scope: CoroutineScope,open: Flow<Boolean>,versions: StateFlow<ExportSnapshot>,
    private val fetch: suspend(DownloadsTab,Int)->ExportQueuePage) {
    private val selection=MutableStateFlow(DownloadsSelection())
    private val mutable=MutableStateFlow(DownloadsPageState());val state=mutable.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            val content=versions.map{it.queueId to it.revision}.distinctUntilChanged()
            combine(open,selection,content){visible,choice,version->Triple(visible,choice,version)}.collectLatest{(visible,choice,version)->
                if(!visible){mutable.update{it.copy(loading=false)};return@collectLatest}
                mutable.update{old->DownloadsPageState(old.page?.takeIf{it.queueId==version.first&&it.tab==choice.tab&&it.offset==choice.offset},loading=true)}
                try {
                    val page=fetch(choice.tab,choice.offset)
                    currentCoroutineContext().ensureActive()
                    val now=versions.value
                    if(page.queueId==now.queueId&&page.revision>=now.revision)mutable.value=DownloadsPageState(page)
                    else mutable.value=DownloadsPageState(loading=true)
                }catch(e: CancellationException){throw e}catch(_: Exception){mutable.value=DownloadsPageState(error=true)}
            }
        }
    }
    fun select(tab: DownloadsTab,offset: Int=0){require(offset>=0&&offset%50==0);selection.update{it.copy(tab=tab,offset=offset)}}
    fun retry(){selection.update{it.copy(refresh=it.refresh+1)}}
}
