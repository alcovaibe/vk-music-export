package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicCatalogPanel(state: NativeMusicState,canPrepare: Boolean,onSection: (CatalogSection)->Unit,
    onSelect: (NativePlaylist)->Unit,onCatalogPage: (Int)->Unit,onRefresh: ()->Unit,
    onLibrary: (Boolean)->Unit,onCategory: (PlaylistKind)->Unit,onClassify: (NativePlaylist,PlaylistKind)->Unit) {
    val catalog=state.catalog
    val unknown=MusicCatalog.lists(catalog,CatalogSection.UNKNOWN,state.catalogKinds)
    val sections=listOf(CatalogSection.LIBRARY,CatalogSection.PLAYLISTS,CatalogSection.ALBUMS)+
        if(unknown.isNotEmpty()||state.catalogSection==CatalogSection.UNKNOWN)listOf(CatalogSection.UNKNOWN) else emptyList()
    ScrollableTabRow(selectedTabIndex=sections.indexOf(state.catalogSection),edgePadding=0.dp) {
        sections.forEach{section->
            val count=if(section==CatalogSection.LIBRARY)state.probe?.library?.count else
                catalog?.let{MusicCatalog.lists(it,section,state.catalogKinds).size}
            Tab(selected=section==state.catalogSection,enabled=!state.busy,onClick={onSection(section)},
                modifier=Modifier.testTag("catalog_tab_${section.name}"),text={Text(section.title+(count?.let{" ($it)"}?:""))})
        }
    }
    if(state.catalogSection==CatalogSection.LIBRARY) {
        Text("Моя музыка: ${state.probe?.library?.count?:0} позиций",style=MaterialTheme.typography.titleMedium)
        Button(enabled=canPrepare,onClick={onLibrary(false)},modifier=Modifier.fillMaxWidth()){Text("Скачать всю мою музыку")}
        OutlinedButton(enabled=canPrepare,onClick={onLibrary(true)},modifier=Modifier.fillMaxWidth()){Text("Скачать мою музыку и группы по альбомам")}
        Text("Группы по альбомам содержат только песни из «Моей музыки». Это не полные альбомы ВК. Сохранённые альбомы каталога открываются во вкладке «Альбомы».",style=MaterialTheme.typography.bodySmall)
    }else {
        val lists=MusicCatalog.lists(catalog,state.catalogSection,state.catalogKinds)
        val visible=lists.drop(state.catalogOffset).take(NativeProtocol.LIMIT)
        Text("${state.catalogSection.title}: ${lists.size}",style=MaterialTheme.typography.titleMedium)
        if(catalog==null)Text("Каталог ещё не загружен.")
        else {
            if(catalog.lists.size!=catalog.count)Text("Каталог получен не полностью: ${catalog.lists.size} из ${catalog.count}. Счётчики относятся только к загруженным подборкам.",color=MaterialTheme.colorScheme.error)
            if(lists.isEmpty())Text(if(catalog.lists.size==catalog.count)"В этой категории нет подборок." else "Среди загруженных подборок эта категория не найдена.")
            if(state.catalogSection!=CatalogSection.UNKNOWN&&lists.isNotEmpty())Button(enabled=canPrepare,
                onClick={onCategory(state.catalogSection.kind!!)},modifier=Modifier.fillMaxWidth().testTag("catalog_download_category")) {
                Text(if(state.catalogSection==CatalogSection.ALBUMS)"Скачать все альбомы" else "Скачать все плейлисты")
            }
            if(visible.isNotEmpty()) {
                Text("Показано ${state.catalogOffset+1}–${state.catalogOffset+visible.size} из ${lists.size}",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled=!state.busy&&state.catalogOffset>0,onClick={onCatalogPage(-1)}){Text("Назад по списку")}
                    TextButton(enabled=!state.busy&&state.catalogOffset+visible.size<lists.size,onClick={onCatalogPage(1)}){Text("Ещё подборки")}
                }
            }
            visible.forEach{list->
                OutlinedButton(enabled=!state.busy,onClick={onSelect(list)},modifier=Modifier.fillMaxWidth().testTag("catalog_list_${list.owner}_${list.id}")) {
                    Text("${list.title} · ${list.count}")
                }
            }
        }
        if(state.catalogSection==CatalogSection.UNKNOWN)Text("ВК не сообщил подтверждённый тип. Открой подборку и выбери «Это плейлист» или «Это альбом». Выбор сохранится для этого аккаунта.",style=MaterialTheme.typography.bodySmall)
    }
    TextButton(enabled=!state.busy,onClick=onRefresh){Text("Обновить каталог")}
    state.catalogError?.let{Text("Каталог: ${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
    state.selected?.takeIf{it.kind==PlaylistKind.UNKNOWN}?.let{list->
        val kind=MusicCatalog.kind(list,state.catalogKinds)
        Text("Тип подборки: "+when(kind){PlaylistKind.PLAYLIST->"Плейлист";PlaylistKind.ALBUM->"Альбом";else->"Не определён"},style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=!state.busy,onClick={onClassify(list,PlaylistKind.PLAYLIST)},modifier=Modifier.weight(1f).testTag("classify_playlist")){Text("Это плейлист")}
            OutlinedButton(enabled=!state.busy,onClick={onClassify(list,PlaylistKind.ALBUM)},modifier=Modifier.weight(1f).testTag("classify_album")){Text("Это альбом")}
        }
        if(list.key in state.catalogKinds)TextButton(enabled=!state.busy,onClick={onClassify(list,PlaylistKind.UNKNOWN)}){Text("Сбросить выбор типа")}
    }
}
