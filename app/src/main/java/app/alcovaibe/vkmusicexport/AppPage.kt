package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppPage(title: String,onBack: ()->Unit,tag: String="app_page",
    actions: @Composable RowScope.()->Unit={},content: @Composable ColumnScope.()->Unit) {
    BackHandler {Diagnostics.event("page_navigation",mapOf("screen" to tag,"detector" to "system_back"));onBack()}
    Scaffold(modifier=Modifier.fillMaxSize().testTag(tag),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(title)},actions=actions,navigationIcon={
            IconButton(onClick={Diagnostics.button(tag+"_back");onBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
        })}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

private tailrec fun Context.hostActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/** Credentials never enter saved state; secure flag is scoped to the credential screen. */
@Composable
internal fun SecureCredentialContent(content: @Composable ()->Unit) {
    val window=LocalContext.current.hostActivity()?.window
    DisposableEffect(window) {
        val wasSecure=((window?.attributes?.flags?:0) and WindowManager.LayoutParams.FLAG_SECURE)!=0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{if(!wasSecure)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LazyAppPage(title: String,onBack: ()->Unit,tag: String,
    actions: @Composable RowScope.()->Unit={},content: LazyListScope.()->Unit) {
    BackHandler {Diagnostics.event("page_navigation",mapOf("screen" to tag,"detector" to "system_back"));onBack()}
    Scaffold(modifier=Modifier.fillMaxSize().testTag(tag),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(title)},actions=actions,navigationIcon={
            IconButton(onClick={Diagnostics.button(tag+"_back");onBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
        })}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag(tag+"_list"),
            contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
