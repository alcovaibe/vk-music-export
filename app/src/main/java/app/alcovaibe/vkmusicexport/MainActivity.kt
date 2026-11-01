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

class MainActivity : ComponentActivity() {
    private val updates: app.alcovaibe.vkmusicexport.updates.UpdateViewModel by viewModels()
    private val nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel by viewModels()
    private fun openNotification(intent: Intent?) {
        if(intent?.getBooleanExtra(app.alcovaibe.vkmusicexport.music.PlaybackRuntime.OPEN_EXTRA,false)==true) {
            app.alcovaibe.vkmusicexport.music.PlaybackRuntime.expanded.value=true
            intent.removeExtra(app.alcovaibe.vkmusicexport.music.PlaybackRuntime.OPEN_EXTRA)
        }
        intent?.getStringExtra(app.alcovaibe.vkmusicexport.music.MaintenanceRuntime.OPEN_EXTRA)?.let{stage->
            nativeMusic.openMaintenanceNotification(stage);intent.removeExtra(app.alcovaibe.vkmusicexport.music.MaintenanceRuntime.OPEN_EXTRA)
        }
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
                window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
            }
            MusicTheme { MusicApp(updates.controller,nativeMusic) }
        }
    }
}

@Composable
internal fun MusicTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary=Color(0xFF786859),onPrimary=Color.White,
        primaryContainer=Color(0xFFE9DFD3),onPrimaryContainer=Color(0xFF30271F),
        secondary=Color(0xFF756758),secondaryContainer=Color(0xFFE9E0D5),onSecondaryContainer=Color(0xFF382C21),
        tertiary=Color(0xFF327943),tertiaryContainer=Color(0xFFE5EBD9),onTertiaryContainer=Color(0xFF233B26),
        background=Color(0xFFF7F4EE),surface=Color.White,onBackground=Color(0xFF20211F),onSurface=Color(0xFF20211F),
        surfaceVariant=Color(0xFFEEEAE3),onSurfaceVariant=Color(0xFF62645D),outline=Color(0xFF89877F),outlineVariant=Color(0xFFD6CFC4),
        surfaceContainer=Color(0xFFF1EDE6),surfaceContainerLow=Color(0xFFFAF7F2),surfaceContainerHigh=Color(0xFFECE7DF),surfaceContainerHighest=Color(0xFFE8E3DB),
        error=Color(0xFFAD3229),errorContainer=Color(0xFFFFDAD6)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)
    ), content = content)
}

@Composable
private fun MusicApp(updates: app.alcovaibe.vkmusicexport.updates.UpdateController,nativeMusic: app.alcovaibe.vkmusicexport.music.NativeMusicViewModel) {
    var updatesRequested by rememberSaveable{mutableStateOf(false)}
    val downloadsOpen by nativeMusic.downloadsOpen.collectAsState()
    val queueAccount by nativeMusic.queueAccount.collectAsState()
    val context=LocalContext.current
    app.alcovaibe.vkmusicexport.updates.HomeUpdateLifecycle(updates,!downloadsOpen)
    app.alcovaibe.vkmusicexport.music.NativeMusicPanel(nativeMusic,true,
        onDismiss={(context as? android.app.Activity)?.finish()},onUpdates={updatesRequested=true})
    app.alcovaibe.vkmusicexport.updates.UpdatePanel(updates,showSheet=!downloadsOpen,showButton=false,requested=updatesRequested,onDismiss={updatesRequested=false})
    queueAccount?.let{owner->AlertDialog(onDismissRequest=nativeMusic::dismissQueueAccount,
        title={Text("Привязать старую очередь?")},
        text={Text("В старой версии аккаунт очереди не сохранялся. Сейчас проверен аккаунт ВК $owner. Подтверди, что эта очередь принадлежит ему. Готовые файлы сохранятся.")},
        confirmButton={TextButton(onClick=nativeMusic::confirmQueueAccount){Text("Привязать и продолжить")}},
        dismissButton={TextButton(onClick=nativeMusic::dismissQueueAccount){Text("Отмена")}})}
}
