# Повторная сверка F01–F30 — 07.11.2026

Работа ведётся **только онлайн через GitHub**, в `fix/audit-stage0-release-delivery-20261104`.
Стартовый фактический HEAD: `4539136253e2ca32f27947f50e7414407aaf3103`, а не указанный в задании `4d3a187`.
Исходный исторический аудит 04.11 не меняется. Отдельное внешнее ТЗ не предоставлено и изученным не считается.

Это текущий рабочий реестр, **не акт полного закрытия аудита**. Все 30 пунктов сохранены.
Наличие реализации, тестового файла и прохождение конкретного запуска — разные факты.
Полный повторный построчный review всех production/test файлов ещё не завершён.
Локальные Git/Gradle/adb здесь не запускаются; незакоммиченные локальные изменения не обследованы и не затронуты.

## Фактическая проверка CI

- [336add9 / 42379016204](https://github.com/alcovaibe/vk-music-export/actions/runs/42379016204): JVM/Lint/assembleRelease шаг success, все девять interface API29–37 и updater jobs success; build в целом failure из-за OSV; R8 instrumentation verification failure; SAST review устарел. Успех старого SHA не переносится на новый код.
- `09455f4`: обновлён точный review VkAudioClient после чтения HTTPS/allowlist/session границы, добавлены подробности XML-ошибок R8 и координат OSV.
- `d4c8616` / [42381892994](https://github.com/alcovaibe/vk-music-export/actions/runs/42381892994): JVM/Lint/R8 compilation шаг success, OSV сообщил 7 affected records; Java SAST сообщил одну непроверенную location; Python/Actions SAST success. R8 tests не прошли: `android-test.testRunFailed: Process crashed`. Это runtime failure тестового процесса, не прежняя missing Modifier compilation error.
- `30e30e3`, `400de2d` / [42382720006](https://github.com/alcovaibe/vk-music-export/actions/runs/42382720006): поиск/browser provider implementation и расширенная crash/SAST диагностика; итог этого run фиксируется отдельно после завершения.
- `400de2d`: 163 Python regression tests и JVM/Lint/compile шаг прошли; OSV оставался красным. На всех R8 API29/36/37 crash buffer показал `NoClassDefFoundError: androidx.tracing.Trace` в AndroidJUnitRunner до выполнения теста. Подготовлено точечное keep-правило публичного tracing API; широкие dontwarn заменены одним JDK-only enum. SARIF pinpoint: VkAudioClient.kt:154 — создание HTTPS Request, review location уточнена.
- Ни один новый ручной тест, численное performance измерение, stable release, update manifest или GitHub ruleset этой итерацией не публиковались/не менялись.

## Матрица реализации и приёмки

Пути с Kotlin-именами находятся в `app/src/main/java/app/alcovaibe/vkmusicexport/music/`, тесты — в соответствующих test/androidTest каталогах. Приоритеты взяты из исторического `findings.json`.

| ID | Приоритет | Исходный дефект | Фактическая реализация | Доказательства | Тесты | Статус | Оставшиеся ограничения |
|---|---|---|---|---|---|---|---|
| F01 | P1 | Подмена независимой read session | MusicReadIdentity и проверки MusicSessionManager до/после получения доступа сохранены | MusicSession.kt; session-boundary.md | MusicSessionBoundaryTest, MusicSessionTest; JVM task d4c8616 прошёл | Реализация есть; новая приёмка не завершена | Нужны Android-результаты текущего SHA и ручные A→B/A→новый A |
| F02 | P1 | Расхождение Latest и канала | Проверки APK/size/hash/package/signer и installer сохранены | Latest API и main channel: 0.12.1/1433/API29, SHA 3dac4905…e3889, 5969335 байт; channel-delivery success на d4c8616 | UpdateDeliveryDeviceTest, UpdateCheckpointDeviceTest; новый run отдельно | Реализация есть; публичные метаданные согласованы | Новая ручная установка не проводилась; manifest APK заново здесь не распаковывался |
| F03 | P1 | Неприменённая защита main | Проверен действующий ruleset 26682452: PR, build/interface/channel-delivery, strict base, no force-push/deletion, bypass=[] | GitHub API /rulesets/26682452; enforcement=active; current_user_can_bypass=never | Read-only проверка GitHub | Подтверждено настройками GitHub | Настройки не изменялись; это не проверка каждого будущего PR |
| F04 | P1 | Зависимость от внутренней авторизации/API | DeviceAuthApi и MusicApi — отдельные контракты; отсутствующие capabilities по умолчанию UNAVAILABLE; VkAudioClient явно EXPERIMENTAL | VkAudioClient.kt, DeviceAuth.kt, MusicSearchController.kt | Контракты fake provider и отказов; новый CI ожидается | Частично | Разрешённый официальный музыкальный provider/партнёрский scope не подтверждён; VK ID не объявляется музыкальным разрешением |
| F05 | P1 | Нет ручной приёмки точного stable APK | Fail-closed release_gate привязывает APK к SHA/source/version/buildRun; добавлены обязательные playback/BT/focus/UI/data сценарии и PROCEDURE.md | docs/release-acceptance/current.json остаётся pending | ReleaseAcceptanceTest; новые gate tests требуют CI | Процедура подготовлена; приёмка открыта | Нет физического устройства, разрешённого live аккаунта и принятого нового APK; stable не публиковался |
| F06 | P1 | Потеря playback state/гонки STOP | Durable checkpoint, session-aware restore и notification-generation защита сохранены | PlaybackRuntime, PlaybackCheckpoint, MusicPlaybackService; playback-resumption.md | PlaybackHistory/Restart/DeviceTest; ранее 336add9 interface API29–37 success | Реализация есть; текущая приёмка частична | Dedicated R8 процесс падал на d4c8616; причина устанавливается; BT/focus/OEM вручную не проверялись |
| F07 | P1 | Квадратичная запись metadata-run | Инкрементальные SQLite targets/progress и прежние миграции сохранены | TrackMetadataCheckpoints; metadata-checkpoints.md | MetadataCheckpointDeviceTest, workloads 100/4500/20000; точные отчёты текущего CI нужны отдельно | Реализация есть; повторная приёмка не завершена | Не приписываем этой итерации новые измерения/power-loss тесты |
| F08 | P2 | Пересериализация receipt JSON | SQLite receipts, version/fingerprint/TTL и owner isolation сохранены | duplicate-receipts.md; production receipts/cache | DuplicateReceiptKeysTest, DuplicateReceiptDeviceTest | Реализация есть; повторная приёмка не завершена | Нужны актуальные migration/ENOSPC/cancellation результаты; пользовательские файлы не удаляются |
| F09 | P2 | N+1 и весь список ошибок в snapshot | QueueReadIndex отделяет counters, bounded page и полный отчёт | QueueReadIndex.kt, DownloadsReader | QueueReadDeviceTest и host verifier | Реализация есть; повторная приёмка не завершена | Измерения нового SHA и 20000 ошибок не выдумываются |
| F10 | P2 | Полные сканы URI/source memberships | URI/physical index сохранён; схема7 нормализует account/source/occurrence с адресным composite index; CatalogDownloads читает точный источник | AudioExportStore.kt, SavedFileIndex.kt, FileMembershipIndex.kt | SourceMembershipDeviceTest (4 обязательных сценария), legacy migrations/relink; новый CI требуется | Реализация подготовлена; приёмка не завершена | Откат/масштабирование/новый upgrade-check должны подтвердиться на API29–37; JSON metadata сохранены совместимо |
| F11 | P2 | Provider queries для каждой строки | SavedLibrary публикует страницы и пакетные presence hints; строгие проверки перед операциями сохранены | SavedLibrary.kt; saved-library-and-partial-playback.md | SavedLibraryDeviceTest; incremental publication contracts | Реализация есть; повторная приёмка не завершена | Нет новых физических CPU/PSS/latency измерений |
| F12 | P2 | Preview друзей живёт после ухода | FriendAvailabilityReader имеет отдельные workers/generation/TTL; экран/foreground отменяют запросы; previewBlocked не использует общий busy | FriendMusicController.kt; friend-preview-lifecycle.md; NativeMusicPanel | FriendAvailabilityReaderTest, FriendPreviewLifecycleDeviceTest | Реализация есть; текущий Android-прогон нужен | Скачивание и maintenance не отменяются этой проверкой; live provider ещё не проверен |
| F13 | P2 | Повторное чтение каталогов при скачивании трека | Exact-ID snapshot download и export read cache сохранены | ExportLayout.single; MusicSourceReader; single-track-download.md | SingleTrackDownloadTest, SingleTrackDownloadDeviceTest, SourceReadDeviceTest | Основные исправления есть; приёмка частична | Нужно проверить все bulk/friend пути и нагрузочные отчёты текущего SHA |
| F14 | P2 | Скачанные подборки исчезают / нет локального входа | Видимость и явный pending filter сохранены; добавлены account-scoped credential-free snapshots, local-only playback с повторами, частичные/unconfirmed labels и retained local sources | LocalCatalogSnapshots.kt, PlaybackLists.library, NativeMusicViewModel/Panel | LocalCatalogDeviceTest (3), LocalCatalogPlaybackDeviceTest (1), PlaybackListsTest (2); новый CI требуется | Реализация подготовлена; приёмка нового SHA не завершена | Снимки создаются для собственного каталога после нового чтения/подготовки. Старые exports без descriptor остаются в Saved. Длительная физическая/offline приёмка ещё нужна |
| F15 | P2 | Нет пользовательского глобального поиска | Отдельный экран из меню MainActivity, debounce, пагинация, отмена/generation/session guards; play/download через PlaybackEntry; distinct denied/challenge/error/empty | MusicSearchController.kt, MusicSearchScreen.kt, VkAudioClient.searchMusic | MusicSearchControllerTest, GlobalMusicSearchDeviceTest; пока ожидают CI | Реализован новый путь; приёмка не завершена | Источник audio.search экспериментальный; результаты не доказывают права; рекомендации не добавлялись |
| F16 | P2 | У исполнителей нет track actions | Существующие session-bound artist snapshots используют общую PlaybackEntry/PlaybackExport и downloadPlayback | PlaybackModel.kt, MusicArtistsController.kt; artist-track-actions-2026-11-07.md | PlaybackListsTest, MusicArtistsControllerTest, SingleTrackDownloadDeviceTest | Реализация есть; приёмка частична | Нужны успешные новые Android jobs и live artist/album проверки |
| F17 | P2 | Собственные треки не запускаются tap | Показанный порядок/исходные позиции/повторы и download references сохранены | PlaybackLists.library, NativeMusicPanel | PlaybackListsTest, PartialPlaybackDeviceTest | Реализация есть; повторная приёмка не завершена | Проверенные локальные копии и свежие URL проверяются существующим runtime; ручной сценарий не проведён |
| F18 | P2 | DownloadsScreen недостижим | Сохранён штатный route DOWNLOADS, кнопка, Back и rememberSaveable; mini-player общий | NativeMusicPanel.kt; downloads-screen-and-queue-reads.md | Downloads/навигационные Android-контракты | Реализация есть; повторная приёмка не завершена | Уведомления и возврат на физическом устройстве не проверялись |
| F19 | P2 | Нет byte-range resume | Сохранён разрешённый безопасный restart; экран объясняет повторную загрузку незавершённого файла | AudioTransfer.kt, DownloadProgressContent | Добавлены cancel→renewed URL с точными bytes/no-append и rejection206/416; CI требуется | Частичное resume не реализовано; выбран безопасный restart | Готовые треки остаются; signed URLs/сегменты не сохраняются для склейки. Это явное ограничение, не Range-resume |
| F20 | P2 | Chrome обязателен | Явно выбирается пользовательский default HTTPS browser с экспортированным Custom Tabs service; нет произвольного fallback/WebView; VK URL validation сохранена | AuthBrowser.kt; manifest queries; обновлённые подписи UI | AuthBrowserPolicyTest, обновлённый NativeBrowserAuthDeviceTest | Реализация подготовлена; приёмка открыта | Нужны Chrome/Firefox/Edge, отсутствие default/service и отмена на устройстве; доверие пользовательскому браузеру не является SPKI pinning |
| F21 | P2 | VK201 приписывается приватности | Нейтральное сообщение; 201/15/7/5/403/empty/network различаются; raw error text не показывается | FriendMusic.kt | FriendMusicTest; JVM task d4c8616 прошёл | Исправлено в коде; ручная приёмка открыта | Реальные серверные ответы аккаунта не запрашивались |
| F22 | P2 | Lookup обложки теряет проверенное продление | Существующий readSessionId/account/expiry fencing сохранён | ArtworkLookup/ArtworkRepair; artwork-read-session.md | ArtworkLookupTest, ArtworkLookupDeviceTest | Реализация есть; повторная приёмка не завершена | Новые независимые входы не должны наследовать apply; текущий Android отчёт нужен |
| F23 | P2 | Неполная доступность | Существующие semantics и подписи сохранены; новый поиск имеет постоянную label и live error region | Compose UI и MusicSearchScreen | Автотесты accessibility + новый MainActivity route test | Частично | TalkBack/Switch Access, 360dp/landscape/IME/fontScale2 вручную не проводились |
| F24 | P2 | Нет полной performance/R8 приёмки | Dedicated minified playback реально исполняется; исправлены ABI/JUnit/report и сбор graphics/startup метрик | src/r8AndroidTest; verify-r8-evidence.py; r8-playback-check.sh | На96f1333 connectedReleaseAndroidTest success API29/36/37; aggregate падал в performance summary | Частично: functional R8 пройден, новый общий CI требуется | Холодный TotalTime и hot-return WaitTime разделены; кадры launcher/home не являются 20k scroll Macrobenchmark или длительной физической приёмкой |
| F25 | P2 | Неполная supply chain/SAST | App/build-tool inventories, OSV/CodeQL и strict verification сохранены; AGP зависимости обновлены адресно; checksums reviewed | dependency-audit.py; build.gradle.kts; config/sast-reviewed.json | build/OSV и все3 SAST на1ac130d/9d86399/5693631 success | Текущие выявленные dependency/SAST blockers исправлены | Это не обещание полного покрытия runner image/runtime; raw findings и точечные reviews сохранены |
| F26 | P2 | Монолитный coordinator/gate | Artists/availability/friend previews/search выделены; MusicCatalogController теперь подключён production и владеет section/playlist/local transitions, continuation identity проверяется до/после metadata write | MusicCatalogController.kt; NativeMusicViewModel wiring; LocalCatalogSnapshots | Controller unit contracts: renewal/new login/account/cancellation/local-only; MainActivity/Android контракты сохраняются; новый CI нужен | Частичная последовательная extraction | Admission/lifecycle по-прежнему у host; Auth/Maintenance/global busy требуют последующих отдельных изменений, полной переписи нет |
| F27 | P3 | Evidence теряется по retention | ZIP manifest/verifier/provenance и будущая публикация bundle предусмотрены | release_evidence.py, verify-release-evidence.py | Синтетические integrity/traversal/duplicate/subject tests | Частично | У актуального Latest видны только APK/SHA256SUMS; новый evidence asset не публиковался; наблюдения после retention нет |
| F28 | P3 | README/compat guide отстают | README обновляется под поиск/browser/filter; исправлена ложная ссылка на stable1311/minSdk34; добавлены Windows/JDK/SDK инструкции | README.md, docs/ANDROID_API29_COMPATIBILITY.md | Сверены manifest, Gradle и публичные Latest/channel метаданные | Обновлено для изменённых путей; полный link audit не завершён | Документация описывает код ветки, а не принятую новую stable-сборку; исторические audit/CHANGELOG сохранены |
| F29 | P3 | Предлагаются неработающие сортировки | Collections: default/title; tracks: artist/title, year только при реальных данных, addition order только library; выбор сохраняется по контексту, показан label | MusicLibrarySorting.kt, NativeMusicPanel | MusicLibrarySortingTest; JVM task d4c8616 прошёл | Исправлено в коде; Android-приёмка открыта | Нужна проверка меню/подписи после recreate; год по названию не угадывается |
| F30 | P3 | Legacy UI и ручная классификация | Недостижимые UI wrappers находятся только в androidTest; прежний standalone catalog controller заменён реально подключённым; catalogKinds/state choices/ignored args удалены из production | LegacyMusicUiFixtures.kt, LegacyCatalogChoices.kt; MusicCatalogController | Source usages review; прежние legacy/meta migration tests сохранены через test-only accessors; фактический UI/Controller tests остаются | Cleanup подготовлен, проверяется новым CI | catalog_kind:* meta не удаляются при migration; будущая auth/maintenance extraction относится к F26, а не к удалению форматов |

## Что остаётся до технического завершения

1. Устранить причину падения minified instrumentation, проверить фактическую Java SAST location и исправить семь OSV records с сохранением strict verification.
2. Добиться обязательных JVM/Lint/Android/R8/security успехов одного точного SHA; отменённый run не считать пройденным.
3. Завершить durable локальные подборки F14, безопасное partial resume F19, выделение и подключение оставшихся controllers F26, source/membership индексы F10 и доказанный cleanup F30.
4. Закончить полный повторный review исходников/тестов и перенести только подтверждённые результаты в эту матрицу.
5. Провести разрешённую live VK и физическую приёмку точного APK по [PROCEDURE.md](../../release-acceptance/PROCEDURE.md), включая Bluetooth/focus/TalkBack/данные после обновления.
6. С разрешения владельца отдельно выполнить публикацию и долговременную проверку evidence; сейчас это не выполняется.

Проценты готовности не используются. Main, подпись, applicationId/packageName, versionCode, minSdk29 и история релизов не изменены.

## Коммиты продолжения

- `ff58303`: точечная R8 tracing-граница, обновления AGP classpath, candidate verification workflow и точная SARIF location.
- `0f0ac41`: видимость скачанных подборок, явный фильтр и отмена скрытых preview/search readers.
- Этот документ и процедура приёмки находятся в следующем отдельном коммите. Итоговые новые CI-результаты ещё ожидаются.

## Проверка кандидата dependency verification

[42383489564](https://github.com/alcovaibe/vk-music-export/actions/runs/42383489564), source `4cfb915`: candidate job success.
Просмотрен полный diff: только 19 добавленных строк, без удаления/замены прежних hashes.
Добавлены JAR/POM jose4j 0.9.6 и jdom2 2.0.6.1, а также отсутствовавший JAR checker-qual 2.5.8.
Это новые явно закреплённые checksums; обычные Android jobs продолжают работать с `--dependency-verification strict`.
Candidate artifact ID: 13578083080; GitHub artifact digest: `0352e24a8c429ef392b764b9509c2d3df4c2e417b73c26593fef79c165e738f0`.
Происхождение значений — генерация Gradle в указанном GitHub job, не независимая криптографическая аттестация автора Maven-пакетов.

Также исправлена пропущенная ссылка `openDeviceAuthChrome` в DeviceConfirmationDeviceTest, из-за которой androidTest compilation `400de2d` падала до исполнения тестов. Проверка package pinning/redirect flags сохранена; выбор провайдера инъецируется в intent-контракт, а production использует только проверенный default browser. Выполнен поиск старого launcher и изменённых UI-подписей по всем существующим androidTest Kotlin-файлам.

### Проверка 58285f1

Новый compile выявил пропущенные imports Compose semantics/contentDescription для добавленного фильтра подборок. Исправлены imports; это ошибка этой итерации, не дефект исходной базы. Новый compile/test run обязателен. Широкий dontwarn, R8, Lint и тестовые gates не отключались.

### Разделение пользовательского поиска и repair lookup

Глобальный search больше не наследует лимит repair-поиска обложек (20/100). У него собственная валидация запроса, страница до 100 и явно показанный предел просмотра 20000 результатов; offset продолжает фактическую страницу. Метод ВК остаётся прежним allowlisted audio.search, без расширения авторизации. Добавлен регрессионный сценарий перехода за первые 100 записей. Старые lookup обложек/метаданных не меняются. Runtime-проверка выполняется следующим CI.

## Фактический прогон 1ac130d

[42384061603](https://github.com/alcovaibe/vk-music-export/actions/runs/42384061603):
build success (JVM/Lint/release R8 compilation/OSV), все SAST java-kotlin/Python/Actions success, channel-delivery и все девять updater jobs API29–37 success.
Ошибки OSV из AGP classpath после адресных обновлений больше не блокируют этот run.
R8 instrumentation ещё не принят: Trace-ошибка устранена, следующий crash — `NoSuchMethodError <init>(I)V` из `TestDirCalculator$outputDir$2`, т.е. отдельно сжатый test runner обращается к Kotlin runtime из target APK с изменённой ABI-сигнатурой.
Добавлено scoped keep публичной бинарной границы kotlin.jvm.internal (с разрешённым obfuscation через общую mapping), без отключения R8 приложения или тестового APK.
Один interface API36 job не стартовал из-за ошибки чтения ZIP при установке Android Emulator, до запуска тестов; это инфраструктурный отказ, а не результат тестирования.
Новый R8/Android run ещё обязателен.

Android suites API31/34 выполнили тесты, но FriendsMusicDeviceTest ещё требовал прежний текст о настройках приватности (F21). Обновлено точное ожидаемое нейтральное сообщение; проверки remote code, отмены и смены доступа в том же сценарии сохранены. Это изменение утверждённого UX-контракта, не увеличение timeout и не удаление теста.

### R8: внешняя ABI общей Kotlin-библиотеки

`9d86399` прошёл прежнюю Lambda-ошибку, но runner затем не нашёл `kotlin.LazyKt`. Это показывает общий контракт: test APK исключает Kotlin stdlib, поставляемую target APK, а target shrinker не видит внешних потребителей runner. Вместо дальнейшего перебора отдельных removed facades сохраняется public/protected ABI **только общей Kotlin-библиотеки** и используемых fixture coroutine entry points; имена продолжают обфусцироваться через mapping AGP. Оптимизация/минификация приложения, Compose/Media3 и самого test APK не отключается. Private implementation members не получают blanket keep. Проверка нового SHA обязательна; успех R8 ещё не заявлен.

В прогоне 9d86399 подтверждены полные interface jobs API30–37 success после исправления browser/F21 тестовых контрактов; API29 на момент записи ещё выполнялся. Это реальные Android jobs, а не только compilation. Предыдущий infrastructure failure загрузки эмулятора API36 в новом run не повторился. R8 target/test ABI продолжает требовать следующего исправления.

Завершение 9d86399: interface API29 также success. Таким образом, все девять interface API29–37 и все девять updater jobs прошли; build/OSV и все SAST тоже success. Aggregate остаётся failure из-за dedicated R8 runtime boundary. Следующий commit сохраняет общую library ABI; он должен пройти свой полный run.

## Продолжение: диагностика R8 и нормализация memberships

В `6c0c3e2` build/OSV и SAST прошли, однако dedicated R8 instrumentation завершился ошибкой тестов. Скрипт раньше прекращал работу до разбора XML. Коммит `7815740` сохраняет fail-closed поведение и печатает точную причину из JUnit XML при ошибке Gradle, не создавая успешное evidence.

В dedicated R8 test найдена некорректная сигнатура: expression body `runBlocking` выводил возвращаемый тип `Int` из `Log.i`, тогда как JUnit4 требует `void`. Коммит `4aa5dd8` задаёт `Unit` явно. Успех сценария на этом основании не заявляется — нужен фактический CI.

Для F10 подготовлена схема 7: отдельные source_account/source_kind/source_id/occurrence в memberships и составной индекс file_sources. URI/physical-file индексы схемы 5 сохраняются. Backfill читает до 100 payload за шаг внутри транзакции SQLiteOpenHelper; повреждённая запись прерывает upgrade с откатом. Повседневная проверка подборки читает только её account/source вместо savedFiles всего пользователя.

Добавлены обязательные Android-сценарии: независимый schema6 fixture и повторное открытие, несколько owners/occurrences одного URI, индексированные 100/4500/20000 memberships без декодирования чужих payload, rollback повреждённой legacy строки, link/adopt/forget. Legacy fixtures v1–v5 воспроизводят старые колонки, а не просто меняют user_version новой базы. Release upgrade-check должен проверять текущую схему 7 вместо устаревшего ожидания 4. Эти изменения требуют нового полного CI и не являются уже пройденной миграционной приёмкой.

Следующий R8 run `4aa5dd8` дошёл до тестового метода, но показал `IncompatibleClassChangeError` во внешнем вызове плеера: target R8 статизировал метод, тогда как test APK использовал прежний virtual ABI. `5693631` сохраняет только явно перечисленные методы PlaybackRuntime/ExportLayout/SharedAudioStore, конструкторы fixture моделей и наблюдаемые getters PlaybackState/PublishedAudio, а также coroutine entry points. Имена по-прежнему обфусцируются через mapping; общий app-package keep не добавлен. Применяется одинаковая конфигурация release, не специальное отключение оптимизации для test target. См. [Android: external test APK dependencies](https://source.android.com/docs/core/perf/r8-optimization).

## R8: реальные результаты 96f1333 и следующий набор изменений

[42389114020](https://github.com/alcovaibe/vk-music-export/actions/runs/42389114020): dedicated connectedReleaseAndroidTest **success на API29/36/37**. Проверка соответствия единственному R8 тесту и измерениям прошла. Ошибка записи отчёта устранена переходом test fixture на Android JSONObject без добавления keep serialization.

Jobs в целом ещё failure: API29 — пустая выборка graphics после reset на пустом home; API36/37 — требование TotalTime у возврата уже существующей Activity. Коммиты 28848d1/e8ba11a/ce0efca сохраняют реально отрисованные launcher frames, выводят verified JSON/raw startup в CI log и разделяют cold TotalTime и hot-return ActivityManager WaitTime. В schema2 явно указано, что WaitTime не является app warm startup/TTID. Отсутствующие, отрицательные, timeout/error samples по-прежнему отклоняются; холодный TotalTime не подменяется WaitTime. Контракт проверен по [AOSP ActivityManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java).

В следующем полном прогоне одновременно проверяются schema7/source memberships, два новых safe-restart transfer regression и перенос исторических UI wrappers только в androidTest. Никакой новый результат этой совокупности заранее не заявляется.

## Подтверждённый зелёный SHA перед F14

[42390158376](https://github.com/alcovaibe/vk-music-export/actions/runs/42390158376), source `6c5e69aa73391467d9d644fb022eeaf421ace154`: workflow **success**.
Build включает 165 Python tests, JVM/Lint/release compilation и OSV 279 resolved dependencies без findings. Все3 SAST, все9 interface API29–37, все9 updater API29–37, все3 R8 playback API29/36/37 и channel-delivery — success. Interface API31 verifier подтвердил **235 distinct tests, no failures or skips**, включая все4 новых source-membership сценария. Release job штатно skipped; публикация не запускалась.

## Локальные snapshots собственного каталога F14

Новый набор хранит только account/source IDs, название, серверный тип, ожидаемое количество и порядок task keys в приватных indexed meta checkpoints. Access keys, credentials, image/media URLs не сохраняются. JSON metadata не заменяют indexed file_sources/physical URI identity. Одна физическая копия и повторяющиеся позиции сохраняют разные memberships и порядок.

При холодном создании модели metadata появляются до provider-проверки. Они не создают NativeProbe/readSession: в UI это локальный каталог с неподтверждённым ВК. Проверка подключения выполняется один раз на модель; локальное открытие не перезапускает её автоматически. Retained источники при свежем каталоге проверяются пакетными MediaStore hints; при открытии строится local-only очередь без online account/readSession/download task. Окончательная проверка ownership/size/hash остаётся в штатном playback resolver. Подтверждённо отсутствующие/изменённые копии не включаются в локальную очередь; неизвестное состояние разрешает попытку с полной проверкой, без ложной Offline-метки.

Удалённый/неполученный source остаётся по собственному локальному снимку, пока есть indexed/доступные копии. Частичное сохранение и неподтверждённый состав отмечены отдельно; прежняя строгая проверка полного Offline включает актуальный состав, файлы и M3U8. Метаданные локального источника никогда не дают право на API и не подставляют accessKey предыдущей сессии.

Обязательные тесты: перезапуск store, одинаковые названия разных IDs, owner12/13, повтор одного URI и первоначальный порядок, удалённый remote source, missing/changed hints, malformed metadata без удаления файлов; MainActivity-hosted реальный NativeMusicPanel с изолированными data и синтетическим аудио при отказе remote API; отсутствие playlist/media запросов и online authority у воспроизводимой очереди. Проверка этого нового набора ещё ожидается.

## Production catalog controller и legacy format cleanup

MusicCatalogController теперь получает реальный NativeMusicState и scheduler/admission/lifecycle от NativeMusicViewModel. Он исполняет переходы разделов, online/local выбор, пагинационный read и сохранение checked metadata. Не добавлен параллельный владелец файловых мутаций или второй job lifecycle. После свежего/кэшированного чтения повторно проверяется MusicReadIdentity; обнаруженное до записи несовпадение сессии/аккаунта очищает недопустимую страницу и не сохраняет её. Повторная проверка после metadata write запрещает публикацию при последующей смене доступа; metadata привязаны к исходному account, admission остаётся у host. Обычное проверенное продление readSession допускается.

Новые unit contracts относятся к подключённому controller: checked renewal, независимый login/owner, non-cooperative late page после cancellation, локальные повторяющиеся файлы без access/API/download authority. Старые две проверки section/deselect сохранены и усилены busy guard. Admission при этом пока остаётся последовательным host policy — полного устранения global gate не заявлено.

Удалены production catalogKind/catalogKinds, NativeMusicState.catalogKinds и ignored choices-аргументы MusicCatalog. Все текущие типы берутся из NativePlaylist.kind; app-код больше не содержит API ручного выбора. Сами исторические meta rows сохранены. Их тестовое создание/чтение вынесено в LegacyCatalogChoices.kt только androidTest; реальные CatalogRefresh tests по-прежнему доказывают, что старый сохранённый override не меняет серверную классификацию. Ни schema/migrations, ни формат очереди, ни пользовательские файлы не удаляются.

Дополнительно local play action использует тот же SavedPlaybackPolicy admission, что Saved screen, чтобы чтение локальной подборки не обходило блокировку редактирования файлов. Общая сборка и все Android/security/R8 проверки нового SHA обязательны.

В `048850e` build/JVM/Lint/OSV, все3 SAST и все3 R8 — success. Android suites завершились с единственным новым LocalCatalogPlaybackDeviceTest failure на обращении к некомпозированному off-screen LazyColumn item. Исправлена именно навигация теста: container.performScrollToNode, затем click; дополнительно проверяются cached owner/key/catalog до взаимодействия. Таймауты и обязательность воспроизведения не ослаблены. Basic LocalCatalog/store миграционные сценарии не отмечены как причина failure; новый полный run обязателен.

## Проверка текущих документов и bundled privacy

В README сверены все внутренние heading anchors и относительные ссылки: отсутствующих целей не найдено. Compatibility guide ссылается на существующий release-acceptance/PROCEDURE.md. Исторические audit/CHANGELOG не переписываются. PRIVACY.md и bundled legal/privacy.txt синхронно исправлены: default Custom Tabs browser, durable playback queue без credentials/media URLs, credential-free account-scoped local collection descriptors и их сохранение вместе с файловым учётом. Удаление данных входа не выдаётся за удаление приватного музыкального индекса или серверный отзыв сессий. Это описание фактического хранения, не новый выпуск и не ручная приёмка.
