package app.alcovaibe.vkmusicexport.music

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.AppPage
import app.alcovaibe.vkmusicexport.LazyAppPage
import app.alcovaibe.vkmusicexport.SecureCredentialContent
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeMusicPanel(model: NativeMusicViewModel,visible: Boolean,onDismiss:()->Unit,onUpdates:()->Unit={}) {
    if(!visible)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle,model) {
        // Adding an observer to a resumed owner immediately replays ON_RESUME.
        // The initial entry already loads the library; revalidate tracks and collections on later returns.
        var skipInitialResume=lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME) {
                model.refreshSavedLibrary()
                if(skipInitialResume)skipInitialResume=false
                else if(model.state.value.probe!=null&&!model.state.value.busy)model.refreshOnResume()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer)}
    }
    val state by model.state.collectAsState()
    val friendState by model.friends.state.collectAsState()
    val session by model.sessionState.collectAsState()
    val removal by model.removal.collectAsState()
    val artwork by model.artwork.collectAsState()
    val trackMetadata by model.trackMetadata.collectAsState()
    val lookup by model.artworkLookup.collectAsState()
    val metadataLookup by model.metadataLookup.collectAsState()
    val duplicates by model.duplicates.collectAsState()
    val savedFiles by model.savedFiles.collectAsState()
    val availability by model.availability.collectAsState()
    val legacy by LegacyArchiveRuntime.state.collectAsState()
    var legacySelection by remember{mutableStateOf<String?>(null)}
    var legacyMessage by remember{mutableStateOf<String?>(null)}
    val legacyExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val name=legacySelection;legacySelection=null
        if(uri!=null&&name!=null)scope.launch {
            try{withContext(Dispatchers.IO){LegacyArchiveMigration.export(context,name,uri)};legacyMessage="Копия сохранена. Старый файл остаётся в приложении."}
            catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(_: Exception){legacyMessage="Не удалось завершить копирование. Старый файл сохранён; проверь место и повтори."}
        }
    }
    val backgroundCheck=duplicates.phase in setOf(DuplicatePhase.QUEUED,DuplicatePhase.SCANNING)
    val savedFilePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)model.cancelSavedFileSelection()else model.previewSavedFile(uri)
    }
    var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){clock=System.currentTimeMillis();kotlinx.coroutines.delay(30_000)}}
    val playback by PlaybackRuntime.state.collectAsState()
    val playerExpanded by PlaybackRuntime.expanded.collectAsState()
    val queue by AudioExportRuntime.state.collectAsState()
    val queueWorking=queue.status in setOf("RUNNING","QUEUED")
    val canPrepare=!state.busy&&!backgroundCheck&&!queue.unfinished&&!queueWorking
    val canPrepareLibrary=canPrepare&&state.probe!=null&&state.readSessionId!=null&&state.localPlayback==null
    var notifications by remember{mutableStateOf(android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=it}
    fun startMaintenance(action: ()->Unit) {
        if(!notifications)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        action()
    }
    var destination by rememberSaveable {mutableStateOf(MusicDestination.LIBRARY)}
    var downloadsOrigin by rememberSaveable {mutableStateOf(MusicDestination.LIBRARY)}
    var downloadsTab by rememberSaveable {mutableStateOf(DownloadsTab.QUEUE)}
    var downloadsOffset by rememberSaveable {mutableIntStateOf(0)}
    val downloadsScroll=rememberLazyListState()
    // Warm the friends list after a successful VK connection; keep the manual refresh available.
    LaunchedEffect(state.probe?.owner,state.initialized,state.configured,state.busy,friendState.friendsComplete,friendState.busy,friendState.listProblem) {
        if(state.probe!=null&&state.initialized&&state.configured&&!state.busy&&!friendState.friendsComplete&&!friendState.busy&&friendState.listProblem==null)model.friends.loadFriends()
    }
    val friendsRootScroll=rememberLazyListState()
    LaunchedEffect(destination,friendState.user?.id,friendsRootScroll) {
        if(destination==MusicDestination.FRIENDS&&friendState.user==null) {
            snapshotFlow {
                friendsRootScroll.layoutInfo.visibleItemsInfo.mapNotNull{item->
                    (item.key as? String)?.takeIf{it.startsWith("friend_")}
                        ?.removePrefix("friend_")?.toLongOrNull()
                }
            }.distinctUntilChanged().collect{model.friends.prioritizeVisible(it)}
        }else model.friends.prioritizeVisible(emptyList())
    }
    val friendQueueItems by produceState<Map<String,String>>(emptyMap(),destination,queue.queueId,queue.total,queue.done,queue.failed,queue.pending,queue.status) {
        if(destination==MusicDestination.FRIENDS)value=withContext(Dispatchers.IO){runCatching{AudioExportRuntime.taskStates(context)}.getOrDefault(emptyMap())}
    }
    var category by rememberSaveable {mutableStateOf(LibraryCategory.TRACKS)}
    LaunchedEffect(state.catalogSection) {if(category!=LibraryCategory.ARTISTS)category=when(state.catalogSection){CatalogSection.LIBRARY->LibraryCategory.TRACKS;CatalogSection.ALBUMS->LibraryCategory.ALBUMS;else->LibraryCategory.PLAYLISTS}}
    val searchState by model.musicSearch.collectAsState()
    var searchOpen by rememberSaveable {mutableStateOf(false)}
    DisposableEffect(lifecycle,model,searchOpen,playerExpanded) {
        model.setSearchActive(searchOpen&&!playerExpanded&&lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer=LifecycleEventObserver{_,event->when(event) {
            Lifecycle.Event.ON_RESUME->model.setSearchActive(searchOpen&&!playerExpanded)
            Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP->model.setSearchActive(false)
            else->Unit
        }}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);model.setSearchActive(false)}
    }
    var aboutOpen by rememberSaveable {mutableStateOf(false)}
    var accessOpen by rememberSaveable {mutableStateOf(false)}
    var toolsOpen by rememberSaveable {mutableStateOf(false)}
    DisposableEffect(lifecycle,model,destination,playerExpanded,aboutOpen,accessOpen,searchOpen) {
        model.friends.setPreviewVisible(destination==MusicDestination.FRIENDS&&!playerExpanded&&!aboutOpen&&!accessOpen&&!searchOpen)
        val observer=LifecycleEventObserver{_,event->when(event) {
            Lifecycle.Event.ON_RESUME->model.friends.setForeground(true)
            Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP->model.friends.setForeground(false)
            else->Unit
        }}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);model.friends.setPreviewVisible(false)}
    }
    val sortPreferences=remember(context){context.getSharedPreferences("music_sort",android.content.Context.MODE_PRIVATE)}
    val sortContext=if(state.selected!=null)"collection_tracks" else category.name
    val sortChoices=MusicLibrarySorting.choices(
        if(state.selected!=null||category==LibraryCategory.TRACKS)state.page?.tracks.orEmpty() else null,
        additionOrder=state.selected==null&&category==LibraryCategory.TRACKS)
    var selectedSort by rememberSaveable(sortContext) {mutableStateOf(
        runCatching{MusicLibrarySort.valueOf(sortPreferences.getString(sortContext,null).orEmpty())}
            .getOrDefault(MusicLibrarySort.DEFAULT))}
    val sort=selectedSort.takeIf{it in sortChoices}?:MusicLibrarySort.DEFAULT
    var onlyPendingCollections by rememberSaveable {mutableStateOf(false)}
    var sortOpen by rememberSaveable {mutableStateOf(false)}
    var openError by remember {mutableStateOf(false)}
    val maintenanceNavigation by model.maintenanceNavigation.collectAsState()
    var handledMaintenanceNavigation by rememberSaveable{mutableLongStateOf(0L)}
    LaunchedEffect(maintenanceNavigation) {
        if(maintenanceNavigation>0&&maintenanceNavigation!=handledMaintenanceNavigation){handledMaintenanceNavigation=maintenanceNavigation;if(model.downloadsOpen.value)model.dismissDownloads();aboutOpen=false;accessOpen=false;toolsOpen=false;destination=MusicDestination.SAVED}
    }
    val downloadsOpen by model.downloadsOpen.collectAsState()
    val downloadsPage by model.downloadsPage.collectAsState()
    var presentationWasOpen by remember{mutableStateOf(downloadsOpen)}
    LaunchedEffect(downloadsOpen) {
        if(downloadsOpen) {
            searchOpen=false
            if(destination!=MusicDestination.DOWNLOADS)downloadsOrigin=destination
            aboutOpen=false;accessOpen=false;destination=MusicDestination.DOWNLOADS
        }else if(presentationWasOpen&&destination==MusicDestination.DOWNLOADS)destination=downloadsOrigin
        presentationWasOpen=downloadsOpen
    }
    // rememberSaveable restores the route after process recreation, independently of a new ViewModel.
    LaunchedEffect(Unit){if(destination==MusicDestination.DOWNLOADS&&!downloadsOpen)model.showDownloads()}
    LaunchedEffect(downloadsTab,downloadsOffset,queue.queueId) {
        model.selectDownloadsPage(downloadsTab,downloadsOffset)
        downloadsScroll.scrollToItem(0)
    }
    if(aboutOpen) {
        app.alcovaibe.vkmusicexport.AboutAppPage(onBack={aboutOpen=false})
        return
    }
    if(!state.stopping && state.authPhase in setOf(DeviceAuthPhase.ENTER_CODE,DeviceAuthPhase.VALIDATING_CODE)) {
        DeviceConfirmationScreen(state,model::submitAuthCode,model::cancel,{model.openAuthChrome(context)})
        return
    }
    val artists by model.artists.collectAsState()
    val unsortedLocalFiles by model.savedLibrary.collectAsState()
    val savedPreferences=remember(context){context.getSharedPreferences("saved_music",android.content.Context.MODE_PRIVATE)}
    var savedSort by remember {mutableStateOf(runCatching{SavedMusicSort.valueOf(savedPreferences.getString("sort",null).orEmpty())}.getOrDefault(SavedMusicSort.NEWEST))}
    var savedSortOpen by remember {mutableStateOf(false)}
    val localFiles by produceState(initialValue=emptyList<SavedLibraryEntry>(),unsortedLocalFiles,savedSort) {
        value=withContext(Dispatchers.Default){SavedMusicSorting.sorted(unsortedLocalFiles,savedSort)}
    }
    val localError by model.savedLibraryError.collectAsState()

    LaunchedEffect(state.initialized,state.busy) {if(state.initialized&&state.configured&&(state.probe==null||state.readSessionId==null)&&!state.busy&&state.error==null)model.openInitialMusic()}
    LaunchedEffect(state.stage) {if(state.stage=="browser_auth")accessOpen=true}
    val friendTasks=remember(friendState.page,friendState.user,friendState.playlist,friendState.viewerAccount) {
        val page=friendState.page;val user=friendState.user;val viewer=friendState.viewerAccount
        if(page!=null&&user!=null&&viewer!=null&&friendState.playlist?.kind!=PlaylistKind.UNKNOWN)FriendMusicExport.references(page,user,viewer,friendState.playlist)else emptyList()
    }
    val savedById=remember(localFiles,state.probe?.owner){localFiles.filter{it.present&&it.file.source?.account==state.probe?.owner}.associateBy{it.file.track.id}}
    val friendSavedById=remember(localFiles,friendState.viewerAccount){
        val viewer=friendState.viewerAccount
        if(viewer==null)emptyMap()else localFiles.filter{it.present&&it.file.source?.account==viewer}.associate{it.file.track.id to it.file}
    }
    val savedPlayback=remember(localFiles){PlaybackLists.saved(localFiles)}
    val savedListeningAllowed=SavedPlaybackPolicy.allowed(state)
    fun openFile(row: PublishedAudio) {
        if(!savedListeningAllowed)return
        val index=savedPlayback.indexOfFirst{it.saved?.uri==row.uri}
        if(index>=0)PlaybackRuntime.play(context,savedPlayback,index)
        else PlaybackRuntime.play(context,listOf(PlaybackEntry(row.track,"Сохранённая музыка",saved=row)))
    }
    fun choose(value: LibraryCategory) {
        if(state.busy||backgroundCheck)return
        if(category==LibraryCategory.ARTISTS&&value!=category)model.pauseArtists()
        category=value
        when(value) {
            LibraryCategory.TRACKS->model.section(CatalogSection.LIBRARY)
            LibraryCategory.PLAYLISTS->model.section(CatalogSection.PLAYLISTS)
            LibraryCategory.ALBUMS->model.section(CatalogSection.ALBUMS)
            LibraryCategory.ARTISTS->model.showArtists()
        }
    }
    fun back() {
        when {
            accessOpen->accessOpen=false
            destination==MusicDestination.DOWNLOADS->{destination=downloadsOrigin;model.dismissDownloads()}
            destination==MusicDestination.FRIENDS&&friendState.user!=null->model.friends.back()
            destination!=MusicDestination.LIBRARY->destination=MusicDestination.LIBRARY
            category==LibraryCategory.ARTISTS&&artists.album!=null->model.backFromArtist()
            category==LibraryCategory.ARTISTS&&artists.selected!=null->model.backFromArtist()
            state.selected!=null->model.section(state.catalogSection)
            else->{model.stopForWindow();onDismiss()}
        }
    }
    val playbackSnapshot=state;val playbackSort=sort;val friendPlaybackSnapshot=friendState
    fun playLibrary(index: Int=-1) {
        if(playbackSnapshot.localPlayback!=null&&!SavedPlaybackPolicy.allowed(model.state.value))return
        PlaybackRuntime.prepare(context,index){PlaybackLists.library(playbackSnapshot,playbackSort)}
    }
    val canPlayLibrary=state.localPlayback?.let{SavedPlaybackPolicy.allowed(state)&&it.rows.isNotEmpty()}
        ?:(state.page?.tracks?.any{it.duration>0&&!it.restricted&&it.metadataError==null}==true&&state.readSessionId!=null)
    Box(Modifier.fillMaxSize()) {
    if(accessOpen)MusicAccessManagementScreen(state,session,clock,backgroundCheck,queueWorking,duplicates.progress.stage,
        onBack={accessOpen=false},onCheck=model::checkAccess,onConnect=model::authenticate,
        onConfirm={model.openAuthChrome(context)},onForget=model::forget,onCancel=model::cancel)
    else if(searchOpen)MusicSearchScreen(searchState,model::searchMusic,model::retryMusicSearch,model::moreMusicSearch,
        onBack={searchOpen=false},onPlay={model.playSearchTrack(searchState,it)},
        onDownload={model.downloadSearchTrack(searchState,it)},canDownload=canPrepare,
        playback=playback,onPlayer={PlaybackRuntime.expanded.value=true})
    else MusicLibraryScaffold(
        title=when(destination) {
            MusicDestination.SAVED->"Сохранённое"
            MusicDestination.DOWNLOADS->"Загрузки"
            MusicDestination.FRIENDS->if(friendState.playlist!=null)when(friendState.playlist?.kind){PlaylistKind.ALBUM->"Альбом";PlaylistKind.UNKNOWN->"Подборка";else->"Плейлист"} else if(friendState.user!=null)"Профиль друга" else "Друзья"
            else->if(category==LibraryCategory.ARTISTS)artists.album?.title?:artists.selected?.name?:"Мои исполнители" else state.selected?.title?:"Библиотека"
        },destination=destination,onDestination={if(model.downloadsOpen.value)model.dismissDownloads();destination=it},queue=queue,onDownloads=model::showDownloads,onAbout={aboutOpen=true},
        prominentTitle=destination==MusicDestination.FRIENDS&&friendState.user==null,
        playback=playback,onPlayerOpen={PlaybackRuntime.expanded.value=true},
        tag=if(destination==MusicDestination.DOWNLOADS)"downloads_screen" else "native_music_screen",
        listIdentity=if(destination==MusicDestination.DOWNLOADS)"downloads:${queue.queueId}:$downloadsTab:$downloadsOffset" else if(destination==MusicDestination.FRIENDS)"friends:${friendState.user?.id}:${friendState.playlist?.key}" else null,
        listState=if(destination==MusicDestination.DOWNLOADS)downloadsScroll else if(destination==MusicDestination.FRIENDS&&friendState.user==null)friendsRootScroll else null,
        onBack=::back,showBack=destination==MusicDestination.DOWNLOADS||destination==MusicDestination.FRIENDS&&friendState.user!=null||destination==MusicDestination.LIBRARY&&(state.selected!=null||category==LibraryCategory.ARTISTS&&artists.selected!=null),
        menuItems={closeMenu->
            DropdownMenuItem(text={Text("Поиск музыки")},modifier=Modifier.testTag("open_music_search"),onClick={closeMenu();searchOpen=true})
            DropdownMenuItem(text={Text(if(destination==MusicDestination.FRIENDS)"Обновить" else "Обновить библиотеку")},enabled=state.configured&&!state.busy&&!backgroundCheck&&!friendState.busy,onClick={closeMenu();if(destination==MusicDestination.FRIENDS)model.friends.refresh()else model.checkAccess()})
            DropdownMenuItem(text={Text("Обновления приложения")},modifier=Modifier.testTag("open_app_updates"),onClick={closeMenu();if(destination==MusicDestination.DOWNLOADS){destination=downloadsOrigin;model.dismissDownloads()};onUpdates()})
            HorizontalDivider()
        }) {
        if(destination==MusicDestination.FRIENDS) {
            friendMusicItems(friendState,model.friends,state.configured,canPrepare,queue,friendQueueItems,friendTasks,friendSavedById,model::previewArtwork,
                onPause=model::pauseQueue,onDownloads=model::showDownloads,
                onOpenVk={user->runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(user.vkUrl)))}.onFailure{openError=true}},onConnect={accessOpen=true},onPlay={index->PlaybackRuntime.prepare(context,index){PlaybackLists.friends(friendPlaybackSnapshot)}})
        }else if(destination==MusicDestination.DOWNLOADS) {
            downloadsItems(queue,downloadsPage,downloadsTab,state.busy||backgroundCheck||friendState.preparing,state.error,
                onTab={downloadsTab=it;downloadsOffset=0},onPage={downloadsOffset=it},onReload=model::retryDownloadsPage,
                onPause=model::pauseQueue,onResume={model.resumeQueue()},onRetry={model.resumeQueue(true)},onClear=model::clearQueue,
                onReport={model.shareExportReport(context)},onSourceRetry={issue,identity->model.retrySource(issue,identity)},
                onOpenSaved=::openFile,canOpenSaved=savedListeningAllowed)
        }else if(destination==MusicDestination.SAVED) {
            item("saved_tools") {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Music/VK Music Export",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Инструменты",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled=state.initialized&&!state.busy&&!backgroundCheck&&!queueWorking,onClick=model::showArtwork,
                    modifier=Modifier.fillMaxWidth().testTag("saved_artwork")){Text("Добавить обложки к сохранённым")}
                OutlinedButton(enabled=state.initialized&&!state.busy&&!backgroundCheck&&!queueWorking,onClick=model::showTrackMetadata,
                    modifier=Modifier.fillMaxWidth().testTag("saved_track_metadata")){Text("Обновить названия и пометки")}
                OutlinedButton(enabled=state.initialized&&!state.busy&&!backgroundCheck&&!queueWorking,onClick=model::showSavedFiles,
                    modifier=Modifier.fillMaxWidth().testTag("saved_file_recovery")){Text("Проверить сохранённые файлы")}
                Text("Файлы: ${localFiles.size}",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={savedSortOpen=true},modifier=Modifier.testTag("saved_sort")){Text(savedSort.title)}
                Text("Копии остаются здесь после удаления музыки из ВК. Проверка целостности доступна в инструментах.",style=MaterialTheme.typography.bodySmall)
                if(localError)Text("Не удалось прочитать список файлов. Повтори обновление.",color=MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick=model::refreshSavedLibrary,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),
                        modifier=Modifier.weight(1f).fillMaxHeight().testTag("refresh_saved_music")) {
                        Icon(Icons.Default.Refresh,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Обновить сохранённое",maxLines=2)
                    }
                    FilledTonalButton(onClick={PlaybackRuntime.play(context,savedPlayback)},enabled=savedPlayback.isNotEmpty()&&savedListeningAllowed,shape=RoundedCornerShape(14.dp),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),modifier=Modifier.weight(1f).fillMaxHeight().testTag("play_saved_music")) {
                        Icon(PlayerPlayIcon,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Воспроизвести",maxLines=2)
                    }
                }
                if(localFiles.isEmpty()&&!localError)Text("Скачанная музыка появится здесь.")
            }}
            itemsIndexed(localFiles,key={_,row->"saved_${row.file.uri}"}){i,entry->
                val row=entry.file
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    MusicTrackRow(row.track,i+1,saved=row.takeIf{entry.playable},onOpen=if(entry.playable&&savedListeningAllowed)({openFile(row)})else null)
                    if(entry.availability==SavedAvailability.UNKNOWN)Text("Доступность проверяется в фоне. Можно запустить трек.",style=MaterialTheme.typography.bodySmall)
                    if(!entry.playable)Text("${row.displayName}: файл недоступен или размер изменился. Открой проверку сохранённых файлов.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
            if(queue.recent.isNotEmpty())item("recent") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                RecentFilesHeading(enabled=!state.busy&&!backgroundCheck,onClear=model::clearRecentFiles)
                queue.recent.forEach{row->TextButton(enabled=savedListeningAllowed,onClick={openFile(row)}){Text(row.displayName)}}
            }}
            if(legacy.files.isNotEmpty())item("legacy") {Column {
                Text("Сохранения старой версии",style=MaterialTheme.typography.titleMedium)
                legacy.message?.let{Text(it)};legacyMessage?.let{Text(it)}
                legacy.files.forEach{file->TextButton(onClick={legacySelection=file.name;legacyExport.launch(file.name)}){Text("Сохранить копию: ${file.name}")}}
            }}
        }else {
            item("connection") {MusicConnectionCard(state.configured,session,state.busy){accessOpen=true}}
            item("categories") {MusicCategoryGrid(category,mapOf(
                LibraryCategory.TRACKS to state.probe?.library?.count,
                LibraryCategory.PLAYLISTS to state.catalog?.let{MusicCatalog.visible(state,CatalogSection.PLAYLISTS).size},
                LibraryCategory.ALBUMS to state.catalog?.let{MusicCatalog.visible(state,CatalogSection.ALBUMS).size},
                LibraryCategory.ARTISTS to artists.takeIf{it.loaded}?.artists?.size),!state.busy&&!backgroundCheck,::choose)}
            if(!notifications)item("notifications") {TextButton(onClick={notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Разрешить уведомления процессов")}}
            if(state.busy)item("busy") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(state.stopping)"Завершаем операцию…" else state.preparing?:when(state.stage){"artists"->"Загружаем подписки на исполнителей…";"artist_details"->"Загружаем музыку исполнителя…";"track_metadata"->"Обновляем названия и пометки в фоне…";"artwork"->"Добавляем обложки в фоне…";"saved_files_background"->"Проверяем сохранённые файлы в фоне…";else->"Обновляем библиотеку…"},style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!state.stopping,onClick=model::cancel){Text("Остановить")}
            }}
            state.error?.let{error->item("error"){Text("${error.label} Код: ${error.name}",color=MaterialTheme.colorScheme.error)}}
            if(category==LibraryCategory.ARTISTS) {
                item("artists_heading") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(artists.selected==null) {
                        Text("Музыкальные подписки ВК",style=MaterialTheme.typography.titleMedium)
                        TextButton(enabled=state.probe!=null&&!artists.busy,onClick={model.showArtists(true)}){Text("Обновить исполнителей")}
                        if(artists.loaded&&artists.artists.isEmpty())Text("В этом аккаунте нет подписок на исполнителей.")
                        if(!state.configured)Text("Подключи ВК, чтобы увидеть свои подписки.")
                    }else {
                        TextButton(enabled=!artists.busy,onClick={if(artists.album!=null)model.selectArtistAlbum(null)else model.selectArtist(null)}){Text(if(artists.album!=null)"К исполнителю" else "Мои исполнители")}
                    }
                    if(artists.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(artists.progress?:"Загружаем музыку исполнителей…")
                        TextButton(onClick=model::backFromArtist){Text("Остановить загрузку исполнителей")}
                    }
                    artists.message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    if(!artists.busy&&!artists.loaded&&artists.artists.isNotEmpty())Text("Получено: ${artists.artists.size}. Список загружен не полностью.",style=MaterialTheme.typography.bodySmall)
                    artists.error?.let{error->
                        Text(artists.errorDetails?:if(error==MusicError.TIMEOUT)"Загрузка остановлена по времени ожидания. ВК не завершил ответ за 45 секунд. Код приложения: TIMEOUT." else error.label,Modifier.testTag("artists_error_reason"),color=MaterialTheme.colorScheme.error)
                        if(artists.selected!=null)TextButton(enabled=!artists.busy,onClick={val album=artists.album;if(album!=null)model.selectArtistAlbum(album)else model.selectArtist(artists.selected)}){Text("Повторить загрузку")}
                    }
                }}
                if(artists.selected==null)itemsIndexed(artists.artists,key={_,artist->"artist_${artist.id}"}){_,artist->
                    MusicCollectionRow(artist.name,"В подписках",!artists.busy,artist=true,photo=artist.photo,tag="followed_artist_${artist.id}"){model.selectArtist(artist)}
                }else {
                    if(artists.album==null) {
                        artists.details?.let{details->
                            item("artist_albums_title"){Text("Альбомы и синглы · ${details.albums.size}",style=MaterialTheme.typography.titleMedium)}
                            itemsIndexed(details.albums,key={_,list->"artist_album_${list.key}"}){_,list->
                                MusicCollectionRow(list.title,"${list.count} треков",!artists.busy,tag="artist_album_${list.owner}_${list.id}"){model.selectArtistAlbum(list)}
                            }
                        }
                    }
                    val page=if(artists.album!=null)artists.albumTracks else artists.details?.tracks
                    page?.let{
                        item("artist_tracks_title"){Text("Треки · ${it.count}",style=MaterialTheme.typography.titleMedium)}
                        itemsIndexed(it.tracks,key={i,track->"artist_track_${i}_${track.id}"}){i,track->
                            val saved=savedById[track.id]?.file
                            val playable=artists.owner==state.probe?.owner&&artists.readSessionId!=null&&
                                artists.readSessionId==state.readSessionId&&track.metadataError==null&&!track.restricted&&track.duration>0
                            MusicTrackRow(track,i+1,preview=model.previewArtwork(track),saved=saved,
                                downloading=queue.status=="RUNNING"&&queue.account==artists.owner&&queue.currentTask?.track?.id==track.id,
                                enabled=canPrepare&&playable,
                                onDownload=if(artists.album?.kind!=PlaylistKind.UNKNOWN)({model.downloadArtistTrack(artists,i)})else null,
                                onOpen=saved?.let{({openFile(it)})},
                                onPlay=if(playable)({model.playArtistTrack(artists,i)})else saved?.let{({openFile(it)})})
                        }
                    }
                }
            }else {
                if(state.probe!=null||state.localCatalogAccount!=null)item("library_actions") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text(state.selected?.title?:if(category==LibraryCategory.TRACKS)state.probe?.library?.count?.let{"$it треков"}?:"Моя музыка ещё не проверена" else category.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(enabled=!state.busy,onClick={sortOpen=true},modifier=Modifier.testTag("library_sort")) {
                            Text(sort.title);Icon(Icons.Default.KeyboardArrowDown,"Выбрать порядок",Modifier.size(18.dp))
                        }
                        if(category==LibraryCategory.TRACKS)IconButton(onClick={toolsOpen=!toolsOpen},modifier=Modifier.testTag("library_tools")){Icon(Icons.Default.MoreVert,"Инструменты библиотеки")}
                    }
                    if(category==LibraryCategory.TRACKS) {
                        if(state.selected==null&&state.libraryRefreshing)
                            Text("Проверяем обновления треков…",style=MaterialTheme.typography.bodySmall,
                                modifier=Modifier.testTag("library_refreshing"))
                        if(state.selected==null&&state.libraryStale)
                            Text("Показан предыдущий список. Новая проверка ещё не завершилась.",
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier=Modifier.testTag("library_stale"))
                        if(toolsOpen) {
                            Button(enabled=canPrepareLibrary,onClick={model.prepare(libraryAlbums=false)},modifier=Modifier.fillMaxWidth()){Text("Скачать всю мою музыку")}
                            OutlinedButton(enabled=state.probe!=null&&!backgroundCheck&&(!state.busy||state.stage in setOf("catalog_refresh","resume_refresh")),onClick=model::showAvailability,modifier=Modifier.fillMaxWidth().testTag("check_unavailable")){Text("Проверить недоступные треки")}
                            OutlinedButton(enabled=!state.busy&&!backgroundCheck,onClick=model::showDuplicates,
                                modifier=Modifier.fillMaxWidth().testTag("reopen_duplicates")) {
                                Text("Дубликаты: проверка и результаты")
                            }
                            OutlinedButton(enabled=canPrepareLibrary,onClick={model.prepare(libraryAlbums=true)},modifier=Modifier.fillMaxWidth()){Text("Скачать мою музыку и группы по альбомам")}
                        }
                    }else {
                        if(state.selected!=null) {
                            TextButton(enabled=!state.busy,onClick={model.section(state.catalogSection)}){Text("Назад к подборкам")}
                            Button(enabled=canPrepare&&state.localPlayback==null&&state.readSessionId!=null,onClick={model.prepare()},modifier=Modifier.fillMaxWidth()){Text(if(state.selected?.kind==PlaylistKind.ALBUM)"Скачать альбом" else "Скачать плейлист")}
                        }else if(MusicCatalog.visible(state).isNotEmpty())Button(enabled=canPrepareLibrary,onClick={model.prepare(catalogKind=state.catalogSection.kind)},modifier=Modifier.fillMaxWidth().testTag("catalog_download_category")) {
                            Text(if(category==LibraryCategory.ALBUMS)"Скачать все альбомы" else "Скачать все плейлисты")
                        }
                        if(state.catalog==null)Text("Каталог ещё не загружен.",style=MaterialTheme.typography.bodySmall)
                        if(state.catalogRefreshing)Text("Обновляем плейлисты и альбомы…",
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("catalog_refreshing"))
                        if(state.catalogStale)Text("Показан предыдущий каталог. Проверка не завершена.",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.testTag("catalog_stale"))
                        if(state.catalogVerifying)Text("Проверяем скачанные подборки в фоне…",
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("catalog_verifying"))
                        state.catalog?.let{catalog->
                            val kind=when(category) {
                                LibraryCategory.ALBUMS->PlaylistKind.ALBUM
                                LibraryCategory.PLAYLISTS->PlaylistKind.PLAYLIST
                                else->null
                            }
                            if(kind!=null&&!catalog.completeFor(kind)&&!state.catalogRefreshing) {
                                val albumOnly=catalog.baseComplete&&catalog.optionalAlbumsIncomplete
                                val scan=if(albumOnly)catalog.albumsPagination else catalog.pagination
                                val counts=scan?.let{" Получено позиций: ${it.positions}/${it.expected}."}.orEmpty()
                                Text((if(albumOnly)"Не все альбомы подтверждены ответом ВК."
                                    else "Каталог подборок получен не полностью.")+
                                    "$counts Обнови каталог перед массовым скачиванием.",
                                    color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("catalog_incomplete_warning"))
                            }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=onlyPendingCollections,onCheckedChange={onlyPendingCollections=it},
                                modifier=Modifier.testTag("catalog_only_pending").semantics{contentDescription="Только ещё не скачанное"})
                            Text("Только ещё не скачанное",style=MaterialTheme.typography.bodySmall)
                        }
                        if(state.hiddenCatalog.isNotEmpty())Text("Проверенные офлайн-подборки: ${state.hiddenCatalog.size}",style=MaterialTheme.typography.bodySmall)
                        state.catalogError?.let{Text(it.label,color=MaterialTheme.colorScheme.error)}
                    }
                    if(toolsOpen||category!=LibraryCategory.TRACKS)Row {
                        TextButton(enabled=!state.busy&&!backgroundCheck,onClick=model::checkAccess){Text("Проверить музыку")}
                        TextButton(enabled=!state.busy&&!backgroundCheck,onClick=model::refreshCatalog){Text("Обновить каталог")}
                    }
                    state.localPlayback?.let{local->
                        Text("Локальная подборка · сохранённых позиций: "+local.rows.size+
                            ". Доступность файлов проверяется при воспроизведении. Состав ВК не подтверждён.",
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("local_collection_notice"))
                    }
                    if(category==LibraryCategory.TRACKS||state.selected!=null) {
                        FilledTonalButton(onClick={playLibrary()},enabled=canPlayLibrary,
                            shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("library_play")) {
                            Icon(PlayerPlayIcon,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Воспроизвести")
                        }
                    }
                }}
                if(category!=LibraryCategory.TRACKS&&state.selected==null) {
                    val lists=MusicLibrarySorting.collections(MusicCatalog.visible(state,onlyPending=onlyPendingCollections),sort)
                    itemsIndexed(lists,key={_,list->"catalog_${list.key}"}){_,list->
                        val localCount=state.localCounts[list.key]?:0
                        val status=when {
                            list.key in state.localCatalogKeys->"Сохранено позиций: $localCount · ВК не подтверждена"
                            list.key in state.hiddenCatalog->"Offline"
                            localCount in 1 until list.count->"Частично сохранено: $localCount/${list.count}"
                            localCount>0->"Сохранено позиций: $localCount · требуется проверка"
                            else->"В каталоге ВК"
                        }
                        MusicCollectionRow(list.title,"${list.count} треков · "+status,!state.busy,
                            tag="catalog_list_${list.owner}_${list.id}"){model.select(list)}
                    }
                    if(state.catalogSection==CatalogSection.UNKNOWN)item("unknown_note"){Text("ВК не сообщил тип этих подборок; ручной выбор не нужен.")}
                    if(MusicCatalog.visible(state,CatalogSection.UNKNOWN).isNotEmpty())item("unknown") {
                        TextButton(enabled=!state.busy,onClick={model.section(CatalogSection.UNKNOWN)}){Text("Подборки без типа")}
                    }
                }
                state.page?.let{page->
                    val rows=MusicLibrarySorting.tracks(page.tracks,sort)
                    if(page.tracks.size!=page.count)item("tracks_count"){Text("Показано ${page.tracks.size} из ${page.count}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    itemsIndexed(rows,key={_,entry->"track_${entry.index}_${entry.value.id}"}){displayIndex,entry->
                        val track=entry.value;val position=entry.index+1
                        val localRow=state.localPlayback?.takeIf{it.account==state.probe?.owner&&it.key==state.selected?.key}
                            ?.rows?.getOrNull(entry.index)?.takeIf{it.track==track}
                        val saved=localRow?:savedById[track.id]?.file
                        MusicTrackRow(track,position,preview=if(localRow==null)model.previewArtwork(track)else null,saved=saved,
                            downloading=queue.status=="RUNNING"&&queue.account==state.probe?.owner&&queue.currentTask?.track?.id==track.id,
                            enabled=canPrepare&&localRow==null,onDownload=if(localRow==null)({model.download(track,position)})else null,
                            onRemove=if(state.selected==null&&state.catalogSection==CatalogSection.LIBRARY)({model.inspectRemoval(track)})else null,
                            onOpen=if(localRow!=null)({playLibrary(displayIndex)})else saved?.let{({openFile(it)})},
                            onPlay=if(localRow!=null||state.readSessionId!=null&&track.metadataError==null&&!track.restricted&&track.duration>0)
                                ({playLibrary(displayIndex)})else saved?.let{({openFile(it)})})
                    }
                }
            }
            if(!state.configured)item("empty_library") {Text("Сохранённые файлы доступны без входа.",style=MaterialTheme.typography.bodyMedium)}
        }
    }
    if(duplicates.visible)DuplicatesSheet(duplicates,model::findDuplicates,model::requestDuplicateRemoval,model::confirmDuplicateRemoval,
        model::cancelDuplicateRemoval,model::skipDuplicate,model::pauseDuplicateCheck,model::dismissDuplicates)
    if(availability.visible)AvailabilitySheet(availability,model::checkUnavailableTracks,model::pauseAvailability,model::dismissAvailability)
    LibraryRemovalSheet(removal,model::confirmRemoval,model::dismissRemoval)
    if(savedFiles.visible)SavedFilesSheet(savedFiles,{startMaintenance{model.checkSavedFiles()}},{startMaintenance{model.checkSavedFiles(true)}},
        onSelect={row->model.selectSavedFile(row);savedFilePicker.launch(arrayOf("audio/*"))},
        onConfirm=model::confirmSavedFile,onForget=model::requestForgetFile,onConfirmForget=model::confirmForgetFile,
        onBack=model::backSavedFiles,onStop=model::cancel,onDismiss=model::dismissSavedFiles)
    if(artwork.visible&&!lookup.visible)ArtworkSheet(artwork,{startMaintenance{model.addSavedArtwork()}},model::cancel,model::dismissArtwork,model::showArtworkLookup)
    if(trackMetadata.visible&&!metadataLookup.visible)TrackMetadataSheet(trackMetadata,{startMaintenance{model.updateSavedTrackMetadata()}},model::cancel,model::dismissTrackMetadata,model::showMetadataLookup)
    if(metadataLookup.visible)TrackMetadataLookupSheet(metadataLookup,model::metadataLookupQuery,model::findMetadataReplacement,model::previewMetadataReplacement,
        model::confirmMetadataReplacement,model::backMetadataReplacement,model::cancel,model::dismissMetadataLookup)
    if(lookup.visible)ArtworkLookupSheet(lookup,model::artworkLookupQuery,model::findArtworkReplacement,model::previewArtworkReplacement,
        model::confirmArtworkReplacement,model::backArtworkReplacement,model::cancel,model::dismissArtworkLookup)
    if(savedSortOpen)ModalBottomSheet(onDismissRequest={savedSortOpen=false},modifier=Modifier.testTag("saved_sort_sheet")) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Сортировка сохранённого",style=MaterialTheme.typography.titleLarge)
            SavedMusicSort.entries.forEach{choice->
                TextButton(onClick={savedSort=choice;savedPreferences.edit().putString("sort",choice.name).apply();savedSortOpen=false},
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("saved_sort_${choice.name}")) {
                    Text(choice.title,Modifier.weight(1f));if(savedSort==choice)Icon(Icons.Default.Check,null)
                }
            }
        }
    }
    if(sortOpen)ModalBottomSheet(onDismissRequest={sortOpen=false},modifier=Modifier.testTag("library_sort_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
            Text("Сортировка",style=MaterialTheme.typography.titleLarge)
            sortChoices.forEach{choice->
                TextButton(onClick={selectedSort=choice;sortPreferences.edit().putString(sortContext,choice.name).apply();sortOpen=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("sort_${choice.name}")) {
                    Text(choice.title,modifier=Modifier.weight(1f))
                    if(sort==choice)Icon(Icons.Default.Check,null)
                }
            }
        }
    }
    if(openError)AlertDialog(onDismissRequest={openError=false},title={Text("Файл не открыт")},text={Text("Не удалось проверить файл или найти приложение для его воспроизведения. Проверь файл в папке Music/VK Music Export.")},confirmButton={TextButton(onClick={openError=false}){Text("Понятно")}})
    if(playerExpanded)PlaybackScreen(playback,onDismiss={PlaybackRuntime.expanded.value=false},onDownload=model::downloadPlayback,canDownload=canPrepare)
    }
}

@Composable
internal fun DeviceConfirmationScreen(state: NativeMusicState,onSubmit: (String)->Unit,onCancel: ()->Unit,onBrowser: ()->Unit) {
    var code by remember {mutableStateOf(TextFieldValue())}
    var fieldEpoch by remember {mutableIntStateOf(0)}
    val owner=LocalLifecycleOwner.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val submitting=state.authCodePending||state.authPhase==DeviceAuthPhase.VALIDATING_CODE
    fun clear(){code=TextFieldValue();fieldEpoch++;focus.clearFocus(force=true);keyboard?.hide()}
    fun cancel(){clear();onCancel()}
    fun submit(){if(!submitting&&code.text.length==6){val entered=code.text;clear();onSubmit(entered)}}
    DisposableEffect(owner,focus) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)clear()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);clear()}
    }
    SecureCredentialContent {
        AppPage("Код подключения ВК",onBack={cancel()},tag="native_auth_code_screen") {
            Text("ВК запросил подтверждение текущего подключения.",style=MaterialTheme.typography.titleMedium)
            Text("Введи 6 цифр, показанных на странице VK ID «Запрос входа с нового устройства», и нажми «Подтвердить код».")
            Text("Пароль и SMS-коды вводи только на странице ВК.",style=MaterialTheme.typography.bodySmall)
            // Recreate the sensitive input connection after clearing: an IME must not
            // retain its previous edit buffer/selection when the Activity resumes.
            key(fieldEpoch) {
            OutlinedTextField(value=code,onValueChange={next->if(next.text.length<=6&&next.text.all{it in '0'..'9'})code=next},
                label={Text("Код подключения · 6 цифр")},enabled=!submitting,
                modifier=Modifier.fillMaxWidth().testTag("native_auth_confirmation_code"),singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,autoCorrectEnabled=false,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={submit()}))
            }
            state.authCodeIssue?.let{Text(it.label,Modifier.testTag("native_auth_code_error"),color=MaterialTheme.colorScheme.error)}
            Button(enabled=code.text.length==6&&!submitting,onClick={submit()},modifier=Modifier.testTag("native_auth_submit_code")) {
                Text(if(submitting)"Проверяем код…" else "Подтвердить код")
            }
            if(submitting)LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled=!submitting,onClick={clear();onBrowser()}){Text("Показать страницу VK ID в браузере")}
            Text("Код относится только к этой заявке. При неверном коде можно повторить ввод; новая заявка автоматически не создаётся.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={cancel()},modifier=Modifier.testTag("native_auth_cancel_code")){Text("Отменить подключение")}
        }
    }
}

