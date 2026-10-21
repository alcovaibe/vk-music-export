package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.diagnostics.DiagnosticControls
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(title: String,onBack: ()->Unit,tag: String="settings_page",
    content: @Composable ColumnScope.()->Unit) {
    BackHandler {Diagnostics.event("settings_navigation",mapOf("screen" to tag,"detector" to "system_back"));onBack()}
    Scaffold(modifier=Modifier.fillMaxSize().testTag(tag),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(title)},navigationIcon={
            IconButton(onClick={Diagnostics.button(tag+"_back");onBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
        })}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable
internal fun SettingsScreen(canClear: Boolean,onBack: ()->Unit,onNative: ()->Unit,
    onUpdates: ()->Unit,onClear: ()->Unit) {
    SettingsPage("Настройки",onBack,"settings_screen") {
        Text("ВКонтакте",style=MaterialTheme.typography.titleMedium)
        TextButton(onClick=onNative){Text("Прямой доступ ВК · эксперимент")}
        HorizontalDivider()
        Text("Диагностика",style=MaterialTheme.typography.titleMedium)
        DiagnosticControls()
        HorizontalDivider()
        TextButton(onClick=onUpdates){Text("Обновления приложения")}
        TextButton(enabled=canClear,onClick=onClear){Text("Очистить коллекцию",color=MaterialTheme.colorScheme.error)}
        Text("VK Music Export · "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private tailrec fun Context.settingsActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> null
}

/** Credentials never enter saved state; secure flag is scoped to the credential screen. */
@Composable
internal fun SecureSettingsContent(content: @Composable ()->Unit) {
    val window=LocalContext.current.settingsActivity()?.window
    DisposableEffect(window) {
        val wasSecure=((window?.attributes?.flags?:0) and WindowManager.LayoutParams.FLAG_SECURE)!=0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{if(!wasSecure)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    content()
}
