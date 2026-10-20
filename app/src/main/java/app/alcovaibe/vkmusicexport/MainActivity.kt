package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.view.WindowInsetsController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.export.Exporter
import app.alcovaibe.vkmusicexport.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val Ink = Color(0xFF202337)
private val Accent = Color(0xFF5B4DE8)

@Composable
private fun musicIcon() = ImageVector.vectorResource(R.drawable.ic_music_note)

private fun counted(n: Int, one: String, few: String, many: String): String = "$n " + when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}
private fun tracks(n: Int) = counted(n, "трек", "трека", "треков")

class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private val updates: app.alcovaibe.vkmusicexport.updates.UpdateViewModel by viewModels()
    private val nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel by viewModels()
    override fun onResume(){super.onResume();updates.controller.resume()}
    override fun onPause(){updates.controller.pause();super.onPause()}
    override fun onStop(){nativeMusic.cancel();super.onStop()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
            }
            MusicTheme { MusicApp(model,updates.controller,nativeMusic) }
        }
    }
}

@Composable
internal fun MusicTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB9AFFF), background = Color(0xFF12131B), surface = Color(0xFF1C1D28),
        surfaceVariant = Color(0xFF2A2B3C), onSurfaceVariant = Color(0xFFB9B9CA)
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White, background = Color(0xFFF6F6FA), surface = Color.White,
        onBackground = Ink, onSurface = Ink, surfaceVariant = Color(0xFFEFEDF9),
        onSurfaceVariant = Color(0xFF727486), outlineVariant = Color(0xFFE8E8F0)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)
    ), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicApp(model: MusicViewModel,updates: app.alcovaibe.vkmusicexport.updates.UpdateController,nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel) {
    val context = LocalContext.current
    val snapshot by model.snapshot.collectAsState()
    val ready by model.ready.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val pending by model.pendingImport.collectAsState()
    var collectorGuide by remember { mutableStateOf(false) }
    var updatePanel by remember{mutableStateOf(false)}
    var nativePanel by remember{mutableStateOf(false)}
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::stageImport) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var chromeUnavailable by remember { mutableStateOf(false) }
    val openVk = {
        Diagnostics.button("open_chrome")
        chromeUnavailable = !app.alcovaibe.vkmusicexport.vk.ChromeBrowser.open(context)
    }
    BackHandler(selected != null || tab != 0) {
        if (selected != null) selected = null else tab = 0
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = Accent, modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(musicIcon(), null, tint = Color.White, modifier = Modifier.size(23.dp)) }
                }
                Spacer(Modifier.width(10.dp))
                Text("VK Music Export", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { Diagnostics.button("settings"); confirm = "settings" }) { Icon(Icons.Default.MoreVert, "Настройки") }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("Коллекция", "Экспорт").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { Diagnostics.button(if(index == 0) "collection_tab" else "export_tab"); tab = index; selected = null },
                        icon = { Icon(if (index == 0) Icons.Default.Home else Icons.Default.Share, null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp), color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == 0) CollectionScreen(snapshot, selected, query, ready,
                onSelect = { selected = it }, onQuery = { query = it }, onOpen = openVk, onCollect={Diagnostics.button("collector_guide");collectorGuide=true}, onImport={Diagnostics.button("choose_import");importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))}, canImport=ready && !busy && pending==null)
            else ExportScreen(snapshot, ready && !busy)
        }
    }
    if(collectorGuide) CollectorGuide(onDismiss={Diagnostics.button("close_collector_guide");collectorGuide=false},onImport={Diagnostics.button("choose_import");collectorGuide=false;importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))},onMusic={Diagnostics.button("collector_open_music");chromeUnavailable=!app.alcovaibe.vkmusicexport.vk.ChromeBrowser.openMusic(context)})
    pending?.let { plan ->
        val previous=plan.source?.let{incoming->snapshot.sources.firstOrNull{it.id==incoming.id}}
        val nativeSample=plan.reason=="NATIVE_ACCESS_SAMPLE_FIRST_10"
        AlertDialog(onDismissRequest={if(!busy)model.cancelImport()},title={Text(if(nativeSample)"Сохранение выборки ВК" else "Импорт из Chrome")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Треков: ${plan.tracks}. Плейлистов в каталоге: ${plan.playlists.size}.")
                if(nativeSample)Text("Это только первые 10 позиций. Выборка останется помеченной как частичная, даже если количество совпало.")
                plan.source?.let {Text("Список: ${it.title} · ${it.status.label}")}
                if(previous!=null)Text("Импорт заменит этот список: сохранено ${previous.entries.size}, в файле ${plan.tracks} треков. Остальные списки сохранятся.")
                else Text("Список будет добавлен в коллекцию. Каталог плейлистов не заменяет уже собранные треки.")
                if(!nativeSample)Text("Обнаруженный плейлист может ещё не содержать треков. Его нужно собрать отдельно в Chrome.")
            }
        },confirmButton={TextButton(enabled=!busy,onClick=model::confirmImport){Text(if(previous!=null)"Заменить список" else "Импортировать")}},
            dismissButton={TextButton(enabled=!busy,onClick=model::cancelImport){Text("Отмена")}})
    }
    if (chromeUnavailable) AlertDialog(onDismissRequest={chromeUnavailable=false},
        title={Text("Chrome недоступен")},
        text={Text("Установи или включи Google Chrome и повтори открытие ВК. Встроенного браузера в приложении больше нет.")},
        confirmButton={TextButton(onClick={chromeUnavailable=false}){Text("Понятно")}})
    if (confirm == "settings") AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Настройки") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VK Music Export · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold)
            Text("Вход и выход из ВК выполняются в Google Chrome. Сохранённая коллекция и её экспорт доступны в приложении.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirm = null; openVk() }) { Text("Открыть ВК в Chrome") }
            TextButton(onClick={Diagnostics.button("native_music_panel");confirm=null;nativePanel=true}){Text("Прямой доступ ВК · эксперимент")}
            DiagnosticControls()
            TextButton(onClick={Diagnostics.button("updates");confirm=null;updatePanel=true}){Text("Обновления приложения")}
            TextButton(enabled = ready && !busy && pending==null, onClick = { Diagnostics.button("request_clear"); confirm = "clear" }) { Text("Очистить коллекцию", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { Diagnostics.button("settings_done"); confirm = null }) { Text("Готово") } })
    app.alcovaibe.vkmusicexport.updates.UpdatePanel(updates,updatePanel){updatePanel=false}
    app.alcovaibe.vkmusicexport.music.NativeMusicPanel(nativeMusic,nativePanel,onDismiss={nativePanel=false},onSample=model::stageNativeSample)
    if (confirm == "clear") AlertDialog(onDismissRequest = { confirm = null },
        title = { Text("Очистить коллекцию?") },
        text = { Text("Списки будут удалены из приложения. Экспортированные файлы останутся.") },
        dismissButton = { TextButton(onClick = { Diagnostics.button("cancel_confirmation"); confirm = null }) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = { Diagnostics.button("confirm_clear"); model.clearCollection(); confirm = null }) { Text("Подтвердить") } })
}

@Composable
private fun CollectionScreen(snapshot: CollectionSnapshot, selected: String?, query: String, ready: Boolean,
                             onSelect: (String?) -> Unit, onQuery: (String) -> Unit, onOpen: () -> Unit, onCollect: () -> Unit, onImport: () -> Unit, canImport: Boolean) {
    val source = snapshot.sources.find { it.id == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected != null) item {
            TextButton(onClick = { Diagnostics.button("collection_back"); onSelect(null) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Коллекция")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected == null) "Моя коллекция" else source?.title ?: "Все треки", Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onCollect,modifier=Modifier.weight(1f)){Text("Сбор из Chrome")}
                FilledTonalButton(enabled=canImport,onClick=onImport,modifier=Modifier.weight(1f)){Text("Импорт JSON")}
            }
        }
        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (snapshot.sources.isEmpty()) {
            item { WelcomeArtwork() }
            item {
                Text("ВКонтакте.\nВ настоящем Chrome.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(10.dp))
                Text("Открой музыку в Chrome, запусти закладку-сборщик и импортируй сохранённый JSON.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Открыть ВК в Chrome", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Text("Вход остаётся в Chrome. Приложение пока не проверяет авторизацию.", Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (selected == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(snapshot.uniqueTracks().size, tracks(snapshot.uniqueTracks().size).substringAfter(" ") + " в списке", Modifier.weight(1f))
                    StatTile(snapshot.playlistCount, counted(snapshot.playlistCount, "плейлист", "плейлиста", "плейлистов").substringAfter(" "), Modifier.weight(1f))
                }
            }
            item {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Открыть ВК в Chrome")
                }
            }
            item { SourceRow("Все треки", "${snapshot.uniqueTracks().size} · общий список", -1, onClick = { onSelect("all") }) }
            item { SectionLabel("МОЯ МУЗЫКА И ПЛЕЙЛИСТЫ") }
            items(snapshot.sources, key = { it.id }) { sourceItem ->
                SourceRow(sourceItem.title, "${tracks(sourceItem.entries.size)} · ${sourceItem.status.label}", snapshot.sources.indexOf(sourceItem),
                    onClick = { onSelect(sourceItem.id) })
            }
            item { InfoNote("Для обновления списка собери его в Chrome и импортируй JSON. Каталог плейлистов не подтверждает полноту сбора.") }
        } else {
            item { OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Трек или исполнитель") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
            if (source != null) item { InfoNote("${source.entries.size} из ${source.expectedCount ?: "?"} · ${source.status.label}") }
            val tracks = if (selected == "all") snapshot.uniqueTracks() else source?.entries?.map { it.track }.orEmpty()
            val filtered = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            items(filtered.size) { index ->
                val track = filtered[index]
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}".padStart(2, '0'), Modifier.width(38.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (filtered.isEmpty()) item { InfoNote("Ничего не найдено. Попробуй другое название.") }
        }
    }
}

@Composable
private fun WelcomeArtwork() {
    Surface(shape = RoundedCornerShape(28.dp), color = Ink, modifier = Modifier.fillMaxWidth().height(188.dp)) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * .76f, size.height * .53f)
                val radius = size.height * .61f
                drawCircle(Color(0xFF333749), radius, center)
                for (i in 1..7) drawCircle(Color(0xFF474B60), radius * (0.35f + i * 0.08f), center, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xFFBFB5FF), radius * .30f, center)
                drawCircle(Ink, radius * .055f, center)
                drawCircle(Color(0xFF8575F5), 5.dp.toPx(), Offset(26.dp.toPx(), size.height - 30.dp.toPx()))
            }
            Column(Modifier.padding(24.dp)) {
                Text("ТВОЯ МУЗЫКА ИЗ ВК", color = Color(0xFFC7C3DE), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                Spacer(Modifier.height(20.dp))
                Icon(musicIcon(), null, tint = Color.White, modifier = Modifier.size(38.dp))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TXT", "CSV", "JSON").forEach { label ->
                        Surface(color = Color(0xFF414457), shape = RoundedCornerShape(6.dp)) {
                            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp)) {
            Text("$value", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun InfoNote(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp) }

@Composable
private fun SourceRow(title: String, subtitle: String, index: Int, onClick: () -> Unit) {
    val colors = listOf(Color(0xFFEAE5FF), Color(0xFFDDF1EB), Color(0xFFFFE9DE), Color(0xFFE0EAFF))
    Surface(onClick = { Diagnostics.button("select_source"); onClick() }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (index < 0) Ink else colors[index % colors.size]), contentAlignment = Alignment.Center) {
                Icon(if (index < 0) musicIcon() else if (index == 0) Icons.Default.Favorite else Icons.AutoMirrored.Filled.List,
                    null, tint = if (index < 0) Color.White else Ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ExportScreen(snapshot: CollectionSnapshot, ready: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formatName by rememberSaveable { mutableStateOf(ExportFormat.ZIP.name) }
    var exporting by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        val uri = activityResult.data?.data
        if (activityResult.resultCode == Activity.RESULT_OK && uri != null) {
            val chosenFormat = ExportFormat.valueOf(formatName)
            val frozenSnapshot = snapshot
            scope.launch {
                exporting = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { Exporter.write(frozenSnapshot, chosenFormat, it) } ?: error("Cannot open output")
                    }
                    result = "Файл сохранён"
                } catch (_: Exception) { result = "Не удалось сохранить файл. Повтори экспорт." }
                finally { exporting = false }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Забрать с собой", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(8.dp))
            Text(if (snapshot.sources.isEmpty()) "Коллекция пока пуста. Во вкладке «Коллекция» открой «Сбор из Chrome» и импортируй JSON сборщика."
                else "${tracks(snapshot.uniqueTracks().size)} · ${counted(snapshot.sources.size, "список", "списка", "списков")}. Выбери удобный формат.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionLabel("ФОРМАТ ФАЙЛА") }
        items(ExportFormat.entries.sortedBy { if (it == ExportFormat.ZIP) 0 else it.ordinal + 1 }) { format ->
            val checked = formatName == format.name
            Surface(onClick = { if (!exporting) { Diagnostics.button("choose_export_format"); formatName = format.name } }, shape = RoundedCornerShape(18.dp),
                color = if (checked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                border = if (checked) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f)) else null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (format == ExportFormat.ZIP) "ZIP · Всё сразу" else format.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(when (format) {
                            ExportFormat.TXT -> "Исполнитель — название, строка за строкой"
                            ExportFormat.CSV -> "Таблица с плейлистами и порядком треков"
                            ExportFormat.JSON -> "Структура коллекции для других приложений"
                            ExportFormat.ZIP -> "Все форматы и каждый плейлист отдельно"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = checked, onClick = null)
                }
            }
        }
        item {
            Button(enabled = ready && snapshot.sources.isNotEmpty() && !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), onClick = {
                Diagnostics.button("export_music")
                val format = ExportFormat.valueOf(formatName)
                launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = format.mime
                    putExtra(Intent.EXTRA_TITLE, "vk-music-${LocalDate.now()}.${format.extension}")
                })
            }) { Icon(Icons.Default.Share, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(if (exporting) "Сохраняем…" else "Сохранить ${formatName}") }
            Spacer(Modifier.height(12.dp)); InfoNote("Полнота сбора указана в отчёте внутри ZIP и JSON.")
        }
        if (result.isNotBlank()) item { Text(result, color = MaterialTheme.colorScheme.primary) }
    }
}
