# Матрица статусов требования F01–F30 (актуальность: 07.11.2026)

Репозиторий: `alcovaibe/vk-music-export`  
Ветка: `fix/audit-stage0-release-delivery-20261104`  
HEAD SHA: `4d3a187efa1430120c0e7f110768c7bedbcfcae3` (с учётом локальных фиксов JVM/R8)

## Сводный статус по категориям

- **Реализовано (12 пунктов):** F01, F02, F03, F06, F07, F08, F09, F10, F11, F17, F18, F22.
- **В процессе / Частично (10 пунктов):** F04, F05, F12, F13, F16, F23, F24, F25, F26, F27.
- **Открыто / Не начато (8 пунктов):** F14, F15, F19, F20, F21, F28, F29, F30.

---

## Полная матрица F01–F30

| ID | Приоритет | Исходный дефект | Фактическая реализация | Доказательства | Тесты | Статус | Оставшиеся ограничения |
|---|---|---|---|---|---|---|---|
| **F01** | High | Подмена сессии при входе другого пользователя | Изоляция сессий в `MusicReadIdentity`, проверка `readSessionId` и `accountId` | `MusicSession.kt` | `MusicAvailabilityControllerTest`, `FriendMusicControllerTest` | **Реализовано** | — |
| **F02** | High | Проблемы доставки обновлений APK | Проверка SHA-256, размера, `versionCode`, подписи, возобновление скачивания | `GithubUpdates.kt`, `UpdateController.kt` | `UpdateProtocolTest.kt` | **Реализовано** | Нужна ручная проверка с реальным сервером релизов |
| **F03** | Critical | Защита `main` ветки | Ruleset в GitHub: PR, required checks, запрет force-push/deletion | `ci-origin.json` | CI workflows | **Реализовано** | — |
| **F04** | High | Смешивание VK Auth и VK Music API | Разделены `MusicAccess` и `MusicSession`, выделены провайдеры | `VkAudioClient.kt`, `WebExchangeProtocol.kt` | `PublicDeviceAuthProbeTest` | **В процессе** | Требуется явное разграничение capabilities (supported/experimental/unavailable) |
| **F05** | High | Отсутствие строгого регламента приёмки stable APK | Разработана схема приёмки, скрипт валидации evidence, проверка SHA/Sign | `tools/verify-release-evidence.py` | `verify-release-evidence.py` tests | **Частично** | Требуются ручные тесты на реальном Android-устройстве |
| **F06** | High | Сброс очереди и позиции воспроизведения | Сохранение состояния очереди, позиции, repeat/shuffle, восстановление после kill | `PlaybackRuntime.kt`, `PlaybackHistory.kt` | `PlaybackHistoryTest`, `R8PlaybackDeviceTest` | **Реализовано** | — |
| **F07** | Medium | Перезапись всего JSON метаданных при каждом треке | Инкрементальные транзакционные SQLite checkpoints | `TrackMetadataRepair.kt`, `AudioExportStore.kt` | `AuditRegressionTest` | **Реализовано** | — |
| **F08** | Medium | Полная сериализация кэша дубликатов | SQLite receipts с поуэрными fingerprint, TTL и изоляцией владельцев | `DuplicateCheckJob.kt`, `DuplicateScanCache.kt` | Unit tests | **Реализовано** | — |
| **F09** | Medium | N+1 SQL-запросы при чтении очереди ошибок | Разделены progress snapshot и постраничное чтение отчётов | `DownloadsReader.kt`, `ExportReportShare.kt` | Unit tests | **Реализовано** | — |
| **F10** | Medium | Сканирование индекса файлов и URI | Индексация URI, ownership, source & memberships в БД | `SharedAudioStore.kt`, `SharedPlaylistStore.kt` | Unit tests | **Реализовано** | — |
| **F11** | Medium | Запросы ContentResolver на каждый элемент | Пакетное чтение и пагинация библиотек | `SavedLibrary.kt`, `SavedFileRecovery.kt` | `SavedLibraryTest` | **Реализовано** | — |
| **F12** | High | Неотменяемые запросы превью друзей | Выделен `FriendMusicController`, отмена при уходе/смене аккаунта | `FriendMusicController.kt` | `FriendDownloadPreparationTest` | **В процессе** | Завершить изоляцию от NativeMusicGate |
| **F13** | High | Повторное чтение списков при скачивании трека | Выделено скачивание конкретного трека без перезагрузки каталога | `AudioExportEngine.kt`, `AudioExportStore.kt` | Unit tests | **Частично** | Нагрузочные тесты 20k записей |
| **F14** | Medium | Исчезновение скачанных подборок из каталога | — | — | — | **Открыто** | Реализовать офлайн-видимость полностью скачанных подборок |
| **F15** | High | Отсутствие глобального музыкального поиска | — | — | — | **Открыто** | Создать экран поиска с пагинацией и debounce |
| **F16** | Medium | Ограничения действий с треками артистов | Выделен `MusicArtistsController` | `MusicArtistsController.kt` | Unit tests | **Частично** | Внедрить единую модель TrackActions/PlaybackEntry |
| **F17** | Medium | Проблемы воспроизведения своих онлайн-треков | Tap-to-play, построение очереди, предпочтение проверенной копии | `NativeMusicViewModel.kt`, `PlaybackRuntime.kt` | `PlaybackRuntimeTest` | **Реализовано** | — |
| **F18** | Medium | Нет доступа к экрану загрузок из UI | `DownloadsScreen` вынесен в штатную навигацию, поддержан System Back | `DownloadProgressSheet.kt`, `NativeMusicPanel.kt` | UI tests | **Реализовано** | — |
| **F19** | High | Отсутствие докачки незавершённых файлов | — | — | — | **Открыто** | Реализовать HTTP Range / ETag / If-Range докачку с валидацией |
| **F20** | High | Жёсткая зависимость от Google Chrome при входе | — | — | — | **Открыто** | Добавить поддержку любого Custom Tabs провайдера с фолбэком |
| **F21** | Medium | Ложное сообщение о закрытой музыке при ошибке 201 | — | — | — | **Открыто** | Нейтральные сообщения об ошибках для 201, 15, 7, 5, 403 |
| **F22** | Medium | Сброс сессии поиска обложек при продлении токена | Фенсинг сессий по `readSessionId`, допуск продления | `ArtworkRepair.kt`, `ArtworkLoader.kt` | `ArtworkLookupTest` | **Реализовано** | — |
| **F23** | Medium | Неполная доступность Compose (TalkBack/FontScale) | Подписи слайдеров, паузы, FontScale >= 1.3 | `MusicLibraryUi.kt`, `MusicCatalogPanel.kt` | Instrumentation accessibility tests | **Частично** | Требуется ручная проверка на TalkBack |
| **F24** | Medium | Измерения производительности и R8 | Запуск R8 playback тестов, замеры heap/CPU | `src/r8AndroidTest/` | R8 instrumentation test | **Частично** | Провести полные Macrobenchmark измерения |
| **F25** | High | Supply chain / SBOM / SAST | Граф зависимостей, SAST review, CodeQL integration | `writeDependencyInventory`, `check-sast.py` | Python check scripts | **Частично** | Достичь 100% зелёных воркфлоу в CI |
| **F26** | Critical | Монолитный `NativeMusicViewModel` | Выделены `MusicArtistsController` и `MusicAvailabilityController` | `MusicAvailabilityController.kt`, `MusicArtistsController.kt` | Unit tests | **Частично** | Выделить remaining Auth / Catalog / Maintenance контроллеры |
| **F27** | High | Срок хранения доказательств релизов | Генерация bundle доказательств релиза, verifier | `verify-release-evidence.py` | `test_audit_guards.py` | **Частично** | Публикация первого asset |
| **F28** | Low | Отставание документации в README | — | — | — | **Открыто** | Обновить README и гайд по Android 10+ |
| **F29** | Medium | Некорректная работа сортировок | — | — | — | **Открыто** | Валидация и исправление всех вариантов сортировки |
| **F30** | Low | Неудалённый legacy UI код | — | — | — | **Открыто** | Аудит и безопасное удаление мёртвого кода |
