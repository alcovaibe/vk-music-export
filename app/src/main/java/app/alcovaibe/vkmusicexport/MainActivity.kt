package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.view.WindowInsetsController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.alcovaibe.vkmusicexport.data.*

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
    override fun onResume(){super.onResume();nativeMusic.setForeground(true);updates.controller.resume()}
    override fun onPause(){nativeMusic.setForeground(false);updates.controller.pause();super.onPause()}
    override fun onStop(){nativeMusic.stopInBackground();super.onStop()}
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

@Composable
private fun MusicApp(model: MusicViewModel,updates: app.alcovaibe.vkmusicexport.updates.UpdateController,nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel) {
    val snapshot by model.snapshot.collectAsState()
    val ready by model.ready.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val pending by model.pendingCollection.collectAsState()
    var settingsScreen by rememberSaveable{mutableStateOf<String?>(null)}
    val updateState by updates.state.collectAsState()
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    var confirmClear by remember{mutableStateOf(false)}
    BackHandler(settingsScreen==null&&selected!=null){selected=null}
    if(settingsScreen==null)Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        bottomBar={
            Text("v ${BuildConfig.VERSION_NAME}",
                modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(start=12.dp,top=8.dp,bottom=8.dp),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        },
        topBar={
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=24.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=RoundedCornerShape(12.dp),color=Accent,modifier=Modifier.size(38.dp)) {
                    Box(contentAlignment=Alignment.Center){Icon(musicIcon(),null,tint=Color.White,modifier=Modifier.size(23.dp))}
                }
                Spacer(Modifier.width(10.dp))
                Text("VK Music Export",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                IconButton(onClick={Diagnostics.button("settings");settingsScreen="settings"}){Icon(Icons.Default.MoreVert,"Настройки")}
            }
        }
    ){padding->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(updateState.release!=null)TextButton(onClick={Diagnostics.button("updates_available");settingsScreen="updates"}) {
                Text(if(updateState.apk!=null)"Установить обновление"else"Доступно обновление приложения")
            }
            message?.let{Text(it,Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            CollectionScreen(snapshot,selected,query,ready,onSelect={selected=it},onQuery={query=it},onMusic={
                Diagnostics.button("open_native_music");settingsScreen="native"
            })
        }
    }
    pending?.let{source->
        val previous=snapshot.sources.firstOrNull{it.id==source.id}
        AlertDialog(onDismissRequest={if(!busy)model.cancelCollectionChange()},title={Text("Сохранение страницы ВК")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Список: ${source.title} · ${source.status.label}")
                Text("Позиций на странице: ${source.entries.size}. Всего по данным ВК: ${source.expectedCount?:"?"}.")
                Text("Сохраняется показанная страница. Совпадение количества не доказывает неизменность библиотеки ВК.")
                if(previous!=null)Text("Будет заменён этот список: сохранено ${previous.entries.size}, станет ${source.entries.size} позиций. Остальные списки сохранятся.")
                else Text("Список будет добавлен в коллекцию. Остальные списки сохранятся.")
            }
        },confirmButton={TextButton(enabled=!busy,onClick=model::confirmCollectionChange){Text(if(previous!=null)"Заменить список"else"Сохранить")}},
            dismissButton={TextButton(enabled=!busy,onClick=model::cancelCollectionChange){Text("Отмена")}})
    }
    if(settingsScreen=="settings")SettingsScreen(
        canClear=ready&&!busy&&pending==null,
        onBack={Diagnostics.button("settings_back");settingsScreen=null},
        onUpdates={Diagnostics.button("updates");settingsScreen="updates"},
        onClear={Diagnostics.button("request_clear");confirmClear=true})
    app.alcovaibe.vkmusicexport.updates.UpdatePanel(updates,settingsScreen=="updates"){settingsScreen="settings"}
    app.alcovaibe.vkmusicexport.music.NativeMusicPanel(nativeMusic,settingsScreen=="native",
        onDismiss={settingsScreen=null},onSample={model.stageNativePage(it);settingsScreen=null})
    if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("Очистить коллекцию?")},
        text={Text("Сохранённые списки будут удалены из приложения. Музыка в Music/VK Music Export и очередь загрузки сохранятся.")},
        dismissButton={TextButton(onClick={Diagnostics.button("cancel_confirmation");confirmClear=false}){Text("Отмена")}},
        confirmButton={TextButton(onClick={Diagnostics.button("confirm_clear");model.clearCollection();confirmClear=false}){Text("Подтвердить")}})
}

@Composable
private fun CollectionScreen(snapshot: CollectionSnapshot, selected: String?, query: String, ready: Boolean,
                             onSelect: (String?) -> Unit, onQuery: (String) -> Unit, onMusic: () -> Unit) {
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
            Button(onClick=onMusic,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) {
                Icon(musicIcon(),null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text("Открыть мою музыку")
            }
        }
        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (snapshot.sources.isEmpty()) {
            item { WelcomeArtwork() }
            item {
                Text("Музыка и плейлисты ВК", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(10.dp))
                Text("Открой музыкальный раздел, подключи ВК и сохраняй треки в Music/VK Music Export. Страницы с названиями можно сохранить здесь, в коллекции.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (selected == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(snapshot.uniqueTracks().size, tracks(snapshot.uniqueTracks().size).substringAfter(" ") + " в списке", Modifier.weight(1f))
                    StatTile(snapshot.playlistCount, counted(snapshot.playlistCount, "плейлист", "плейлиста", "плейлистов").substringAfter(" "), Modifier.weight(1f))
                }
            }
            item { SourceRow("Все треки", "${snapshot.uniqueTracks().size} · общий список", -1, onClick = { onSelect("all") }) }
            item { SectionLabel("МОЯ МУЗЫКА И ПЛЕЙЛИСТЫ") }
            items(snapshot.sources, key = { it.id }) { sourceItem ->
                SourceRow(sourceItem.title, "${tracks(sourceItem.entries.size)} · ${sourceItem.status.label}", snapshot.sources.indexOf(sourceItem),
                    onClick = { onSelect(sourceItem.id) })
            }
            item { InfoNote("Сохранённые страницы обновляются кнопкой «Сохранить эту страницу в коллекцию» в музыкальном разделе. Коллекция и очередь скачивания хранятся отдельно.") }
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
                    listOf("MP3", "M4A").forEach { label ->
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
