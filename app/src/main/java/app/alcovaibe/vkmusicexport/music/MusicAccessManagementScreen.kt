package app.alcovaibe.vkmusicexport.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.R
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun accessIcon(name: String,path: String)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
    addPath(addPathNodes(path),fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f)
}.build()
private val AccessCalendarIcon=accessIcon("AccessExpiry","M5 4 H19 Q21 4 21 6 V20 Q21 22 19 22 H5 Q3 22 3 20 V6 Q3 4 5 4 M7 1 V7 M17 1 V7 M3 10 H21")
private val AccessChromeIcon=accessIcon("ChromeAccess","M22 12 A10 10 0 1 1 2 12 A10 10 0 1 1 22 12 M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12 M12 8 H21 M8.5 14 L4 6 M14 15.5 L9 22")
private val AccessExpandIcon=accessIcon("ExpandAccessInfo","M5 9 L12 16 L19 9")
private val AccessCollapseIcon=accessIcon("CollapseAccessInfo","M5 15 L12 8 L19 15")

@Composable
private fun AccessCard(tag: String,content: @Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable
private fun AccessDetail(icon: ImageVector,text: String,modifier: Modifier=Modifier,color: Color=MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=color)
        Text(text,style=MaterialTheme.typography.bodyLarge,color=color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicAccessManagementScreen(
    state: NativeMusicState,session: MusicSessionState,now: Long,backgroundCheck: Boolean,queueWorking: Boolean,scanStage: String,
    onBack:()->Unit,onCheck:()->Unit,onConnect:()->Unit,onManual:()->Unit,onConfirm:()->Unit,onForget:()->Unit,onCancel:()->Unit
) {
    var details by rememberSaveable{mutableStateOf(true)}
    val enabled=state.initialized&&!state.busy&&!backgroundCheck
    val expiry=session.expiresAtMs
    val expired=expiry!=null&&expiry<=now
    val needsLogin=session.status==SessionStatus.LOGIN_REQUIRED||expired
    fun back(){Diagnostics.event("page_navigation",mapOf("screen" to "music_access_management","detector" to "system_back"));onBack()}
    BackHandler(onBack=::back)
    Scaffold(modifier=Modifier.fillMaxSize().testTag("music_access_management"),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text("Доступ к музыке",fontWeight=FontWeight.Bold)},navigationIcon={
            IconButton(onClick={Diagnostics.button("music_access_management_back");onBack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
        },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("music_access_management_list"),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("connection") {
                AccessCard("music_access_connection") {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(R.drawable.ic_vk_logo),"ВКонтакте",Modifier.size(44.dp))
                        Text(when {
                            !state.initialized->"Проверяем доступ…"
                            state.accessDamaged->"Данные доступа повреждены"
                            state.configured&&needsLogin->"Требуется новый вход"
                            state.configured->"Доступ сохранён"
                            else->"Доступ не подключён"
                        },fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        if(state.configured)Icon(if(needsLogin)FriendErrorIcon else Icons.Default.CheckCircle,null,
                            tint=if(needsLogin)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,modifier=Modifier.size(24.dp))
                    }
                    if(!state.initialized)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.configured) {
                        (session.accountId?:state.probe?.owner)?.let{AccessDetail(Icons.Default.Person,"Аккаунт ВК · ID $it")}
                        AccessDetail(AccessCalendarIcon,when {
                            session.status==SessionStatus.RENEWING->"Обновляем доступ ВК…"
                            session.status==SessionStatus.LOGIN_REQUIRED->"Доступ нужно подключить заново через Chrome."
                            expiry==null->"ВК не сообщил срок действия доступа."
                            expired->"Срок действия доступа истёк."
                            else->"Действует до "+DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(expiry))
                        },Modifier.testTag("vk_access_lifetime"))
                        AccessDetail(Icons.Default.Refresh,if(session.renewable)"Автоматическое продление включено" else "Автопродление недоступно",
                            color=if(session.renewable&&!needsLogin)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(session.renewable)"Если ВК отклонит продление, потребуется войти заново."
                            else "Новый вход через Chrome может подключить автоматическое продление.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(session.status==SessionStatus.RETRY)Text("Не удалось продлить доступ. Прежние данные сохранены; приложение повторит попытку позже.",style=MaterialTheme.typography.bodyMedium)
                        Button(onClick=onCheck,enabled=enabled,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(12.dp),
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("music_access_check")) {
                            Icon(Icons.Default.Check,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Проверить музыку")
                        }
                    }else {
                        Text(if(state.accessDamaged)"Сохрани новый доступ через Chrome или удали повреждённые данные. Музыка и очередь останутся."
                            else "Войди через Chrome, чтобы открыть свою музыку и музыкальные профили друзей.",style=MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick=onManual,enabled=enabled,shape=RoundedCornerShape(14.dp),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),
                            border=BorderStroke(1.dp,if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Настроить доступ ВК")}
                    }
                }
            }
            item("chrome") {
                AccessCard("music_access_chrome") {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(AccessChromeIcon,null,Modifier.size(26.dp))
                        Text("Вход через Chrome",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)
                    }
                    Text("Открой вход в Chrome. Если ВК покажет шестизначный код, вернись в приложение и введи его. Если код не потребуется, подключение проверится автоматически.",style=MaterialTheme.typography.bodyLarge)
                    if(state.stage!="browser_auth")OutlinedButton(onClick=onConnect,enabled=enabled,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(12.dp),
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),
                        border=BorderStroke(1.dp,if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("music_access_connect")) {
                        Text(if(state.configured)"Войти заново через Chrome" else "Войти через Chrome")
                    }
                    state.authPhase?.let{Text(it.label,Modifier.testTag("native_auth_phase"),style=MaterialTheme.typography.bodyMedium)}
                    if(state.busy&&state.stage=="browser_auth") {
                        if(!state.stopping&&state.authPhase in setOf(DeviceAuthPhase.READY,DeviceAuthPhase.WAITING,DeviceAuthPhase.REQUEST_OPENED))
                            Button(onClick=onConfirm,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Открыть подтверждение ВК")}
                        Text("Срок заявки идёт и в Chrome: не более 5 минут. Проверка результата продолжается после возвращения в приложение.",style=MaterialTheme.typography.bodyMedium)
                    }
                    state.authIssue?.let{Text(it.label+" Код: AUTH_"+it.name,color=MaterialTheme.colorScheme.error)}
                }
            }
            item("information") {
                AccessCard("music_access_information") {
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("music_access_info_toggle").clickable(role=Role.Button){details=!details},
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Info,null,Modifier.size(26.dp))
                        Text("Что важно знать",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        Icon(if(details)AccessCollapseIcon else AccessExpandIcon,if(details)"Свернуть сведения" else "Развернуть сведения")
                    }
                    if(details) {
                        Text("Используется внутренний протокол ВК. Сессия может предоставлять права шире доступа к музыке. Музыкальный раздел может быть недоступен для этой сессии.",style=MaterialTheme.typography.bodyMedium)
                        Text("Новый доступ сохраняется после успешной проверки музыкального раздела.",style=MaterialTheme.typography.bodyMedium)
                        Text("Удаление данных завершит вход только на этом устройстве. Отозвать сессию можно в настройках аккаунта ВК. Музыка и очередь сохранятся.",style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if(state.message!=null||state.error!=null||state.busy)item("result") {
                AccessCard("music_access_result") {
                    state.message?.let{Text(it)}
                    state.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
                    if(state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(when {
                            state.stopping->"Завершаем предыдущую операцию…"
                            state.waitingForSlot->"Ожидаем завершения предыдущей операции…"
                            state.preparing!=null->state.preparing
                            state.stage=="browser_auth"->state.authPhase?.label?:"Ожидание входа ВК"
                            state.stage.startsWith("duplicates_")->scanStage.ifEmpty{"Подготовка проверки дубликатов"}
                            else->"Проверка музыкального доступа"
                        })
                        if(state.stage!="browser_auth")Text("Держи приложение открытым. При уходе операция остановится; готовые файлы сохранятся.",style=MaterialTheme.typography.bodyMedium)
                        TextButton(enabled=!state.stopping,onClick=onCancel){Text("Остановить")}
                    }
                }
            }
            if(state.configured||state.accessDamaged)item("forget") {
                TextButton(enabled=!state.busy&&!backgroundCheck&&!queueWorking,onClick=onForget,
                    colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.testTag("music_access_forget").heightIn(min=48.dp)) {
                    Icon(Icons.Default.Delete,null,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text("Удалить данные доступа")
                }
            }
        }
    }
}
