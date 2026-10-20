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

Android 16+ (API 36). Первый нативный прототип музыкального доступа.

Настройки → «Прямой доступ ВК · эксперимент». Проверка получает первые 10 треков и первые 10 подборок с уже выданным владельцу пользовательским токеном ВК. Автоматическое получение токена пока не реализовано. Вход в Chrome не передаёт приложению музыкальный доступ; если такого токена нет, подключение этим способом пока не готово.

Токен и исходный User-Agent вводятся только на телефоне и хранятся отдельно от GitHub в AES-GCM/AndroidKeyStore, вне backup. В логах нет токенов, access_key, URL потоков и тел ответов. API использует только фиксированные методы чтения; CDN не получает API-секретов.

Выборка сохраняется после подтверждения, всегда как частичная, с сохранением повторов, длительности и сведений об альбоме в JSON/CSV. Возможна одиночная загрузка прямого файла или ограниченного HLS VOD: MP3 остаётся MP3, AAC сохраняется как M4A без перекодирования. Ошибочный сегмент и неподдерживаемый формат не создают готовый файл. Есть отмена, проверка длительности/хэша, открытие и сохранение файла через системный диалог. При уходе из приложения операция останавливается; массовая фоновая очередь ещё не реализована.

Проверки используют синтетические ответы и сгенерированные аудиосигналы. Реальные авторизация, музыкальная библиотека и скачивание на аккаунте владельца ещё не проверены. Не отправляйте токен в чат. После реальной проверки можно скопировать обычный диагностический лог.

Обновление проверяется поверх опубликованной 0.6.0 (1050), пакет и постоянная подпись сохранены. Настроенный обновлятор GitHub используется как прежде. Диагностика E6 и сбор из Chrome доступны.

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
