package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.view.View
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.export.Exporter
import app.alcovaibe.vkmusicexport.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
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
            MusicTheme { MusicApp(model) }
        }
    }
    override fun onStop() { model.stop(); super.onStop() }
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
private fun MusicApp(model: MusicViewModel) {
    val context = LocalContext.current
    val snapshot by model.snapshot.collectAsState()
    val running by model.running.collectAsState()
    val ready by model.ready.collectAsState()
    val connected by model.connected.collectAsState()
    val message by model.message.collectAsState()
    val popup by model.popup.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var browser by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val generation by model.browserGeneration.collectAsState()
    val web = remember(generation) { WebView(context).also(model::attach) }
    DisposableEffect(web) { onDispose { model.detach(web) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeVk = { Diagnostics.button("close_browser"); model.stop(); model.activeWeb()?.clearFocus(); keyboard?.hide(); browser = false }
    val openVk = { Diagnostics.button("open_vk"); browser = true; model.openLogin() }
    BackHandler(browser || selected != null || tab != 0) {
        when {
            browser -> if (!model.back()) closeVk()
            selected != null -> selected = null
            else -> tab = 0
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Keep the same browser attached while the collection or export screen is visible.
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            if (browser) BrowserHeader(model, onClose = closeVk)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize(), update = {
                    it.visibility = if (browser && popup == null) View.VISIBLE else View.INVISIBLE
                })
                popup?.let { child ->
                    AndroidView(factory = { child }, onRelease = { if(WebDiagnostics.alive(it)) { WebDiagnostics.destroyed(it); it.destroy() } }, modifier = Modifier.fillMaxSize(),
                        update = { it.visibility = if (browser) View.VISIBLE else View.INVISIBLE })
                }
            }
            if (browser) BrowserActions(model, onDone = closeVk)
        }
        if (!browser) Scaffold(
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
                if (running) Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { browser = true }) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (tab == 0) CollectionScreen(snapshot, selected, query, ready, connected,
                    onSelect = { selected = it }, onQuery = { query = it }, onOpen = openVk)
                else ExportScreen(snapshot, ready)
            }
        }
    }
    if (confirm == "settings") AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Настройки") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VK Music Export · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold)
            Text("Названия, исполнители и плейлисты сохраняются на телефоне. Для сбора оставьте приложение открытым.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirm = null; openVk() }) { Text(if (connected) "Открыть ВКонтакте" else "Подключить ВКонтакте") }
            DiagnosticControls()
            TextButton(onClick = {
                Diagnostics.button("updates"); val updates = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/alcovaibe/vk-music-export/releases/latest"))
                try {
                    context.startActivity(updates)
                    confirm = null
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "Откройте GitHub Releases в браузере", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Обновления на GitHub") }
            TextButton(enabled = !running, onClick = { Diagnostics.button("request_logout"); confirm = "logout" }) { Text("Выйти из ВК") }
            TextButton(enabled = !running, onClick = { Diagnostics.button("request_clear"); confirm = "clear" }) { Text("Очистить коллекцию", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { Diagnostics.button("settings_done"); confirm = null }) { Text("Готово") } })
    if (confirm == "clear" || confirm == "logout") AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "clear") "Очистить коллекцию?" else "Выйти из ВК?") },
        text = { Text(if (confirm == "clear") "Списки будут удалены из приложения. Экспортированные файлы останутся." else "Потребуется повторный вход. Собранные списки останутся на телефоне.") },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = { Diagnostics.button(if(confirm == "clear") "confirm_clear" else "confirm_logout"); if (confirm == "clear") model.clearCollection() else model.logout(); confirm = null }) { Text("Подтвердить") } })
}

@Composable
private fun CollectionScreen(snapshot: CollectionSnapshot, selected: String?, query: String, ready: Boolean, connected: Boolean,
                             onSelect: (String?) -> Unit, onQuery: (String) -> Unit, onOpen: () -> Unit) {
    val source = snapshot.sources.find { it.id == selected }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected != null) item {
            TextButton(onClick = { onSelect(null) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Коллекция")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected == null) "Моя коллекция" else source?.title ?: "Все треки", Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            }
        }
        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (snapshot.sources.isEmpty()) {
            item { WelcomeArtwork() }
            item {
                Text("Вся твоя музыка.\nВ одном списке.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(10.dp))
                Text("Сохрани названия и исполнителей из «Моей музыки» и своих плейлистов ВК.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(if (connected) "Открыть музыку и собрать" else "Подключить ВКонтакте", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Text("Вход на сайте ВК · Пароль не хранится в приложении", Modifier.fillMaxWidth(),
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
                    Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (connected) "ВК подключён · Обновить коллекцию" else "Подключить ВК и обновить")
                }
            }
            item { SourceRow("Все треки", "${snapshot.uniqueTracks().size} · общий список", -1, onClick = { onSelect("all") }) }
            item { SectionLabel("МОЯ МУЗЫКА И ПЛЕЙЛИСТЫ") }
            items(snapshot.sources, key = { it.id }) { sourceItem ->
                SourceRow(sourceItem.title, "${tracks(sourceItem.entries.size)} · ${sourceItem.status.label}", snapshot.sources.indexOf(sourceItem),
                    onClick = { onSelect(sourceItem.id) })
            }
            item { InfoNote("Сверь найденные плейлисты с ВК: полнота коллекции пока не подтверждена.") }
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
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
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
private fun BrowserHeader(model: MusicViewModel, onClose: () -> Unit) {
    val page by model.currentPage.collectAsState()
    val loading by model.pageLoading.collectAsState()
    val error by model.browserError.collectAsState()
    val popup by model.popup.collectAsState()
    val running by model.running.collectAsState()
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Diagnostics.button("close_window"); if (popup != null) model.closePopup() else onClose() }) { Icon(Icons.Default.Close, if (popup != null) "Закрыть окно входа" else "К коллекции") }
            Column(Modifier.weight(1f)) {
                Text(if (popup != null) "Вход в ВКонтакте" else "ВКонтакте", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(runCatching { URI(page).host }.getOrNull() ?: "Открываем сайт ВК…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(enabled = !running, onClick = { Diagnostics.button("reload"); model.reload() }) { Icon(Icons.Default.Refresh, "Обновить страницу") }
        }
        DiagnosticControls(compact = true)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        error?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BrowserActions(model: MusicViewModel, onDone: () -> Unit) {
    val running by model.running.collectAsState()
    val connected by model.connected.collectAsState()
    val loading by model.pageLoading.collectAsState()
    val ready by model.ready.collectAsState()
    val message by model.message.collectAsState()
    val code by model.lastCode.collectAsState()
    val popup by model.popup.collectAsState()
    var more by remember { mutableStateOf(false) }
    if (popup != null && !running) return
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { Diagnostics.button("stop"); model.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить и сохранить") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connected && popup == null) TextButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Подключено · К коллекции")
                    } else Button(onClick = { Diagnostics.button("openMusic"); model.openMusic() }, modifier = Modifier.weight(1f)) { Text("Открыть мою музыку") }
                    Box {
                        IconButton(onClick = { Diagnostics.button("page_menu"); more = true }) { Icon(Icons.Default.MoreVert, "Действия со страницей") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("Моя музыка") }, onClick = { more = false; Diagnostics.button("openMusic"); model.openMusic() })
                            DropdownMenuItem(text = { Text("Мои плейлисты") }, onClick = { more = false; Diagnostics.button("openPlaylists"); model.openPlaylists() })
                            DropdownMenuItem(enabled = popup == null, text = { Text("Проверить подключение") }, onClick = { more = false; Diagnostics.button("verifySession"); model.verifySession() })
                        }
                    }
                }
                if (connected) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = ready && !loading, onClick = { Diagnostics.button("scan_current"); model.scan(false) }, modifier = Modifier.weight(1f)) { Text("Этот список") }
                        Button(enabled = ready && !loading, onClick = { Diagnostics.button("scan_all"); model.scan(true) }, modifier = Modifier.weight(1f)) { Text("Собрать всё") }
                }
                if (popup == null && code !in setOf("NOT_STARTED", "BROWSING", "MUSIC_PAGE_READY", "SCANNING", "NAVIGATION_BLOCKED", "PAGE_LOAD_FAILED", "TLS_FAILED")) InfoNote(message)
                if (connected && popup == null) InfoNote("Повторный сбор заменит сохранённую коллекцию.")
            }
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
            Text(if (snapshot.sources.isEmpty()) "Сначала собери музыку во вкладке «Коллекция», затем выбери формат файла."
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
