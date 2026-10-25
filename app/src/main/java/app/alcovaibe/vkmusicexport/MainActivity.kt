package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.view.WindowInsetsController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

private val Ink = Color(0xFF202337)
private val Accent = Color(0xFF5B4DE8)

@Composable
private fun musicIcon() = ImageVector.vectorResource(R.drawable.ic_music_note)

class MainActivity : ComponentActivity() {
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
            MusicTheme { MusicApp(updates.controller,nativeMusic) }
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
private fun MusicApp(updates: app.alcovaibe.vkmusicexport.updates.UpdateController,nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel) {
    var settingsScreen by rememberSaveable{mutableStateOf<String?>(null)}
    val updateState by updates.state.collectAsState()
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
            HomeScreen(onMusic={
                Diagnostics.button("open_native_music");settingsScreen="native"
            })
        }
    }
    if(settingsScreen=="settings")SettingsScreen(
        onBack={Diagnostics.button("settings_back");settingsScreen=null},
        onUpdates={Diagnostics.button("updates");settingsScreen="updates"})
    app.alcovaibe.vkmusicexport.updates.UpdatePanel(updates,settingsScreen=="updates"){settingsScreen="settings"}
    app.alcovaibe.vkmusicexport.music.NativeMusicPanel(nativeMusic,settingsScreen=="native",
        onDismiss={settingsScreen=null})
}

@Composable
private fun HomeScreen(onMusic: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Музыка ВК", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        }
        item {
            Button(onClick=onMusic,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) {
                Icon(musicIcon(),null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text("Открыть мою музыку")
            }
        }
        item { WelcomeArtwork() }
        item {
            Text("Музыка и плейлисты ВК", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(10.dp))
            Text("Открой музыкальный раздел, подключи ВК и сохраняй треки в Music/VK Music Export.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
