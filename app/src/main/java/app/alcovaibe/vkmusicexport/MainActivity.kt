package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.alcovaibe.vkmusicexport.data.CollectionSnapshot
import app.alcovaibe.vkmusicexport.data.MusicSource
import app.alcovaibe.vkmusicexport.export.Exporter
import app.alcovaibe.vkmusicexport.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA7C8FF)) else lightColorScheme(
                    primary = Color(0xFF246BFD), background = Color(0xFFF5F6FA), surface = Color(0xFFFDFDFF)),
                shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))
            ) { MusicApp(model) }
        }
    }
    override fun onStop() { model.stop(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicApp(model: MusicViewModel) {
    val context = LocalContext.current
    val snapshot by model.snapshot.collectAsState()
    val running by model.running.collectAsState()
    val ready by model.ready.collectAsState()
    val message by model.message.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    BackHandler(tab != 0 || selected != null) {
        if (selected != null) selected = null
        else if (tab != 1 || !model.back()) { model.stop(); tab = 0 }
    }
    Scaffold(
        topBar = { TopAppBar(title = {
            Column {
                Text("VK Music Export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Ваша музыка — в вашем списке", style = MaterialTheme.typography.labelMedium)
            }
        }, actions = { IconButton(onClick = { confirm = "settings" }) { Icon(Icons.Default.MoreVert, "Действия") } }) },
        bottomBar = {
            NavigationBar {
                listOf("Коллекция", "ВКонтакте", "Экспорт").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { if (tab != index) { model.stop(); selected = null; tab = index } },
                        icon = { Icon(when (index) { 0 -> Icons.Default.Home; 1 -> Icons.Default.AccountCircle; else -> Icons.Default.Share }, label) },
                        label = { Text(label) })
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
            when (tab) {
                0 -> CollectionScreen(snapshot, selected, query, ready,
                    onSelect = { selected = it }, onQuery = { query = it }, onOpen = { tab = 1 })
                1 -> BrowserScreen(model)
                else -> ExportScreen(snapshot, ready)
            }
        }
    }
    if (confirm == "settings") AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Данные на телефоне") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Версия 0.1.0 • Android 8+\nПервый запуск проверяет работу входа и сбора на вашем устройстве.")
                Text("Коллекция обрабатывается локально. Сбор выполняется при открытом приложении. После остановки результаты можно экспортировать.")
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VK Music Export diagnostics", model.diagnosticText()))
                    model.message.value = "Диагностика скопирована: версии и счётчики, без названий треков и данных входа"
                    confirm = null
                }) { Text("Скопировать диагностику") }
                TextButton(enabled = !running, onClick = { confirm = "logout" }) { Text("Выйти из ВК") }
                TextButton(enabled = !running, onClick = { confirm = "clear" }) { Text("Очистить сохранённую коллекцию") }
            }
        }, confirmButton = { TextButton(onClick = { confirm = null }) { Text("Закрыть") } })
    if (confirm == "clear" || confirm == "logout") AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "clear") "Очистить коллекцию?" else "Очистить вход в ВК?") },
        text = { Text(if (confirm == "clear") "Собранные списки будут удалены из приложения. Сохранённые вами файлы останутся." else "В следующий раз потребуется вход. Собранные списки останутся на телефоне.") },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = { if (confirm == "clear") model.clearCollection() else model.logout(); confirm = null }) { Text("Подтвердить") } })
}

@Composable
private fun CollectionScreen(snapshot: CollectionSnapshot, selected: String?, query: String, ready: Boolean,
                             onSelect: (String?) -> Unit, onQuery: (String) -> Unit, onOpen: () -> Unit) {
    val source = snapshot.sources.find { it.id == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (selected == null) "Коллекция" else source?.title ?: "Все треки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${snapshot.uniqueTracks().size} записей • ${snapshot.playlistCount} плейлистов", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected != null) item { TextButton(onClick = { onSelect(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("К источникам") } }
        if (!ready) item { Text("Загрузка сохранённой коллекции…") }
        else if (snapshot.sources.isEmpty()) {
            item {
                ElevatedCard {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Home, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Сохраните названия любимых треков", style = MaterialTheme.typography.titleLarge)
                        Text("Войдите в ВК, откройте «Мою музыку» и запустите сбор. Приложение сохранит исполнителей, названия и состав плейлистов.")
                        Button(onClick = onOpen) { Text("Открыть ВКонтакте") }
                    }
                }
            }
        } else if (selected == null) {
            item {
                Card(onClick = { onSelect("all") }) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Общий список", style = MaterialTheme.typography.titleMedium)
                    Text("${snapshot.uniqueTracks().size} записей • повторы по идентификатору ВК объединены")
                } }
            }
            items(snapshot.sources, key = { it.id }) { item -> SourceCard(item) { onSelect(item.id) } }
            item { Text("Полнота обнаружения плейлистов пока не подтверждена. Сверьте найденные списки с вашей коллекцией ВК.", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedButton(onClick = onOpen) { Text("Открыть ВК и повторить сбор") } }
        } else {
            item { OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("Название или исполнитель") }, singleLine = true) }
            if (source != null) item { Text("${source.status.label} • ${source.entries.size}/${source.expectedCount ?: "?"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val tracks = if (selected == "all") snapshot.uniqueTracks() else source?.entries?.map { it.track }.orEmpty()
            val filtered = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            items(filtered.size) { index ->
                val track = filtered[index]
                ListItem(headlineContent = { Text(track.title) }, supportingContent = { Text(track.artist) },
                    leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.labelLarge) })
            }
            if (filtered.isEmpty()) item { Text("Треки не найдены") }
        }
    }
}

@Composable
private fun SourceCard(source: MusicSource, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(if (source.id == "my-music") Icons.Default.Favorite else Icons.Default.List, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(source.title, style = MaterialTheme.typography.titleMedium)
                Text("${source.entries.size} треков${source.expectedCount?.let { " из $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                Text(source.status.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BrowserScreen(model: MusicViewModel) {
    val context = LocalContext.current
    val running by model.running.collectAsState()
    val loading by model.pageLoading.collectAsState()
    val ready by model.ready.collectAsState()
    val page by model.currentPage.collectAsState()
    var desktop by rememberSaveable { mutableStateOf(false) }
    val web = remember { WebView(context).also { model.attach(it) } }
    LaunchedEffect(web) { if (desktop) model.desktop(true) }
    DisposableEffect(web) { onDispose { model.detach(web) } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !running, onClick = model::openMusic) { Text("Моя музыка") }
                TextButton(enabled = !running, onClick = model::openPlaylists) { Text("Плейлисты") }
                IconButton(enabled = !running, onClick = model::reload) { Icon(Icons.Default.Refresh, "Обновить") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Версия для ПК", style = MaterialTheme.typography.labelMedium)
                Switch(checked = desktop, enabled = !running, onCheckedChange = { desktop = it; model.desktop(it) })
            }
            if (running) Button(onClick = model::stop, modifier = Modifier.fillMaxWidth()) { Text("Остановить и сохранить результат") }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = ready && !loading, onClick = { model.scan(false) }, modifier = Modifier.weight(1f)) { Text("Текущий список") }
                Button(enabled = ready && !loading, onClick = { model.scan(true) }, modifier = Modifier.weight(1f)) { Text("Вся коллекция") }
            }
            Text("Новый сбор всей коллекции заменит предыдущий.", style = MaterialTheme.typography.labelSmall)
            Text(page.ifBlank { "Страница ВКонтакте" }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
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
                        context.contentResolver.openOutputStream(uri, "wt")?.use { Exporter.write(frozenSnapshot, chosenFormat, it) }
                            ?: error("Cannot open output")
                    }
                    result = "Файл сохранён"
                } catch (_: Exception) { result = "Сохранение не завершено. При необходимости удалите частичный файл и повторите экспорт." }
                finally { exporting = false }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Экспорт", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("${snapshot.uniqueTracks().size} записей в общем списке • ${snapshot.sources.size} источников") }
        item { Text("Полнота выгрузки не гарантирована. В JSON и ZIP включён отчёт о состоянии каждого списка.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(ExportFormat.entries) { format ->
            OutlinedCard(onClick = { formatName = format.name }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = formatName == format.name, onClick = { formatName = format.name })
                    Column {
                        Text(format.name, fontWeight = FontWeight.Bold)
                        Text(when (format) {
                            ExportFormat.TXT -> "Общий список исполнителей и названий"
                            ExportFormat.CSV -> "Таблица с плейлистами и позициями"
                            ExportFormat.JSON -> "Полная структура и статусы"
                            ExportFormat.ZIP -> "Все форматы и отдельные плейлисты"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(enabled = ready && snapshot.sources.isNotEmpty() && !exporting, modifier = Modifier.fillMaxWidth(), onClick = {
                val format = ExportFormat.valueOf(formatName)
                launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = format.mime
                    putExtra(Intent.EXTRA_TITLE, "vk-music-${LocalDate.now()}.${format.extension}")
                })
            }) { Text(if (exporting) "Сохранение…" else "Сохранить файл") }
        }
        if (result.isNotBlank()) item { Text(result) }
    }
}
