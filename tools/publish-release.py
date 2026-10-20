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

Android 16+ (API 36). Настройки и их подстраницы теперь отдельные экраны с кнопкой «Назад».

Настройки → «Прямой доступ ВК · эксперимент» → «Подключить ВК через Chrome». Приложение создаёт одноразовую заявку веб-версии ВК; нажмите «Открыть подтверждение ВК», подтвердите её на странице ВК в Chrome и вернитесь в приложение. Результат получается автоматически, копирование токена не требуется.

Этот внутренний веб-протокол может быть отклонён ВК, выдать другой тип токена или доступ без музыки. Он может предоставить права шире музыкальных. Новый токен сохраняется только после успешных users.get/audio.get. При отказе сохранённый ранее доступ не заменяется. Обмен super_app_token не реализован. Реальная авторизация владельца пока не проверена.

Секреты и одноразовая заявка не попадают в диагностику; токен хранится в AES-GCM/AndroidKeyStore. Опрос приостановлен, пока открыт Chrome, и продолжается после возврата. Заявка действует максимум 5 минут. Закрытие страницы отменяет ожидание. Пароли и коды приложение не запрашивает.

Сохраняются первые 10 треков/подборок, частичный JSON/CSV и одиночная загрузка MP3/M4A из 0.7.0. Полная выгрузка и фоновая очередь ещё не реализованы. Проверки используют искусственные ответы и аудиосигналы; успешные синтетические тесты не означают работающий вход на аккаунте владельца.

Обновление проверяется поверх опубликованной 0.7.0 (1053), пакет и постоянная подпись сохранены. Диагностика и приватный обновлятор доступны в отдельных страницах настроек.

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
