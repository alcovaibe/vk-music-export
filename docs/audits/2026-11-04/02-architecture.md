# Фактическая архитектура

Один Gradle-модуль `:app`, общий package music, ручное создание зависимостей и process-singletons. Есть отдельные протоколы/чистые функции и service owners; полного UI/domain/data-разделения нет. Kotlin main: 98 файлов, 12371 строк, 852244 байта. Всего 175 Kotlin-файлов: 98 main, 41 unit, 36 androidTest.

| Класс / компоненты | Зависимости | Ответственность | Вход → выход |
|---|---|---|---|
| MainActivity / MusicApp | NativeMusicViewModel, UpdateViewModel, NativeMusicPanel | Вход, lifecycle, routing уведомлений | Intent/lifecycle → Compose root и foreground signals |
| NativeMusicViewModel | MusicApi, MusicSessionManager, vault, AudioExportRuntime, MaintenanceRuntime, FriendMusicController | Оркестрация музыкального UI и операций | Действия пользователя → StateFlow/запуски операций; 1414 строк |
| FriendMusicController | MusicApi, sessions/vault, coroutine scope, export runtime | Друзья, статусы, профили и подготовка выбора | Аккаунт/друг/выбор → FriendMusicState и export tasks |
| VkDeviceAuthClient / VkWebTokenExchange | OkHttp, DeviceAuthProtocol, WebExchangeProtocol, PairingCookieJar | Экспериментальный WEB pairing и обмен | Одноразовая заявка/код → проверяемый MusicAccess; это не VK ID SDK |
| MusicAccessStore | Android Keystore, AES/GCM, AtomicFile, MusicCredentialCodec | Зашифрованный доступ и материал продления | MusicAccess ↔ noBackupFilesDir/native-music-access.bin |
| MusicSessionManager | MusicCredentialStore, MusicRenewer, verify, Mutex | Продление/CAS/границы read session | Старый access/ответ ошибки → текущий проверенный access или отказ |
| VkAudioClient : MusicApi | OkHttp, sessions, NativeProtocol, MusicApiPacer | Фиксированные методы, POST, parsing/error policy | Access+метод+параметры → typed DTO; signed URL только в памяти |
| MusicPagination / MusicCatalog / MusicArtists | MusicApi/fetch callbacks и protocol DTO | Полнота страниц, типы списков, subscriptions layout | Страницы offset/cursor → NativePage/NativeCatalog/artist list |
| AudioExportRuntime | AudioExportStore, AudioExportEngine, Mutex, readiness, transport | Единый coordinator экспорта и edits | Tasks/start/pause → ExportSnapshot; singleton process lifetime |
| BackgroundTransport | JobScheduler либо ContextCompat FGS | Выбор Android механизма | API29–33 → Legacy services; API34+ → UIDT jobs |
| AudioExportJob / LegacyAudioExportService | AudioExportRuntime, notification/service lifecycle | Фоновое владение экспортом | Задание системы → run/остановка/checkpoint без самовольного restart |
| AudioExportEngine | Store, SharedAudioStore, MusicApi, AudioTransfer, SavedAudioReuse, DownloadArtwork | Восстановление, claim, download/retry, publish, M3U8 | PENDING task → DONE/FAILED/PAUSED и MediaStore запись |
| AudioTransfer / HlsPlaylist | OkHttp media client, allowlist, AndroidAudioProcessor, AES-128 subset | Передача finite VOD и обработка разрешённого формата | Свежий URL/NativeTrack → staging TransferResult; не универсальный HLS/CAS |
| AndroidAudioProcessor / EmbeddedArtwork | Media3 extractors, MediaMuxer, MP3/MP4 parsing | MP3 pass/remux AAC→M4A, теги и подпись аудиоданных | Staging input → MP3/M4A и duration/fingerprint |
| AudioExportStore | SQLiteOpenHelper v3, WAL/FULL, Kotlin serialization | Tasks, files/memberships, meta, directories, source failures | DTO ↔ SQLite; JSON payload не является нормализованным музыкальным каталогом |
| SharedAudioStore / SharedPlaylistStore | ContentResolver, MediaStore, hash, store | Владение/целостность/pending и переносимый M3U8 | Проверенный staging/URI → опубликованный файл + индексный checkpoint |
| SavedAudioReuse / SavedFileRecovery / LegacyArchiveMigration / FriendFolderMigration | Store, shared audio/playlists, AtomicFile/journals | Повторное использование и миграции | Старые/перемещённые записи → подтверждённые новые ссылки без удаления оригиналов |
| TrackMetadataRepair / ArtworkRepair | Store, shared audio, durable backup, EmbeddedArtwork | Транзакционное изменение существующего файла | Подтверждённый before → verified after либо rollback |
| MaintenanceRuntime / MaintenanceService | AtomicFile targets/progress, wakelock, notifications, supplied operation | Долгие операции с файлами вне Activity scope | Start/resume/pause → persistent cursor и reports |
| MusicDuplicates / DuplicateAudioHasher / DuplicateScanCache | MusicApi, transfer/local file, fingerprints, AtomicFile | Поиск точных дублей и одноразовое удаление | Library → candidates/signatures/report; consent → одна проверенная mutation |
| MusicPlaybackService / PlaybackRuntime | ExoPlayer, MediaSession, MusicSessions, API, ArtworkLoader | Единственный player и process-memory playback state | PlaybackSelection → media session, mini/full-player StateFlow |
| PlaybackMediaSource / PlaybackDataSources / PlaybackNetwork | Media3 source factory, OkHttp, allowlist, content URI checks | Ленивое разрешение URL и защита media boundary | Opaque media ID → проверенный online/offline child source |
| UpdateController / GithubUpdates / UpdateProtocol | OkHttp, preferences, PackageManager, FileProvider | Публичный канал, APK download и проверка подписи | Manifest → available/verified APK → системный installer Intent |
| Diagnostics / RunJournal / Privacy | Bounded queue, async disk, NDJSON, FileProvider | Ограниченная диагностика без auth/media secrets | Fixed event fields → текущий/предыдущий JSON по явному share |

## Границы и владение

UI получает StateFlow, но также непосредственно обращается к PlaybackRuntime и использует Android Context. ViewModel создаёт конкретные stores и maintenance use cases. Export engine сохраняет каждый готовый item; playback queue к этому механизму не относится. Singleton MusicSessions объединяет обновления токена, vault имеет собственный synchronized lock. Разные SQLiteOpenHelper экземпляры обслуживают одну БД; SQLite-транзакции, runtime controls и гейты решают разные задачи и не должны считаться одной общей блокировкой.

Существуют SupervisorJob scopes приложения/сервисов, viewModelScope и compose scopes. В сетевом withResponse cancellation отменяет настоящий OkHttp Call; большинство бизнес-путей пробрасывает CancellationException. Mutex/CAS вокруг refresh защищает от многих поздних результатов; F01 описывает отдельный непокрытый промежуток request(). MaintenanceRuntime умышленно продолжает операцию после Activity/ViewModel, это само по себе не memory leak. Утечка Activity heap dump не проверялась.

В `appendix/architecture-lexical.json/.tsv` перечислены обнаруженные объявления классов/interfaces/objects с точными файлами/строками и ссылками на символы того же файла; `function-signatures.json` сохраняет сигнатурные строки. Это лексическая карта покрытия, **не compiler call graph и не доказанные зависимости каждого вложенного класса**. Таблица выше — вручную прослеженные зависимости ключевых путей. Для точного compiler graph нужна успешная Gradle/Kotlin сборка.
