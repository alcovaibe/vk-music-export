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

Android 16+ (API 36). Диагностическая сборка для подтверждённого E6 внутри плейлиста на телефоне владельца. Это ошибка распознавания контейнера; запуск закладки уже подтверждён панелью. Исправление разметки пока не заявляется.

Изменения:
- Отдельная кнопка «Копировать диагностику разметки» в «Сбор из Chrome».
- Самодостаточная закладка VKM Layout: 4137 ASCII-символов, предел 4950; без сервера, eval, fetch/XHR.
- Файл vk-layout.json содержит только фиксированные названия признаков/тегов, счётчики и ограниченные цепочки предков. Произвольные значения атрибутов, текст, HTML, ID, URL, data-audio, cookies, хранилища и поля входа не сохраняются.
- Максимум 12 цепочек по 8 элементов; счётчики ограничены 10000, JSON ограничен 100 КБ. Это наблюдение Light DOM, без просмотра iframe/shadow DOM, не доказательство состава или полноты плейлиста.
- Прежний сборщик 0.5.2 и импорт музыки сохранены. Диагностический файл не является файлом импорта музыки.
- Проверки генерации/URL, Chromium с запретом чтения текста/HTML/полей и реальным скачиванием на синтетической странице; нативное копирование и Android URI-поле. Это не проверка реального аккаунта.

На телефоне:
1. Установить APK поверх существующего приложения без удаления.
2. «Сбор из Chrome» → «Копировать диагностику разметки».
3. Создать отдельную закладку Chrome VKM Layout; полностью заменить её адрес скопированным кодом. В конце — VKM_LAYOUT_1.
4. Открыть плейлист, на котором появился E6. В подсказках адресной строки выбрать VKM Layout. После «VKM layout 1» нажать «Скачать диагностику».
5. Прикрепить файл vk-layout.json из загрузок Chrome в чат. Не импортировать его как музыку.

Пакет и постоянная release-подпись сохранены. Перед публикацией проверяется обновление поверх опубликованной 0.5.2 (1046), сохранность коллекции и журналов после перезапуска. Настройки безопасности ВК менять не требуется для этой диагностики. Причина отсутствия узнаваемого контейнера будет уточнена по файлу с телефона; E6 сам по себе не доказывает блокировку со стороны ВК.
Исходный коммит APK: `{source_sha}`.
Коммит релиза и проверки: `{release_commit}` (исходники приложения и Gradle совпадают).
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
