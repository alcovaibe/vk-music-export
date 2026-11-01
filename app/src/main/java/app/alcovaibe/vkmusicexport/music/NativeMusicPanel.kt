package app.alcovaibe.vkmusicexport.music

import android.content.ClipData
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeMusicPanel(model: NativeMusicViewModel,visible: Boolean,onDismiss:()->Unit,onUpdates:()->Unit={}) {
    if(!visible)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle,model) {
        // Adding an observer to a resumed owner immediately replays ON_RESUME.
        // The home entry already refreshed the catalog; refresh again only on a later return.
        var skipInitialResume=lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME) {
                if(skipInitialResume)skipInitialResume=false
                else if(model.state.value.probe!=null&&!model.state.value.busy)model.refreshCatalog()
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
    val queue by AudioExportRuntime.state.collectAsState()
    val queueWorking=queue.status in setOf("RUNNING","QUEUED")
    val canPrepare=!state.busy&&!backgroundCheck&&!queue.unfinished&&!queueWorking
    var notifications by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=it}
    fun startMaintenance(action: ()->Unit) {
        if(!notifications)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        action()
    }
    var destination by rememberSaveable {mutableStateOf(MusicDestination.LIBRARY)}
    var downloadsReturn by rememberSaveable {mutableStateOf(MusicDestination.LIBRARY)}
    LaunchedEffect(destination,state.initialized,state.configured,state.busy) {
        if(destination==MusicDestination.FRIENDS&&state.initialized&&state.configured&&!state.busy&&!friendState.friendsComplete&&!friendState.busy&&friendState.user==null&&friendState.listProblem==null)model.friends.loadFriends()
    }
    val friendQueueItems by produceState<Map<String,String>>(emptyMap(),destination,queue.total,queue.done,queue.failed,queue.pending,queue.status) {
        if(destination==MusicDestination.FRIENDS)value=withContext(Dispatchers.IO){runCatching{AudioExportRuntime.taskStates(context)}.getOrDefault(emptyMap())}
    }
    var category by rememberSaveable {mutableStateOf(LibraryCategory.TRACKS)}
    LaunchedEffect(state.catalogSection) {if(category!=LibraryCategory.ARTISTS)category=when(state.catalogSection){CatalogSection.LIBRARY->LibraryCategory.TRACKS;CatalogSection.ALBUMS->LibraryCategory.ALBUMS;else->LibraryCategory.PLAYLISTS}}
    var aboutOpen by rememberSaveable {mutableStateOf(false)}
    var accessOpen by rememberSaveable {mutableStateOf(false)}
    var toolsOpen by rememberSaveable {mutableStateOf(false)}
    var ascending by rememberSaveable {mutableStateOf(false)}
    var configure by rememberSaveable {mutableStateOf(false)}
    var openError by remember {mutableStateOf(false)}
    val maintenanceNavigation by model.maintenanceNavigation.collectAsState()
    LaunchedEffect(maintenanceNavigation) {
        if(maintenanceNavigation>0){aboutOpen=false;accessOpen=false;configure=false;toolsOpen=false;destination=MusicDestination.SAVED}
    }
    if(aboutOpen) {
        app.alcovaibe.vkmusicexport.AboutAppPage(onBack={aboutOpen=false})
        return
    }
    if(configure) {
        NativeAccessScreen(onBack={configure=false},onSave={token,agent->model.configure(token,agent);configure=false})
        return
    }
    if(!state.stopping && state.authPhase in setOf(DeviceAuthPhase.ENTER_CODE,DeviceAuthPhase.VALIDATING_CODE)) {
        DeviceConfirmationScreen(state,model::submitAuthCode,model::cancel,{model.openAuthChrome(context)})
        return
    }
    val artists by model.artists.collectAsState()
    val localFiles by model.savedLibrary.collectAsState()
    val localError by model.savedLibraryError.collectAsState()
    LaunchedEffect(state.initialized,state.busy,queue.done,queue.status) {if(state.initialized&&!state.busy)model.refreshSavedLibrary()}
    LaunchedEffect(state.initialized,state.busy) {if(state.initialized&&state.configured&&state.probe==null&&!state.busy)model.openMusic()}
    LaunchedEffect(state.stage) {if(state.stage=="browser_auth")accessOpen=true}
    val friendTasks=remember(friendState.page,friendState.user,friendState.playlist,state.probe?.owner) {
        val page=friendState.page;val user=friendState.user;val viewer=state.probe?.owner
        if(page!=null&&user!=null&&viewer!=null)FriendMusicExport.tasks(page,user,viewer,friendState.playlist)else emptyList()
    }
    val savedById=remember(localFiles,state.probe?.owner){localFiles.filter{it.present&&it.file.source?.account==state.probe?.owner}.associateBy{it.file.track.id}}
    val friendSavedById=remember(savedById){savedById.mapValues{it.value.file}}
    fun openFile(row: PublishedAudio) {
        scope.launch {
            try {
                withContext(Dispatchers.IO){SharedAudioStore(context).requireValid(row)}
                val uri=Uri.parse(row.uri)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,row.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply{clipData=ClipData.newRawUri("Audio",uri)})
            }catch(e: kotlinx.coroutines.CancellationException){throw e}catch(_: Exception){openError=true}
        }
    }
    fun choose(value: LibraryCategory) {
        if(state.busy||backgroundCheck)return
        category=value;ascending=false
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
            destination==MusicDestination.DOWNLOADS->destination=downloadsReturn
            destination==MusicDestination.FRIENDS&&friendState.user!=null->model.friends.back()
            destination!=MusicDestination.LIBRARY->destination=MusicDestination.LIBRARY
            category==LibraryCategory.ARTISTS&&artists.album!=null->model.backFromArtist()
            category==LibraryCategory.ARTISTS&&artists.selected!=null->model.backFromArtist()
            state.selected!=null->model.section(state.catalogSection)
            else->{model.stopForWindow();onDismiss()}
        }
    }
    if(accessOpen)LazyAppPage("Доступ к музыке",onBack={accessOpen=false},tag="music_access_management") {
        item("access") {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Вход через Chrome",style=MaterialTheme.typography.titleMedium)
                Text("Открой заявку в Chrome. Если VK ID покажет код подключения из 6 цифр, вернись сюда: приложение откроет экран для этого кода. Если код не потребуется, результат будет проверен автоматически.")
                Text("Это внутренний протокол ВК. Веб-сессия может иметь права шире музыки, а музыкальный метод может быть недоступен. Новый доступ сохраняется только после успешной проверки музыки.",style=MaterialTheme.typography.bodySmall)
                Text("Удаление данных доступа завершает только локальный вход. Для отзыва сессии используй настройки аккаунта ВК. Музыка и очередь остаются.",style=MaterialTheme.typography.bodySmall)
                if(state.stage!="browser_auth")Button(enabled=state.initialized&&!state.busy&&!backgroundCheck,onClick=model::authenticate) {
                    Text(if(state.configured)"Подключить ВК заново через Chrome" else "Подключить ВК через Chrome")
                }
                state.authPhase?.let{Text(it.label,Modifier.testTag("native_auth_phase"))}
                if(state.busy&&state.stage=="browser_auth") {
                    if(!state.stopping && state.authPhase in setOf(DeviceAuthPhase.READY,DeviceAuthPhase.WAITING,DeviceAuthPhase.REQUEST_OPENED))
                        Button(onClick={model.openAuthChrome(context)}){Text("Открыть подтверждение ВК")}
                    Text("Срок заявки идёт и в Chrome: не более 5 минут. Проверка результата продолжается после возвращения в приложение.",style=MaterialTheme.typography.bodySmall)
                }
                state.authIssue?.let{Text(it.label+" Код: AUTH_"+it.name,color=MaterialTheme.colorScheme.error)}
                HorizontalDivider()
                if(!state.initialized)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(state.configured)"Доступ ВК сохранён на устройстве" else "Музыкальный доступ ВК не настроен")
                if(state.configured) {
                    session.accountId?.let{Text("Аккаунт ВК: $it",style=MaterialTheme.typography.bodySmall)}
                    val expiry=session.expiresAtMs
                    Text(when {
                        session.status==SessionStatus.RENEWING->"Обновляем доступ ВК…"
                        session.status==SessionStatus.LOGIN_REQUIRED->"Доступ нужно подключить заново через Chrome."
                        expiry==null->"ВК не сообщил срок действия этого токена."
                        expiry<=clock->"Срок токена истёк."
                        else->"Токен действует до "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(expiry))
                    },Modifier.testTag("vk_access_lifetime"),style=MaterialTheme.typography.bodySmall)
                    Text(if(session.renewable)"Включена автоматическая попытка продления. Если ВК отклонит её, понадобится новый вход."
                        else "Автопродление недоступно для этого входа. Новый вход через Chrome может подключить его.",style=MaterialTheme.typography.bodySmall)
                    if(session.status==SessionStatus.RETRY)Text("Не удалось продлить доступ. Прежние данные сохранены; приложение повторит попытку позже.",style=MaterialTheme.typography.bodySmall)
                }
                if(!state.configured)Button(enabled=state.initialized&&!state.busy&&!backgroundCheck,onClick={Diagnostics.button("native_music_access_dialog");configure=true}) {
                    Text("Настроить доступ ВК")
                }
                if(state.configured) {
                    OutlinedButton(enabled=!state.busy&&!backgroundCheck,onClick=model::checkAccess){Text("Проверить музыку")}
                }
                if(state.configured||state.accessDamaged)TextButton(enabled=!state.busy&&!backgroundCheck&&!queueWorking,onClick=model::forget){Text("Удалить только данные доступа ВК")}
                state.message?.let{Text(it)}
                state.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                if(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(state.stopping)"Завершаем предыдущую операцию…"
                    else if(state.waitingForSlot)"Ожидаем завершения предыдущей операции…"
                    else if(state.preparing!=null)state.preparing!!
                    else if(state.stage=="browser_auth")state.authPhase?.label?:"Ожидание входа ВК"
                    else if(state.stage.startsWith("duplicates_"))duplicates.progress.stage.ifEmpty{"Подготовка проверки дубликатов"}
                    else state.preparing?:"Проверка музыкального доступа")
                    if(state.stage!="browser_auth")Text("Держи приложение открытым. При уходе операция остановится; готовые файлы сохранятся.",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!state.stopping,onClick=model::cancel){Text("Остановить")}
                }
                TextButton(onClick={accessOpen=false}){Text("Вернуться в библиотеку")}
        }}
    }else MusicLibraryScaffold(
        title=when(destination) {
            MusicDestination.SAVED->"Сохранённое"
            MusicDestination.DOWNLOADS->"Загрузки"
            MusicDestination.FRIENDS->if(friendState.playlist!=null)if(friendState.playlist?.kind==PlaylistKind.ALBUM)"Альбом" else "Плейлист" else if(friendState.user!=null)"Профиль друга" else "Друзья"
            else->if(category==LibraryCategory.ARTISTS)artists.album?.title?:artists.selected?.name?:"Мои исполнители" else state.selected?.title?:"Библиотека"
        },destination=destination,onDestination={destination=it},queue=queue,onDownloads={downloadsReturn=destination;destination=MusicDestination.DOWNLOADS},onAbout={aboutOpen=true},
        prominentTitle=destination==MusicDestination.FRIENDS&&friendState.user==null,
        onBack=::back,showBack=destination==MusicDestination.DOWNLOADS||destination==MusicDestination.FRIENDS&&friendState.user!=null||destination==MusicDestination.LIBRARY&&(state.selected!=null||category==LibraryCategory.ARTISTS&&artists.selected!=null),
        menuItems={closeMenu->
            DropdownMenuItem(text={Text(if(destination==MusicDestination.FRIENDS)"Обновить" else "Обновить библиотеку")},enabled=state.configured&&!state.busy&&!backgroundCheck&&!friendState.busy,onClick={closeMenu();if(destination==MusicDestination.FRIENDS)model.friends.refresh()else model.checkAccess()})
            DropdownMenuItem(text={Text("Обновления приложения")},modifier=Modifier.testTag("open_app_updates"),onClick={closeMenu();onUpdates()})
            HorizontalDivider()
        }) {
        if(destination==MusicDestination.FRIENDS) {
            friendMusicItems(friendState,model.friends,state.configured,canPrepare,queue,friendQueueItems,friendTasks,friendSavedById,model::previewArtwork,
                onPause=model::pauseQueue,onDownloads={downloadsReturn=destination;destination=MusicDestination.DOWNLOADS},
                onOpenVk={user->runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(user.vkUrl)))}.onFailure{openError=true}},onConnect={accessOpen=true})
        }else if(destination==MusicDestination.DOWNLOADS) {
            item("queue") {DownloadProgressContent(queue,state.busy||backgroundCheck,state.error,
                onPause=model::pauseQueue,onResume={model.resumeQueue()},onRetry={model.resumeQueue(true)},onClear=model::clearQueue,
                onSourceRetry=model::retrySource,onExportReport={model.shareExportReport(context)})}
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
                Text("Копии остаются здесь после удаления музыки из ВК. Проверка целостности доступна в инструментах.",style=MaterialTheme.typography.bodySmall)
                if(localError)Text("Не удалось прочитать список файлов. Повтори обновление.",color=MaterialTheme.colorScheme.error)
                TextButton(onClick=model::refreshSavedLibrary){Text("Обновить сохранённое")}
                if(localFiles.isEmpty()&&!localError)Text("Скачанная музыка появится здесь.")
            }}
            itemsIndexed(localFiles,key={_,row->"saved_${row.file.uri}"}){i,entry->
                val row=entry.file
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    MusicTrackRow(row.track,i+1,saved=row.takeIf{entry.present},onOpen=if(entry.present)({openFile(row)})else null)
                    if(!entry.present)Text("${row.displayName}: файл недоступен или размер изменился. Открой проверку сохранённых файлов.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
            if(queue.recent.isNotEmpty())item("recent") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                RecentFilesHeading(enabled=!state.busy&&!backgroundCheck,onClear=model::clearRecentFiles)
                queue.recent.forEach{row->TextButton(onClick={openFile(row)}){Text(row.displayName)}}
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
                        TextButton(enabled=state.probe!=null&&!state.busy,onClick={model.showArtists(true)}){Text("Обновить исполнителей")}
                        if(artists.loaded&&artists.artists.isEmpty())Text("В этом аккаунте нет подписок на исполнителей.")
                        if(!state.configured)Text("Подключи ВК, чтобы увидеть свои подписки.")
                    }else {
                        TextButton(enabled=!state.busy,onClick={if(artists.album!=null)model.selectArtistAlbum(null)else model.selectArtist(null)}){Text(if(artists.album!=null)"К исполнителю" else "Мои исполнители")}
                    }
                    artists.message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    if(!state.busy&&!artists.loaded&&artists.artists.isNotEmpty())Text("Получено: ${artists.artists.size}. Список загружен не полностью.",style=MaterialTheme.typography.bodySmall)
                    artists.error?.let{error->
                        Text(artists.errorDetails?:if(error==MusicError.TIMEOUT)"Загрузка остановлена по времени ожидания. ВК не завершил ответ за 45 секунд. Код приложения: TIMEOUT." else error.label,Modifier.testTag("artists_error_reason"),color=MaterialTheme.colorScheme.error)
                        if(artists.selected!=null)TextButton(enabled=!state.busy,onClick={val album=artists.album;if(album!=null)model.selectArtistAlbum(album)else model.selectArtist(artists.selected)}){Text("Повторить загрузку")}
                    }
                }}
                if(artists.selected==null)itemsIndexed(artists.artists,key={_,artist->"artist_${artist.id}"}){_,artist->
                    MusicCollectionRow(artist.name,"В подписках",!state.busy,artist=true,photo=artist.photo,tag="followed_artist_${artist.id}"){model.selectArtist(artist)}
                }else {
                    if(artists.album==null) {
                        artists.details?.let{details->
                            item("artist_albums_title"){Text("Альбомы и синглы · ${details.albums.size}",style=MaterialTheme.typography.titleMedium)}
                            itemsIndexed(details.albums,key={_,list->"artist_album_${list.key}"}){_,list->
                                MusicCollectionRow(list.title,"${list.count} треков",!state.busy,tag="artist_album_${list.owner}_${list.id}"){model.selectArtistAlbum(list)}
                            }
                        }
                    }
                    val page=if(artists.album!=null)artists.albumTracks else artists.details?.tracks
                    page?.let{
                        item("artist_tracks_title"){Text("Треки · ${it.count}",style=MaterialTheme.typography.titleMedium)}
                        itemsIndexed(it.tracks,key={i,track->"artist_track_${i}_${track.id}"}){i,track->MusicTrackRow(track,i+1,preview=model.previewArtwork(track))}
                    }
                }
            }else {
                if(state.probe!=null)item("library_actions") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text(state.selected?.title?:if(category==LibraryCategory.TRACKS)"${state.probe?.library?.count?:0} треков" else category.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(enabled=!state.busy,onClick={ascending=!ascending}){Text(if(ascending)"А–Я ↓" else "Порядок ВК ↕")}
                        if(category==LibraryCategory.TRACKS)IconButton(onClick={toolsOpen=!toolsOpen},modifier=Modifier.testTag("library_tools")){Icon(Icons.Default.MoreVert,"Инструменты библиотеки")}
                    }
                    if(category==LibraryCategory.TRACKS) {
                        if(toolsOpen) {
                            Button(enabled=canPrepare,onClick={model.prepare(libraryAlbums=false)},modifier=Modifier.fillMaxWidth()){Text("Скачать всю мою музыку")}
                            OutlinedButton(enabled=canPrepare,onClick=model::showDuplicates,modifier=Modifier.fillMaxWidth().testTag("clean_duplicates")){Text("Очистить дубликаты")}
                            OutlinedButton(enabled=!state.busy&&!backgroundCheck,onClick=model::showAvailability,modifier=Modifier.fillMaxWidth().testTag("check_unavailable")){Text("Проверить недоступные треки")}
                            OutlinedButton(enabled=canPrepare,onClick={model.prepare(libraryAlbums=true)},modifier=Modifier.fillMaxWidth()){Text("Скачать мою музыку и группы по альбомам")}
                            Text("Группы по альбомам содержат только песни из «Моей музыки».",style=MaterialTheme.typography.bodySmall)
                        }
                    }else {
                        if(state.selected!=null) {
                            TextButton(enabled=!state.busy,onClick={model.section(state.catalogSection)}){Text("Назад к подборкам")}
                            Button(enabled=canPrepare,onClick={model.prepare()},modifier=Modifier.fillMaxWidth()){Text(if(state.selected?.kind==PlaylistKind.ALBUM)"Скачать альбом" else "Скачать плейлист")}
                        }else if(MusicCatalog.visible(state).isNotEmpty())Button(enabled=canPrepare,onClick={model.prepare(catalogKind=state.catalogSection.kind)},modifier=Modifier.fillMaxWidth().testTag("catalog_download_category")) {
                            Text(if(category==LibraryCategory.ALBUMS)"Скачать все альбомы" else "Скачать все плейлисты")
                        }
                        if(state.catalog==null)Text("Каталог ещё не загружен.",style=MaterialTheme.typography.bodySmall)
                        else if(state.catalog?.complete==false)Text("Каталог получен не полностью. Обнови список перед скачиванием.",color=MaterialTheme.colorScheme.error)
                        if(state.hiddenCatalog.isNotEmpty())Text("Полностью скачанные подборки скрыты: ${state.hiddenCatalog.size}",style=MaterialTheme.typography.bodySmall)
                        state.catalogError?.let{Text(it.label,color=MaterialTheme.colorScheme.error)}
                    }
                    if(toolsOpen||category!=LibraryCategory.TRACKS)Row {
                        TextButton(enabled=!state.busy&&!backgroundCheck,onClick=model::checkAccess){Text("Проверить музыку")}
                        TextButton(enabled=!state.busy&&!backgroundCheck,onClick=model::refreshCatalog){Text("Обновить каталог")}
                    }
                    if(duplicates.scanId.isNotEmpty()&&!duplicates.visible)TextButton(onClick=model::showDuplicates,modifier=Modifier.testTag("reopen_duplicates")){Text("Дубликаты: процесс и результат")}
                }}
                if(category!=LibraryCategory.TRACKS&&state.selected==null) {
                    val lists=MusicCatalog.visible(state).let{if(ascending)it.sortedBy{l->l.title.lowercase()}else it}
                    itemsIndexed(lists,key={_,list->"catalog_${list.key}"}){_,list->MusicCollectionRow(list.title,"${list.count} треков",!state.busy,
                        tag="catalog_list_${list.owner}_${list.id}"){model.select(list)}}
                    if(state.catalogSection==CatalogSection.UNKNOWN)item("unknown_note"){Text("ВК не сообщил тип этих подборок; ручной выбор не нужен.")}
                    if(MusicCatalog.visible(state,CatalogSection.UNKNOWN).isNotEmpty())item("unknown") {
                        TextButton(enabled=!state.busy,onClick={model.section(CatalogSection.UNKNOWN)}){Text("Подборки без типа")}
                    }
                }
                state.page?.let{page->
                    val rows=page.tracks.withIndex().let{if(ascending)it.sortedBy{r->r.value.displayTitle.lowercase()}else it.toList()}
                    if(page.tracks.size!=page.count)item("tracks_count"){Text("Показано ${page.tracks.size} из ${page.count}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    itemsIndexed(rows,key={_,entry->"track_${entry.index}_${entry.value.id}"}){_,entry->
                        val track=entry.value;val position=entry.index+1;val saved=savedById[track.id]?.file
                        MusicTrackRow(track,position,preview=model.previewArtwork(track),saved=saved,
                            downloading=queue.status=="RUNNING"&&queue.account==state.probe?.owner&&queue.currentTask?.track?.id==track.id,
                            enabled=canPrepare,onDownload={model.download(track,position)},
                            onRemove=if(state.selected==null&&state.catalogSection==CatalogSection.LIBRARY)({model.inspectRemoval(track)})else null,
                            onOpen=saved?.let{({openFile(it)})})
                    }
                }
            }
            if(!state.configured)item("empty_library") {Text("Подключи ВК через карточку сверху. Сохранённые файлы доступны без входа.",style=MaterialTheme.typography.bodyMedium)}
        }
    }
    if(duplicates.visible)DuplicatesSheet(duplicates,model::findDuplicates,model::requestDuplicateRemoval,model::confirmDuplicateRemoval,
        model::cancelDuplicateRemoval,model::skipDuplicate,model::pauseDuplicateCheck,model::dismissDuplicates)
    if(availability.visible)AvailabilitySheet(availability,model::checkUnavailableTracks,model::cancel,model::dismissAvailability)
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
    if(openError)AlertDialog(onDismissRequest={openError=false},title={Text("Файл не открыт")},text={Text("Не удалось проверить файл или найти приложение для его воспроизведения. Проверь файл в папке Music/VK Music Export.")},confirmButton={TextButton(onClick={openError=false}){Text("Понятно")}})
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
            TextButton(enabled=!submitting,onClick={clear();onBrowser()}){Text("Показать страницу VK ID в Chrome")}
            Text("Код относится только к этой заявке. При неверном коде можно повторить ввод; новая заявка автоматически не создаётся.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={cancel()},modifier=Modifier.testTag("native_auth_cancel_code")){Text("Отменить подключение")}
        }
    }
}

@Composable
private fun NativeAccessScreen(onBack: ()->Unit,onSave: (String,String)->Unit) {
    var token by remember {mutableStateOf("")}
    var agent by remember {mutableStateOf(NativeProtocol.DEFAULT_AGENT)}
    var invalid by remember {mutableStateOf(false)}
    fun close(){token="";agent="";onBack()}
    SecureCredentialContent {
        AppPage("Музыкальный доступ ВК",onBack=::close,tag="native_access_screen") {
            Text("Введи уже выданный токен ВК. Если он выдан определённому клиенту, укажи исходный User-Agent. Эти значения не попадают в диагностический лог.")
            OutlinedTextField(value=token,onValueChange={if(it.length<=4096){token=it;invalid=false}},label={Text("Токен ВК")},
                modifier=Modifier.fillMaxWidth().testTag("native_vk_token"),singleLine=true,visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            OutlinedTextField(value=agent,onValueChange={if(it.length<=512)agent=it},label={Text("User-Agent доступа")},
                modifier=Modifier.fillMaxWidth().testTag("native_vk_agent"),singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            if(invalid)Text(MusicError.INVALID_ACCESS.label,color=MaterialTheme.colorScheme.error)
            Button(onClick={
                Diagnostics.button("native_music_submit_access")
                if(!NativeProtocol.validAccess(token,agent))invalid=true else {
                    onSave(token,agent);token="";agent=""
                }
            }){Text("Сохранить и проверить ВК")}
            TextButton(onClick={Diagnostics.button("native_music_access_cancel");close()}){Text("Отмена")}
        }
    }
}
