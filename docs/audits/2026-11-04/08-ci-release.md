# GitHub Actions, тесты и релизная цепочка

Проверен run [41665666564](https://github.com/alcovaibe/vk-music-export/actions/runs/41665666564) для исходного SHA аудита и companion [41665666515](https://github.com/alcovaibe/vk-music-export/actions/runs/41665666515). Статусы и декодированные логи получены через GitHub API; извлечения с exact job IDs включены в appendix. Новые runs/перезапуски не запускались.

## Все workflows

| Workflow | Что делает | Фактическая оценка |
|---|---|---|
| android.yml | changes → build + interface-tests API29–37 → aggregate interface; условный release | Полные SHA Actions, read permissions по умолчанию, timeout/concurrency; compiler/lint/R8/SCA и реальные device fixtures. Все jobs текущего pinned run success. Основные недостающие проверки — F24/F25, ветка выпуска с pending acceptance — F05 |
| release-artifact-check.yml | v4 upload → v8 download, проверяет exact layout/bytes synthetic artifact | Честный transport smoke. Файл с расширением .apk — synthetic text, он не устанавливается и не доказывает release APK compatibility. Нельзя считать этот зелёный workflow проверкой Android-клиента |
| recover-release.yml | Взять signed candidate artifact по run, проверить origin/checks/hash/cert/build inputs; API36 upgrade; publish RC либо exact accepted stable | Обычный stable требует release_gate.py; старый Android source не подменяется новой сборкой. Writable permissions scoped job. Доступ/содержимое секретов не проверялись |
| archive-releases.yml | План/скрытие старых релизов и перенос APK артефактов в draft | Отдельная обслуживающая операция, не quality gate. Делает мутации при разрешённых trigger/mode; в аудите не запускалась. Доказательства digest до удаления исходного artifact предусмотрены |

`ci-change-scope.py` пропускает тяжёлый Android только при push main с **единственным M docs/updates/android.json**, без release marker. PR/manual dispatch и неизвестная история запускают всё. Это не общий docs-only skip, несмотря на оставшийся исторический комментарий workflow. JSON-only skip не валидирует сам manifest через отдельный Python job; при включённом PR-процессе его проверяют полные PR tests. При отсутствующей main protection (F03) нельзя полагаться только на этот процесс.

## Версии и подпись

| Источник | versionName | versionCode | minSdk / замечание |
|---|---:|---:|---|
| version.properties | 0.12.1 | 1305 | Номер для локальной разработки; source minSdk29 |
| Latest release / build-info из CI | 0.12.1 | 1433 | minSdk29 из APK; точный SHA совпадает с аудитом |
| docs/updates/android.json | 0.12.0 | 1343 | minSdk29; приложение получает именно этот stale manifest |

`APP_VERSION_CODE=1000+GITHUB_RUN_NUMBER` намеренно переопределяет локальный номер; само отличие 1305/1433 не ошибка подписанного релиза. Release build использует постоянный keystore из ANDROID_SIGNING, apksigner сверяет сертификат с закреплённым PEM и обоими baseline, номер растёт относительно previous. Приватный key находится в RUNNER_TEMP и удаляется always. Факт наличия актуальной резервной копии ключа, настройки доступа к secret и environment protection данным аудитом не подтверждены.

build-release.sh формирует build-info из фактического manifest, копирует APK/лицензии, SHA256SUMS; publish-release.py публикует APK и checksum после обязательного evidence set. Owner promotion перепроверяет signer и digest загруженного asset. Attestation/SLSA provenance не обнаружены в workflows; SHA и подпись APK уже дают важные, но другие гарантии. Полные evidence хранятся 30 дней, F27.

## Что фактически прошло

| Проверка | Результат и происхождение |
|---|---|
| Python tools tests локально | 85 tests, 2.603 s, OK; audit-evidence/python-tests.log |
| Shell syntax локально | bash -n: 7 файлов (включая gradlew) |
| XML/JSON локально | 16 XML well-formed (включая verification metadata); JSON parsed; не semantic Android validation |
| Android unit/Lint/R8 локально | НЕ выполнено: wrapper distribution download упал с Network is unreachable до Gradle configuration |
| JVM debug, lintDebug/lintRelease, assembleRelease/R8 в CI | BUILD SUCCESSFUL, job 126020268433; это не testReleaseUnitTest и не полная release instrumented suite |
| OSV в CI | 217 resolved dependencies, no findings в 18:43:14 МСК 03.11; повторный scan в release job тоже successful. Свежий локальный scan 04.11 не выполнялся |
| API29 | 144 distinct tests, no failures/skips; job 126020269086 |
| API30 | 144, no failures/skips; job 126020268664 |
| API31 | 144, no failures/skips; job 126020268689 |
| API32 | 144, no failures/skips; job 126020269006 |
| API33 | 144, no failures/skips; job 126020268711 |
| API34 | 144, no failures/skips; job 126020268884 |
| API35 | 144, no failures/skips; job 126020268790 |
| API36 | 144, no failures/skips; job 126020268906 |
| API37 | 144, no failures/skips; job 126020268787 |
| Signed release upgrade | Текущий stable и oldest supported APK, API36, job 126026866925; успешный CI |
| Host SQLite/JSON 100/4500/20000 | Выполнено; модель SQL/декодирования, не instrumented нагрузка |
| Macrobenchmark, Android heap/CPU/profiling | НЕ выполнялись, существующий pipeline их не содержит |
| Live VK, час/длительная OEM screen-off, privacy/region/subscription matrix | НЕ проверены; разрешённый аккаунт не использовался |
| TalkBack, крупный текст, реальная пользовательская upgrade-библиотека | Новая ручная приёмка НЕ выполнялась |

Во время аудита checkout после проверок остался чистым. Компиляцию не объявлять неуспешной: она **не стартовала локально**, а в CI выбранного SHA успешна. JDK host17 отличается от документированного JDK21/toolchain; это второе ограничение среды, не причина, доказанная данным download stacktrace.

## Что тесты означают и чего не означают

В источниках 264 unit test method declarations и 144 androidTest declarations (плюс helper file EmulatorWindow). `PublicDeviceAuthProbeTest` зависит от VKM_PUBLIC_BOOTSTRAP_PROBE=1; workflow не задаёт его и по умолчанию test пропускается через assumeTrue. Он даже при opt-in проверяет только anonymous unapproved bootstrap, не аккаунт и музыку. Точное количество passed JVM cases без исходного JUnit XML здесь не присваивается. Успех task подтверждён логом.

verify-instrumentation.py отвергает failures/errors/skipped, отсутствие XML, неполный runner exit и отсутствие обязательных классов/методов; minimum_tests=136, фактически текущий run исполнил 144 на каждой версии. Это хороший guard; при добавлении suite точный expected inventory полезнее одного минимального порога. Нельзя заявлять, что instrumented tests пропущены, потому что многие используют fake API: fake input и непройденный test — разные вещи.

PlaybackDeviceTest использует реальный ExoPlayer/декодер, местные файлы и synthetic HLS responses через interceptor. Тест background уводит Activity, но не доказывает переживание настоящего process death и длительной работы OEM. LargeAudioAndReuseDeviceTest содержит настоящий >100 MiB MP3 parser/publication — не заменять это ошибочным заявлением о лимите 100 MiB. MusicExportTest имеет 4449 synthetic positions — это не real VK library и не Android 20000-song performance.

На API29–33 compat-upgrade-check.sh обновляет две debug сборки одного source с повышенным code и проверяет marker сохранности приватных данных. Он прямо не выдаёт это за historical signed upgrade. Полный upgrade-check.sh на API36 использует реальные подписанные baseline APK, но seed коллекции/очереди/legacy audio синтетический и небольшой. Он проверяет schema v1/v2→3, оригинал, один импорт, public fixture и журналы; не реальный личный архив на десятки тысяч записей. Это ограничение объёма, а не фиктивность проверки install -r.

## Правила выпуска нового клиента

Для stable обязательны: точный source/build-info/APK SHA, полный CI, разрешённая provider capability matrix, manual acceptance и долговременное evidence. JSON канала — завершаемая часть доставки с отдельным review. Исключения владельца должны оставаться явными и не превращать pending в passed. Аудит не применял ruleset, не менял acceptance, не создавал PR, не публиковал и не отзывал релизы.
