# Полный реестр выявленных проблем

P0 — немедленный критический ущерб; P1 — до публичного запуска нового клиента; P2 — плановое устранение/реализация; P3 — сопровождение и полировка. Недостающая возможность относительно нового ТЗ не называется доказанной аварией текущего экспортёра.

Всего: P0: 0, P1: 7, P2: 19, P3: 4.

| ID | Приоритет | Тип | Проблема |
|---|---|---|---|
| F01 | P1 | Гонка / граница аккаунта | request() допускает подстановку новой независимой сессии между двумя чтениями |
| F02 | P1 | Релизная цепочка | Встроенный канал отстаёт от GitHub Latest |
| F03 | P1 | Защита репозитория | main фактически не защищён подготовленным ruleset |
| F04 | P1 | Риск платформы / prerequisite | Музыкальное ядро и вход зависят от внутреннего протокола и чужих client ID |
| F05 | P1 | Пробел в приёмке | Stable опубликован с неподтверждённой ручной приёмкой реального сценария |
| F06 | P1 | Недостающая возможность клиента | Очередь, позиция и режимы прослушивания не переживают смерть процесса |
| F07 | P1 | Производительность хранения | Курсор метаданных переписывает весь снимок целей после каждого файла |
| F08 | P2 | Производительность хранения | Кэш дубликатов полностью сериализуется после каждого нового receipt |
| F09 | P2 | SQLite / N+1 | snapshot() загружает все ошибки и делает отдельный meta-запрос для каждой |
| F10 | P2 | SQLite / масштабирование | Поиск алиасов URI и обновление ссылок читают весь индекс |
| F11 | P2 | MediaStore / старт экрана | Обновление «Сохранённого» делает по несколько provider-запросов на каждый файл |
| F12 | P2 | Coroutine lifecycle | pauseRead() не отменяет проверки доступности друзей |
| F13 | P2 | Запросы / latency | Открытие подборки и скачивание одной песни повторно читают большие списки |
| F14 | P2 | UX / несоответствие будущему клиенту | Полностью скачанные подборки исчезают из библиотеки |
| F15 | P2 | Недостающая возможность | Нет отдельного глобального музыкального поиска и рекомендаций |
| F16 | P2 | UX / недостающая связность | Треки исполнителя отображаются без воспроизведения и скачивания |
| F17 | P2 | UX / недостающая связность | Собственные онлайн-треки не запускаются нажатием, а плеер не получает download task |
| F18 | P2 | Навигация | Самостоятельный экран загрузок есть в коде, но не достижим штатным выбором |
| F19 | P2 | Известное ограничение | Незавершённый большой файл после остановки загружается с начала |
| F20 | P2 | Доступность входа | Вход жёстко требует пакет Google Chrome |
| F21 | P2 | Корректность ошибки | Код VK 201 без дополнительных данных приписывается приватности друга |
| F22 | P2 | Логика сессии / UX | Ручной поиск обложки привязан к credentialId и не переносит штатное продление |
| F23 | P2 | Проверка доступности / UX | TalkBack, крупный текст и семантика музыкальных действий не подтверждены |
| F24 | P2 | Пробел тестирования | Нет доказательств Macrobenchmark, нагрузочной Android-библиотеки и R8 instrumented playback |
| F25 | P2 | Supply chain / полнота SCA | SBOM/OSV не охватывает всю цепочку сборки; SAST workflow отсутствует |
| F26 | P2 | Архитектурный долг | ViewModel объединяет слишком много независимых подсистем и общий busy gate |
| F27 | P3 | Долговечность доказательств | Полные доказательства релиза исчезают по retention CI |
| F28 | P3 | Документация | README и Android compatibility document содержат устаревшие инструкции |
| F29 | P3 | UX сортировки | Меню предлагает сортировки, которые для подборок не применяются |
| F30 | P3 | Мёртвые ветки / сопровождение | Старые UI-компоненты и ручная классификация остаются рядом с актуальным UX |

## F01 · P1 · request() допускает подстановку новой независимой сессии между двумя чтениями

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicSession.kt:188](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicSession.kt#L188), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicSession.kt:202](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicSession.kt#L202).

**Статус доказательств:** Статически прослежена конкретная последовательность чтений. Регрессионный Kotlin-тест подготовлен отдельно, НЕ выполнен; ошибка не объявляется воспроизведённой на телефоне.

**Воспроизводимость / проверка:** Передать persisted-доступ A. Первое store.read() возвращает A; до чтения внутри current()/resolve() заменить хранилище на B с другим readSessionId. После current() block получает B. В приложении возможны параллельные UI, playback и фоновые чтения; сохранение ручного входа использует отдельную блокировку vault.

**Причина:** Проверка supplied/stored выполняется до current(), а полученный access повторно не сравнивается с supplied.readSessionId. Проверка continueRead() после resolve() есть, но этот путь request() её не использует.

**Последствия:** Чтение, начатое в сессии A, может отправиться с токеном B. Это нарушение изоляции запросов, а не доказанное удаление данных другого аккаунта: audio.delete идёт отдельным явным путём.

**Решение:** После разрешения текущего доступа проверить readSessionId и ожидаемый account ID до block; оформить общую выдачу read lease и сравнения для всех чтений. Не держать глобальный mutex на всё время сетевого запроса.

**Необходимые тесты:** В приложенном SessionRequestRaceProposedTest.kt заданы два последовательных результата store.read(). Дополнить A→B, A→новый вход A, logout, проверенное продление A→A. Убедиться, что block не вызывается для новой сессии.



## F02 · P1 · Встроенный канал отстаёт от GitHub Latest

**Код:** [docs/updates/android.json:7](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/docs/updates/android.json#L7), [app/src/main/java/app/alcovaibe/vkmusicexport/updates/UpdateProtocol.kt:16](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/updates/UpdateProtocol.kt#L16), [tools/promote-owner-release.py:140](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/tools/promote-owner-release.py#L140).

**Статус доказательств:** Подтверждено актуальным GitHub API и кодом. Никакого обновления канала во время аудита не выполнялось.

**Воспроизводимость / проверка:** Сравнить pinned docs/updates/android.json (0.12.0, 1343) и GET releases/latest (v0.12.1-build.1433, тот же исходный SHA аудита). Установленный 1343 проверяет этот JSON и не обнаруживает 1433.

**Причина:** Документированный owner-requested путь публикует Latest, но сохраняет новый manifest только в release-output. Публикация канала остаётся отдельным незавершённым действием.

**Последствия:** Пользователи встроенного обновлятора не узнают о текущем Latest. Публикация APK и доставка информации об обновлении расходятся.

**Решение:** Завершать каждый выпуск проверенным PR канала либо явно учитывать отложенную публикацию; добавить проверку расхождения Latest и канала без автоматического слияния непроверенных данных.

**Необходимые тесты:** Контрактный тест обоих путей promotion: версия, minSdk, размер, SHA и digest вложения; внешняя проверка публичного JSON после завершения канала.



## F03 · P1 · main фактически не защищён подготовленным ruleset

**Код:** [.github/main-ruleset.json:4](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/.github/main-ruleset.json#L4), [CONTRIBUTING.md:216](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/CONTRIBUTING.md#L216).

**Статус доказательств:** Подтверждено API branches/main и пустым списком rulesets. Содержимое admin-only endpoint НЕ прочитано; права/настройки организации сверх этих ответов не проверены.

**Воспроизводимость / проверка:** GET branches/main возвращает protected=false и выключенную protection; GET rulesets возвращает []. Прямой branch-protection endpoint недоступен интеграции (403).

**Причина:** JSON ruleset в репозитории — проект конфигурации, а не применённое правило GitHub.

**Последствия:** Обязательность PR, ревью и build/interface не обеспечена серверным правилом. Неизменность main нельзя выводить из наличия JSON.

**Решение:** Администратору применить согласованные правила и проверить effective rules для main; решить вопрос второго ревьюера и аварийных исключений.

**Необходимые тесты:** Read-only проверка effective protection; в отдельном тестовом репозитории проверить запрет merge без checks, force-push и удаления.



## F04 · P1 · Музыкальное ядро и вход зависят от внутреннего протокола и чужих client ID

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceAuth.kt:92](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceAuth.kt#L92), [app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceTokenExchange.kt:48](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceTokenExchange.kt#L48), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeProtocol.kt:93](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeProtocol.kt#L93), [app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt:128](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt#L128).

**Статус доказательств:** Зависимость подтверждена кодом. Работоспособность и разрешения реального VK-аккаунта НЕ проверялись. Публичная схема VKCOM на проверенном SHA не содержит audio/methods.json; это не доказательство отсутствия отдельных партнёрских соглашений.

**Воспроизводимость / проверка:** Проверить константы 7913379/6287487, auth.getAuthCode/checkAuthCode/validateAuthCode, login.vk.com web_token и фиксированные audio/catalog методы. В зависимостях нет официального VK ID SDK.

**Причина:** Используется экспериментальное WEB pairing и web-token exchange, а музыкальные методы не подтверждены как общедоступный контракт для собственного зарегистрированного приложения.

**Последствия:** Вход, музыка друзей, каталог и поиск могут перестать работать независимо от корректности Android-кода. Успешный вход/метод на одном токене не доказывает поддержку всей матрицы возможностей или право распространения аудио.

**Решение:** Выделить VkProvider и AuthProvider, capabilities по аккаунту/сессии, явно различать supported/experimental/unavailable. Проверить зарегистрированное приложение и разрешённую модель интеграции; отдельно оценить официальный VK ID без обещания, что он даст audio-доступ.

**Необходимые тесты:** Разрешённый тестовый аккаунт: отдельные проверки собственного списка, друга, поиска, карточки/альбома, URL, продления, отзыва, подписки/региона; фиксировать только обезличенные результаты по версии протокола.



## F05 · P1 · Stable опубликован с неподтверждённой ручной приёмкой реального сценария

**Код:** [docs/release-acceptance/current.json:2](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/docs/release-acceptance/current.json#L2), [tools/promote-owner-release.py:38](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/tools/promote-owner-release.py#L38), [.github/workflows/android.yml:305](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/.github/workflows/android.yml#L305).

**Статус доказательств:** Подтверждено current.json, журналом CI и notes релиза. Авторизация владельца в существующем пути признана; аудит ничего не публиковал.

**Воспроизводимость / проверка:** Сопоставить current.json=pending, release job 126026866925 и notes Latest: ручная приёмка не подтверждена, публикация сделана по отдельному запросу владельца.

**Причина:** Существуют два намеренно разных пути: обычный stable с exact-APK gate и ограниченное owner-requested продвижение при pending.

**Последствия:** Слово stable не означает проверку входа, 4449+ реальных записей, часа с погашенным экраном, сети и OEM. Нельзя использовать зелёную матрицу как замену этих результатов.

**Решение:** Для будущего клиента определить единый критерий стабильности. Если исключения сохраняются, отдельно отображать статус ручной приёмки и хранить точные результаты APK. Это не утверждение о несанкционированной публикации.

**Необходимые тесты:** Выполнить восемь обязательных сценариев из acceptance, плюс online/offline resumption, BT/audio focus и поиск. Каждый результат привязать к SHA, versionCode и SHA-256 APK.



## F06 · P1 · Очередь, позиция и режимы прослушивания не переживают смерть процесса

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicPlaybackService.kt:24](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicPlaybackService.kt#L24), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicPlaybackService.kt:150](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicPlaybackService.kt#L150), [app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackModel.kt:32](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackModel.kt#L32), [app/src/main/AndroidManifest.xml:23](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/AndroidManifest.xml#L23).

**Статус доказательств:** Отсутствие persistence подтверждено кодом и PRIVACY.md. Сценарий настоящего убийства playback-процесса в этом окружении не запускался; это известное ограничение, а не регрессия обещанного экспортёра.

**Воспроизводимость / проверка:** Создать очередь, включить shuffle/repeat, перейти к середине записи, остановить процесс и открыть приложение. В новом процессе PlaybackRuntime создаёт пустой PlaybackState; устойчивой записи playback queue и callback resumption нет.

**Причина:** Состояние хранится в singleton/полях сервиса; MediaButtonReceiver и onPlaybackResumption не реализованы.

**Последствия:** Нельзя продолжить ежедневное прослушивание с прежнего места после выгрузки процесса/перезагрузки. Скачивания при этом имеют другую, устойчивую очередь — их нельзя путать.

**Решение:** Сохранить media IDs, источник/аккаунт, порядок и shuffle seed, index/position/repeat без токенов и signed URL; восстановление выполнять по явному действию или Media3 resumption с проверкой доступа.

**Необходимые тесты:** Kill/recreate/reboot на API 29–37, точное повторное вхождение, offline без VK, logout и другой account, expired URL, некорректный локальный файл.



## F07 · P1 · Курсор метаданных переписывает весь снимок целей после каждого файла

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/TrackMetadataBatch.kt:7](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/TrackMetadataBatch.kt#L7), [app/src/main/java/app/alcovaibe/vkmusicexport/music/TrackMetadataBatch.kt:71](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/TrackMetadataBatch.kt#L71), [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt:210](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt#L210).

**Статус доказательств:** Квадратичная структура подтверждена исходниками и host-side моделью сериализации; Android-время и фактические байты флеш-записи НЕ измерены.

**Воспроизводимость / проверка:** Создать TrackMetadataRun на 100/4500/20000 целей. Каждый save(run) вызывает JSON encode всего targets и INSERT OR REPLACE meta.

**Причина:** Неизменяемый список целей и изменяемый next/results объединены в одном JSON значении. Вынесение целей в MaintenanceRuntime не устраняет отдельный SQLite metadataRun.

**Последствия:** Объём сериализации за проход O(N²), большие строки и WAL-записи. Синтетическая модель 20000 целей даёт 3.51 MB на снимок и около 70.1 GB повторно сериализуемых байтов; это расчёт, не измеренный физический износ флеш-памяти.

**Решение:** Снимок целей записывать один раз, курсор/счётчики отдельно; результаты поштучно в таблицу с транзакцией. Сохранить возобновление по аккаунту и одноразовый ручной выбор.

**Необходимые тесты:** Нагрузочные 100/4500/20000; считать сериализованные байты и WAL, p95 checkpoint, heap/GC; kill между записью файла, индексом и next; миграция прежнего metadataRun.



## F08 · P2 · Кэш дубликатов полностью сериализуется после каждого нового receipt

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/DuplicateScanCache.kt:71](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DuplicateScanCache.kt#L71), [app/src/main/java/app/alcovaibe/vkmusicexport/music/DuplicateScanCache.kt:84](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DuplicateScanCache.kt#L84).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** При первоначальном проходе добавлять N новых сигнатур: save() вызывает write() и AtomicFile.finishWrite() для всего checks.

**Причина:** Накопительный JSON вместо инкрементальных строк/журнала.

**Последствия:** O(N²) сериализации и синхронизаций; модель для 20000 receipts — около 31.8 GB текста суммарно без учёта обёртки. Повторный проход с cached не имеет той же стоимости.

**Решение:** Таблица receipts с owner, fingerprint/version, TTL и индексом; каждый завершённый receipt фиксировать независимо. Согласия на удаление по-прежнему не сохранять.

**Необходимые тесты:** Холодный и повторный scan, TTL, другой аккаунт, переупорядочивание, повреждённый receipt, отмена при записи, отсутствие полной аудиокопии в durable cache.



## F09 · P2 · snapshot() загружает все ошибки и делает отдельный meta-запрос для каждой

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt:152](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt#L152), [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportEngine.kt:64](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportEngine.kt#L64).

**Статус доказательств:** Подтверждено кодом и выполненным host SQLite benchmark. Это НЕ замер Android SQLite или Kotlin JSON.

**Воспроизводимость / проверка:** Создать много FAILED tasks и прочитать snapshot(). UI показывает по 50 проблем, но store сначала декодирует весь список; для каждого ключа вызывается get(failure_detail).

**Причина:** Агрегаты прогресса и полный подробный отчёт объединены в одном методе, который используется в горячем цикле очереди.

**Последствия:** Host-side подмножество чтения ошибок: N+2 SQL; при 20000 — 20002 запроса и median 93.941 ms на сервере. При частых refresh работа повторяется.

**Решение:** Разделить progress snapshot, страницу ошибок и полный экспорт; JOIN отдельной таблицы details или bulk-чтение. Полные ошибки не терять и не ограничивать молча пятью/десятью.

**Необходимые тесты:** 100/4500/20000 ошибок; число SQL должно быть ограниченным на страницу, счётчики и полная выгрузка совпадают после restart/retry; замеры на API29 и реальном медленном накопителе.



## F10 · P2 · Поиск алиасов URI и обновление ссылок читают весь индекс

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt:365](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt#L365), [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt:368](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt#L368).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Изменять обложки/имена многих файлов большой библиотеки. Для каждой правки savedFiles() декодирует все payload, taskRows() читает все задачи; aliases выбираются в памяти по uri.

**Причина:** URI, account/source и другие часто используемые поля спрятаны в JSON; SQL-индекса files(uri) нет.

**Последствия:** Полный обход на каждый изменённый физический файл, т.е. O(N²) при массовой обработке. Индекс file_tracks существует и полезен, но не решает поиск по URI.

**Решение:** Добавить нормализованные колонки/таблицы physical files и memberships, индекс URI; выбрать алиасы адресным запросом в той же транзакции.

**Необходимые тесты:** Миграция v1/v2/v3; много источников на один URI, одинаковые названия/разные IDs, забытая ссылка, cleared recent, retag rollback, M3U8 старых очередей.



## F11 · P2 · Обновление «Сохранённого» делает по несколько provider-запросов на каждый файл

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:68](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L68), [app/src/main/java/app/alcovaibe/vkmusicexport/music/SharedAudioStore.kt:65](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/SharedAudioStore.kt#L65), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:152](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L152).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Открыть приложение с большой savedFiles; refreshSavedLibrary читает все строки и последовательно вызывает present() для каждого уникального URI.

**Причина:** present() сначала проверяет owner/path/pending, затем отдельным query размер; нет bulk-проекции MediaStore. Обновление запускается также при изменениях busy/очереди.

**Последствия:** Не менее двух provider-запросов на файл в успешном пути: для 20000 это около 40000 обращений, без учёта миниатюр. Время на устройстве не измерено.

**Решение:** Пакетное чтение метаданных собственных URI, постраничная локальная библиотека, адресные обновления изменённых файлов; полную SHA-проверку сохранить для чувствительных операций.

**Необходимые тесты:** Счётчик ContentResolver запросов, время до первых 20 строк, отмена обновления, исчезнувшие/переименованные файлы, account/source membership без потери локальных копий.



## F12 · P2 · pauseRead() не отменяет проверки доступности друзей

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt:27](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt#L27), [app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt:117](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt#L117), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:1371](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L1371).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Начать предварительную проверку доступности списка друзей, затем вызвать pauseRead()/свернуть Activity. work отменяется, previewWork остаётся дочерней coroutine viewModelScope.

**Причина:** Проверки badges имеют отдельный Job; цикл использует checked(initial), но не проверяет generation или foreground. Смена generation в pauseRead не инвалидирует этот цикл.

**Последствия:** Предварительные музыкальные запросы могут продолжаться без видимого экрана, занимая глобальный pacer и трафик. Это не доказанная утечка токенов.

**Решение:** Отменять previewWork и wake канал при уходе экрана/фоне; возобновлять только для актуальной сессии и видимых карточек.

**Необходимые тесты:** Fake API с барьером: после pauseRead ни один новый probe не начинается; уже начатый Call отменяется; возврат корректно использует TTL cache. Отдельно logout и смена профиля.



## F13 · P2 · Открытие подборки и скачивание одной песни повторно читают большие списки

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:1201](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L1201), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:1288](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L1288), [app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt:241](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicController.kt#L241).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Нажать скачать один трек в библиотеке из 20000 записей или открыть подборку при большом каталоге.

**Причина:** download(track,position) заново получает весь список для проверки вхождения; select() сначала перечитывает currentCatalog. Для друзей выбор также перепроверяет полный список.

**Последствия:** При page size 100 и pacer 350 ms только 200 страниц требуют минимум около 69.65 s межзапросных интервалов, без RTT, ошибок и других потребителей. Это аналитическая нижняя граница, не замер сети VK.

**Решение:** Разделить семантику «скачать точный трек» и «сохранить вхождение источника». Для первого свежие exact-ID/owner проверки; для второго явно проверяемый снимок членства/позиции. Не заменять проверку предположением о неизменности библиотеки.

**Необходимые тесты:** Измерить число запросов/latency на 100/4500/20000; перестановка/удаление и повторные occurrences должны сохранять корректный источник; foreground playback не должен голодать из-за фоновых badges.



## F14 · P2 · Полностью скачанные подборки исчезают из библиотеки

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/CatalogDownloads.kt:9](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/CatalogDownloads.kt#L9), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicCatalog.kt:73](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicCatalog.kt#L73).

**Статус доказательств:** Поведение подтверждено кодом и существующим CatalogDownloadsTest; это продуктовая смена требований, не случайная ошибка экспортёра.

**Воспроизводимость / проверка:** Сохранить подборку целиком и дождаться успешной downloadedCatalog проверки — её key попадает в hiddenCatalog и отфильтровывается из visible.

**Причина:** Каталог спроектирован как перечень ещё не экспортированного.

**Последствия:** Ежедневный клиент теряет привычный вход к любимому скачанному альбому/плейлисту. «Сохранённое» представляет плоские файлы, а не полную локальную модель подборок.

**Решение:** Сохранять подборки видимыми, показывать состояние offline/частично/доступно online; отделить фильтр «ещё не скачано» от основной библиотеки.

**Необходимые тесты:** Скачивание/удаление части копий, offline открытие источника, одинаковые названия, удалённый remote источник и сохранённый local snapshot. Текущие тесты скрытия придётся заменить согласованным контрактом.



## F15 · P2 · Нет отдельного глобального музыкального поиска и рекомендаций

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt:229](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt#L229), [app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt:239](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/VkAudioClient.kt#L239), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt:43](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt#L43).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Проследить audio.search: он вызывается из поиска источника обложки/названия, с PAGE_SIZE=20 и MAX_RESULTS=100. Музыкального destination Search и recommendations provider нет.

**Причина:** Существующий поиск — вспомогательная операция ремонта метаданных, а не discover/play/download продукта.

**Последствия:** Нельзя считать глобальный поиск, каталог новинок и рекомендации реализованными только из-за присутствия audio.search в allowlist.

**Решение:** Выделить search capability и экран с пагинацией, loading/empty/denied/challenge состояниями; рекомендации реализовать только при подтверждённом доступе к конкретному источнику.

**Необходимые тесты:** Debounce/cancellation, поздний ответ старого запроса, 201/15/5/6/14/17, partial и пустые результаты, выбор точного вхождения, play/download. Реальную доступность проверить отдельно.



## F16 · P2 · Треки исполнителя отображаются без воспроизведения и скачивания

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:315](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L315), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:316](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L316).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Открыть исполнителя и его треки либо альбом; строки получают только track, position, preview, без onOpen/onDownload.

**Причина:** MusicTrackRow по умолчанию не имеет действия при onOpen=null; callbacks не подключены в artist ветке.

**Последствия:** Страница выглядит музыкальным каталогом, но полученную музыку нельзя слушать или скачать из этого экрана.

**Решение:** Общая модель TrackActions/PlaybackEntry и source для artist/album; разрешённые действия определять capabilities и состоянием трека.

**Необходимые тесты:** Нажатие строки/кнопки, очередь с текущего трека, album back, unavailable row, expiry, корректный source owner/account.



## F17 · P2 · Собственные онлайн-треки не запускаются нажатием, а плеер не получает download task

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:193](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L193), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:408](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L408), [app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackUi.kt:241](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackUi.kt#L241).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Открыть нескопированный трек «Моей музыки»: строка не имеет onOpen, хотя кнопка всей библиотеки может запустить online queue. У этих PlaybackEntry download=null.

**Причина:** Библиотечная ветка связывает onOpen только с saved URI; online entries создаются без AudioExportTask.

**Последствия:** Нельзя естественно начать с выбранной песни и нажать «Скачать» в плеере своей библиотеки; у друзей эти действия связаны полнее.

**Решение:** Единый tap→play-from-index и отдельный download; сохранить исходные VK позиции/occurrence при сортировке и собрать корректный task для entry.

**Необходимые тесты:** Сортированный список, повтор одного ID, частичная страница, restricted+сохранённая копия, queue busy, одинаковые названия и artist source.



## F18 · P2 · Самостоятельный экран загрузок есть в коде, но не достижим штатным выбором

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt:73](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt#L73), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:226](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L226), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:152](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L152).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Перечислить места изменения destination: пользовательская нижняя панель содержит три раздела без DOWNLOADS; верхняя кнопка вызывает showDownloads(), который открывает sheet.

**Причина:** Старый enum/ветка полноэкранных загрузок сохранены после замены нижнего пункта на «Друзья», но вход к ним не подключён.

**Последствия:** Требование отдельного экрана загрузок пока выполнено только декларативно; длинная очередь остаётся ограничена bottom sheet.

**Решение:** Добавить явный route Downloads через верхнюю кнопку/меню; sheet оставить компактным summary при необходимости. Восстанавливать route после process recreation.

**Необходимые тесты:** Из всех экранов открыть full downloads, system Back, мини-плеер, notification routing, большой текст, page ошибок и возврат к источнику.



## F19 · P2 · Незавершённый большой файл после остановки загружается с начала

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioTransfer.kt:172](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioTransfer.kt#L172), [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportEngine.kt:107](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportEngine.kt#L107).

**Статус доказательств:** Подтверждено кодом и прямо описано в CONTRIBUTING.md. Это известный компромисс; успешное сохранение завершённых файлов не оспаривается.

**Воспроизводимость / проверка:** Приостановить загрузку большого прямого файла или позднего HLS-сегмента; возобновление создаёт UUID staging заново.

**Причина:** Нет durable Range/ETag/If-Range checkpoint или сохранения подтверждённых сегментов; текущие частичные файлы удаляются.

**Последствия:** Готовые треки сохранены, но трафик текущего файла повторяется, что чувствительно для длинных записей и нестабильной сети.

**Решение:** При необходимости ввести безопасное частичное возобновление с проверкой ресурса/identity, длины, аккаунта и меняющихся signed URL. Для нестабильного HLS либо проверенный manifest fingerprint, либо явный restart.

**Необходимые тесты:** 206/200/416, ETag смена, Range ignored, новая ссылка на тот же/другой контент, key rotation, process kill, ENOSPC. Не принимать склеенный частичный файл как полный.



## F20 · P2 · Вход жёстко требует пакет Google Chrome

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceAuth.kt:364](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceAuth.kt#L364), [app/src/main/AndroidManifest.xml:12](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/AndroidManifest.xml#L12).

**Статус доказательств:** Текущее ограничение подтверждено кодом и SettingsAndAuthDeviceTest; это явный продуктовый выбор, который требует пересмотра под Android10+ клиент.

**Воспроизводимость / проверка:** На устройстве без com.android.chrome либо с отключённым Chrome попытаться открыть подтверждение. Код возвращает BROWSER и не выбирает другой Custom Tabs provider.

**Причина:** Provider захардкожен; текущие тесты намеренно закрепляют отсутствие fallback.

**Последствия:** Часть Android10+ устройств не может подключиться через доступный иной браузер. Это особенно существенно для широкого списка OEM.

**Решение:** Согласовать поддержку проверенных Custom Tabs providers; сохранить HTTPS allowlist, запрет чтения браузерных cookies и корректный chooser/error. Не внедрять небезопасный WebView ради обхода.

**Необходимые тесты:** Chrome установлен/отключён/отсутствует, выбранный поддерживаемый провайдер, возврат и отмена, lifecycle кодов, вредоносный intent handler.



## F21 · P2 · Код VK 201 без дополнительных данных приписывается приватности друга

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusic.kt:15](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusic.kt#L15).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Создать FriendMusicProblem(DENIED,201): UI сообщает, что именно пользовательские настройки друга запрещают доступ.

**Причина:** Из общего кода Access denied выводится конкретная причина. В проверенной публичной errors.json VKCOM описание 201 — Access denied, без доказательства настроек конкретного пользователя.

**Последствия:** Пользователь получает неподтверждённое объяснение; ограничение клиента/токена может выглядеть как личный запрет друга.

**Решение:** Нейтральный текст «ВК не разрешил получить аудиозаписи этого профиля»; конкретную приватность указывать только по отдельно подтверждённым данным.

**Необходимые тесты:** 201 при валидном/ограниченном подключении, 15/7/5, HTTP403 без envelope, пустой успешный раздел; обновить тест ожидаемого текста.



## F22 · P2 · Ручной поиск обложки привязан к credentialId и не переносит штатное продление

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:452](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L452), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:466](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L466), [app/src/main/java/app/alcovaibe/vkmusicexport/music/ArtworkLookup.kt:42](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/ArtworkLookup.kt#L42).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Получить результаты поиска обложки, штатно продлить токен той же read session, затем открыть/применить выбранную обложку. Сравнения vault.credentialId и choice.credentialId отвергают операцию.

**Причина:** Artwork flow использует ID конкретного токена, metadata flow уже использует readSessionId. Продление и новый вход смешиваются в одном сравнении.

**Последствия:** Пользователя вынуждают искать заново при нормальной ротации токена. Защита записи сохраняется; это отказ в выполнении, не доступ чужого аккаунта.

**Решение:** После решения F01 использовать read lease/owner для поиска обложок, сохраняя expiry и одноразовое подтверждение; новый независимый вход по-прежнему инвалидирует выбор.

**Необходимые тесты:** Ротация токена между search/preview/apply, новый вход в тот же и другой account, background и expiresAt. Сравнить контракт с TrackMetadataLookupTest.



## F23 · P2 · TalkBack, крупный текст и семантика музыкальных действий не подтверждены

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackUi.kt:202](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/PlaybackUi.kt#L202), [app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicUi.kt:25](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/FriendMusicUi.kt#L25), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt:120](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibraryUi.kt#L120).

**Статус доказательств:** Подтверждён пробел проверки и конкретные отсутствующие labels; визуальные дефекты на устройстве не объявляются воспроизведёнными.

**Воспроизводимость / проверка:** Нужна ручная проверка TalkBack и fontScale 1.3/2.0. По коду у Slider нет осмысленного имени позиции, поиск друзей использует placeholder без постоянного label, ряд заголовков обрезается maxLines.

**Причина:** Наличие contentDescription у части кнопок не покрывает всю семантическую модель, контекст slider и сценарии крупного текста; соответствующего приёмочного результата нет.

**Последствия:** Есть риск непонятного управления и скрытого текста. Непроходимость TalkBack или конкретное визуальное перекрытие в этом аудите НЕ установлены.

**Решение:** Добавить согласованные semantics/labels/stateDescription, явное действие выбора вместо зависимости от long press, проверить фокус и чтение ошибок/прогресса.

**Необходимые тесты:** TalkBack последовательность от поиска до play/download, Switch Access/клавиатура, slider с минутами, 200% текст/экран 360dp/ландшафт, gesture cancel и Back без потери очереди.



## F24 · P2 · Нет доказательств Macrobenchmark, нагрузочной Android-библиотеки и R8 instrumented playback

**Код:** [.github/workflows/android.yml:100](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/.github/workflows/android.yml#L100), [app/build.gradle.kts:72](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/build.gradle.kts#L72).

**Статус доказательств:** Подтверждено конфигурацией и журналами. 144 device tests на каждом API29–37 реально подтверждены CI; они не объявляются пропущенными.

**Воспроизводимость / проверка:** Проверить Gradle modules/dependencies/workflows: instrumented suite connectedDebugAndroidTest, release собирается и проверяется установкой/миграцией, benchmark module и explicit Macrobenchmark отсутствуют.

**Причина:** Хорошее функциональное synthetic покрытие не измеряет startup/frame timing/heap/CPU/трафик и не запускает всю suite на minified release.

**Последствия:** Нельзя подтвердить latency, memory budget, 20000 записей и release-only runtime поведение по зелёному CI. >100MiB audio test существует и не заменяет нагрузку числа треков.

**Решение:** Отдельный benchmark target и deterministic datasets, периодический signed/minified smoke на API29/36/37, профилирование без реальных секретов.

**Необходимые тесты:** Cold/warm startup, scroll frame p50/p95/p99, search 20k, queue restore, 1h/8h screen-off, network fault injection, DB/WAL/heap/GC, reinstallation/upgrade без удаления файлов.



## F25 · P2 · SBOM/OSV не охватывает всю цепочку сборки; SAST workflow отсутствует

**Код:** [app/build.gradle.kts:104](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/build.gradle.kts#L104), [tools/dependency-audit.py:9](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/tools/dependency-audit.py#L9), [.github/workflows/android.yml:68](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/.github/workflows/android.yml#L68).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Проследить writeDependencyInventory: выбранные app runtime/compile/test и lint configurations. Buildscript/plugin/toolchain, Actions, Gradle distribution и SDK не включены в эту Maven inventory.

**Причина:** OSV scan корректно обрабатывает свой вход, но вход ограничен. Отдельного CodeQL/SAST workflow среди четырёх workflows нет.

**Последствия:** Зелёный scan 217 зависимостей не означает отсутствие проблем во всех инструментах сборки или исходниках.

**Решение:** Сохранить lock/checksum/pinned Actions; инвентаризировать plugins/tools отдельно, добавить соразмерный SAST/secret scan и source-specific проверки без фиктивного обещания полного покрытия.

**Необходимые тесты:** Известный vulnerability fixture должен ломать scan; network/OSV pagination ошибки не проходят; отсутствие любого требуемого inventory блокирует выпуск; отдельный отчёт build tools.



## F26 · P2 · ViewModel объединяет слишком много независимых подсистем и общий busy gate

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:33](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L33), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt:841](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicViewModel.kt#L841), [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:50](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L50).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** ViewModel — 1414 строк / 103187 байт; содержит auth, library/catalog, artists, downloads, metadata/artwork lookup, removal и maintenance orchestration. Panel — 487 строк.

**Причина:** UI/application orchestration, конкретные stores/services и бизнес-процессы в одном coordinator; глобальный NativeMusicGate и busy сериализуют много операций.

**Последствия:** Изменение одной функции затрагивает общие generation/cancellation/state, сложно независимо развивать поиск и playback. Размер сам по себе не является доказанной утечкой памяти.

**Решение:** Постепенно выделить AuthCoordinator, CatalogRepository, DownloadCoordinator, SavedFilesRepository и maintenance use cases, отдельные screen ViewModels; оставить одно владение файловыми операциями.

**Необходимые тесты:** Контрактные тесты смены экрана/сессии и конкуренции до/после extraction, lifecycle cancellation, retained background service. Не переписывать всё одним PR.



## F27 · P3 · Полные доказательства релиза исчезают по retention CI

**Код:** [.github/workflows/android.yml:81](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/.github/workflows/android.yml#L81), [tools/publish-release.py:55](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/tools/publish-release.py#L55).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Публичный release содержит APK+SHA256SUMS; build-info/SBOM/OSV/upgrade evidence остаются в CI artifact на 30 дней.

**Причина:** Долговременный архив полного комплекта не является частью публикации.

**Последствия:** После истечения retention труднее независимо проверить точный граф зависимостей и воспроизвести доказательства исторической сборки.

**Решение:** Хранить обезличенный build-info, SBOM, результаты checks/upgrade и provenance долговременно рядом с точным SHA/APK. Закрытый ключ и секреты никогда не включать.

**Необходимые тесты:** Проверить комплект для нового и 31+ дневного релиза, согласованность SHA/APK, доступность пользователю без CI-админских прав.



## F28 · P3 · README и Android compatibility document содержат устаревшие инструкции

**Код:** [README.md:209](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/README.md#L209), [docs/ANDROID_API29_COMPATIBILITY.md:3](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/docs/ANDROID_API29_COMPATIBILITY.md#L3).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** README обещает вход «Настроить доступ ВК», хотя текущий access screen удалил ручной ввод. Compatibility документ всё ещё описывает stable 0.11.9/minSdk34, тогда как Latest 0.12.1/minSdk29.

**Причина:** Перенесённые изменения не согласованы с текущим руководством.

**Последствия:** Пользователь ищет отсутствующий путь и получает неверный контекст требований опубликованного APK.

**Решение:** Обновить текущие инструкции, оставив исторические CHANGELOG записи историческими; отделить build-config и фактически проверенный release.

**Необходимые тесты:** Сверка навигации README с UI; ссылки/доступность документов offline, minSdk/version по manifest выбранного APK. Новая сборка только ради Markdown не требуется.



## F29 · P3 · Меню предлагает сортировки, которые для подборок не применяются

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt:432](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/NativeMusicPanel.kt#L432), [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibrarySorting.kt:33](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicLibrarySorting.kt#L33).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** На списке альбомов/плейлистов выбрать «По году» или «По исполнителю»: collections() обрабатывает только OLDEST и TITLE, остальные возвращают исходный список.

**Причина:** Общее меню без capabilities выбранного типа списка.

**Последствия:** Видимый выбор не соответствует результату; отсутствие полей года/артиста у NativePlaylist не объясняется.

**Решение:** Показывать применимые варианты или добавить достоверные поля и сортировку. Не выдумывать год из названия.

**Необходимые тесты:** Каждая доступная сортировка действительно меняет контрольный набор; выбранный label после смены категории соответствует фактическому порядку.



## F30 · P3 · Старые UI-компоненты и ручная классификация остаются рядом с актуальным UX

**Код:** [app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicCatalogPanel.kt:13](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/MusicCatalogPanel.kt#L13), [app/src/main/java/app/alcovaibe/vkmusicexport/music/DownloadProgressSheet.kt:32](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/DownloadProgressSheet.kt#L32), [app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt:434](https://github.com/alcovaibe/vk-music-export/blob/90e6848257e93d82b3a6b8af09cc58ef067a7703/app/src/main/java/app/alcovaibe/vkmusicexport/music/AudioExportStore.kt#L434).

**Статус доказательств:** Подтверждено статическим анализом; новый Android-сценарий в этом аудите не запускался.

**Воспроизводимость / проверка:** Поиск ссылок в main показывает объявления MusicCatalogPanel, DownloadQueueButton, LibraryRemoveButton без production call sites. catalogKind(s) и choices сохраняют прежнюю ручную классификацию, которую MusicCatalog.kind игнорирует.

**Причина:** Исторические UI paths и compatibility seams не выделены как legacy; некоторые тесты проверяют их отдельно.

**Последствия:** Лишняя поверхность сопровождения и риск принять тест старого компонента за покрытие реальной навигации. Не вся legacy логика мертва: миграции и формат данных удалять нельзя.

**Решение:** Проверить usages компилятором/IDE; удалить только недостижимые UI paths, пометить обязательные миграции и совместимость. Тесты основного UX привязать к actual MainActivity.

**Необходимые тесты:** MainActivity navigation smoke, upgrade v1/v2/v3, сравнение достигнутых routes и тестовых entry points, release R8 smoke.
