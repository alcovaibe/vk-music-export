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

Android 16+ (API 36). Переход на настоящий Google Chrome. По выбору владельца это промежуточный этап: сначала вход, затем отдельная разработка нового сбора музыки.

Изменения:
- Встроенный WebView, дополнительные окна, подмена User-Agent/Client Hints и выбор профилей удалены.
- «Открыть ВК в Chrome» запускает обычную Activity пакета com.android.chrome с HTTPS-адресом vk.ru. Сеанс принадлежит Chrome. Если Chrome отсутствует или отключён, приложение сообщает об этом; другой браузер автоматически не подставляется.
- Приложение не считает запуск Chrome или возврат в приложение успешной авторизацией. Статус входа, cookies и содержимое Chrome недоступны приложению.
- Сбор и обновление музыки на этом этапе недоступны. Ранее сохранённая коллекция, поиск, экспорт TXT/CSV/JSON/ZIP и очистка коллекции сохранены.
- Выход из ВК выполняется в Chrome. Приложение не очищает сессию Chrome или прежнее хранилище WebView.
- Диагностика остаётся в нативных настройках: текущий/предыдущий запуск, копирование и JSON-файл. Записываются установленная версия Chrome и отправка Intent, но не навигация, DOM, запросы или результат входа внешнего браузера.

Как проверить: установить поверх 0.3.4 → «Открыть ВК в Chrome» → завершить вход на сайте ВК. Вернуться в приложение кнопкой «Назад» или через список последних приложений. Проверить ранее сохранённую коллекцию и её экспорт. Если Chrome не открылся, отправить JSON из настроек приложения. Проблемы самой страницы Chrome в этот лог не попадают.

Постоянная release-подпись. Пакет сохранён; удалять приложение не нужно. Проверка обновления использует опубликованный APK 0.3.4 (1036), сравнивает подписи, устанавливает обновление и проверяет сохранность тестовой коллекции и журналов после перезапуска.

Автоматические проверки: unit-тесты экспорта и диагностики; lint; нативная кнопка с перехватом Intent в тесте; отсутствие встроенного браузера в дереве Activity; ошибка недоступного Chrome; экспорт синтетической коллекции через UI; светлая/тёмная тема; копирование/экспорт журналов.
Перехват Intent проверяет только передачу управления. Реальный Chrome и успешный вход на Samsung/Android 17 проверяются владельцем; автоматические тесты этого не доказывают.

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
