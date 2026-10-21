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

Android 16+ (API 36). Новые файлы сохраняются как «Исполнитель — Песня.mp3» или .m4a, без порядкового номера впереди и без ID в квадратных скобках. Папки плейлистов и альбомов получают обычные названия: например, «Солнечный день» вместо «Солнечный день [607277757_535]».

Служебные ID и позиции остаются только во внутреннем учёте. Совпадающие имена файлов и одинаковые названия разных подборок разделяются коротким суффиксом (2), (3)…; существующие файлы не перезаписываются. Реальные цифры и скобки из названия композиции сохраняются. Соответствие папок хранится в SQLite и не теряется при перезапуске.

Уже скачанные файлы и папки автоматически не переименовываются. Прежние ключи задач, сохранённые URI, контрольные точки и очередь совместимы; ранее скачанное распознаётся по прежнему ключу и проверяется по размеру/SHA-256. Незавершённые задачи после обновления используют новые имена. Схема очереди обновляется с v1 на v2 добавлением таблицы папок, без замены задач или индекса файлов.

Авторизация Chrome, доступ ВК, AudioTransfer/Media3, пауза и продолжение очереди, диагностический JSON и обновлятор сохранены. Проверки используют синтетические ответы и локальные MP3/AAC, а не реальную библиотеку владельца.

Проверки: JVM/lint/build, Android 16 MediaStore с одинаковыми именами и повторениями, разные подборки с одинаковым названием, повторное открытие БД и переиспользование URI без сети, миграция очереди v1 с уже опубликованным файлом. Подписанное обновление проверяется поверх опубликованной 0.9.1 (1070), с прежним пакетом и release-сертификатом, сохранением коллекции, публичной музыки и приостановленной очереди.

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
