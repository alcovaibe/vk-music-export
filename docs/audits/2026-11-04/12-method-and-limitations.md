# Методика, покрытие и открытые проверки

Зафиксированы 278 tracked files; выполнен полный файловый/лексический проход и SHA-256 inventory. Включены 175 Kotlin-файлов, 7 Markdown, Gradle конфигурации/lock/checksum metadata, manifest/res XML, shell/Python scripts и четыре workflows. Все текстовые файлы читаются inventory скриптом; binary audio fixtures и Gradle wrapper JAR инвентаризированы по байтам/хешу, не декомпилировались. Лексическое чтение файла не означает ручное доказательство каждой ветви. Вручную прослежены основные end-to-end пути auth/session, catalog/friends, export/storage/recovery, playback, updater и release scripts; тесты проверены по структуре, assertions/fixtures и целевым контрактам, а не объявлены все повторно запущенными локально.

**Граница полноты:** это широкий статический аудит с ограниченной динамикой. Полный верификационный аудит по всем пунктам ТЗ ещё не закрыт: нет новой Android-сессии/профилирования, реального тестового VK-аккаунта, полного текста текущих VK terms и исторического первичного audio API notice. Эти ограничения нельзя устранить процентом готовности или синтетическим HTTP200. Полученные CI результаты описаны отдельно от новых тестов.

Для каждого finding указан конкретный файл и начальная строка, ссылка закреплена полным SHA. Несколько строк одного выражения/метода читаются по ссылке в pinned source; позиции не относятся к будущему main. Reproduction — либо фактически выполненная проверка/замер, либо точный предлагаемый сценарий, с явным статусом. Нет claim о новом подтверждённом crash там, где не запускался Android.

## Незавершённые проверки

| Направление | Что осталось и какое доказательство нужно |
|---|---|
| Live VK | Разрешённый тестовый аккаунт с ожидаемыми доступами; own/friend/search/artist/album/renew/revoke capability matrix, обезличенный результат |
| Документация VK | Получить полный текущий terms/публичную документацию и первичный historical notice; зафиксировать версии/даты, отделить договорные исключения |
| Локальная сборка | Среда JDK21+SDK37+adb, доступ к Gradle/Maven/Google, unit/lint/release; текущее сетевое падение wrapper не является compile failure |
| Android performance | Macrobenchmark и profiling CPU/heap/GC/IO/network на реальном API29 и современном устройстве, API37 отдельный прогон |
| Масштаб | 100/4500/20000 реальных Android DB rows/files с provider cost, длительные queues и restart; существующий host benchmark недостаточен |
| Файлы | Большой signed upgrade с hash manifest до/после, ENOSPC/kill на точках публикации и edits, modified user file после interrupted edit |
| Долгая работа | Screen-off/Doze/OEM policies, сеть WiFi→offline→cellular, socket/redirect failures, процесс системы/force-stop и явный resume |
| Playback | Process-death/reboot, audio focus/звонки/BT, controller trust, long queues, seek expired URL и HLS key changes |
| UX | TalkBack/large text/small screens/IME/back-stack/жесты/таблица маршрутов на actual app, а не только composable fixture |
| Инфраструктура GitHub | Admin-only protection details, доступ к signing secret/backup, environment reviewers и долгосрочный storage evidence вне видимых API |

## Что сознательно не объявляется ошибкой

Нет blanket «музыка отсутствует», «плеера/MediaSession нет», «все тесты фиктивны», «нет индексов/транзакций», «лимит 100MiB/10 минут», «любая ошибка403 означает expired token», «API29 не поддерживается», «стабильный APK подписан временным ключом». Эти утверждения противоречат исходникам/полученному CI. Не найдена доказанная утечка реального token/cookie, проигрывание чужого content provider, universal SSRF exploit или фактическое удаление чужих файлов.

## Основные внешние источники

- [Исходный коммит аудита](https://github.com/alcovaibe/vk-music-export/commit/90e6848257e93d82b3a6b8af09cc58ef067a7703), [проверенный Actions run](https://github.com/alcovaibe/vk-music-export/actions/runs/41665666564), [точный Latest release](https://github.com/alcovaibe/vk-music-export/releases/tag/v0.12.1-build.1433).
- [VK ID Android SDK](https://github.com/VKCOM/vkid-android-sdk), [VKCOM public API schema на проверенном SHA](https://github.com/VKCOM/vk-api-schema/tree/333481bd082ad747d4873ef4a77f9247097eeef0), [errors.json](https://github.com/VKCOM/vk-api-schema/blob/333481bd082ad747d4873ef4a77f9247097eeef0/errors.json).
- [Media3 background playback / resumption](https://developer.android.com/media/media3/session/background-playback), [MediaSession.Callback](https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback).
- [Android FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout), [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types), [FGS behavior changes](https://developer.android.com/develop/background-work/services/fgs/changes).

Временные статусы/API данные относятся к проверке 04.11.2026, а CI measurements — к 03.11.2026. Состояние main может измениться после этого snapshot.
