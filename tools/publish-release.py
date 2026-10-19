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

Android 16+ (API 36). Обновление формата Chrome-закладки и диагностики запуска. На телефоне владельца короткая alert-закладка работает, полный сборщик 0.5.0 не показал панель. Причина конкретного сбоя пока не установлена.

Изменения:
- Компактный код в одну ASCII-строку без комментариев и переносов; проценты и решётки экранируются один раз. Многострочный JavaScript URL воспроизводимо ломается в desktop Chrome 155; это ещё не доказательство причины на Samsung.
- При запуске появляется «VKM Export 0.5.1: код запущен». После ОК создаётся панель. Ошибка инициализации показывает фиксированный INIT_код без текста/стека исключения или данных страницы.
- Браузерные тесты запускают фактический JavaScript URL, включая скачивание JSON и проверку приватности. Android-редактор закладок и реальный ВК ими не покрыты.

Проверка на телефоне:
1. Установить APK поверх 0.5.0, не удаляя приложение.
2. «Сбор из Chrome» → «Скопировать закладку». В Chrome заменить адрес прежней VKM Export новым кодом целиком. Обновление APK само закладку не меняет.
3. На странице музыки ВК выбрать VKM Export из подсказок адресной строки. Сообщить, появился ли alert версии 0.5.1, панель или INIT_код.
4. Если панель работает: «Начать сбор» → после остановки «Сохранить JSON» → вернуться в приложение → «Импорт JSON», проверить числа и подтвердить.

Сохраняются названия, исполнители, порядок и повторы доступного открытого списка; каждый плейлист запускается отдельно. Полнота не гарантируется. Пароли, коды, cookies, токены, HTML и аудиофайлы не собираются. Chrome-профиль остаётся в настоящем Chrome; WebView отсутствует. Нативный журнал не наблюдает выполнение страницы. Успешный вход подтверждён владельцем, успешный реальный сбор пока не подтверждён.

Пакет и постоянная release-подпись сохранены. Перед публикацией проверяются unit-тесты, lint, синтетические страницы в Chrome, Android UI/буфер/импорт/экспорт, обновление поверх фактически опубликованной 0.5.0 (1043), сохранность коллекции и журналов после перезапуска.
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
