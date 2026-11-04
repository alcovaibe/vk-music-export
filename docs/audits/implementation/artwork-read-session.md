# F22: выбор обложки переживает проверенное продление доступа

Дата: 4 ноября 2026. База: `5c8dc1011976a3e3e6de4cd54dc0fa625a57bfd1`.
Ветка: `fix/audit-stage0-release-delivery-20261104`.

## Причина и изменение

Поиск/preview/подтверждение сравнивали credentialId с текущим vault. Этот ID меняется при штатном продлении, поэтому корректный результат поиска и уже просмотренная обложка отклонялись. Теперь состояние поиска и ArtworkChoice содержат `MusicReadIdentity`: readSessionId и известный accountId, без токена/секретов. `toString()` скрывает идентификаторы. Эту же проверку использует MusicSessionManager; проверка владельца renewal и CAS из F01 сохранена.

ViewModel проверяет первоначальную и разрешённую текущую сессию через общий `artworkAccess()`. При настоящем VkAudioClient он вызывает `MusicSessionManager.continueRead()`, а в независимом adapter/test пути читает vault и проверяет ту же identity. Продление того же readSessionId допустимо; новый независимый вход, включая тот же account/token, недопустим. Известный accountId также не может измениться при сохранённом ID сессии.

Подтверждение по-прежнему отдельное и одноразовое. Срок результатов — 10 минут, preview choice — 5 минут; ротация их не продлевает. Сохранены foreground/generation guards, проверка перед физической записью, исходная идентичность трека/URI/имени/папки, резервная копия и rollback ArtworkRepair. Формат БД и envelope, ключ подписи, версия приложения, API методы VK и разрешения Android не меняются.

## Новые проверки

Четыре JVM-теста ArtworkLookupTest:

1. Два продления через настоящий MusicSessionManager с синтетическим renewer/owner proof во время поиска и загрузки обложки; подтверждение с новым credentialId проходит ровно один раз.
2. Новый вход с тем же token/account либо изменённый owner при том же session ID не допускает запись; identity не выводит ID/token в toString.
3. Logout/новый вход между preview и подтверждением блокирует writer даже при передаче старого объекта доступа.
4. Ротация токена не продлевает TTL choice; просроченное и повторное подтверждение не записывают файл.

Новый `ArtworkLookupViewModelDeviceTest.verifiedRenewalKeepsArtworkChoiceWhileLoginAndLogoutCannotWrite` использует настоящий ViewModel, Keystore envelope/CAS, SQLite, MediaStore и MP3 fixture. Он проверяет блокировку нового входа с тем же token/account и logout с сохранностью исходного файла/индекса, затем выполняет два проверенных продления через MusicSessionManager между search → preview → apply и подтверждает добавление обложки при сохранении track/URI/имени/папки. Сетевые ответы и изображение синтетические; это не проверка реального VK-аккаунта. Существующий сценарий отмены при уходе в фон сохранён.

Verifier инструментальных результатов требует точное имя нового ViewModel-теста на каждом API. Python-регрессия подтверждает, что отчёт с остальными зелёными тестами и отсутствующим новым сценарием отвергается. Общий численный минимум не подменяет эту проверку.

## Фактические локальные результаты и ограничения

`python3 -m unittest discover -s tools -p 'test_*.py'`: **96 tests, 3.023 s, OK**. `git diff --check` пройден.

Локальный Gradle `testDebugUnitTest --tests '*ArtworkLookupTest' --tests '*MusicSession*'` не дошёл до конфигурации: wrapper не загрузил Gradle 9.8.2, `Network is unreachable`. Локальные Kotlin/device tests успешными не объявляются. Результаты JVM/Lint/R8 и API29–37 нужно читать в Android Actions по точному SHA этого изменения, а не по предшествующему F01 run.

Реальный VK renew/token expiry, длительная OEM-проверка и ручная приёмка не выполнялись. Новый файл не изменяет исторический аудит. Main и опубликованные Releases не затрагиваются; перенос исправления требует отдельного одобрения владельца.

## Разбор первого CI-прогона

Первый коммит F22: `755689bfcb714630b388461756ce63d98db95387`, Android run `41837220217`. JVM-отчёт содержит 277 случаев: 276 passed, 1 штатно skipped (`PublicDeviceAuthProbeTest`), без failures/errors. Все 17 ArtworkLookupTest и все 25 MusicSession/Boundary tests прошли. Lint debug/release, release R8 и OSV (217 зависимостей) успешны.

API34 завершился с 1 failure из 145 тестов. Новый F22 ViewModel-сценарий прошёл (0.867 s); упал существующий `DevicePairingViewModelDeviceTest.nextActivityWaitsForCancelledOwnerInsteadOfReceivingBusy` на немедленном assert строки 163 (весь сценарий 0.064 s). Эти результаты взяты из JUnit XML артефакта `Android-interface-API-34`, не из предположения о причине любого красного CI.

Тест ожидал синхронной публикации combined UI StateFlow после `runOnMainSync`, хотя `_state` и `_visibleState` обновляются разными coroutine collectors. Исправлена синхронизация проверки stopping/waiting: bounded await удерживает существующий `releaseCleanup` закрытым и требует busy + waitingForSlot. Во время ожидания по-прежнему обязательны отсутствие ошибки и ноль вызовов второго auth begin. Gate освобождается только после подтверждённого состояния; проверки READY и передачи третьему владельцу сохранены. Код авторизации ради этого падения не изменялся, тест не отключён и его условие не заменено просто задержкой или повторным запуском.

После изменения теста требуется новый полный CI по новому SHA; успешные результаты первого прогона не присваиваются следующему коммиту автоматически.
