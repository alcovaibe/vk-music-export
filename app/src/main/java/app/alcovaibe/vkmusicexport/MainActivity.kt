package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.view.WindowInsetsController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext

private val Ink = Color(0xFF202337)
private val Accent = Color(0xFF5B4DE8)

@Composable
private fun musicIcon() = ImageVector.vectorResource(R.drawable.ic_music_note)

class MainActivity : ComponentActivity() {
    private val updates: app.alcovaibe.vkmusicexport.updates.UpdateViewModel by viewModels()
    private val nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel by viewModels()
    private fun openNotification(intent: Intent?) {
        if(intent?.getBooleanExtra(app.alcovaibe.vkmusicexport.music.DuplicateCheckRuntime.OPEN_EXTRA,false)==true){
            nativeMusic.openDuplicateNotification();intent.removeExtra(app.alcovaibe.vkmusicexport.music.DuplicateCheckRuntime.OPEN_EXTRA)
        }
    }
    override fun onNewIntent(intent: Intent){super.onNewIntent(intent);setIntent(intent);openNotification(intent)}
    override fun onResume(){super.onResume();nativeMusic.setForeground(true)}
    override fun onPause(){nativeMusic.setForeground(false);super.onPause()}
    override fun onStop(){nativeMusic.stopInBackground();super.onStop()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNotification(intent)
        enableEdgeToEdge()
        setContent {
            SideEffect {
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(0, lightBars)
            }
            MusicTheme { MusicApp(updates.controller,nativeMusic) }
        }
    }
}

@Composable
internal fun MusicTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFB9AFFF), background = Color.Black, surface = Color(0xFF1C1D28),
        surfaceVariant = Color(0xFF2A2B3C), onSurfaceVariant = Color(0xFFB9B9CA)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)
    ), content = content)
}

@Composable
private fun MusicApp(updates: app.alcovaibe.vkmusicexport.updates.UpdateController,nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel) {
    var screen by rememberSaveable{mutableStateOf<String?>(null)}
    val downloadsOpen by nativeMusic.downloadsOpen.collectAsState()
    val queue by app.alcovaibe.vkmusicexport.music.AudioExportRuntime.state.collectAsState()
    val musicState by nativeMusic.state.collectAsState()
    val duplicates by nativeMusic.duplicates.collectAsState()
    val queueAccount by nativeMusic.queueAccount.collectAsState()
    val context=LocalContext.current
    LaunchedEffect(duplicates.visible){if(duplicates.visible)screen="native"}
    app.alcovaibe.vkmusicexport.updates.HomeUpdateLifecycle(updates,screen!="native"&&!downloadsOpen)
    if(screen!="native")Scaffold(
        modifier=Modifier.testTag("home_screen"),containerColor=MaterialTheme.colorScheme.background,
        bottomBar={
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Text("v ${BuildConfig.VERSION_NAME}",Modifier.weight(1f),
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                app.alcovaibe.vkmusicexport.updates.UpdatePanel(updates,showSheet=!downloadsOpen)
            }
        },
        topBar={
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=24.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=RoundedCornerShape(12.dp),color=Accent,modifier=Modifier.size(38.dp)) {
                    Box(contentAlignment=Alignment.Center){Icon(musicIcon(),null,tint=Color.White,modifier=Modifier.size(23.dp))}
                }
                Spacer(Modifier.width(10.dp))
                Text("VK Music Export",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                LatestReportMenu()
            }
        }
    ){padding->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HomeScreen(queue,onDownloads=nativeMusic::showDownloads,onMusic={
                Diagnostics.button("open_native_music");screen="native"
            })
        }
    }
    app.alcovaibe.vkmusicexport.music.NativeMusicPanel(nativeMusic,screen=="native",
        onDismiss={screen=null})
    if(downloadsOpen)app.alcovaibe.vkmusicexport.music.DownloadProgressSheet(queue,musicState.busy||duplicates.busy,musicState.error,
        onPause=nativeMusic::pauseQueue,onResume={nativeMusic.resumeQueue()},onRetry={nativeMusic.resumeQueue(true)},
        onClear=nativeMusic::clearQueue,onDismiss=nativeMusic::dismissDownloads,
        onSourceRetry=nativeMusic::retrySource,onExportReport={nativeMusic.shareExportReport(context)})
    queueAccount?.let{owner->AlertDialog(onDismissRequest=nativeMusic::dismissQueueAccount,
        title={Text("Привязать старую очередь?")},
        text={Text("В старой версии аккаунт очереди не сохранялся. Сейчас проверен аккаунт ВК $owner. Подтверди, что эта очередь принадлежит ему. Готовые файлы сохранятся.")},
        confirmButton={TextButton(onClick=nativeMusic::confirmQueueAccount){Text("Привязать и продолжить")}},
        dismissButton={TextButton(onClick=nativeMusic::dismissQueueAccount){Text("Отмена")}})}
}

@Composable
private fun HomeScreen(queue: app.alcovaibe.vkmusicexport.music.ExportSnapshot,onDownloads: ()->Unit,onMusic: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Button(onClick=onMusic,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) {
                Icon(musicIcon(),null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text("VK Music")
            }
        }
        item {app.alcovaibe.vkmusicexport.music.DownloadQueueButton(queue,onDownloads)}
        item { WelcomeArtwork() }
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
