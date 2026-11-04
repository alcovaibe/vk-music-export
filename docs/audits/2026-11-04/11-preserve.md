# Уже реализовано: сохранить при преобразовании

| Область | Сохраняемый контракт |
|---|---|
| Android | minSdk29/target36/compile37 и совместимые ветки системного транспорта; API29 pending query |
| Плеер | Один service-owned ExoPlayer, MediaSession, audio focus, noisy handling, media notification, mini/full-player, queue/shuffle/repeat/seek и collapse gesture |
| Безопасный media source | Exact-ID resolve на IO, opaque public timeline, HTTPS allowlists и проверка каждого HLS ресурса/redirect |
| Авторизация | Старый envelope не заменяется до успешной проверки нового; AES-GCM/Keystore, no-backup, CAS renewal, известный срок без выдуманного lifetime |
| Экспорт | Устойчивая SQLite queue, PENDING/RUNNING/DONE/FAILED, pause/retry, prepared checkpoints, сохранение всех ошибок и source issues |
| Идентичность | account+source+track+occurrence в stable task key; сортировка/переименование не создаёт новую сущность |
| Файлы | MediaStore pending/fsync/hash/owner, final provider name, контент URI, shared physical file на несколько memberships |
| Аудио | Реальный MP3/AAC parser/remux, AAC→M4A без lossy transcoding, Unicode metadata/subtitle, ограниченный finite HLS subset, duration completeness против preview |
| Обложки/имена | APIC/covr, сохранение audio fingerprint/decoder tables, durable backup+recovery, подтверждённый ручной lookup без смены исходного audio ID |
| Подборки | Порядок/повторы в переносимом M3U8, incomplete marker, защита изменённого пользователем M3U8 и durable relocation |
| Друзья | viewer account отдельно от friend/source, read-only чужая библиотека, независимые ошибки и реальный пустой раздел |
| Каталоги | Partial/complete/stale, cursor/offset guards, unknown type не становится выдуманным альбомом, subscriptions не подменяются рекомендациями |
| Дубликаты | Exact fingerprint, metadata candidate filter, fresh verification перед отдельным consent, один mutation без auto retry, не удалять local copy |
| Восстановление | Old archive originals/index сохранены, уникальное byte-identity match, explicit manual local replacement с catalogueVerified=false |
| Обновления | Постоянная подпись/applicationId, exact SHA/manifest/signer check, no downgrade, installer user action; исправить доставку manifest без ослабления проверки |
| CI | Pinned Actions/dependencies/checksums, strict verification, fail-closed OSV, реальные 144 device tests/API, signed upgrade fixtures и honest pending acceptance |
| Документы/диагностика | GPL/NOTICE и offline notices, ограниченные redacted logs, отдельные current/previous report и явный metadata export |

Не сохранять по инерции только exporter-UX скрытия скачанных источников, фрагментарные действия треков и process-only playback state: они изменяются ради нового назначения продукта. Обязательные старые схемы/ключи/форматы и migration paths нельзя удалять вместе с визуально устаревшими компонентами.
