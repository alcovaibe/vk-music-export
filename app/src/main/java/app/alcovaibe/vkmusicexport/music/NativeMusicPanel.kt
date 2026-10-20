package app.alcovaibe.vkmusicexport.music

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.FileProvider
import app.alcovaibe.vkmusicexport.data.MusicSource
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import app.alcovaibe.vkmusicexport.export.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioFileProvider: FileProvider()

@Composable
internal fun NativeMusicPanel(model: NativeMusicViewModel,visible: Boolean,onDismiss:()->Unit,onSample:(MusicSource)->Unit) {
    if(!visible)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by model.state.collectAsState()
    var configure by remember {mutableStateOf(false)}
    var selectedFile by remember {mutableStateOf<SavedAudio?>(null)}
    var openError by remember {mutableStateOf(false)}
    val saveMp3=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")){uri->
        selectedFile?.let{row->if(uri!=null)model.saveFile(row,uri)};selectedFile=null
    }
    val saveM4a=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")){uri->
        selectedFile?.let{row->if(uri!=null)model.saveFile(row,uri)};selectedFile=null
    }
    Dialog(onDismissRequest={model.cancel();onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Прямой доступ ВК",style=MaterialTheme.typography.headlineSmall)
                Text("Эксперимент: первые 10 треков и 10 подборок. Массовая выгрузка и автоматический вход ещё не реализованы.")
                Text("Сессия Chrome сюда не переносится. Для проверки нужен уже выданный тебе токен ВК с доступом к музыке. Приложение не получает пароль ВК.",style=MaterialTheme.typography.bodyMedium)
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
                if(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val p=state.progress
                    Text(when(p?.stage) {
                        "segments"->"Получено сегментов: ${p.segment}/${p.segments} · ${p.bytes/1024} КБ"
                        "verifying"->"Проверка формата и длительности"
                        "receiving"->"Получение потока · ${p.bytes/1024} КБ"
                        else->"Проверка музыкального доступа"
                    })
                    Text("Держи приложение открытым. При уходе операция остановится; готовые файлы сохранятся.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=model::cancel){Text("Остановить")}
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
                        Diagnostics.button("native_music_preview_sample");model.sample()?.let{onSample(it);onDismiss()}
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
                TextButton(onClick={Diagnostics.button("native_music_close");model.cancel();onDismiss()}){Text("Закрыть")}
            }
        }
    }
    if(configure) {
        var token by remember {mutableStateOf("")};var agent by remember {mutableStateOf(NativeProtocol.DEFAULT_AGENT)}
        var invalid by remember {mutableStateOf(false)}
        AlertDialog(onDismissRequest={token="";agent="";configure=false},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
            title={Text("Музыкальный доступ ВК")},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Введи только токен ВК, не GitHub и не адрес страницы. Если токен выдан определённому клиенту, укажи его исходный User-Agent. Эти значения не попадают в диагностический лог.")
                    Text("Автоматическое получение токена пока не реализовано. Если готового музыкального токена нет, этот способ подключения ещё нельзя проверить.")
                    OutlinedTextField(value=token,onValueChange={if(it.length<=4096)token=it},label={Text("Токен ВК")},
                        modifier=Modifier.fillMaxWidth().testTag("native_vk_token"),singleLine=true,visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                    OutlinedTextField(value=agent,onValueChange={if(it.length<=512)agent=it},label={Text("User-Agent доступа")},
                        modifier=Modifier.fillMaxWidth().testTag("native_vk_agent"),singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                    if(invalid)Text(MusicError.INVALID_ACCESS.label,color=MaterialTheme.colorScheme.error)
                }
            },confirmButton={TextButton(onClick={
                if(!NativeProtocol.validAccess(token,agent))invalid=true else {
                    model.configure(token,agent);token="";agent="";configure=false
                }
            }){Text("Сохранить и проверить ВК")}},dismissButton={TextButton(onClick={token="";agent="";configure=false}){Text("Отмена")}})
    }
    if(openError)AlertDialog(onDismissRequest={openError=false},title={Text("Файл не открыт")},text={Text("Не удалось проверить файл или найти приложение для его воспроизведения. Попробуй сохранить файл.")},confirmButton={TextButton(onClick={openError=false}){Text("Понятно")}})
}
