package app.alcovaibe.vkmusicexport.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import java.io.File
import kotlinx.coroutines.launch

internal object UpdateInstaller {
    fun permission(context: Context)=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))
    fun install(context: Context,file: File): Intent {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=android.content.ClipData.newRawUri("Update",uri)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatePanel(controller: UpdateController,showSheet: Boolean=true) {
    val state by controller.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var installing by remember{mutableStateOf(false)}
    var retryEnabled by remember{mutableStateOf(true)}
    LaunchedEffect(state.status) {
        retryEnabled=state.status !in setOf(UpdateStatus.CURRENT,UpdateStatus.ERROR,UpdateStatus.ACCESS,UpdateStatus.RATE_LIMIT)
        if(!retryEnabled){kotlinx.coroutines.delay(5000);retryEnabled=true}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){installing=false}
    val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){installing=false}
    fun close(){Diagnostics.button(if(state.status==UpdateStatus.DOWNLOADING)"cancel_update_download" else "close_updates");controller.defer()}
    fun install() {
        if(installing)return
        installing=true;Diagnostics.button("install_update")
        scope.launch {
            var launched=false
            try {
                val file=controller.verifiedApk() ?: return@launch
                if(context.packageManager.canRequestPackageInstalls())installer.launch(UpdateInstaller.install(context,file))
                else permission.launch(UpdateInstaller.permission(context))
                launched=true
            }catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch (_: Exception){controller.installFailed()}
            finally{if(!launched)installing=false}
        }
    }
    Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
        IconButton(onClick={Diagnostics.button("check_updates");controller.check()},
            enabled=state.initialized&&state.status !in setOf(UpdateStatus.CHECKING,UpdateStatus.DOWNLOADING),
            modifier=Modifier.testTag("check_updates")) {
            Icon(UpdateIcon,"Проверить обновления",Modifier.size(26.dp))
        }
        if(state.status==UpdateStatus.CHECKING)CircularProgressIndicator(Modifier.size(42.dp).testTag("updates_progress"),strokeWidth=2.dp)
    }
    val showResult=state.status in setOf(UpdateStatus.CURRENT,UpdateStatus.ACCESS,UpdateStatus.ERROR,UpdateStatus.RATE_LIMIT)
    if(showSheet&&(state.release!=null||showResult)) {
        ModalBottomSheet(onDismissRequest=::close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("updates_sheet")) {
                Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(state.release?.let{"Доступно обновление ${it.name}"}?:"Проверка обновлений",style=MaterialTheme.typography.titleLarge)
                    Text(when(state.status) {
                        UpdateStatus.CURRENT->"Новых совместимых версий нет."
                        UpdateStatus.ACCESS->"Источник обновлений недоступен. Повтори позже."
                        UpdateStatus.ERROR->"Не удалось проверить или загрузить обновление. Проверь сеть и повтори."
                        UpdateStatus.RATE_LIMIT->"Слишком много запросов. Повтори позже."
                        UpdateStatus.DOWNLOADING->"Загружено ${state.received/1024} из ${state.total/1024} КБ"
                        UpdateStatus.READY->"APK проверен. Установку нужно подтвердить в Android."
                        UpdateStatus.INVALID_APK->"APK не прошёл проверку. Повтори загрузку."
                        UpdateStatus.INSTALL_ERROR->"Не удалось открыть установку. APK сохранён; повтори попытку."
                        else->"Скачать и установить новую версию приложения?"
                    },Modifier.testTag("update_status"))
                    if(state.status==UpdateStatus.DOWNLOADING)LinearProgressIndicator(
                        progress={if(state.total>0)(state.received.toFloat()/state.total).coerceIn(0f,1f)else 0f},
                        modifier=Modifier.fillMaxWidth().testTag("download_progress"))
                    if(state.apk!=null)Text("Если откроется разрешение установки, разреши его, вернись и снова нажми «Установить».",style=MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=::close){Text(if(state.status==UpdateStatus.DOWNLOADING)"Отменить" else if(state.release!=null)"Позже" else "Закрыть")}
                    when {
                        state.apk!=null->TextButton(enabled=!installing,onClick=::install){Text("Установить")}
                        state.release!=null&&state.status!=UpdateStatus.DOWNLOADING->TextButton(onClick={Diagnostics.button("download_update");controller.download()}){Text(if(state.status==UpdateStatus.AVAILABLE)"Скачать" else "Повторить")}
                        showResult&&state.status!=UpdateStatus.CURRENT->TextButton(enabled=retryEnabled,onClick={controller.cancel();controller.check()}){Text("Повторить")}
                    }
                }
            }
        }
    }
}

/** Metadata checks enter only the visible home; explicit transfers survive pause/rotation. */
@Composable
internal fun HomeUpdateLifecycle(controller: UpdateController,home: Boolean) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,home,controller) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME&&home)controller.resume()
            else if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_RESUME)controller.pause()
        }
        lifecycle.addObserver(observer)
        if(home&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))controller.resume() else controller.pause()
        onDispose{lifecycle.removeObserver(observer);controller.pause()}
    }
}
