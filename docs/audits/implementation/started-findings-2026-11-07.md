# Продолжение начатых F26 / F06 / F23 / F24 / F25 / F27

База: `e7706f314c765c2f28a73914c01897a9ccab5013`. Работа только в `fix/audit-stage0-release-delivery-20261104`; main, версия и публикация релизов не изменяются. Исторический аудит не переписывается.

## F26 — разделение управления

Чтение подписок, страниц исполнителя и альбомов вынесено в `MusicArtistsController`. У него собственные `busy`, `stage`, progress, coroutine job и generation. Он не занимает `NativeMusicGate`, не отменяет каталог и не останавливает maintenance/download service. Его Back, переход в другую категорию, уход в фон и смена доступа отменяют только его чтения. Поздний ответ и finally старого запроса не заменяют состояние нового. Доступ после результата проверяется по `MusicReadIdentity`; проверенное продление допускается, независимый вход — нет. Методы ViewModel сохранены как фасад.

UI использует `artists.busy` для действий исполнителей; переключение категорий и другие подсистемы остаются доступны во время этого запроса. Запись файлов по-прежнему принадлежит существующим runtime owners и не распараллеливается изменением UI-флага.

Добавлены JVM-контракты отмены/позднего ответа, независимых владельцев и границы сессии. Существующая Android-проверка timeout/retry переведена на собственный busy исполнителей. Полное выделение auth, каталогов и maintenance из ViewModel ещё не закончено: F26 остаётся частичным, одного extraction недостаточно для закрытия исходного замечания.

## Ограничения проверки

В рабочей среде нет Android SDK/ADB и Gradle distribution 9.8.2. `./gradlew --version` завершился `Network is unreachable` при скачивании distribution. Это не успешная JVM/Android/R8/Lint проверка. Эти результаты фиксируются по Actions конкретного нового SHA. Физический телефон, TalkBack, гарнитура, OEM screen-off 1h/8h и настоящий VK-аккаунт в этой среде недоступны.

## F23 — доступность

Поиск друзей/музыки сохраняет label после ввода текста. Slider имеет имя «Позиция воспроизведения», elapsed/duration с единицами времени и стандартный SetProgress. Кнопка паузы остаётся подписанной, когда вместо иконки показан spinner. Название/исполнитель полноэкранного плеера доступны без ограничения числа строк. Категории при fontScale >=1.3 занимают отдельные строки; состояния выбора доступны в semantics. У трека друга есть отдельная кнопка выбора и accessibility action, длительное нажатие не является единственным входом. Названия действий скачивания/меню включают название и исполнителя трека.

Добавлены два обязательных Compose instrumentation сценария: 360dp, fontScale 1.3/2.0, позиция/SetProgress/пауза во время буферизации, постоянная подпись заполненного поиска и явный выбор. Это проверка semantics и достижимости действий, а не ручное прослушивание TalkBack. Реальный порядок фокуса TalkBack, Switch Access, аппаратная клавиатура и визуальная приёмка ландшафта остаются открытыми. F23 частичен до этих результатов.

## F24 — дополнительные измерения и R8

Подготовлен отдельный release instrumentation source set `src/r8AndroidTest`, включаемый только `-Pr8PlaybackCheck=true`. Release остаётся minified и non-debuggable; используется одноразовая debug-подпись runner, постоянный release-ключ не нужен. Эта сборка не публикуется и не называется точным APK публичного релиза. Основные debug tests не подменяются этим коротким набором.

Обязательные jobs API29/36/37 требуют настоящий mapping R8, единственный исполненный (не skipped) playback testcase, фактическое `playing`/`seekable`, pause/seek/resume, STOP и новый Play, отсутствие playback error и пятесекундный фоновый sample. Пишутся first-play latency, process CPU, PSS и Java heap. Сохраняются пять cold/warm `am start -W` samples, gfxinfo framestats и meminfo. Aggregate interface требует успех и этих jobs. Нагрузочные DB/queue/history сценарии 100/4500/20000 из прежнего полного набора сохраняются.

Это ещё не AndroidX Macrobenchmark, не scroll по 20000 реально отображённых треков, не профилирование сети/GC и не 1h/8h screen-off. Замеры launcher без загруженной библиотеки нельзя выдавать за показатели библиотеки 20k. Все новые численные результаты появятся только после реального запуска Actions; до него F24 частичен.

## F25 — область SBOM/OSV и SAST

Application inventory теперь требует наличия всех восьми выбранных конфигураций. Отдельная `writeBuildToolInventory` собирает фактически разрешённые Maven-графы project/settings plugins; пустой граф блокирует успех. `toolchain-inventory.py` фиксирует pinned Actions, checksum Gradle distribution, наблюдаемые версии Java и установленных SDK packages. Секреты/переменные окружения целиком не выгружаются.

SBOM объединяет app+plugin Maven coordinates и Actions purl; OSV проверяет Maven versions и pinned Action source commits, в том числе при повторном сканировании candidate SBOM. Pagination и сетевые ошибки по-прежнему не превращаются в clean. Необрабатываемый тип candidate component отклоняется. Добавлен CodeQL security-extended для java-kotlin (реальная manual-компиляция), Python и Actions. SHA CodeQL Action закреплён; SARIF сохраняется. Отсутствие SARIF, ошибка анализа и любая находка блокируют aggregate interface; автоматических suppressions нет.

Gradle/JDK/Android SDK здесь инвентаризированы, но не объявлены проверенными Maven OSV. Образ runner и транзитивные зависимости Action runtime также не покрываются этой inventory. Пока нет успешных новых resolved inventory/OSV/SAST отчётов и разбора возможных находок, F25 остаётся частичным.

## F27 — долговременные доказательства

Существующий `release-evidence.zip` сохраняется как release asset, а не только CI artifact. Дополнительно будущий bundle требует toolchain, исходные dependency graphs, R8 evidence API29/36/37, CodeQL SARIF и `ci-origin.json` с source/verification SHA, run/job/artifact IDs и доступными GitHub artifact digests. Recovery получает те же артефакты из исходного candidate run и отдельно записывает текущую verification revision; исходный SHA APK не меняется.

`verify-release-evidence.py` работает без авторизации в CI: не распаковывает архив, запрещает duplicate/traversal/symlink entries, ограничивает суммарный размер, проверяет точный набор файлов и каждый SHA-256/размер, связывает build-info/checksums с APK, отвергает неполный SBOM/OSV и несовпадение ожидаемого commit/digest. Publisher вызывает его перед первой публикацией. Schema1 читается для прежних архивов; schema2 проверяет источник CI. Хэши внутри архива сами по себе не удостоверяют автора — для независимой проверки нужен доверенный APK/digest и source SHA.

Проверка скачанного комплекта: `python3 tools/verify-release-evidence.py release-evidence.zip --apk VK-Music-Export-<version>-<code>.apk --expected-commit <source-sha>`. Сам verifier хранится в исходном коде этой revision. CI удалит временные артефакты по retention, но опубликованный release asset не удаляется этим сроком.

Добавлены проверки повреждения содержимого/APK, чужого source SHA, лишнего файла, traversal, дубля и чтения архива со старой датой без CI. Это синтетическая проверка отсутствия зависимости от времени, а не реальное наблюдение release asset спустя 31 день. Новый релиз не создавался; фактические публикация/анонимное скачивание полного нового комплекта и 31-дневная проверка остаются неподтверждёнными. F27 имеет реализацию будущей цепочки, но не полную эксплуатационную приёмку.

## Первый новый CI — фактические отказы

Прогон `6c1a8f1` / [42371276528](https://github.com/alcovaibe/vk-music-export/actions/runs/42371276528) не прошёл: instrumentation compilation использовала `SemanticsProperties.CustomActions` вместо `SemanticsActions.CustomActions`; inventory потребовала отсутствующую `releaseUnitTestRuntimeClasspath`; CodeQL gate не учёл rules в `tool.extensions`. Исправлены конкретные причины. Порог тестов, запреты skip и STOP-проверки не ослаблялись. В этой AGP-конфигурации существуют семь обязательных выбранных classpaths, а не восемь; отсутствующий release unit-test variant не выдаётся за просканированный.

Скачаны и прочитаны исходные SARIF. Actions: 23 правила, 0 результатов. Python: 50 правил, 3 результата в `prepare-signing.py`: GitHub add-mask, запись PKCS12 и runner-managed GITHUB_ENV. Для этих конкретных операций добавлен ручной разбор `config/sast-reviewed.json` с rule/path/line и SHA-256 всего файла; изменение источника инвалидирует review, другая находка не покрывается им. Raw SARIF сохраняется без удаления результатов. Запись PKCS12 усилена exclusive-create/0600 до записи байтов, без chmod-окна и перехода по прежнему symlink. Два теста подтверждают права и отказ перезаписи/symlink. Это не исключение файла из SAST и не автоматический allowlist новых результатов.

## F26 — проверка реального совместного выполнения

Добавлен обязательный Android-контракт настоящей ViewModel: stalled artist request → refreshCatalog завершается, пока artist busy остаётся true → pauseArtists отменяет только artist request и сохраняет каталог. Это дополняет JVM-контракты двух независимых владельцев. У friend previews отдельный `previewBlocked`: чтение каталога не ставит их в ожидание; изменение auth продолжает блокировать их. Admission подготовки очереди друзей сохраняет прежний более строгий write gate. Единое владение файловыми мутациями не менялось.

Java/Kotlin SARIF того же прогона содержит пять находок. Разобраны без изменения протоколов: AES-CBC — расшифровка провайдерского HLS AES-128, а не шифрование собственного хранилища; installer Intent — намеренная функция после `verifiedApk()` с проверками digest/package/version/signer и системным подтверждением; три missing-certificate-pinning фиксируют отсутствие SPKI pinning при действующей системной проверке TLS/hostname. Эти точные места также получили source-bound review с обоснованием. Pinning не объявлен реализованным и новые открытые возможности ради него не начаты. Наличие принятых ограничений доступно и в raw SARIF, и в config — clean не означает отсутствие всех находок.
