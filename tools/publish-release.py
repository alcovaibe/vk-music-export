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

Android 16+ (API 36). Добавлен отсутствовавший обмен super_app_token после подтверждения ВК. Исправление по реальному скриншоту AUTH_UNSUPPORTED_TOKEN в 0.8.2.

При status=2 с super_app_token приложение завершает connect_code_auth, проверенный callback ВК (если указан) и web_token. Только полученный API-токен передаётся в users.get/audio.get. Промежуточный токен не объявляется музыкальным доступом. Появляется отдельная фаза обмена.

Подтверждение открывается в Custom Tab настоящего Google Chrome с его обычным профилем. Отключена отправка редиректов обработчику по умолчанию. Последующие явные внешние действия страницы и Android целиком не контролируются приложением. WebView и чтение cookies браузера не используются.

Настройки → «Прямой доступ ВК · эксперимент» → «Подключить ВК через Chrome» → «Открыть подтверждение ВК». Если VK ID покажет код подключения, вернитесь в приложение и введите его на появившемся экране. Пароль и SMS-коды вводятся только на стороне ВК. При ошибке скопируйте лог текущей попытки.

Экран шестизначного кода, повтор неверного кода и исправление BUSY сохранены. Коды находятся только в памяти заявки, не входят в saved state и журнал; поле очищается при отправке/закрытии/уходе в фон. Экран защищён FLAG_SECURE.

Временные cookies новой собственной HTTPS-сессии обмена хранятся только в ограниченном CookieJar в памяти, очищаются при любом исходе. Cookies Chrome не читаются. POST не следуют редиректам; callback допускает только точные HTTPS-узлы ВК и ограниченное число переходов. Его HTML не читается и не разбирается.

Новый доступ сохраняется в AES-GCM/AndroidKeyStore только после успешных users.get/audio.get; ранее сохранённый токен при отказе не заменяется. Секреты, одноразовые URL и тела ответов не сохраняются в журнал. Сохраняются отдельные экраны настроек, приватный обновлятор, частичная выборка первых 10 треков/подборок и одиночная загрузка.

Проверки Kotlin и Android используют синтетические ответы: последовательность обмена, очистка, запрет внешних переходов, истечение, отмена, экран кода и сохранение старого доступа при отказе музыки. Реальный обмен и чтение музыки владельца этой сборкой ещё не подтверждены. Внутренний веб-протокол остаётся экспериментальным; автоматическое обновление истёкшего веб-токена не реализовано.

Обновление проверяется поверх опубликованной 0.8.2 (1059), имя пакета и постоянная release-подпись сохранены. Установка APK проверяет сохранение коллекции и журнала.

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
