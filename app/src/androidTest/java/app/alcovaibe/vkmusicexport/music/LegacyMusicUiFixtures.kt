package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Historical component contracts only; actual navigation is tested through MainActivity. */
@Composable
internal fun MusicCatalogPanel(state: NativeMusicState,canPrepare: Boolean,onSection: (CatalogSection)->Unit,
    onRefresh: ()->Unit,
    onLibrary: (Boolean)->Unit,onCategory: (PlaylistKind)->Unit,onDuplicates: ()->Unit={},onUnavailable: (()->Unit)?=null) {
    val catalog=state.catalog
    val unknown=MusicCatalog.visible(state,CatalogSection.UNKNOWN)
    val sections=listOf(CatalogSection.LIBRARY,CatalogSection.PLAYLISTS,CatalogSection.ALBUMS)+
        if(unknown.isNotEmpty()||state.catalogSection==CatalogSection.UNKNOWN)listOf(CatalogSection.UNKNOWN) else emptyList()
    ScrollableTabRow(selectedTabIndex=sections.indexOf(state.catalogSection),edgePadding=0.dp) {
        sections.forEach{section->
            val count=if(section==CatalogSection.LIBRARY)state.probe?.library?.count else
                catalog?.let{MusicCatalog.visible(state,section).size}
            Tab(selected=section==state.catalogSection,enabled=!state.busy,onClick={onSection(section)},
                modifier=Modifier.testTag("catalog_tab_${section.name}"),text={Text(section.title+(count?.let{" ($it)"}?:""))})
        }
    }
    if(state.catalogSection==CatalogSection.LIBRARY) {
        Text("Моя музыка: ${state.probe?.library?.count?:0} позиций",style=MaterialTheme.typography.titleMedium)
        Button(enabled=canPrepare,onClick={onLibrary(false)},modifier=Modifier.fillMaxWidth()){Text("Скачать всю мою музыку")}
        OutlinedButton(enabled=canPrepare,onClick=onDuplicates,modifier=Modifier.fillMaxWidth().testTag("clean_duplicates")){Text("Очистить дубликаты")}
        onUnavailable?.let{check->OutlinedButton(enabled=!state.busy,onClick=check,modifier=Modifier.fillMaxWidth().testTag("check_unavailable")){Text("Проверить недоступные треки")}}
        OutlinedButton(enabled=canPrepare,onClick={onLibrary(true)},modifier=Modifier.fillMaxWidth()){Text("Скачать мою музыку и группы по альбомам")}
        Text("Группы по альбомам содержат только песни из «Моей музыки». Это не полные альбомы ВК. Сохранённые альбомы каталога открываются во вкладке «Альбомы».",style=MaterialTheme.typography.bodySmall)
    }else {
        val lists=MusicCatalog.visible(state)
        Text("${state.catalogSection.title}: ${lists.size}",style=MaterialTheme.typography.titleMedium)
        if(state.catalogRefreshing)Text("Загружаем плейлисты и альбомы…",style=MaterialTheme.typography.bodySmall)
        if(state.catalogStale)Text("Показан предыдущий каталог; обновление не завершено.",style=MaterialTheme.typography.bodySmall)
        if(state.catalogVerifying)Text("Проверяем скачанные подборки в фоне…",style=MaterialTheme.typography.bodySmall)
        if(catalog==null)Text("Каталог ещё не загружен.")
        else {
            val complete=catalog.completeFor(state.catalogSection.kind)
            if(!complete&&!state.catalogRefreshing) {
                val albumOnly=catalog.baseComplete&&catalog.optionalAlbumsIncomplete
                val scan=if(albumOnly)catalog.albumsPagination else catalog.pagination
                val counts=scan?.let{" Получено позиций: ${it.positions}/${it.expected}."}.orEmpty()
                Text((if(albumOnly)"Каталог альбомов получен не полностью." else "Каталог подборок получен не полностью.")+
                    "$counts Обнови каталог перед массовым скачиванием.",
                    color=MaterialTheme.colorScheme.error)
            }
            if(lists.isEmpty())Text(if(complete)"В этой категории нет подборок." else "Среди загруженных подборок эта категория не найдена.")
            if(lists.isNotEmpty())Button(enabled=canPrepare,
                onClick={onCategory(state.catalogSection.kind!!)},modifier=Modifier.fillMaxWidth().testTag("catalog_download_category")) {
                Text(when(state.catalogSection){CatalogSection.ALBUMS->"Скачать все альбомы";CatalogSection.PLAYLISTS->"Скачать все плейлисты";else->"Скачать все подборки"})
            }
        }
        if(state.catalogSection==CatalogSection.UNKNOWN)Text("ВК не сообщил тип этих подборок. Их можно открыть и скачать без ручного выбора типа.",style=MaterialTheme.typography.bodySmall)
    }
    if(state.hiddenCatalog.isNotEmpty())Text("Полностью скачанные подборки скрыты: ${state.hiddenCatalog.size}",style=MaterialTheme.typography.bodySmall)
    TextButton(enabled=!state.busy,onClick=onRefresh){Text("Обновить каталог")}
    state.catalogError?.let{Text("Каталог: ${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}

}

/** Every collection is reachable in one lazy list, independently of the API page size. */
internal fun LazyListScope.musicCatalogEntries(state: NativeMusicState,onSelect: (NativePlaylist)->Unit) {
    if(state.catalogSection!=CatalogSection.LIBRARY) {
        items(MusicCatalog.visible(state),key={"catalog_${it.key}"}) {list->
            OutlinedButton(enabled=!state.busy,onClick={onSelect(list)},
                modifier=Modifier.fillMaxWidth().testTag("catalog_list_${list.owner}_${list.id}")) {
                Text("${list.title} · ${list.count}")
            }
        }
    }
}

// Retained as historical fixtures, excluded from the production APK.
@Composable
internal fun DownloadQueueButton(queue: ExportSnapshot,onClick: ()->Unit) {
    OutlinedButton(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("open_home_downloads")) {
        Icon(DownloadIcon,null);Spacer(Modifier.width(8.dp))
        Text(if(queue.total>0)"Скачивания · ${queue.done}/${queue.total}" else "Скачивания")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadProgressSheet(queue: ExportSnapshot,busy: Boolean=false,actionError: MusicError?=null,
    onPause: ()->Unit,onResume: ()->Unit,onRetry: ()->Unit,onClear: ()->Unit,onDismiss: ()->Unit,
    onSourceRetry: (ExportSourceFailure)->Unit={},onExportReport: (()->Unit)?=null) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("downloads_sheet")) {
            DownloadProgressContent(queue,busy,actionError,onPause,onResume,onRetry,onClear,onSourceRetry,onExportReport,scrollable=true)
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onDismiss,modifier=Modifier.testTag("downloads_dismiss")){Text("Свернуть")}
            }
        }
    }
}

@Composable
internal fun LibraryRemoveButton(inLibrary: Boolean,enabled: Boolean,onClick: ()->Unit) {
    if(inLibrary)IconButton(enabled=enabled,onClick=onClick,modifier=Modifier.testTag("remove_from_vk")) {
        Icon(Icons.Default.Delete,"Удалить из моей музыки ВК",tint=MaterialTheme.colorScheme.error)
    }
}

