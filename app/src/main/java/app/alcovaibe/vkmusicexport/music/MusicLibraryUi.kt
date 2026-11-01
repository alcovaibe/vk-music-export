package app.alcovaibe.vkmusicexport.music

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.alcovaibe.vkmusicexport.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.diagnostics.LatestReportMenu
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request

internal enum class MusicDestination(val label: String,val icon: ImageVector) {
    LIBRARY("Библиотека",LibraryNoteIcon),SAVED("Сохранённое",SavedLibraryIcon),FRIENDS("Друзья",FriendsMusicIcon),DOWNLOADS("Загрузки",DownloadIcon)
}
internal enum class LibraryCategory(val label: String,val icon: ImageVector) {
    TRACKS("Треки",LibraryNoteIcon),PLAYLISTS("Плейлисты",PlaylistIcon),
    ALBUMS("Альбомы",RecordIcon),ARTISTS("Исполнители",Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicLibraryScaffold(title: String,destination: MusicDestination,onDestination: (MusicDestination)->Unit,
    queue: ExportSnapshot,onDownloads: ()->Unit,onAbout: ()->Unit,onBack: ()->Unit,showBack: Boolean=false,
    menuItems: @Composable ColumnScope.(()->Unit)->Unit={},tag: String="native_music_screen",prominentTitle: Boolean=false,listIdentity: String?=null,content: LazyListScope.()->Unit) {
    BackHandler(onBack=onBack)
    Scaffold(Modifier.fillMaxSize().testTag(tag),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(title,style=if(prominentTitle)MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),
            navigationIcon={if(showBack)IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}},
            actions={
                IconButton(onClick=onDownloads,modifier=Modifier.testTag("open_music_downloads")) {
                    BadgedBox(badge={if(queue.pending>0)Badge{Text(if(queue.pending>99)"99+" else queue.pending.toString())}}){Icon(DownloadIcon,"Скачивания")}
                }
                LatestReportMenu(onAbout=onAbout,extraItems=menuItems)
            })},
        bottomBar={NavigationBar(containerColor=MaterialTheme.colorScheme.background,tonalElevation=0.dp) {
            listOf(MusicDestination.LIBRARY,MusicDestination.SAVED,MusicDestination.FRIENDS).forEach{item->NavigationBarItem(selected=destination==item,onClick={onDestination(item)},
                icon={Icon(item.icon,null)},label={Text(item.label,maxLines=1)},modifier=Modifier.testTag("music_nav_${item.name}"),
                colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.secondaryContainer))}
        }}) {padding->
        key(listIdentity) {LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(tag+"_list"),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(if(destination==MusicDestination.FRIENDS)8.dp else 12.dp),content=content)}
    }
}

@Composable
internal fun MusicConnectionCard(configured: Boolean,session: MusicSessionState,busy: Boolean,onClick: ()->Unit) {
    val expired=session.expiresAtMs?.let{it<=System.currentTimeMillis()}==true
    val needsLogin=session.status==SessionStatus.LOGIN_REQUIRED||expired
    val active=configured&&!needsLogin
    Surface(onClick=onClick,shape=RoundedCornerShape(20.dp),color=if(active)MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier=Modifier.fillMaxWidth().testTag("music_connection")) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_vk_logo),contentDescription="ВКонтакте",modifier=Modifier.size(44.dp).testTag("vk_connection_logo"))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(if(configured)"ВК подключён" else "Подключить ВК",fontWeight=FontWeight.SemiBold)
                Text(when {
                    busy->"Выполняется операция"
                    needsLogin->"Нужно войти заново"
                    session.status==SessionStatus.RENEWING->"Обновляем доступ…"
                    session.status==SessionStatus.RETRY->"Продление ожидает повторной попытки"
                    configured->"Доступ к музыке · управление"
                    else->"Войти и открыть свою музыку"
                },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(active)Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
            Icon(MusicChevronIcon,null)
        }
    }
}

@Composable
internal fun MusicCategoryGrid(category: LibraryCategory,counts: Map<LibraryCategory,Int?>,enabled: Boolean,onCategory:(LibraryCategory)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        LibraryCategory.entries.chunked(2).forEach{pair->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            pair.forEach{item->
                val selected=category==item
                Surface(onClick={onCategory(item)},enabled=enabled,shape=RoundedCornerShape(18.dp),
                    color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier=Modifier.weight(1f).testTag("library_category_${item.name}")) {
                    Row(Modifier.padding(horizontal=12.dp,vertical=12.dp).heightIn(min=38.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Surface(color=if(selected)MaterialTheme.colorScheme.onPrimary.copy(alpha=.14f)else MaterialTheme.colorScheme.surface,
                            shape=RoundedCornerShape(12.dp)) {Box(Modifier.size(38.dp),contentAlignment=Alignment.Center){Icon(item.icon,null,Modifier.size(23.dp))}}
                        Column(Modifier.weight(1f)) {
                            Text(item.label,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(counts[item]?.toString()?:"—",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }}
    }
}

private object MusicThumbnailCache {
    val gate=Semaphore(3)
    val images=object: android.util.LruCache<String,ImageBitmap>(6*1024*1024) {
        override fun sizeOf(key: String,value: ImageBitmap)=value.width*value.height*4
    }
    val client=NetworkClients.api()
}

/** Only visible rows load thumbnails. Signed image addresses live in memory, never in the saved-file index. */
@Composable
internal fun MusicThumbnail(url: String?=null,uri: String?=null,identity: String=uri?:url.orEmpty(),artist: Boolean=false,size: androidx.compose.ui.unit.Dp=if(artist)64.dp else 52.dp) {
    val context=LocalContext.current
    val key=identity+":"+(uri?:url.orEmpty())
    val bitmap by produceState<ImageBitmap?>(MusicThumbnailCache.images.get(key),key) {
        if(value==null&&(url!=null||uri!=null))withContext(Dispatchers.IO) {
            MusicThumbnailCache.gate.withPermit {
                try {
                    val bytes=if(uri!=null) {
                        val reader=MediaMetadataRetriever()
                        try{reader.setDataSource(context,Uri.parse(uri));reader.embeddedPicture}finally{reader.release()}
                    }else {
                        val address=ArtworkAddress.require(url!!)
                        val response=MusicThumbnailCache.client.newCall(Request.Builder().url(address).header("User-Agent","VK-Music-Export-Artwork/1").build()).readBounded(2*1024*1024)
                        if(response.status==200)response.bytes else null
                    }
                    bytes?.let{raw->
                        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,info)
                        if(info.outWidth in 1..4096&&info.outHeight in 1..4096&&info.outWidth.toLong()*info.outHeight<=16_000_000) {
                            info.inJustDecodeBounds=false;info.inSampleSize=1
                            while(maxOf(info.outWidth,info.outHeight)/info.inSampleSize>160)info.inSampleSize*=2
                            BitmapFactory.decodeByteArray(raw,0,raw.size,info)?.asImageBitmap()?.let{image->MusicThumbnailCache.images.put(key,image);value=image}
                        }
                    }
                }catch(e: CancellationException){throw e}catch(_: Exception){/* A missing image never blocks music navigation. */}
            }
        }
    }
    Box(Modifier.size(size).clip(if(artist)CircleShape else RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(if(artist)Icons.Default.Person else LibraryNoteIcon,null,tint=MaterialTheme.colorScheme.onSecondaryContainer,modifier=Modifier.size(26.dp))
    }
}

@Composable
internal fun MusicTrackRow(track: NativeTrack,position: Int,preview: String?=null,saved: PublishedAudio?=null,downloading: Boolean=false,
    enabled: Boolean=true,onDownload:(()->Unit)?=null,onRemove:(()->Unit)?=null,onOpen:(()->Unit)?=null,tag: String="music_track_${position}_${track.id}") {
    var menu by remember{mutableStateOf(false)}
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start=12.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            MusicThumbnail(url=preview,uri=saved?.uri,identity=saved?.let{it.uri+it.sha256}?:track.id)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(track.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                track.subtitle?.takeIf{it.isNotBlank()}?.let{subtitle->
                    Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                        Text(subtitle,Modifier.padding(horizontal=6.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Text(track.artist,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(track.restricted||track.duration==0)Text("Ограничение ВК",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            }
            Text("${track.duration/60}:${(track.duration%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                downloading->CircularProgressIndicator(Modifier.size(28.dp).padding(3.dp),strokeWidth=3.dp)
                saved!=null->IconButton(enabled=onOpen!=null,onClick={onOpen?.invoke()},modifier=Modifier.testTag("saved_copy_${position}_${track.id}")){
                    Icon(Icons.Default.CheckCircle,"Есть копия на устройстве",tint=MaterialTheme.colorScheme.tertiary)}
                onDownload!=null->IconButton(enabled=enabled,onClick=onDownload){Icon(DownloadIcon,"Скачать этот трек")}
            }
            if(onDownload!=null||onRemove!=null||onOpen!=null)Box {
                IconButton(onClick={menu=true},modifier=Modifier.testTag("track_menu_${position}_${track.id}")){Icon(Icons.Default.MoreVert,"Действия с треком")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    onOpen?.let{DropdownMenuItem(text={Text("Открыть файл")},onClick={menu=false;it()})}
                    onDownload?.let{DropdownMenuItem(enabled=enabled,text={Text("Скачать этот трек")},onClick={menu=false;it()})}
                    onRemove?.let{DropdownMenuItem(enabled=enabled,text={Text("Удалить из музыки ВК…")},modifier=Modifier.testTag("remove_from_vk"),onClick={menu=false;it()})}
                }
            }
        }
    }
}

@Composable
internal fun MusicCollectionRow(title: String,detail: String,enabled: Boolean=true,artist: Boolean=false,photo: String?=null,tag: String,onClick:()->Unit) {
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,
        modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            MusicThumbnail(url=photo,artist=artist)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(MusicChevronIcon,null)
        }
    }
}
