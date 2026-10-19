package app.alcovaibe.vkmusicexport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.alcovaibe.vkmusicexport.vk.CollectorBookmark
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*

@Composable
internal fun CollectorGuide(onDismiss: ()->Unit,onImport: ()->Unit,onMusic: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();var copying by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Сбор из Chrome")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Один раз создай закладку VKM Export в Chrome:")
            Text("1. Скопируй код кнопкой ниже. В Chrome добавь любую страницу в закладки через ⋮ → ★.")
            Text("2. Измени закладку: имя — VKM Export, адрес — скопированный код целиком, начиная с javascript:.")
            OutlinedButton(enabled=!copying,onClick={
                copying=true;Diagnostics.button("copy_collector_bookmark")
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val code=withContext(Dispatchers.IO){CollectorBookmark.code(context)}
                        check(code.length*2L<=240000)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VKM Export",code))
                        Toast.makeText(context,"Код закладки скопирован",Toast.LENGTH_SHORT).show()
                        Diagnostics.event("collector_bookmark_copied",mapOf("characters" to code.length))
                    }catch (_: Exception) {Diagnostics.event("collector_bookmark_copy_failed");Toast.makeText(context,"Не удалось скопировать код. Повтори попытку.",Toast.LENGTH_LONG).show()}
                    finally {copying=false}
                }
            },modifier=Modifier.fillMaxWidth()){Text(if(copying)"Готовим код…" else "Скопировать закладку")}
            Text("Для каждого списка:")
            Text("3. Открой в Chrome «Мою музыку» или отдельный плейлист. Нажми адресную строку, набери VKM Export и выбери закладку из подсказок — не поиск Google.")
            Text("4. В появившейся панели нажми «Начать сбор». Дождись остановки или останови вручную. Затем «Сохранить JSON».")
            Text("5. Вернись в приложение, выбери «Импорт JSON» и файл из загрузок Chrome. Проверь числа перед подтверждением замены списка.")
            Text("Повтори для каждого нужного плейлиста. Пароли, коды, cookies и аудиофайлы не собираются. Неизвестная разметка или ограничения Chrome могут помешать запуску: это проверяется на телефоне.")
            TextButton(onClick=onMusic){Text("Открыть музыку в Chrome")}
            TextButton(onClick=onImport){Text("Выбрать JSON для импорта")}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Закрыть")}})
}
