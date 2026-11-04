# Безопасность

## Что подтверждено исходниками

Доступ хранится AES/GCM/NoPadding с Android Keystore ключом и AAD package:format; IV проверяется 12 bytes, envelope ограничен, AtomicFile и CAS replace сохраняют прежний доступ при неудачной замене. Это не обещает аппаратное/StrongBox хранение на каждом устройстве: оно отдельно не подтверждено. Application allowBackup=false, исключения cloud/device transfer, приватные секреты в noBackupFilesDir. Пользовательские метаданные SQLite не зашифрованы отдельно; это app sandbox, не доказанная утечка токена.

API credentials передаются POST body фиксированным VK endpoints; media/image/update clients не наследуют API token/cookies. HTTPS, порт, userinfo, fragment, домен и bounded redirects проверяются отдельно. HLS child resources проходят online datasource guard; file/content/data URI не разрешаются как online resource. Принимается системный TLS trust, отключения проверки сертификатов не найдено в просмотренных путях. APK проверяется SHA-256, package/version/minSdk/debuggable и совпадением signer с установленным приложением до установки. Android дополнительно проверяет подпись обновления.

MusicPlaybackService экспортирован намеренно для MediaSession. Callback разрешает trusted/system или собственный package и удаляет команды подстановки media items; arbitrary external URI не должен попадать в player. MainActivity экспортирован как launcher, auth callback provider/универсального web deep link здесь нет. FileProvider отчётов и APK не exported и ограничен отдельными cache directories с временным read grant. Наличие exported media service само по себе не является уязвимостью.

MediaStore проверяет owner package и допустимый путь; publication использует pending, fsync, hash и prepared checkpoint. Edits существующих файлов имеют durable backup/journal; legacy import сохраняет оригиналы. Мутация audio.delete привязана к точному собственному owner/ID и не повторяется автоматически после неоднозначного ответа. Дубликаты не удаляются только по названию; ручная локальная замена catalogueVerified=false не разрешает удаление оригинала из VK.

Диагностика ограничена очередью/ротацией; основные call sites передают фиксированные коды, counts и поля схемы. Исключения не экспортируются целиком; toString секретных объектов редактирован. Отчёт экспорта намеренно содержит account ID, названия и пути; UI/PRIVACY это объясняют. Вызов Diagnostics.event сам по себе принимает произвольные строки, поэтому безопасность зависит от call-site discipline, а не универсального sanitizer. Реальные пользовательские JSON из прошлых чатов не читались и не переэкспортировались.

## Что исправлять и проверять

Приоритет — F01 (read-session race), F03 (серверная защита main), F04 (экспериментальный login/API и объём прав), F05 (точная реальная приёмка), F25 (границы supply chain), F27 (долговременное provenance). F22 — отказ при нормальной ротации токена, а не подтверждённая компрометация.

SSRF: открытие произвольного input URL не предусмотрено; ссылка песни разбирается в ID, API endpoint фиксирован, CDN hosts допускаются по точному домену/поддомену. DNS/IP-level private-range guard отдельно не реализован/не проверялся. Это возможность усиления модели угроз, **не доказанный SSRF exploit**. Certificate pinning отсутствует, но отсутствие pinning само по себе не уязвимость TLS. Не заменять системное доверие фиктивными «trust all» решениями.

Для destructive/file-edit путей требуется проверка внешнего изменения между проверкой и записью, включая изменение файла после прерванной операции и до recovery. Существующая стратегия rollback отличает проверенный after от before и восстанавливает backup; поведение при поздней ручной правке пользователя нужно отдельно зафиксировать. Ни потери пользовательского файла, ни универсальной защиты от root/debugger/скомпрометированной ОС этот аудит не установил.

Logout в коде — удаление локального envelope/Keystore alias; серверный отзыв и выход из Chrome не выполняются, это честно описано. Для нового UX назвать действие соответственно и дать понятный путь управления VK-сеансами. Автоматический remote revoke возможен только по подтверждённому поддерживаемому протоколу, его здесь не выдумывать.

Полный security acceptance: A→B request barriers; отказ/ротация/отзыв; tampered envelope; redirect chain на каждый HLS ресурс; неполный/слишком большой JSON; попытки подстановки MediaSession URI; modified/foreign MediaStore и recovery; tampered/downgrade/different-signer APK; secret scan release APK и log/export regression. Часть этих сценариев уже покрыта synthetic CI. Нового динамического pentest с реальным VK, proxy или APK здесь не выполнялось.
