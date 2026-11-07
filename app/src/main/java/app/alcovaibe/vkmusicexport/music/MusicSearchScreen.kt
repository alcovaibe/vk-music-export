package app.alcovaibe.vkmusicexport.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicSearchScreen(state: MusicSearchState,onQuery:(String)->Unit,onRetry:()->Unit,onMore:()->Unit,
    onBack:()->Unit,onPlay:(Int)->Unit,onDownload:(Int)->Unit,canDownload: Boolean,
    playback: PlaybackState,onPlayer:()->Unit) {
    BackHandler(onBack=onBack)
    Scaffold(modifier=Modifier.testTag("music_search_screen"),
        topBar={TopAppBar(title={Text("Поиск музыки")},navigationIcon={
            IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
        })},bottomBar={PlaybackMiniPlayer(playback,onPlayer)}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("query") {
                OutlinedTextField(value=state.query,onValueChange=onQuery,label={Text("Исполнитель или название")},
                    singleLine=true,modifier=Modifier.fillMaxWidth().testTag("music_search_query"))
                if(state.capability==CapabilityStatus.EXPERIMENTAL)
                    Text("Экспериментальный источник ВК. Доступность результатов и аудио зависит от текущего подключения.",
                        style=MaterialTheme.typography.bodySmall)
            }
            if(state.phase==MusicSearchPhase.LOADING)item("loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription="Поиск музыки"})
            }
            val status=when(state.phase) {
                MusicSearchPhase.READY->"Введи не менее двух символов."
                MusicSearchPhase.EMPTY->"По этому запросу ВК не вернул записей."
                MusicSearchPhase.DENIED->"ВК не разрешил поиск при текущем подключении. Проверь доступ к музыке."
                MusicSearchPhase.CHALLENGE->"ВК требует подтверждение или капчу. Заверши проверку на стороне ВК и повтори."
                MusicSearchPhase.UNAVAILABLE->"Текущий провайдер не предоставляет музыкальный поиск."
                MusicSearchPhase.ERROR->state.error?.label?:"Не удалось выполнить поиск."
                else->null
            }
            status?.let{message->item("status") {
                Text(message,modifier=Modifier.testTag("music_search_status").semantics{liveRegion=LiveRegionMode.Polite})
                if(state.phase!=MusicSearchPhase.READY&&state.phase!=MusicSearchPhase.EMPTY)
                    TextButton(onClick=onRetry){Text("Повторить")}
            }}
            itemsIndexed(state.tracks,key={index,track->"search_${index}_${track.id}"}) {index,track->
                val ready=state.audio!=CapabilityStatus.UNAVAILABLE&&state.active&&state.phase==MusicSearchPhase.RESULTS&&track.duration>0&&
                    !track.restricted&&track.metadataError==null
                MusicTrackRow(track,index+1,enabled=canDownload&&ready,
                    onPlay=if(ready)({onPlay(index)})else null,
                    onDownload=if(ready)({onDownload(index)})else null,tag="search_track_${index}")
            }
            if(state.canLoadMore)item("more") {
                TextButton(onClick=onMore,modifier=Modifier.testTag("music_search_more")){Text("Загрузить ещё")}
            }
        }
    }
}
