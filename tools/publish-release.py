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

Android 16+ (API 36). Исправление подтверждённого обрезания закладки на Android и поддержка новой разметки VK Music. Сохранённый пользователем код 0.5.1 совпал с префиксом первых 5000 символов исходного адреса после URL-нормализации; это стандартный предел однострочного поля Android.

Изменения:
- Самодостаточный адрес 4857 ASCII-символов; жёсткий предел 4950 при генерации и копировании. Нет eval, сервера и загрузки стороннего JS.
- Компактный JSON v2 разворачивается и строго проверяется в нативном приложении. Прежний v1 поддерживается. Порядок, повторения и частичные результаты сохранены.
- Новые селекторы VK Music. Сбор ограничен выделенным списком; неоднозначная разметка не превращается в сбор всей страницы.
- Проверка платформенного URI-поля воспроизводит обрезание длинного текста и проверяет сохранность нового адреса. Браузерные тесты используют фактический JavaScript URL с CSP на синтетических страницах.

Проверка на телефоне:
1. Установить APK поверх текущей версии, не удаляя приложение.
2. «Сбор из Chrome» → «Скопировать закладку». Заменить весь адрес прежней VKM Export в Chrome. APK сам закладку не меняет. В конце должна остаться метка VKM_END_052.
3. На музыкальной странице выбрать VKM Export из подсказок адресной строки. Ожидается окно «VKM 0.5.2», после ОК — панель.
4. «Сбор» → дождаться остановки или «Стоп» → «JSON». X после остановки закрывает панель. E0 означает остановку прокрутки, а не гарантированную полноту; E6 — неизвестная разметка; E10 — нет надёжного пересечения виртуализированных порций.
5. Вернуться в приложение → «Импорт JSON», проверить числа и подтвердить. Начать с небольшого плейлиста и сверить порядок/повторы.

Пакет и постоянная подпись сохранены. Перед публикацией проверяется обновление поверх фактически опубликованной 0.5.1 (1044), сохранность коллекции и журналов после перезапуска. Авторизация в настоящем Chrome подтверждена владельцем; успешный реальный сбор и фактическая DOM-разметка на Samsung требуют проверки. Синтетические тесты не выдаются за реальный вход или сбор. WebView отсутствует. Пароли, коды, cookies, токены, HTML и аудиофайлы не собираются; диагностический журнал не содержит содержимого импорта.
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
