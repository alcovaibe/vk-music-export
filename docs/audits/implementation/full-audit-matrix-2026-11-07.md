# Матрица F01–F30 — результат сверки 07.11.2026

Работа выполнена онлайн через GitHub, только в `fix/audit-stage0-release-delivery-20261104`.
Стартовый фактический HEAD — `4539136253e2ca32f27947f50e7414407aaf3103`; указанный в задании `4d3a187` не подменял актуальную ветку.

Это реестр реализации и доказательств, **не акт полного закрытия всех30 пунктов**.
Исторический аудит 04.11 и опубликованные записи CHANGELOG не переписаны.
Полный новый построчный аудит всего репозитория не объявляется выполненным: сверены затронутые реализации, контракты, usages и обнаруженные CI failures.
Локальные Git/Gradle/adb не запускались; чужие локальные изменения не обследовались и не затрагивались.

## Подтверждённый код и CI

[Полный run42401058839][CI] — **success**, source `003229d22725535bef13fe1f5f62110be142542d`.
На этом SHA прошли:

- 166 Python host tests; JVM debug (включая 6 новых MusicAuthCoordinatorTest), Lint debug/release и release/R8 compilation.
- OSV: 279 resolved dependencies, findings0; SAST Java/Kotlin, Python и Actions.
- Все9 interface API29–37; Android verifier подтвердил239 distinct tests без failures/skips (например API32).
- Все9 updater API29–37, channel-delivery, все3 dedicated R8 playback API29/36/37.
- Aggregate interface и отдельный update-channel consistency — success. Release job — skipped.

После F26 последующие изменения этого отчёта, архитектурной заметки и CONTRIBUTING являются Markdown-only: Android/build inputs указанного SHA сохраняются.
Их отдельный текущий Actions run виден в [истории ветки](https://github.com/alcovaibe/vk-music-export/actions?query=branch%3Afix%2Faudit-stage0-release-delivery-20261104).
Успех одного SHA не объявляется автоматическим успехом будущего изменения кода.

## Матрица

`*.kt` основного music-кода находятся в `app/src/main/java/app/alcovaibe/vkmusicexport/music/`.
JVM tests — в `app/src/test/java/app/alcovaibe/vkmusicexport/music/`; Android test classes — в `app/src/androidTest/java/app/alcovaibe/vkmusicexport/`.
Некоторые Android classes находятся совместно в одном файле; Legacy fixtures с package music находятся только в подкаталоге `androidTest/.../music/`.
«Автопроверено» относится к указанному синтетическому CI и не означает live VK/физическую приёмку.
Приоритеты сохранены из исторического findings.json.

| ID | Приоритет | Исходный дефект | Реализация | Файлы / доказательство | Тесты | Статус | Ограничения |
|---|---|---|---|---|---|---|---|
| F01 | P1 | Независимая read session подменялась при продолжении запроса | Identity/account fences до и после получения доступа; catalog continuation повторно проверяется перед/после metadata write | MusicSession.kt, VkAudioClient.kt, MusicCatalogController.kt | MusicSessionBoundaryTest, MusicSessionTest, MusicCatalogControllerTest; [CI] | Автопроверено | Live A→B/новый A относится к F05; локальные descriptors не дают online authority. |
| F02 | P1 | Latest и публичный канал расходились | Проверки доставки, подписи/размера/hash/package и согласованности метаданных | tools/channel_public.py, tools/update-delivery-check.sh; [Latest], [channel] | channel-delivery и updater API29–37; [CI] | Автопроверено; метаданные согласованы | Физическая установка точного нового APK не выполнялась. |
| F03 | P1 | Защита main не была применена | Read-only подтверждён active ruleset26682452: PR, required approvals=0, thread resolution, strict build/interface/channel-delivery, no force/delete, bypass=[] | [Ruleset] | GitHub API, 07.11.2026 17:19 UTC | Подтверждено настройками | Одобрений reviewer требуется0; необходимость второго reviewer остаётся решением владельца. Настройки здесь не менялись; JSON шаблон не заменяет effective state GitHub. |
| F04 | P1 | Внутренний auth/music protocol выдавался за стабильный поддержанный provider | Контракты MusicApi/DeviceAuthApi, capability defaults UNAVAILABLE; VkAudioClient явно EXPERIMENTAL | VkAudioClient.kt, DeviceAuth.kt, MusicSearchController.kt | Provider/refusal/capability contracts; [CI] | Частично | Официальный партнёрский музыкальный scope не подтверждён; VK ID не объявляется audio permission. |
| F05 | P1 | Нет ручной приёмки точного stable APK | Fail-closed gate для exact SHA/APK/version/run и 13 сценариев; процедура приёмки | tools/release_gate.py; docs/release-acceptance/PROCEDURE.md | 166 host tests, включая release gate; [CI] | Открыта внешняя приёмка | current.json остаётся pending. Live VK, физические устройства и новый подписанный accepted APK отсутствуют; публикации не было. |
| F06 | P1 | Playback queue/cursor терялись; гонки STOP/new play | Durable queue/order/modes/cursor, identity-aware restore, notification generation, coalesced history writer | PlaybackHistory.kt, PlaybackHistoryStore.kt, PlaybackCheckpoint.kt, PlaybackRuntime.kt | PlaybackHistoryDeviceTest, PlaybackDeviceTest; paused/active process-death/resumption; R8 API29/36/37; [CI] | Автопроверено | Короткий AVD сценарий не доказывает длительную OEM/Bluetooth/focus работу. |
| F07 | P1 | Metadata cursor переписывал весь список | Однократные targets и адресные progress/issues в SQLite; миграции и rollback сохранены | TrackMetadataCheckpoints.kt | MetadataCheckpointDeviceTest: migration, full/rollback, owner, 100/4500/20000; [CI] | Автопроверено | Физический power-loss/ENOSPC конкретного телефона не имитируется успехом CI. |
| F08 | P2 | Receipt cache пересериализовывался целиком | SQLite receipts с fingerprint/version/TTL/owner, поштучные commits; consent не сохраняется | DuplicateReceiptStore.kt, DuplicateReceiptKeys.kt | DuplicateReceiptKeysTest, DuplicateReceiptDeviceTest; [CI] | Автопроверено | Живой provider/контент не использовался. |
| F09 | P2 | snapshot делал N+1 и читал все ошибки | Counters отдельно от bounded error page и полного экспорта | QueueReadIndex.kt, DownloadsReader.kt | QueueReadDeviceTest, DownloadsReaderDeviceTest, workloads100/4500/20000; [CI] | Автопроверено | Полный отчёт намеренно читает все строки и содержит пользовательские metadata. |
| F10 | P2 | Полные сканы aliases/source memberships | URI/physical index сохранён; schema7 добавляет account/kind/source/occurrence и file_sources; CatalogDownloads читает exact source | AudioExportStore.kt, SavedFileIndex.kt, FileMembershipIndex.kt | SourceMembershipDeviceTest (4 mandatory), legacy/relink tests; [CI] | Автопроверено | JSON metadata сохранены. Миграция ошибочной строки откатывается, не пересоздаёт DB/файлы. |
| F11 | P2 | Provider запросы на каждый файл блокировали Saved | DB pages публикуются до presence; hints пакетные, изменения адресные; sensitive ops оставляют hash check | SavedLibrary.kt, SharedAudioStore.kt | SavedLibraryDeviceTest, PartialPlaybackDeviceTest; [CI] | Автопроверено | Новые физические CPU/PSS/latency измерения большой библиотеки не проводились. |
| F12 | P2 | Friend preview продолжался после ухода | Отдельные workers/generation/TTL; отмена по экрану/foreground, restart только актуальных видимых карточек | FriendMusicController.kt, NativeMusicPanel.kt | FriendAvailabilityReaderTest, FriendPreviewLifecycleDeviceTest; [CI] | Автопроверено | Отмена preview не останавливает пользовательскую очередь/maintenance. |
| F13 | P2 | Один трек повторно читал каталог/страницы | Exact-ID download и session-bound source snapshots/cache; bulk читает только нужные источники | MusicSourceReader.kt, ExportReadCache.kt, ExportLayout.kt | SingleTrackDownloadTest/DeviceTest, SourceReadDeviceTest, BulkDownloadDeviceTest; [CI] | Автопроверено | Cached membership не заменяет свежую проверку прав/ID перед API/queue commit. |
| F14 | P2 | Скачанные подборки исчезали и не открывались локально | Default visible + явный pending filter; credential-free snapshots собственного каталога, partial/unconfirmed labels, retained sources и local-only повторяющаяся очередь | LocalCatalogSnapshots.kt, PlaybackModel.kt, NativeMusicPanel.kt | LocalCatalogDeviceTest (3), LocalCatalogPlaybackDeviceTest (1), PlaybackListsTest; [CI] | Автопроверено для своего каталога | Snapshots создаются после нового чтения/подготовки; старые exports без descriptor остаются в Saved. Для карточек нужен сохранённый account; logout не удаляет файлы/индекс. Friend cache не становится доказательством доступа. |
| F15 | P2 | Не было отдельного глобального поиска | MainActivity route, debounce350ms, страницы100/предел20000, cancellation/generation/session, play/download и distinct error states | MusicSearchController.kt, MusicSearchScreen.kt, VkAudioClient.kt | MusicSearchControllerTest, GlobalMusicSearchDeviceTest; [CI] | Автопроверено | audio.search EXPERIMENTAL; рекомендации не добавлялись без подтверждённой capability. |
| F16 | P2 | Artist tracks не имели play/download | Общая PlaybackEntry/PlaybackExport, artist/album identity и session fences | PlaybackModel.kt, MusicArtistsController.kt | PlaybackListsTest, MusicArtistsControllerTest, SingleTrackDownloadDeviceTest; [CI] | Автопроверено | Доступ реальных artist/album источников требует live VK приёмки. |
| F17 | P2 | Tap своего трека не запускал нужное вхождение | Play-from-index следует видимому порядку; download identity/occurrence не теряется при сортировке | PlaybackModel.kt, NativeMusicPanel.kt | PlaybackListsTest, PartialPlaybackDeviceTest; [CI] | Автопроверено | Потоковые URLs разрешаются заново; окончательная локальная проверка остаётся в runtime. |
| F18 | P2 | DownloadsScreen был недостижим | Штатный DOWNLOADS route, кнопка, Back/rememberSaveable, mini-player и независимая UIDT presentation | NativeMusicPanel.kt, DownloadsScreen.kt | DownloadsScreenDeviceTest, DownloadsReaderDeviceTest, PlaybackDeviceTest; [CI] | Автопроверено | Физические notification/OEM сценарии относятся к F05. |
| F19 | P2 | Текущий большой файл не продолжался с байта | Сохранён допустимый безопасный restart; UI объясняет повторную загрузку незавершённого файла | AudioTransfer.kt, DownloadProgressSheet.kt | AudioTransferTest: cancellation→renewed URL/no append; reject unsolicited206/416; [CI] | Выбран и проверен safe restart | Range/ETag/If-Range и segment resume не реализованы; готовые треки не теряются, HLS не склеивается вслепую. |
| F20 | P2 | Вход требовал Chrome | Только пользовательский default HTTPS browser с экспортированными activity/Custom Tabs service; URL allowlist/package pin сохранены | AuthBrowser.kt, AndroidManifest.xml | AuthBrowserPolicyTest, DeviceConfirmationDeviceTest, SettingsAndAuthDeviceTest; [CI] | Автопроверено | Реальные Chrome/Firefox/Edge/no-provider/cancel требуют устройства. Доверие браузеру не является SPKI pinning. |
| F21 | P2 | VK201 приписывался приватности | Нейтральный текст; коды201/15/7/5/403/empty/network различаются, raw response text не показывается | FriendMusic.kt | FriendMusicTest, FriendsMusicDeviceTest; [CI] | Автопроверено | Живые серверные ответы аккаунта не запрашивались. |
| F22 | P2 | Artwork lookup отвергал проверенное продление | Read identity/owner/expiry и одноразовое apply; независимый login не наследует выбор | ArtworkLookup.kt, ArtworkRepair.kt | ArtworkLookupTest, ArtworkLookupDeviceTest, ArtworkLookupViewModelDeviceTest; [CI] | Автопроверено | Ручная оценка обложек/тегов в сторонних проигрывателях не проведена. |
| F23 | P2 | Недостаточная accessibility приёмка | Labels/semantics/state/progress, явные действия, постоянная search label и live errors | MusicLibraryUi.kt, MusicSearchScreen.kt, PlaybackUi.kt | MusicAccessibilityDeviceTest, navigation/recreate и large-text tests; [CI] | Частично | TalkBack/Switch Access/IME/landscape на физическом устройстве не проверены. |
| F24 | P2 | Нет minified playback/performance evidence | R8 остаётся включён для app/test; исправлены shared ABI, JUnit return type и сбор реальных измерений | tools/r8-playback-check.sh, tools/verify-r8-evidence.py, tools/summarize-r8-performance.py | R8 API29/36/37 success; first-play/CPU/PSS/heap, 5 cold и 5 hot-return command samples; [CI] | Частично; R8 автопроверен | Hot WaitTime не является warm TTID; launcher frames не являются20k scroll Macrobenchmark. 1h/8h OEM, profiling и полноценный Macrobenchmark остаются открыты. |
| F25 | P2 | Неполные dependency/SAST gates | App/build-tool inventories, адресные исправления7 AGP records, reviewed added checksums, exact SAST source reviews; strict verification сохранён | build.gradle.kts, gradle/verification-metadata.xml, tools/dependency-audit.py, config/sast-reviewed.json | OSV279 resolved deps: no findings; SAST Kotlin/Java, Python, Actions success; [CI] | Выявленные blockers устранены | Это не полное доказательство безопасности runner/runtime. Raw findings не удаляются; pinning limitations не объявлены исправленными. |
| F26 | P2 | Монолитный ViewModel/общий gate | Auth, retained maintenance, saved-file recovery и operation admission вынесены в четыре production coordinators; существующие независимые screen readers сохранены | MusicAuthCoordinator.kt, MusicMaintenanceCoordinator.kt, MusicSavedFilesCoordinator.kt, MusicOperationCoordinator.kt; [границы](f26-coordinator-extraction.md) | 6 новых auth contracts; прежние lifecycle/session/cancellation/retained-maintenance device suites; [CI] | Поэтапная extraction автопроверена | Общий admission сохранён для согласования journal/SQLite cleanup; единственный владелец файловых изменений AudioExportRuntime.editSaved. Индивидуальные lookup/removal screens и catalog refresh policy остаются возможным продолжением; полный rewrite не заявляется. |
| F27 | P3 | Evidence исчезает по CI retention | Manifest/ZIP verifier/provenance и публикация release-evidence.zip предусмотрены для следующего разрешённого выпуска | tools/release_evidence.py, tools/verify-release-evidence.py, tools/publish-release.py | Integrity/traversal/subject/duplicate synthetic tests; [CI] | Частично | У текущего Latest только APK/SHA256SUMS. Новый evidence asset и проверка после retention не выполнялись: публикация не разрешена этой задачей. |
| F28 | P3 | Текущие руководства отставали от кода | README, compatibility/acceptance guide, CONTRIBUTING и обе privacy copies описывают новые contracts/storage; Windows wrapper приведён | README.md, CONTRIBUTING.md, PRIVACY.md, app/src/main/assets/legal/privacy.txt | Сверены code/Latest/channel; README anchors и relative targets; bundled privacy copies идентичны | Обновлено | Это документация ветки, не объявление принятой stable. Локальная Windows-сборка не выполнялась; исторические CHANGELOG/audit не переписывались. |
| F29 | P3 | Меню предлагало неприменимые сортировки | Collections default/title; tracks artist/title, year только по данным, addition order только library; label/preferences по контексту | MusicLibrarySorting.kt, NativeMusicPanel.kt | MusicLibrarySortingTest и Android navigation/recreate; [CI] | Автопроверено | Год по названию не угадывается; недоступный вариант имеет явный effective fallback. |
| F30 | P3 | Недостижимый UI/ручная классификация рядом с текущим UX | Legacy wrappers только в androidTest; catalog controller имеет production call site; catalogKinds/choices API удалены из app, старые meta сохранены | music/LegacyMusicUiFixtures.kt и LegacyCatalogChoices.kt в androidTest; MusicCatalog.kt | Compiler + source usages, actual MainActivity suites и legacy meta/migration contracts; [CI] | Cleanup автопроверен | Исторический fixture не выдаётся за актуальную навигацию. Миграции, services и API29 пути не удалены. |

## Внешнее состояние и неизменённые границы

На 07.11.2026 17:19 UTC [Latest] и JSON из [main][channel] согласованы: `v0.12.1-build.1433`, source `90e6848257e93d82b3a6b8af09cc58ef067a7703`, minSdk29, APK5969335 bytes,
SHA-256 `3dac4905703c990d53a431b54cfd01ce12389db439943165d7a99ac30f6e3889`.
У этого существующего выпуска только APK/SHA256SUMS; новый permanent evidence asset не публиковался.
Read-only повторно подтверждён active ruleset26682452, strict required checks и отсутствие bypass. required_approving_review_count=0: обязательное одобрение reviewer не заявляется.

В этой работе не изменялись main, public update manifest, Releases/Latest, ruleset settings, permanent signing key, applicationId/packageName, versionName/versionCode или minSdk29.
Обычные проверки не получали production signing key. Minified CI использует явно обозначенную ephemeral test signing; это не новый принятый stable APK.

## Что остаётся открытым

1. F04: подтверждённый разрешённый provider/partner scope. Experimental protocol и capabilities не являются доказательством прав.
2. F05/F23: live VK и физическая приёмка точного APK по [PROCEDURE.md](../../release-acceptance/PROCEDURE.md), включая account switches, long background, Bluetooth/audio focus, UI/accessibility, upgrade/data и сторонние проигрыватели.
3. F24: полноценный20k scroll Macrobenchmark/profiling и длительная OEM работа. Полученные AVD startup/home/R8 данные не подменяют их.
4. F27: следующий отдельно разрешённый выпуск durable evidence и наблюдение после retention. Самостоятельная публикация здесь не выполняется.
5. F03: политика обязательных одобрений/второго reviewer; текущее значение0 зафиксировано, настройки без разрешения не менялись.
6. Полный независимый повторный построчный review всех production/test файлов; отсутствие новых сбоев CI не объявляется таким review.

`docs/release-acceptance/current.json` остаётся **pending**, source_commit пустой, все13 сценариев неподтверждены.
Статусы не повышались по наличию тестового файла, старому запуску, отменённому job или синтетическим данным вместо ручных.

[CI]: https://github.com/alcovaibe/vk-music-export/actions/runs/42401058839
[Latest]: https://github.com/alcovaibe/vk-music-export/releases/latest
[channel]: https://github.com/alcovaibe/vk-music-export/blob/main/docs/updates/android.json
[Ruleset]: https://api.github.com/repos/alcovaibe/vk-music-export/rulesets/26682452
