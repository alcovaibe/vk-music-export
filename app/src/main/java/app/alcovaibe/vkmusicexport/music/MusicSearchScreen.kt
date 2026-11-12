package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.MusicTheme

/**
 * Shared lazy content for the dedicated Search navigation destination.
 * Kept inside MusicLibraryScaffold so the mini-player and bottom tabs are never lost.
 */
internal fun LazyListScope.musicSearchItems(
    state: MusicSearchState,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onPlay: (Int) -> Unit,
    onDownload: (Int) -> Unit,
    canDownload: Boolean,
    preview: (NativeTrack) -> String? = { null }
) {
    item("search_input") {
        OutlinedTextField(
            value=state.query,
            onValueChange=onQuery,
            singleLine=true,
            placeholder={Text("Исполнитель или название")},
            leadingIcon={Icon(Icons.Default.Search,contentDescription=null)},
            trailingIcon={
                if(state.query.isNotEmpty()) IconButton(onClick={onQuery("")}) {
                    Icon(Icons.Default.Close,contentDescription="Очистить поиск")
                }
            },
            shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth().testTag("music_search_query").semantics {
                contentDescription="Исполнитель или название"
            },
            colors=OutlinedTextFieldDefaults.colors(
                focusedBorderColor=MaterialTheme.colorScheme.primary,
                unfocusedBorderColor=MaterialTheme.colorScheme.outline
            )
        )
    }

    if(state.query.trim().length>=2 || state.tracks.isNotEmpty()) {
        item("search_results_heading") {
            Column(Modifier.fillMaxWidth().padding(top=8.dp,bottom=4.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Результаты поиска",style=MaterialTheme.typography.headlineSmall,
                    fontWeight=FontWeight.Bold,modifier=Modifier.semantics { heading() })
                if(state.phase==MusicSearchPhase.RESULTS || state.tracks.isNotEmpty()) {
                    Text("Найдено ${state.total} записей",
                        style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.testTag("music_search_count"))
                }
            }
        }
    }
    if(state.phase==MusicSearchPhase.LOADING) {
        item("search_loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("music_search_loading")
                .semantics { contentDescription="Поиск музыки" })
        }
    }
    val status=when(state.phase) {
        MusicSearchPhase.READY->if(state.query.length<2)"Введите не менее двух символов для поиска." else null
        MusicSearchPhase.EMPTY->"Ничего не найдено. Попробуйте другой запрос."
        MusicSearchPhase.DENIED->"ВК не разрешил поиск при текущем подключении. Проверьте доступ к музыке."
        MusicSearchPhase.CHALLENGE->"ВК требует подтверждения или капчи. Завершите проверку и повторите поиск."
        MusicSearchPhase.UNAVAILABLE->"Поиск музыки сейчас недоступен."
        MusicSearchPhase.ERROR->state.error?.label?:"Не удалось выполнить поиск."
        else->null
    }
    status?.let { message->
        item("search_status") {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(message,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.testTag("music_search_status").semantics { liveRegion=LiveRegionMode.Polite })
                if(state.phase !in setOf(MusicSearchPhase.READY,MusicSearchPhase.EMPTY)) {
                    TextButton(onClick=onRetry) { Text("Повторить") }
                }
            }
        }
    }

    itemsIndexed(state.tracks,key={index,track->"search_${index}_${track.id}"}) { index,track->
        val ready=state.audio!=CapabilityStatus.UNAVAILABLE && state.active &&
            (state.phase==MusicSearchPhase.RESULTS || state.phase==MusicSearchPhase.LOADING) &&
            track.duration>0 && !track.restricted && track.metadataError==null
        MusicSearchTrackRow(
            track=track,artwork=preview(track),
            onPlay=if(ready)({onPlay(index)}) else null,
            onDownload=if(ready && canDownload)({onDownload(index)}) else null,
            tag="search_track_$index"
        )
    }
    if(state.nextOffset>=MusicSearchQuery.MAX_RESULTS && state.nextOffset<state.total) {
        item("search_limit") {
            Text("Достигнут предел просмотра: ${MusicSearchQuery.MAX_RESULTS} результатов.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall)
        }
    }
    if(state.canLoadMore) {
        item("search_more") {
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                TextButton(onClick=onMore,modifier=Modifier.testTag("music_search_more")) {
                    Text("Загрузить ещё")
                }
            }
        }
    }
}

@Composable
private fun MusicSearchTrackRow(
    track: NativeTrack,
    artwork: String?,
    onPlay: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    tag: String
) {
    Surface(
        shape=RoundedCornerShape(18.dp),
        color=MaterialTheme.colorScheme.surface,
        modifier=Modifier.fillMaxWidth().testTag(tag).then(
            if(onPlay!=null)Modifier.clickable(
                onClickLabel="Воспроизвести ${track.displayTitle}",
                role=Role.Button,onClick=onPlay
            )else Modifier
        )
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            MusicThumbnail(url=artwork,identity=track.id,size=64.dp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(track.displayTitle,style=MaterialTheme.typography.titleMedium,
                    fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(track.artist,style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${track.duration/60}:${(track.duration%60).toString().padStart(2,'0')}",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(track.restricted || track.metadataError!=null) {
                    Text("Недоступно для воспроизведения",
                        color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
                }
            }
            FilledTonalIconButton(
                onClick={onDownload?.invoke()},
                enabled=onDownload!=null,
                shape=RoundedCornerShape(12.dp),
                modifier=Modifier.size(48.dp).testTag("${tag}_download"),
                colors=IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor=Color(0xFFE9E0D5),contentColor=Color(0xFF675747)
                )
            ) {
                Icon(DownloadIcon,contentDescription="Скачать ${track.displayTitle}")
            }
        }
    }
}

@Preview(name="Поиск музыки — результаты",showBackground=true,widthDp=393,heightDp=820)
@Composable
private fun MusicSearchResultsPreview() {
    val state=MusicSearchState(query="Мираж",phase=MusicSearchPhase.RESULTS,total=124,nextOffset=5,
        tracks=listOf(
            NativeTrack("preview_1","Музыка нас связала","Мираж",263),
            NativeTrack("preview_2","Новый герой","Мираж",250),
            NativeTrack("preview_3","Я больше не прошу","Мираж",268),
            NativeTrack("preview_4","Снова вместе","Мираж",245),
            NativeTrack("preview_5","Эта ночь","Мираж",237)
        ),active=true,account=1,readSessionId="preview",audio=CapabilityStatus.SUPPORTED)
    MusicTheme {
        MusicLibraryScaffold(
            title="Поиск музыки",destination=MusicDestination.SEARCH,
            onDestination={},queue=ExportSnapshot(),onDownloads={},onAbout={},onBack={},
            tag="music_search_screen",showBack=true
        ) {
            musicSearchItems(state,{},{},{},{},{},true)
        }
    }
}

@Preview(name="Поиск музыки — пустой результат",showBackground=true,widthDp=393,heightDp=700)
@Composable
private fun MusicSearchEmptyPreview() {
    MusicTheme {
        MusicLibraryScaffold(
            title="Поиск музыки",destination=MusicDestination.SEARCH,
            onDestination={},queue=ExportSnapshot(),onDownloads={},onAbout={},onBack={},
            tag="music_search_screen",showBack=true
        ) {
            musicSearchItems(MusicSearchState(query="Нет такого исполнителя",phase=MusicSearchPhase.EMPTY),
                {},{},{},{},{},false)
        }
    }
}
