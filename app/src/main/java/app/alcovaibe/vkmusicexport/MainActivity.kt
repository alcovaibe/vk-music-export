package app.alcovaibe.vkmusicexport

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
    val web = remember { WebView(context).also(model::attach) }
    DisposableEffect(web) { onDispose { model.detach(web) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeVk = { model.stop(); model.activeWeb()?.clearFocus(); keyboard?.hide(); browser = false }
    val openVk = { browser = true; model.openLogin() }
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
                    AndroidView(factory = { child }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize(),
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
                    IconButton(onClick = { confirm = "settings" }) { Icon(Icons.Default.MoreVert, "Настройки") }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("Коллекция", "Экспорт").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index; selected = null },
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
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("VK Music Export diagnostics", model.diagnosticText()))
                android.widget.Toast.makeText(context, "Диагностика скопирована", android.widget.Toast.LENGTH_SHORT).show()
                confirm = null
            }) { Text("Скопировать диагностику") }
            TextButton(onClick = {
                val updates = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/alcovaibe/vk-music-export/releases/latest"))
                try {
                    context.startActivity(updates)
                    confirm = null
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "Откройте GitHub Releases в браузере", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Обновления на GitHub") }
            TextButton(enabled = !running, onClick = { confirm = "logout" }) { Text("Выйти из ВК") }
            TextButton(enabled = !running, onClick = { confirm = "clear" }) { Text("Очистить коллекцию", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { confirm = null }) { Text("Готово") } })
    if (confirm == "clear" || confirm == "logout") AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "clear") "Очистить коллекцию?" else "Выйти из ВК?") },
        text = { Text(if (confirm == "clear") "Списки будут удалены из приложения. Экспортированные файлы останутся." else "Потребуется повторный вход. Собранные списки останутся на телефоне.") },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = { if (confirm == "clear") model.clearCollection() else model.logout(); confirm = null }) { Text("Подтвердить") } })
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
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightI…4628 tokens truncated…"Полнота сбора указана в отчёте внутри ZIP и JSON.")
        }
        if (result.isNotBlank()) item { Text(result, color = MaterialTheme.colorScheme.primary) }
    }
}
