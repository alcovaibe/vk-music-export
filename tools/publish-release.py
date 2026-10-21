"""Publish once, after checks. A rerun never silently replaces an existing release APK."""
import json
import os
import re
import subprocess
from pathlib import Path

info = json.loads(Path('release-output/build-info.json').read_text())
tag = info['tag']
source_sha = os.environ.get('RELEASE_SOURCE_SHA', os.environ['GITHUB_SHA'])
assert re.fullmatch(r'[0-9a-f]{40}', source_sha) and info['commit'] == source_sha
release_commit = os.environ.get('RELEASE_TARGET_SHA', source_sha)
assert re.fullmatch(r'[0-9a-f]{40}', release_commit)
assert info.get('releaseCommit', source_sha) == release_commit
assert Path('release-output/upgrade-check.txt').read_text().startswith('PASS:')
existing = subprocess.run(['gh', 'release', 'view', tag, '--json', 'targetCommitish,assets'], capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    assert release['targetCommitish'] == release_commit, 'Tag points to another source revision'
    assert info['apk'] in [a['name'] for a in release['assets']], 'Existing release is incomplete; inspect it manually'
    print(f'Release {tag} already exists; assets were not replaced.')
else:
    notes = Path(os.environ['RUNNER_TEMP']) / 'release-notes.md'
    notes.write_text(f'''VK Music Export {info['versionName']} · сборка {info['versionCode']}

Android 16+ (API 36). Удалены обычное открытие страницы ВК, закладки и сбор из Chrome, импорт музыкального JSON, вкладка TXT/CSV/JSON/ZIP-экспорта и раздел старых файлов. На главном экране «Открыть мою музыку» сразу открывает нативный музыкальный раздел. Нижняя панель с единственной оставшейся вкладкой убрана.

Рабочее «Подключить ВК через Chrome», защищённый экран кода и музыкальная проверка сохранены. Обычный запуск сайта отделён от Chrome Custom Tabs авторизации. Метаданные установленного Chrome по-прежнему записываются в диагностику.

Журнал нативных операций вынесен из старого аудиоархива в NativeOperationJournal. Сохранение страницы в коллекцию и подтверждение замены списка используют отдельный CollectionMerge без парсера браузерного импорта. Схема сохранённой коллекции прежняя, другие списки не удаляются. Диагностический JSON, копирование лога, отправка файла и обновлятор сохранены.

При первом запуске 0.9.1 удаляются только прежний внутренний каталог files/native-audio, его индекс и старый cache/native-audio-transfer. Очистка выполняется вне главного потока, не следует по символическим ссылкам, отмечает завершение и допускает повтор после сбоя. Ошибка этой очистки не блокирует авторизацию. Новые файлы Music/VK Music Export, база очереди, зашифрованные доступы, коллекция и диагностические журналы не входят в область удаления.

Нативные API-запросы, пагинация, AudioTransfer, Media3, публикация MediaStore, SQLite-очередь и её восстановление остаются прежними. Владелец подтвердил сохранение первого реального трека в новый каталог на 0.9.0. Полная массовая загрузка библиотеки остаётся проверкой на его телефоне.

Проверки: сохранение/отмена страницы коллекции, восстановление журнала операций, границы очистки и символические ссылки, сохранность публичного аудио и очереди, вход с подставной сетью, реальная обработка синтетических MP3/AAC на Android, MediaStore и UIDT. Удалены тесты и Node-зависимости только снятого браузерного сборщика и музыкального экспортёра. Это не объявляется новым реальным входом или массовым скачиванием из ВК.

Подписанное обновление проверяется поверх опубликованной 0.9.0 (1067), с прежним пакетом и постоянным release-сертификатом. На эмуляторе проверяются коллекция, диагностический перезапуск, точечное удаление старых файлов и сохранение публичного Music и приостановленной очереди.

Исходный коммит APK: `{source_sha}`.
Коммит релиза и проверки: `{release_commit}`.
Файлы: APK, SHA-256, публичный сертификат подписи, сведения о сборке и результат проверки обновления.
''')
    # An older recovery/rerun must never move the Latest link backwards.
    latest = subprocess.run(['gh', 'release', 'view', '--json', 'tagName'], capture_output=True, text=True)
    make_latest = True
    if latest.returncode == 0:
        latest_tag = json.loads(latest.stdout)['tagName']
        match = re.search(r'-build\.(\d+)$', latest_tag)
        assert match, 'Latest release has an unknown version code; inspect it before publishing'
        make_latest = info['versionCode'] > int(match[1])
    assets = [str(p) for p in sorted(Path('release-output').iterdir()) if p.is_file()]
    subprocess.run(['gh', 'release', 'create', tag, *assets, '--target', release_commit,
                    '--title', f"VK Music Export {info['versionName']} · {info['versionCode']}",
                    '--notes-file', str(notes), '--latest=' + str(make_latest).lower()], check=True)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
    summary.write(f"## Signed release\n\nhttps://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/tag/{tag}\n\n"
                  'Release upgrade and collection preservation: **passed**.\n')
