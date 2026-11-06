# Срез перед продолжением аудита: 2026-11-06

Это дополнение к историческому аудиту 2026-11-04. Первоначальные находки и доказательства не переписываются задним числом.

## Проверенные изменения из другого обсуждения

- Main: `59285ca35bd9f798bd68a4a4778209f3a1442ad5`.
- Рабочая ветка: `fix/audit-stage0-release-delivery-20261104`, база продолжения `66fe54b4e15a800007f2fad783425b22f08a2005`.
- PR [69](https://github.com/alcovaibe/vk-music-export/pull/69), [70](https://github.com/alcovaibe/vk-music-export/pull/70), [71](https://github.com/alcovaibe/vk-music-export/pull/71), [72](https://github.com/alcovaibe/vk-music-export/pull/72) слиты в main. PR [73](https://github.com/alcovaibe/vk-music-export/pull/73) слит в рабочую ветку и переносит эти изменения. Новые рабочие ветки для продолжения не создаются.
- Перенесены setup-gradle 6.4.1 с закреплённым SHA, AGP 9.5.1, application Guava 33.8.0-android, lockfile и verification metadata. Отдельный Guava 33.7.2-jre остаётся в classpath инструмента Lint; это не Android runtime configuration.
- В release-artifact-check сохранена проверка прежнего ZIP producer v4 и добавлен producer v7; оба проверяют восстановление точных байтов и структуры через download-artifact v8. Это синтетический транспортный сценарий, не установка APK.
- Содержимое перенесённых Gradle-файлов, lockfile, verification metadata и release-artifact-check совпадает с main. Сохранены последующие исправления рабочей ветки, включая F06.
- CI базы: [Android 42174077485, attempt 2](https://github.com/alcovaibe/vk-music-export/actions/runs/42174077485), [update channel 42174077400](https://github.com/alcovaibe/vk-music-export/actions/runs/42174077400) — completed/success. Это результаты базы, не предварительное подтверждение нового кода.

## F03 — подтверждён применёнными правилами

GitHub API вернул `main.protected=true`. Ruleset [26682452 — Public release main protection](https://github.com/alcovaibe/vk-music-export/rules/26682452) активен и применяется именно к `refs/heads/main`.

Фактические правила: обязательный PR, разрешение review threads, обязательные `build`, `interface`, `channel-delivery` от GitHub Actions (integration 15368), strict checks относительно актуальной базы; запрет удаления и non-fast-forward. Bypass actors отсутствуют, текущий пользователь не может обходить ruleset. Одобрение второго человека не требуется (`required_approving_review_count=0`), что соответствует работе одного владельца. Правила в этом продолжении не менялись. Прямой запрещённый push для проверки не выполнялся.

## Состояние до нового этапа F13

| Состояние реализации | Пункты |
|---|---|
| Реализованы — 12 | F01, F02, F03, F06, F07, F08, F09, F10, F11, F17, F18, F22 |
| Частично — 6 | F13, F23, F24, F25, F26, F27 |
| Ещё открыты — 12 | F04, F05, F12, F14, F15, F16, F19, F20, F21, F28, F29, F30 |

Это учёт реализации замечаний, не процент готовности полного клиента и не заявление о ручной приёмке каждого сценария. Половинные веса частичных пунктов не назначаются.

Сначала завершаются частичные пункты: F13 (источники/друзья), оставшееся разделение обязанностей F26, доступность F23, расширенные измерения и R8-проверки F24, SAST/inventory/provenance F25, долговечность evidence F27. Требования к реальному VK-аккаунту, физическим устройствам и фактическому выпуску фиксируются отдельно; зелёный synthetic CI их не подменяет. После этого очередь открытых возможностей пересматривается по актуальному коду и доступности провайдера.
