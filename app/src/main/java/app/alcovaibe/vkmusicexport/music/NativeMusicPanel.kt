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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.alcovaibe.vkmusicexport.SettingsPage
import app.alcovaibe.vkmusicexport.SecureSettingsContent
import app.alcovaibe.vkmusicexport.data.MusicSource
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeMusicPanel(model: NativeMusicViewModel,visible: Boolean,onDismiss:()->Unit,onSample:(MusicSource)->Unit) {
    if(!visible)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by model.state.collectAsState()
    val queue by AudioExportRuntime.state.collectAsState()
    val queueWorking=queue.status in setOf("RUNNING","QUEUED")
    val canPrepare=!state.busy&&!queue.unfinished&&!queueWorking
    var notifications by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=it}
    var configure by rememberSaveable {mutableStateOf(false)}
    var openError by remember {mutableStateOf(false)}
    if(configure) {
        NativeAccessScreen(onBack={configure=false},onSave={token,agent->model.configure(token,agent);configure=false})
        return
    }
    if(!state.stopping && state.authPhase in setOf(DeviceAuthPhase.ENTER_CODE,DeviceAuthPhase.VALIDATING_CODE)) {
        DeviceConfirmationScreen(state,model::submitAuthCode,model::cancel,{model.openAuthChrome(context)})
        return
    }
    SettingsPage("Прямой доступ ВК",onBack={model.stopForWindow();onDismiss()},tag="native_music_screen") {
                Text("Папка: Music/VK Music Export",style=MaterialTheme.typography.titleMedium)
                Text("Моя музыка, Плейлисты и Альбомы создаются при первом сохранении. Готовые файлы не удаляются приложением по количеству или возрасту.")
                Text("Один трек может сохраняться в нескольких папках: это отдельные копии и дополнительное место. В папки альбомов из «Моей музыки» попадают только сохранённые у тебя композиции.",style=MaterialTheme.typography.bodySmall)
                if(!notifications)OutlinedButton(onClick={Diagnostics.button("audio_notification_permission");notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Разрешить уведомления загрузки")}
                if(queue.total==0)queue.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                if(queue.total>0) {
                    Text("Очередь: ${queue.done}/${queue.total} файлов · пропущено ${queue.failed}",style=MaterialTheme.typography.titleMedium)
                    Text(when(queue.status){"RUNNING"->"Скачивание продолжается, приложение можно свернуть";"QUEUED"->"Ожидание сети и запуска системой";"FINISHED"->"Готово";"FINISHED_WITH_ERRORS"->"Готово с пропусками";else->"Приостановлено"})
                    if(queueWorking)LinearProgressIndicator(progress={if(queue.total>0)(queue.done+queue.failed).toFloat()/queue.total else 0f},modifier=Modifier.fillMaxWidth())
                    queue.progress?.let{Text("Текущий файл: ${it.bytes/1024} КБ" )}
                    queue.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                    if(queue.sourceIssues>0)Text("Не прочитаны источники/подборки: ${queue.sourceIssues}. Экспорт всей библиотеки не подтверждён.",color=MaterialTheme.colorScheme.error)
                    if(queueWorking)TextButton(onClick=model::pauseQueue){Text("Пауза скачивания")}
                    else {
                        if(queue.unfinished)Button(enabled=!state.busy,onClick={model.resumeQueue()}){Text("Продолжить скачивание")}
                        if(queue.failed>0)OutlinedButton(enabled=!state.busy,onClick={model.resumeQueue(true)}){Text("Повторить пропущенные треки")}
                        TextButton(enabled=!state.busy,onClick=model::clearQueue){Text("Очистить очередь · файлы останутся")}
                    }
                    queue.failures.forEach{Text("${it.task.track.artist} — ${it.task.track.title}: ${it.error.name}",style=MaterialTheme.typography.bodySmall)}
                }
                if(queue.recent.isNotEmpty()) {
                    Text("Последние сохранённые файлы",style=MaterialTheme.typography.titleMedium)
                    queue.recent.forEach{row->
                        Text("${row.displayName}\n${row.relativePath}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={
                            Diagnostics.button("audio_public_open")
                            scope.launch {
                                try{withContext(Dispatchers.IO){musicCheck(SharedAudioStore(context).valid(row),MusicError.INCOMPLETE)}
                                    val uri=Uri.parse(row.uri)
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,row.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("Audio",uri)})
                                }catch(_: Exception){openError=true}
                            }
                        }){Text("Открыть файл")}
                    }
                }
                HorizontalDivider()
                Text("Вход через Chrome",style=MaterialTheme.typography.titleMedium)
                Text("Открой заявку в Chrome. Если VK ID покажет код подключения из 6 цифр, вернись сюда: приложение откроет экран для этого кода. Если код не потребуется, результат будет проверен автоматически.")
                Text("Это внутренний протокол ВК. Веб-сессия может иметь права шире музыки, а музыкальный метод может быть недоступен. Новый доступ сохраняется только после успешной проверки музыки.",style=MaterialTheme.typography.bodySmall)
                if(state.stage!="browser_auth")Button(enabled=state.initialized&&!state.busy,onClick=model::authenticate) {
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
                Text("Ручной доступ",style=MaterialTheme.typography.titleMedium)
                Text("Если у тебя уже есть музыкальный токен ВК, его можно ввести вручную. Пароль и SMS-коды вводи только на странице ВК.",style=MaterialTheme.typography.bodySmall)
                if(!state.initialized)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(state.configured)"Доступ ВК сохранён на устройстве" else "Музыкальный доступ ВК не настроен")
                Button(enabled=state.initialized&&!state.busy,onClick={Diagnostics.button("native_music_access_dialog");configure=true}) {
                    Text(if(state.configured)"Изменить доступ ВК" else "Настроить доступ ВК")
                }
                if(state.configured) {
                    OutlinedButton(enabled=!state.busy,onClick=model::checkAccess){Text("Проверить музыку")}
                    TextButton(enabled=!state.busy&&!queueWorking,onClick=model::forget){Text("Удалить доступ ВК")}
                }
                state.message?.let{Text(it)}
                state.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                if(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(state.stopping)"Завершаем предыдущую операцию…"
                    else if(state.waitingForSlot)"Ожидаем завершения предыдущей операции…"
                    else if(state.stage=="browser_auth")state.authPhase?.label?:"Ожидание входа ВК"
                    else state.preparing?:"Проверка музыкального доступа")
                    if(state.stage!="browser_auth")Text("Держи приложение открытым. При уходе операция остановится; готовые файлы сохранятся.",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!state.stopping,onClick=model::cancel){Text("Остановить")}
                }
                state.probe?.let { probe ->
                    HorizontalDivider()
                    Text("Моя музыка: ${probe.library.count} позиций")
                    Button(enabled=canPrepare,onClick={model.select(null);model.prepare()}){Text("Скачать всю мою музыку")}
                    OutlinedButton(enabled=canPrepare,onClick={model.prepare(allCatalog=true)}){Text("Скачать музыку и все подборки")}
                    Text("Подготовку списков дождись на этом экране. После создания очереди можно свернуть приложение. После перезапуска нажми «Продолжить скачивание»; готовые файлы будут проверены и использованы повторно.",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!state.busy,onClick={model.select(null)}){Text("Показать мою музыку")}
                }
                state.catalog?.let { catalog ->
                    Text("Подборки: ${state.catalogOffset+1}–${state.catalogOffset+catalog.lists.size} из ${catalog.count}",style=MaterialTheme.typography.titleMedium)
                    Text("Если ВК не указал тип подборки явно, она сохраняется в «Плейлисты». Для открытой подборки можно выбрать «Альбомы» вручную.",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!state.busy&&state.catalogOffset>0,onClick={model.catalogPage(-1)}){Text("Подборки назад")}
                        TextButton(enabled=!state.busy&&catalog.lists.isNotEmpty()&&state.catalogOffset+catalog.lists.size<catalog.count,onClick={model.catalogPage(1)}){Text("Ещё подборки")}
                    }
                    catalog.lists.forEach { list ->
                        OutlinedButton(enabled=!state.busy,onClick={model.select(list)}){Text("${list.title} · ${list.count}")}
                    }
                }
                state.catalogError?.let{Text("Каталог: ${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                state.page?.let { page ->
                    HorizontalDivider()
                    Text(state.selected?.title?:"Моя музыка",style=MaterialTheme.typography.titleMedium)
                    Text("Показано ${if(page.tracks.isEmpty())0 else state.pageOffset+1}–${state.pageOffset+page.tracks.size} из ${page.count}")
                    if(state.selected!=null) {
                        Button(enabled=canPrepare,onClick={model.prepare(kind=PlaylistKind.PLAYLIST)}){Text("Скачать в «Плейлисты»")}
                        OutlinedButton(enabled=canPrepare,onClick={model.prepare(kind=PlaylistKind.ALBUM)}){Text("Скачать в «Альбомы»")}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!state.busy&&state.pageOffset>0,onClick={model.page(-1)}){Text("Предыдущие треки")}
                        TextButton(enabled=!state.busy&&page.tracks.isNotEmpty()&&state.pageOffset+page.tracks.size<page.count,onClick={model.page(1)}){Text("Следующие треки")}
                    }
                    OutlinedButton(enabled=!state.busy,onClick={
                        Diagnostics.button("native_music_preview_sample");model.sample()?.let{onSample(it)}
                    }){Text("Сохранить эту страницу в коллекцию…")}
                    page.tracks.forEachIndexed { i,track ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("${state.pageOffset+i+1}. ${track.title}",style=MaterialTheme.typography.titleSmall)
                                Text(track.artist)
                                track.album?.let{Text("Альбом: ${it.title}",style=MaterialTheme.typography.bodySmall)}
                                if(track.restricted||track.duration==0)Text("ВК отметил ограничение или не вернул длительность")
                                TextButton(enabled=canPrepare&&!track.restricted&&track.duration>0,onClick={model.download(track,state.pageOffset+i+1)}){Text("Скачать этот трек")}
                            }
                        }
                    }
                }
                TextButton(onClick={Diagnostics.button("native_music_close");model.stopForWindow();onDismiss()}){Text("Закрыть")}
    }
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
    SecureSettingsContent {
        SettingsPage("Код подключения ВК",onBack={cancel()},tag="native_auth_code_screen") {
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
    SecureSettingsContent {
        SettingsPage("Музыкальный доступ ВК",onBack=::close,tag="native_access_screen") {
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
