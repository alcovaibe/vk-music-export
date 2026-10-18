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

Android 16+ (API 36). Уточнение диагностики после реального лога и записи экрана. Причина пустой страницы VK ID пока не доказана.

Добавлено:
- «Ещё логи → Профиль входа»: повтор входа со штатным WebView или Chrome-совместимым профилем. Выбор создаёт новый WebView до начала навигации, cookies и коллекция не очищаются. По умолчанию сохранён Chrome-совместимый профиль.
- «Ещё логи → Отметить белый экран»: метка момента проблемы и дополнительная проверка состояния страницы.
- Проверка готовности визуального состояния, событий рисования, размеров, видимости и числовых/булевых признаков DOM. HTML, текст страницы и значения полей не сохраняются.
- Стабильные псевдонимы ресурсов внутри запуска для сопоставления запросов, HTTP/сетевых ошибок и источников консоли. Параметры URL и секреты исключены; ключ псевдонимов не сохраняется.
- Категории CORS, CSP, Permissions Policy, cookie policy, загрузки модулей и известные сетевые коды. Произвольный текст консоли остаётся исключённым.
- Повторяющиеся UA/Client Hints вынесены в отдельные записи идентичности, чтобы уменьшить JSON без обрезания событий.
- Исправлено присоединение заново созданного WebView в Compose при смене профиля или завершении рендерера.

Как проверить: установить поверх 0.3.3 → открыть ВК → «Ещё логи → Профиль входа → Открыть: Штатный WebView» → повторить вход при прежнем VPN. Если страница снова пустая, выбрать «Отметить белый экран», подождать 10–15 секунд и скопировать лог либо отправить JSON-файл. Chrome-совместимый вариант доступен там же для сравнения.

Постоянная release-подпись. Удалять приложение не нужно. Проверка обновления использует настоящий опубликованный APK 0.3.3 (1034), а не пересобранный макет.

Автоматические проверки: unit-тесты очистки, сопоставления ресурсов, ротации и восстановления; тесты парсера; lint; интерфейс и копирование/экспорт на эмуляторе Android 16; сравнение профилей и повторное присоединение WebView на синтетических страницах; сохранение тестовой коллекции и журналов после обновления/перезапуска.
Успешность реального входа на Samsung/Android 17 этими проверками не подтверждается.

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
