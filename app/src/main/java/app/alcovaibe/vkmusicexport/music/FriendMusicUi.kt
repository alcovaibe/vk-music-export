@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
private fun FriendSearch(value: String,label: String,tag: String,onValue:(String)->Unit) {
    TextField(value,onValue,modifier=Modifier.fillMaxWidth().testTag(tag),singleLine=true,
        placeholder={Text(label,maxLines=1,overflow=TextOverflow.Ellipsis)},leadingIcon={Icon(Icons.Default.Search,null)},
        trailingIcon={if(value.isNotEmpty())IconButton(onClick={onValue("")}){Icon(Icons.Default.Close,"Очистить поиск")}},
        shape=RoundedCornerShape(20.dp),colors=TextFieldDefaults.colors(
            focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
}
@Composable
private fun FriendBadge(text: String,error: Boolean=false,modifier: Modifier=Modifier) {
    Surface(modifier=modifier,shape=RoundedCornerShape(24.dp),color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            Icon(if(error)FriendErrorIcon else Icons.Default.Check,null,Modifier.size(18.dp),tint=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            Text(text,textAlign=TextAlign.Center,style=MaterialTheme.typography.labelMedium,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
@Composable
private fun FriendProfileHeader(user: MusicFriend,available: Boolean,problem: FriendMusicProblem?,onOpen:()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth().testTag("friend_profile_header")) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MusicThumbnail(url=user.photo,identity="friend:${user.id}",artist=true,size=72.dp)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(user.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    user.handle?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("friend_handle"))}
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart) {
                    if(problem!=null)FriendBadge(if(problem.code==MusicError.DENIED)"Музыка недоступна" else "Ошибка загрузки",true)
                    else if(available)FriendBadge("Музыка доступна")
                    else Text("Проверяем доступ…",Modifier.padding(vertical=6.dp),style=MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick=onOpen,shape=RoundedCornerShape(12.dp),
                    contentPadding=PaddingValues(horizontal=9.dp,vertical=0.dp),modifier=Modifier.heightIn(min=36.dp).testTag("friend_open_vk")) {
                    Icon(FriendOpenIcon,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Открыть в ВК",style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
@Composable
private fun FriendTabs(state: FriendMusicState,onTab:(FriendMusicTab)->Unit) {
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            FriendMusicTab.entries.forEach{tab->
                val selected=state.tab==tab
                Surface(onClick={onTab(tab)},shape=RoundedCornerShape(15.dp),modifier=Modifier.weight(1f).testTag("friend_tab_${tab.name}"),
                    color=if(selected)MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Column(Modifier.padding(horizontal=3.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(tab.title,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(state.count(tab)?.toString()?:"—",style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
@Composable
private fun FriendProblemCard(problem: FriendMusicProblem,onRetry:()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth().testTag("friend_music_problem")) {
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Icon(if(problem.code==MusicError.DENIED)Icons.Default.Lock else Icons.Default.Info,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
            Text(when(problem.code){MusicError.DENIED->"Доступ к музыке запрещён";MusicError.METHOD->"Раздел недоступен подключению";else->"Не удалось загрузить раздел"},
                style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
            Text(problem.message,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().testTag("friend_problem_message"))
            Text(problem.codeText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().testTag("friend_problem_code"))
            TextButton(onClick=onRetry,modifier=Modifier.testTag("friend_retry")){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(6.dp));Text("Повторить")}
        }
    }
}

/** Rendering is driven by actual server/queue state; percentages are only used for known totals. */
internal fun LazyListScope.friendMusicItems(state: FriendMusicState,controller: FriendMusicController,configured: Boolean,enabled: Boolean,
    queue: ExportSnapshot,queueItems: Map<String,String>,tasks: List<AudioExportTask>,saved: Map<String,PublishedAudio>,preview:(NativeTrack)->String?,
    onPause:()->Unit,onDownloads:()->Unit,onOpenVk:(MusicFriend)->Unit,onConnect:()->Unit,onPlay:(Int)->Unit={}) {
    if(!configured) {
        item("friends_connect"){Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Подключи ВК, чтобы открыть друзей и доступную тебе музыку.")
            Button(onClick=onConnect){Text("Подключить ВК")}
        }}
        return
    }
    val user=state.user
    if(user==null) {
        item("friend_search"){FriendSearch(state.friendsQuery,"Найти друга","friends_search",controller::friendsQuery)}
        val query=state.friendsQuery.trim()
        val matches=state.friends.filter{query.isEmpty()||it.name.contains(query,true)||it.handle?.contains(query,true)==true}
        item("friends_count"){Text(if(query.isNotEmpty())"Найдено: ${matches.size}" else if(state.friendsComplete)"Друзей: ${state.friends.size}" else "Загружено друзей: ${state.friends.size}${state.friendsTotal?.let{" из $it"}.orEmpty()}",
            color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}
        if(state.busy)item("friends_loading"){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Загружаем список друзей…",style=MaterialTheme.typography.bodySmall)}
        state.listProblem?.let{problem->item("friends_problem"){FriendProblemCard(problem,controller::loadFriends)}}
        items(matches,key={"friend_${it.id}"}){friend->
            Surface(onClick={controller.open(friend)},enabled=!state.preparing,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,
                modifier=Modifier.fillMaxWidth().testTag("friend_${friend.id}")) {
                Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    MusicThumbnail(url=friend.photo,identity="friend:${friend.id}",artist=true,size=48.dp)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(friend.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("Открыть музыкальный профиль",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(MusicChevronIcon,null)
                }
            }
        }
        if(state.friendsComplete&&matches.isEmpty())item("friends_empty") {
            Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search,null,Modifier.size(40.dp));Text(if(query.isEmpty())"Список друзей пуст" else "Друзья по этому запросу не найдены")
            }
        }
        if(!state.busy&&!state.friendsComplete&&state.listProblem==null)item("friends_refresh"){TextButton(onClick=controller::loadFriends){Text("Загрузить друзей")}}
        return
    }
    val problem=if(state.playlist!=null)state.playlistProblem else state.problems[state.tab]
    val available=if(state.playlist!=null)state.playlistTracks!=null else when(state.tab){FriendMusicTab.TRACKS->state.tracks!=null;else->state.catalog!=null}
    if(state.playlist==null)item("friend_header"){FriendProfileHeader(user,available,problem){onOpenVk(user)}}
    else item("friend_playlist_owner"){TextButton(onClick=controller::back){Icon(Icons.Default.Person,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(user.name)}}
    if(state.playlist==null)item("friend_tabs"){FriendTabs(state,controller::tab)}
    else item("friend_playlist_header") {
        val list=state.playlist
        Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth().testTag("friend_playlist_header")) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                MusicThumbnail(url=list.photo,identity=list.key,size=80.dp)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(list.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(user.name,style=MaterialTheme.typography.bodySmall)
                    Text("Треков: ${state.playlistTracks?.count?:list.count}",style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if(state.tab!=FriendMusicTab.TRACKS&&state.playlist==null) {
        if(state.busy&&state.catalog==null)item("friend_catalog_loading"){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Получаем плейлисты и альбомы…")}
        problem?.let{item("friend_catalog_problem"){FriendProblemCard(it,controller::refresh)}}
        val collections=state.collections()
        if(state.catalog?.complete==true&&collections.isEmpty()&&problem==null)item("friend_collections_empty"){Text(if(state.tab==FriendMusicTab.ALBUMS)"Нет доступных альбомов" else "Нет доступных плейлистов")}
        items(collections,key={"friend_collection_${it.key}"}){list->
            Surface(onClick={controller.playlist(list)},shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,
                enabled=!state.preparing,modifier=Modifier.fillMaxWidth().testTag("friend_collection_${list.owner}_${list.id}")) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    MusicThumbnail(url=list.photo,identity=list.key,size=56.dp)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(list.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("${user.name} · ${list.count} треков",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    };Icon(MusicChevronIcon,null)
                }
            }
        }
        return
    }
    if(problem!=null&&state.page==null) {
        item("friend_tracks_problem"){FriendProblemCard(problem,{state.playlist?.let(controller::playlist)?:controller.refresh()})}
        return
    }
    item("friend_music_search"){FriendSearch(state.musicQuery,if(state.playlist==null)"Поиск по музыке" else "Поиск по трекам плейлиста","friend_music_search",controller::musicQuery)}
    val page=state.page
    val ready=enabled&&!state.busy&&state.complete&&page?.tracks?.isNotEmpty()==true
    item("friend_track_actions") {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state.selecting) {
                Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Выбрано: ${state.selection.size}",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        TextButton(onClick=controller::selectMode){Text("Снять выбор")}
                    }
                }
                Button(onClick={controller.download(state.selection)},shape=RoundedCornerShape(14.dp),enabled=ready&&state.selection.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("friend_download_selected")) {
                    Icon(DownloadIcon,null);Spacer(Modifier.width(8.dp));Text("Скачать выбранное")
                }
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={controller.download()},shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),enabled=ready,modifier=Modifier.weight(1f).testTag("friend_download_all")) {
                    Icon(DownloadIcon,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(state.playlist==null)"Скачать всё" else when(state.playlist.kind){PlaylistKind.ALBUM->"Скачать альбом";PlaylistKind.UNKNOWN->"Скачать подборку";else->"Скачать плейлист"},maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                FilledTonalButton(onClick={onPlay(0)},shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),enabled=state.complete&&!state.busy&&page?.tracks?.isNotEmpty()==true,modifier=Modifier.weight(1f).testTag("friend_play")){Icon(PlayerPlayIcon,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Воспроизвести",maxLines=2,overflow=TextOverflow.Ellipsis)}
            }
            if(!state.complete&&state.busy) {
                if(page!=null&&page.count>0)LinearProgressIndicator(progress={page.tracks.size.toFloat()/page.count},modifier=Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(page!=null)"Получено ${page.tracks.size} из ${page.count} треков" else "Получаем музыку…",style=MaterialTheme.typography.bodySmall)
            }
            if(!state.complete&&!state.busy&&problem==null)TextButton(onClick=controller::refresh){Text("Повторить загрузку")}
            if(problem!=null&&page!=null&&!state.complete)Text("Получено ${page.tracks.size} из ${page.count} треков. Уже полученные записи показаны ниже.",
                modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.bodySmall)
            val metadataErrors=page?.tracks?.count{it.metadataError!=null}?:0
            if(metadataErrors>0)Text("Неполные описания записей: $metadataErrors. Они отмечены в списке; остальные треки доступны.",style=MaterialTheme.typography.bodySmall)
            if(state.preparing) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Проверяем актуальный список перед скачиванием…",style=MaterialTheme.typography.bodySmall)}
        }
    }
    problem?.let{item("friend_tracks_problem"){FriendProblemCard(it,{state.playlist?.let(controller::playlist)?:controller.refresh()})}}
    state.message?.let{item("friend_message"){Text(it,style=MaterialTheme.typography.bodyMedium)}}
    if(queue.unfinished||queue.status in setOf("RUNNING","QUEUED"))item("friend_queue_progress") {
        Surface(onClick=onDownloads,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Общая очередь: сохранено ${queue.done} из ${queue.total}",fontWeight=FontWeight.SemiBold)
                if(queue.total>0)LinearProgressIndicator(progress={queue.done.toFloat()/queue.total},modifier=Modifier.fillMaxWidth())
                Text(if(queue.status=="PAUSED")"Приостановлено · открыть загрузки" else "Скачивание продолжается в фоне",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    val matching=page?.tracks.orEmpty().withIndex().filter{state.musicQuery.isBlank()||("${it.value.artist} ${it.value.displayTitle}").contains(state.musicQuery.trim(),true)}
    if(state.complete&&matching.isEmpty())item("friend_tracks_empty"){Text(if(state.musicQuery.isBlank())"Нет доступных треков" else "Треки по этому запросу не найдены")}
    items(matching,key={"friend_track_${it.index+1}_${it.value.id}"}){entry->
        val position=entry.index+1;val track=entry.value;val task=tasks.getOrNull(entry.index)
        FriendTrackRow(track,position,state.selecting,position in state.selection,{controller.toggle(position)},preview(track),saved[track.id],
            ready,{controller.download(setOf(position))},queue,task?.key,queueItems[task?.key],onPause,onPlay={onPlay(entry.index)},playable=state.complete&&!state.busy&&track.metadataError==null&&!track.restricted&&track.duration>0)
    }
}
@Composable
private fun FriendTrackRow(track: NativeTrack,position: Int,selecting: Boolean,selected: Boolean,onToggle:()->Unit,photo: String?,saved: PublishedAudio?,
    enabled: Boolean,onDownload:()->Unit,queue: ExportSnapshot,taskKey: String?,taskState: String?,onPause:()->Unit,onPlay:()->Unit,playable: Boolean) {
    val current=taskKey!=null&&queue.currentTask?.key==taskKey&&queue.status=="RUNNING"
    val failure=queue.failures.firstOrNull{it.task.key==taskKey}
    Surface(
        shape=RoundedCornerShape(18.dp),color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=Modifier.fillMaxWidth().testTag("friend_track_$position").combinedClickable(enabled=selecting||playable,onClick={if(selecting)onToggle()else onPlay()},onLongClick=onToggle)) {
        Row(Modifier.padding(start=if(selecting)0.dp else 10.dp,end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(selecting)Checkbox(selected,{onToggle()},modifier=Modifier.testTag("friend_select_$position"))
            MusicThumbnail(url=photo,uri=saved?.uri,identity=saved?.let{it.uri+it.sha256}?:track.id,size=44.dp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(track.displayTitle,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${track.artist} · ${track.duration/60}:${(track.duration%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            when {
                current->Row(verticalAlignment=Alignment.CenterVertically) {
                    val p=queue.progress
                    if(p!=null&&p.segments>0)CircularProgressIndicator(progress={p.segment.toFloat()/p.segments},modifier=Modifier.size(24.dp),strokeWidth=2.dp)
                    else CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                    IconButton(onClick=onPause){Icon(FriendPauseIcon,"Приостановить очередь")}
                }
                saved!=null->FriendRowStatus(Icons.Default.CheckCircle,"Скачано",MaterialTheme.colorScheme.tertiary)
                track.metadataError!=null->FriendRowStatus(FriendErrorIcon,"Ошибка данных",MaterialTheme.colorScheme.error)
                failure!=null||track.restricted||track.duration==0->FriendRowStatus(FriendErrorIcon,
                    if(failure!=null&&failure.error !in setOf(MusicError.UNAVAILABLE,MusicError.DENIED))"Ошибка" else "Недоступно",MaterialTheme.colorScheme.error)
                taskState in setOf("PENDING","RUNNING")->FriendRowStatus(FriendQueueIcon,if(queue.status=="PAUSED")"На паузе" else "В очереди",MaterialTheme.colorScheme.primary)
                else->IconButton(onClick=onDownload,enabled=enabled,modifier=Modifier.testTag("friend_download_$position")){Icon(DownloadIcon,"Скачать этот трек")}
            }
        }
    }
}

@Composable
private fun FriendRowStatus(icon: androidx.compose.ui.graphics.vector.ImageVector,text: String,color: Color) {
    Column(Modifier.widthIn(max=76.dp).padding(horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,null,Modifier.size(22.dp),tint=color)
        Text(text,style=MaterialTheme.typography.labelSmall,color=color,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}
