package app.alcovaibe.vkmusicexport.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.SettingsPage
import app.alcovaibe.vkmusicexport.SecureSettingsContent
import app.alcovaibe.vkmusicexport.BuildConfig
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

@Composable
internal fun UpdatePanel(controller: UpdateController,open: Boolean,onClose: ()->Unit) {
    val state by controller.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var credentials by remember{mutableStateOf(false)}
    var input by remember{mutableStateOf("")}
    var invalid by remember{mutableStateOf(false)}
    var installing by remember{mutableStateOf(false)}
    var retryEnabled by remember{mutableStateOf(true)}
    LaunchedEffect(state.status) {
        retryEnabled=state.status !in setOf(UpdateStatus.CURRENT,UpdateStatus.ERROR,UpdateStatus.ACCESS,UpdateStatus.RATE_LIMIT)
        if(!retryEnabled){kotlinx.coroutines.delay(5000);retryEnabled=true}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){installing=false}
    val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){installing=false}
    val visible=open
    fun close(){Diagnostics.button(if(state.status==UpdateStatus.DOWNLOADING)"cancel_update_download" else "close_updates");input="";credentials=false;controller.defer();onClose()}
    fun closeCredentials(){Diagnostics.button("dismiss_update_access");input="";credentials=false}
    fun browse(url: String){try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch (_: Exception){android.widget.Toast.makeText(context,"Не удалось открыть браузер",android.widget.Toast.LENGTH_LONG).show()}}
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
    if(visible&&!credentials)SettingsPage("Обновления",onBack=::close,tag="updates_screen") {
            Text("Установлено: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text(if(state.configured)"Доступ к приватным релизам настроен" else "Для приватных релизов нужен токен GitHub")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Проверять при открытии\nНе чаще раза в сутки",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(checked=state.automatic,onCheckedChange={Diagnostics.button("automatic_updates");controller.automatic(it)},enabled=state.initialized&&!state.editing)
            }
            state.release?.let{Text("Доступно: ${it.name} (${it.code})")}
            val text=when(state.status) {
                UpdateStatus.CHECKING->"Проверяем GitHub…"
                UpdateStatus.CURRENT->"Новых совместимых версий нет."
                UpdateStatus.ACCESS->"Проверь токен, срок его действия и право Contents: Read-only для этого репозитория. GitHub также возвращает отказ, если релиз недоступен."
                UpdateStatus.ERROR->"Не удалось проверить или загрузить обновление. Проверь сеть и повтори."
                UpdateStatus.RATE_LIMIT->"GitHub ограничил частоту запросов. Повтори позже."
                UpdateStatus.DOWNLOADING->"Загружено ${state.received/1024} из ${state.total/1024} КБ"
                UpdateStatus.READY->"APK проверен. Нажми «Установить» и подтверди установку в Android."
                UpdateStatus.INVALID_APK->"APK не прошёл проверку. Установка заблокирована; повтори загрузку."
                UpdateStatus.INSTALL_ERROR->"Не удалось открыть установку. APK сохранён; повтори попытку."
                UpdateStatus.AVAILABLE->"Обновление готово к загрузке."
                else->if(state.editing)"Сохраняем доступ…" else ""
            }
            if(text.isNotEmpty())Text(text,Modifier.testTag("update_status"))
            if(state.status==UpdateStatus.CHECKING)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.status==UpdateStatus.DOWNLOADING)LinearProgressIndicator(progress={if(state.total>0)(state.received.toFloat()/state.total).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())
            if(state.apk!=null)Text("Если Android откроет разрешение установки из этого приложения, разреши его, вернись и снова нажми «Установить».",style=MaterialTheme.typography.bodySmall)
            if(state.status!=UpdateStatus.DOWNLOADING&&state.apk==null) {
                if(state.release!=null)TextButton(enabled=retryEnabled,onClick={Diagnostics.button("refresh_update");controller.cancel();controller.check()}){Text("Проверить заново")}
                TextButton(enabled=state.initialized&&!state.editing,onClick={Diagnostics.button("configure_update_access");input="";invalid=false;credentials=true}){Text(if(state.configured)"Изменить токен" else "Настроить доступ GitHub")}
                if(state.configured)TextButton(enabled=!state.editing,onClick={Diagnostics.button("remove_update_access");controller.configure(null)}){Text("Удалить токен")}
                TextButton(onClick={Diagnostics.button("open_update_releases");browse("https://github.com/alcovaibe/vk-music-export/releases/latest")}){Text("Открыть релизы GitHub")}
            }

        when {
            state.apk!=null->TextButton(enabled=!installing,onClick=::install){Text("Установить")}
            state.release!=null&&state.status!=UpdateStatus.DOWNLOADING->TextButton(onClick={Diagnostics.button("download_update");controller.download()}){Text("Скачать обновление")}
            state.status!=UpdateStatus.DOWNLOADING->TextButton(enabled=retryEnabled&&state.initialized&&state.configured&&!state.editing&&state.status!=UpdateStatus.CHECKING,onClick={Diagnostics.button("check_updates");controller.check()}){Text("Проверить обновления")}
        }

        TextButton(onClick=::close){Text(if(state.status==UpdateStatus.DOWNLOADING)"Отменить загрузку" else "Закрыть")}
    }

    if(visible&&credentials)SecureSettingsContent {
        SettingsPage("Доступ GitHub",onBack=::closeCredentials,tag="update_access_screen") {
            Text("Создай fine-grained token: владелец alcovaibe → Only select repositories → vk-music-export → Contents: Read-only. Выбери срок действия. Вставь токен только здесь.")
            TextButton(onClick={Diagnostics.button("open_update_token_setup");browse("https://github.com/settings/personal-access-tokens/new")}){Text("Создать токен в GitHub")}
            OutlinedTextField(value=input,onValueChange={if(it.length<=256){input=it;invalid=false}else invalid=true},label={Text("Токен GitHub")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),modifier=Modifier.fillMaxWidth().testTag("update_token"),isError=invalid)
            if(invalid)Text("Нужен fine-grained токен github_pat_… длиной до 256 символов.")
            Text("Токен хранится на устройстве в зашифрованном виде. Можно удалить его здесь или отозвать в GitHub.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!state.editing,onClick={
        Diagnostics.button("save_update_access")
        if(UpdateProtocol.validToken(input.trim())){controller.configure(input);input="";credentials=false}else invalid=true
    }){Text("Сохранить и проверить")}
            TextButton(onClick=::closeCredentials){Text("Отмена")}
        }
    }

}
