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

Android 16+ (API 36). Исправлена ошибочная надпись «Сеть» при сбоях локального разбора и сохранения аудио. Добавлены этапы скачивания и точные безопасные категории отказа. Это диагностическое исправление: причина сообщения владельца о неудачном скачивании пока не установлена.

Полный реальный лог 0.8.3 подтвердил успешное подключение ВК через Chrome, 10 полученных треков при общем счётчике 4449 и 9 подборок. Событий скачивания в этом файле нет. Старый ACCESS/code=5 предшествовал повторному входу и не считается причиной загрузки.

Сетевые ошибки фиксируются на границе HTTPS. Media3 ParserException -> FORMAT, EOF при обработке файла -> INCOMPLETE, локальная запись -> STORAGE. Собственный общий срок -> TIMEOUT; родительская отмена остаётся отменой. Диагностические события содержат этап, счётчики запросов/переходов, HTTP-статус, категорию MIME, байты и фиксированную категорию ошибки. URL, тела, сообщения исключений, стеки, cookies, токены и коды не записываются.

Для проверки: Настройки → «Прямой доступ ВК · эксперимент» → «Проверить 10 треков» → у одной позиции «Скачать этот трек». Держите приложение открытым. Если появится ошибка, на экране останется этап и код DOWNLOAD_<этап>_<ошибка>. Затем Настройки → «Другие логи» → «Поделиться JSON-файлом». Отправьте файл после этой попытки.

Работающая авторизация Chrome с обычным профилем, защищённый экран кода, отдельные экраны настроек и приватный обновлятор сохранены. Секреты доступа остаются в AES-GCM/AndroidKeyStore; новый токен принимается после музыкальной проверки. Автоматическое обновление истёкшего веб-токена, полная пагинация библиотеки и массовая очередь пока не реализованы.

Тесты используют синтетические ответы и локальные аудиофикстуры: ошибки DNS/потока, Media3 ParserException после HTTP 200, локальная запись/EOF, собственный срок и внешняя отмена, очистка незавершённых файлов и отсутствие секретов в событиях. Эти проверки не считаются успешным скачиванием реальной библиотеки.

Обновление проверяется поверх опубликованной 0.8.3 (1062), имя пакета и постоянная release-подпись сохранены. Проверяется сохранение коллекции и журнала без удаления приложения.

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
