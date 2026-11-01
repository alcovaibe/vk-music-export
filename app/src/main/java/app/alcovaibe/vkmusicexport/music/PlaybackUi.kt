package app.alcovaibe.vkmusicexport.music

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun playerIcon(name: String,path: String,filled: Boolean=false)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
    addPath(addPathNodes(path),fill=if(filled)SolidColor(Color.Black)else null,
        stroke=if(filled)null else SolidColor(Color.Black),strokeLineWidth=1.8f)
}.build()
internal val PlayerPlayIcon=playerIcon("PlayMusic","M7 4 L21 12 L7 20 Z",true)
internal val PlayerNextIcon=playerIcon("NextMusic","M5 4 L17 12 L5 20 Z M18 4 H21 V20 H18 Z",true)
internal val PlayerPreviousIcon=playerIcon("PreviousMusic","M19 4 L7 12 L19 20 Z M3 4 H6 V20 H3 Z",true)
internal val PlayerShuffleIcon=playerIcon("ShuffleMusic","M3 5 H6 L18 19 H22 M18 15 L22 19 L18 23 M3 19 H6 L10 14 M14 10 L18 5 H22 M18 1 L22 5 L18 9")
internal val PlayerRepeatIcon=playerIcon("RepeatMusic","M4 11 V7 H21 M17 3 L21 7 L17 11 M20 13 V17 H3 M7 13 L3 17 L7 21")
internal val PlayerCollapseIcon=playerIcon("CollapsePlayer","M4 8 L12 16 L20 8")
internal val PlayerOnlineIcon=playerIcon("OnlineMusic","M2 8 Q12 -1 22 8 M5 12 Q12 5 19 12 M8 16 Q12 12 16 16 M11 20 H13")
internal val PlayerOfflineIcon=playerIcon("OfflineMusic","M5 2 H14 L20 8 V22 H5 Z M14 2 V8 H20 M9 16 L11 18 L16 13")

internal fun playbackTime(ms: Long): String {
    val seconds=ms.coerceAtLeast(0)/1000
    return if(seconds>=3600)"${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}"
        else "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
}
@Composable
private fun PlayerArtwork(state: PlaybackState,modifier: Modifier) {
    val image=remember(state.artwork){state.artwork?.let{BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap()}}
    Surface(modifier=modifier,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment=Alignment.Center) {
            if(image!=null)Image(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else Icon(LibraryNoteIcon,null,Modifier.fillMaxSize(.3f),tint=MaterialTheme.colorScheme.primary.copy(alpha=.55f))
        }
    }
}
@Composable
internal fun PlaybackMiniPlayer(state: PlaybackState,onOpen:()->Unit) {
    val entry=state.current?:return;val context=LocalContext.current
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer,
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp).testTag("music_mini_player").clickable(onClick=onOpen)) {
        Row(Modifier.padding(start=8.dp,end=4.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            PlayerArtwork(state,Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.track.displayTitle,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(entry.track.artist,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(state.buffering)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
            IconButton(onClick={PlaybackRuntime.toggle(context)},modifier=Modifier.testTag("mini_player_toggle")) {
                Icon(if(state.playing||state.buffering)FriendPauseIcon else PlayerPlayIcon,if(state.playing||state.buffering)"Пауза" else "Продолжить")
            }
            IconButton(onClick=PlaybackRuntime::next,enabled=state.next){Icon(PlayerNextIcon,"Следующий трек")}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackScreen(state: PlaybackState,onDismiss:()->Unit,onDownload: (PlaybackEntry)->Unit,canDownload: Boolean) {
    BackHandler(onBack=onDismiss)
    val context=LocalContext.current
    var queue by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)}
    var seeking by remember(state.index){mutableStateOf<Float?>(null)}
    val current=state.current
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize().testTag("music_player")) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val cover=minOf(maxWidth-32.dp,maxHeight*.43f)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onDismiss,modifier=Modifier.testTag("player_collapse")){Icon(PlayerCollapseIcon,"Свернуть плеер")}
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Text("Сейчас играет",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)}
                    Box {
                        IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,"Меню плеера")}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("Остановить воспроизведение")},onClick={menu=false;PlaybackRuntime.stop();onDismiss()})
                        }
                    }
                }
                PlayerArtwork(state,Modifier.size(cover).testTag("player_artwork"))
                Column(Modifier.fillMaxWidth().padding(horizontal=3.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(current?.track?.displayTitle?:"Подготовка воспроизведения…",fontSize=25.sp,lineHeight=29.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.testTag("player_title"))
                    Text(current?.track?.artist.orEmpty(),fontSize=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(current?.sourceLabel.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Column(Modifier.fillMaxWidth()) {
                    val duration=state.durationMs.coerceAtLeast(1)
                    Slider(value=seeking?:state.positionMs.coerceIn(0,duration).toFloat(),valueRange=0f..duration.toFloat(),
                        onValueChange={seeking=it},onValueChangeFinished={seeking?.let{PlaybackRuntime.seek(it.toLong())};seeking=null},
                        enabled=state.seekable,modifier=Modifier.fillMaxWidth().testTag("player_seek"))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(playbackTime(seeking?.toLong()?:state.positionMs),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(playbackTime(state.durationMs),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    IconToggleButton(checked=state.shuffle,onCheckedChange={PlaybackRuntime.shuffle()},modifier=Modifier.testTag("player_shuffle")) {
                        Icon(PlayerShuffleIcon,if(state.shuffle)"Случайный порядок включён" else "Включить случайный порядок",
                            tint=if(state.shuffle)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick=PlaybackRuntime::previous,enabled=state.previous,modifier=Modifier.testTag("player_previous")){Icon(PlayerPreviousIcon,"Предыдущий трек",Modifier.size(30.dp))}
                    FilledIconButton(onClick={PlaybackRuntime.toggle(context)},enabled=current!=null,shape=CircleShape,modifier=Modifier.size(72.dp).testTag("player_toggle")) {
                        if(state.buffering)CircularProgressIndicator(Modifier.size(32.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp)
                        else Icon(if(state.playing)FriendPauseIcon else PlayerPlayIcon,if(state.playing)"Пауза" else "Воспроизвести",Modifier.size(35.dp))
                    }
                    IconButton(onClick=PlaybackRuntime::next,enabled=state.next,modifier=Modifier.testTag("player_next")){Icon(PlayerNextIcon,"Следующий трек",Modifier.size(30.dp))}
                    IconButton(onClick=PlaybackRuntime::repeat,modifier=Modifier.testTag("player_repeat")) {
                        Box(contentAlignment=Alignment.Center) {
                            Icon(PlayerRepeatIcon,when(state.repeat){1->"Повторять трек";2->"Повторять очередь";else->"Повтор выключен"},
                                tint=if(state.repeat!=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if(state.repeat==1)Text("1",fontSize=9.sp,fontWeight=FontWeight.Bold)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={queue=true},shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),
                        enabled=current!=null,modifier=Modifier.weight(1f).testTag("player_queue")) {
                        Icon(FriendQueueIcon,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Очередь")
                    }
                    FilledTonalButton(onClick={current?.let(onDownload)},enabled=current?.offline==false&&current.download!=null&&canDownload,
                        shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),modifier=Modifier.weight(1f).testTag("player_download")) {
                        Icon(if(current?.offline==true)PlayerOfflineIcon else DownloadIcon,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(current?.offline==true)"Сохранено" else "Скачать")
                    }
                }
                state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth().testTag("player_error"))}
                Row(Modifier.padding(top=3.dp,bottom=18.dp).testTag("player_connection"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    Icon(if(current?.offline==true)PlayerOfflineIcon else PlayerOnlineIcon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(current?.offline==true)"Офлайн" else "Онлайн",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if(queue)ModalBottomSheet(onDismissRequest={queue=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
            Text("Очередь воспроизведения",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(if(state.shuffle)"Случайный порядок" else "По порядку",color=MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp).testTag("playback_queue_list"),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.entries,key={index,_->index}) {index,entry->
                    Surface(onClick={PlaybackRuntime.service?.playIndex(index);queue=false},shape=RoundedCornerShape(14.dp),
                        color=if(index==state.index)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier=Modifier.fillMaxWidth().testTag("playback_queue_$index")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(entry.track.displayTitle,fontWeight=if(index==state.index)FontWeight.Bold else FontWeight.Normal)
                            Text(entry.track.artist,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
