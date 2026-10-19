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

Android 16+ (API 36). Сбор метаданных открытого музыкального списка в настоящем Chrome через пользовательскую закладку и импорт JSON в приложение. WebView не возвращается. Успешный вход в Chrome подтверждён владельцем на Samsung/Android 17 в 0.4.0; новый сбор на реальном аккаунте ещё требует проверки.

Как пользоваться:
1. В приложении открыть «Сбор из Chrome» и скопировать код закладки.
2. В Chrome создать закладку VKM Export; заменить её адрес скопированным кодом целиком, включая javascript:.
3. На странице «Моя музыка» или отдельного плейлиста нажать адресную строку, набрать VKM Export и выбрать закладку из подсказок (не поиск Google).
4. В панели сборщика нажать «Начать сбор». После остановки — «Сохранить JSON».
5. Вернуться в приложение → «Импорт JSON» → выбрать скачанный файл. Проверить числа и подтвердить импорт/замену одного списка.
6. Повторить отдельно для каждого нужного плейлиста. Обнаруженные плейлисты добавляются в каталог без выдумывания их треков.

Сохраняются названия, исполнители, порядок и повторы. Известные позиции позволяют объединять виртуализированные порции; отсутствие позиций или пересечения отмечается как ограничение/частичный результат. Конец прокрутки и совпадение счётчика не гарантируют полноту. Изменившийся список, неизвестная разметка, ручная остановка и лимиты не превращаются в успешный полный сбор.

В JSON нет HTML, полей входа, cookies, токенов, audio URL, параметров URL или приватных ключей плейлистов. Сборщик не делает fetch/XHR и не читает хранилища. Он запускается пользователем в Chrome и читает DOM музыкальной страницы. Установка/запуск закладки, политика сайта и фактическая вёрстка проверяются владельцем на телефоне.

Импорт: максимум 10 МБ, строгая схема и ограничения строк/идентификаторов/глубины. До подтверждения файл коллекции не меняется. Замена касается только собранного списка; каталог не очищает прежние треки. Остальные источники сохраняются. Прерывание или неверный JSON не заменяют коллекцию. Имена файлов, содержимое импорта и названия треков в диагностический журнал не пишутся.

Постоянная подпись, пакет и данные сохранены. Проверка установки поверх опубликованной 0.4.0 (1038). Автоматические проверки включают синтетические DOM-страницы в настоящем Chromium, скачивание JSON, повторы и виртуализацию; unit-тесты импорта/экспорта и диагностики; Android file-picker/предпросмотр/отмена/подтверждение/ошибочный файл/перезапуск; lint и upgrade-check. Это не доказательство успешного сбора с реальной страницы ВК.

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
