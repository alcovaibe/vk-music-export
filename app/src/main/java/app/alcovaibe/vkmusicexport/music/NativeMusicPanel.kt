package app.alcovaibe.vkmusicexport.music

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.FileProvider
import app.alcovaibe.vkmusicexport.data.MusicSource
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import app.alcovaibe.vkmusicexport.export.Exporter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioFileProvider: FileProvider()

@Composable
internal fun NativeMusicPanel(model: NativeMusicViewModel,visible: Boolean,onDismiss:()->Unit,onSample:(MusicSource)->Unit) {
    if(!visible)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by model.state.collectAsState()
    var configure by rememberSaveable {mutableStateOf(false)}
    var selectedFile by remember {mutableStateOf<SavedAudio?>(null)}
    var openError by remember {mutableStateOf(false)}
    val saveMp3=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")){uri->
        selectedFile?.let{row->if(uri!=null)model.saveFile(row,uri)};selectedFile=null
    }
    val saveM4a=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")){uri->
        selectedFile?.let{row->if(uri!=null)model.saveFile(row,uri)};selectedFile=null
    }
    if(configure) {
        NativeAccessScreen(onBack={configure=false},onSave={token,agent->model.configure(token,agent);configure=false})
        return
    }
    if(!state.stopping && state.authPhase in setOf(DeviceAuthPhase.ENTER_CODE,DeviceAuthPhase.VALIDATING_CODE)) {
        DeviceConfirmationScreen(state,model::submitAuthCode,model::cancel,{model.openAuthChrome(context)})
        return
    }
    SettingsPage("Прямой доступ ВК",onBack={model.stopForWindow();onDismiss()},tag="native_music_screen") {
                Text("Эксперимент: первые 10 треков и 10 подборок. Массовая выгрузка пока не реализована.")
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
                    OutlinedButton(enabled=!state.busy,onClick=model::checkAccess){Text("Проверить 10 треков")}
                    TextButton(enabled=!state.busy,onClick=model::forget){Text("Удалить доступ ВК")}
                }
                state.message?.let{Text(it)}
                state.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                if(state.error!=null)state.downloadStep?.let{step->
                    Text("Этап: ${step.label}. Диагностический код: DOWNLOAD_${step.name}_${state.error!!.name}",color=MaterialTheme.colorScheme.error)
                }
                if(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val p=state.progress
                    Text(if(state.stopping)"Завершаем предыдущую операцию…"
                    else if(state.waitingForSlot)"Ожидаем завершения предыдущей операции…"
                    else if(state.stage=="browser_auth")state.authPhase?.label?:"Ожидание входа ВК" else when(p?.stage) {
                        "segments"->"Получено сегментов: ${p.segment}/${p.segments} · ${p.bytes/1024} КБ"
                        "verifying"->"Проверка формата и длительности"
                        "manifest"->"Чтение списка сегментов HLS"
                        "receiving"->"Получение потока · ${p.bytes/1024} КБ"
                        else->"Проверка музыкального доступа"
                    })
                    if(state.stage!="browser_auth")Text("Держи приложение открытым. При уходе операция остановится; готовые файлы сохранятся.",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!state.stopping,onClick=model::cancel){Text("Остановить")}
                }
                state.probe?.let { probe ->
                    HorizontalDivider()
                    Text("Моя музыка: получено ${probe.library.tracks.size} из ${probe.library.count}")
                    TextButton(enabled=!state.busy,onClick={model.select(null)}){Text("Показать мою музыку")}
                }
                state.catalog?.let { catalog ->
                    Text("Подборки: получено ${catalog.lists.size} из ${catalog.count}",style=MaterialTheme.typography.titleMedium)
                    Text("Тип «плейлист» или «альбом» пока не подтверждается этим прототипом.",style=MaterialTheme.typography.bodySmall)
                    catalog.lists.forEach { list ->
                        OutlinedButton(enabled=!state.busy,onClick={model.select(list)}){Text("${list.title} · ${list.count}")}
                    }
                }
                state.catalogError?.let{Text("Каталог: ${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                state.page?.let { page ->
                    HorizontalDivider()
                    Text(state.selected?.title?:"Моя музыка",style=MaterialTheme.typography.titleMedium)
                    Text("Проверочная выборка: ${page.tracks.size}/${page.count}. Полнота не подтверждена.")
                    OutlinedButton(enabled=!state.busy,onClick={
                        Diagnostics.button("native_music_preview_sample");model.sample()?.let{onSample(it)}
                    }){Text("Сохранить выборку в коллекцию…")}
                    page.tracks.forEachIndexed { i,track ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("${i+1}. ${track.title}",style=MaterialTheme.typography.titleSmall)
                                Text(track.artist)
                                track.album?.let{Text("Альбом: ${it.title}",style=MaterialTheme.typography.bodySmall)}
                                if(track.restricted||track.duration==0)Text("ВК отметил ограничение или не вернул длительность")
                                TextButton(enabled=!state.busy&&!track.restricted&&track.duration>0,onClick={model.download(track)}){Text("Скачать этот трек")}
                            }
                        }
                    }
                }
                if(state.saved.isNotEmpty()) {
                    HorizontalDivider();Text("Проверенные файлы",style=MaterialTheme.typography.titleMedium)
                    Text("Хранятся последние пять файлов, суммарно до 256 МБ. Сохрани нужные в выбранную папку.",style=MaterialTheme.typography.bodySmall)
                    state.saved.forEach { row ->
                        Text("${row.track.artist} — ${row.track.title} · ${row.extension.uppercase()} · ${row.bytes/1024} КБ")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled=!state.busy,onClick={
                                Diagnostics.button("native_music_open_audio")
                                scope.launch {
                                    try {
                                        val file=model.verifiedFile(row)
                                        val uri=FileProvider.getUriForFile(context,"${context.packageName}.audio",file)
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,row.mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("Audio",uri)})
                                    }catch(_: Exception){openError=true}
                                }
                            }){Text("Открыть")}
                            TextButton(enabled=!state.busy,onClick={
                                Diagnostics.button("native_music_choose_audio_export");selectedFile=row
                                val name=Exporter.safeName("${row.track.artist} — ${row.track.title}")+".${row.extension}"
                                if(row.extension=="mp3")saveMp3.launch(name)else saveM4a.launch(name)
                            }){Text("Сохранить файл…")}
                        }
                    }
                }
                TextButton(onClick={Diagnostics.button("native_music_close");model.stopForWindow();onDismiss()}){Text("Закрыть")}
    }
    if(openError)AlertDialog(onDismissRequest={openError=false},title={Text("Файл не открыт")},text={Text("Не удалось проверить файл или найти приложение для его воспроизведения. Попробуй сохранить файл.")},confirmButton={TextButton(onClick={openError=false}){Text("Понятно")}})
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
