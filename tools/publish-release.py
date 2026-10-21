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

Android 16+ (API 36). Убрана проверочная граница в 10 треков: библиотека и подборки читаются страницами по 100, скачивание — через постоянную очередь. Владелец подтвердил успешное скачивание и воспроизведение одного реального MP3 на предыдущей версии. Массовую загрузку своей библиотеки нужно проверить на телефоне после обновления.

Папка: Music/VK Music Export. Внутри — «Моя музыка», «Плейлисты» и «Альбомы». Создание через MediaStore, без доступа ко всему накопителю. Готовые файлы не ротируются по количеству или возрасту. Для одного трека в разных источниках создаются отдельные копии, с повторным использованием проверенного локального аудио. Альбомы из «Моей музыки» содержат только сохранённые там треки. Если тип подборки не указан явно, используется «Плейлисты»; открытую подборку можно сохранить в «Альбомы» вручную.

Настройки → «Прямой доступ ВК · эксперимент» → «Проверить музыку» → «Скачать всю мою музыку» или «Скачать музыку и все подборки». Подготовку списков дождитесь в приложении, затем загрузку можно свернуть. Разрешите уведомления для контроля прогресса. Есть пауза, продолжение, повтор пропусков. После перезапуска продолжение запускается кнопкой в приложении; незавершённый текущий трек начинается заново. Очистка очереди сохраняет готовые файлы.

Очередь и завершение каждого файла сохраняются в SQLite; неполные MediaStore-файлы скрыты IS_PENDING. После отмены/перезапуска опубликованный файл с контрольной точкой проверяется по SHA-256 и принимается без повторного скачивания. Сетевые сбои повторяются ограниченно; при устойчивой проблеме сети, доступа или накопителя очередь приостанавливается. Недоступные/неподдерживаемые треки и непрочитанные подборки видны как пропуски. Полнота не объявляется при неполном ответе ВК или изменении счётчика.

Работающая авторизация Chrome, защищённый экран кода, отдельные экраны настроек и приватный обновлятор сохранены. Секреты остаются в AES-GCM/AndroidKeyStore. Подписанные URL, cookies, коды, токены и тела ответов не сохраняются в очередь и журнал. Истёкший токен требует повторного входа. Лимит 100 МиБ и 10 минут на один трек остаётся; AAC сохраняется в M4A без перекодирования.

Проверки используют синтетические ответы и локальные аудиофикстуры: пагинация 4449 позиций, повторы, неполные ответы, Media3 → MediaStore, больше пяти готовых файлов, отмена до публикации, восстановление контрольной точки, копирование по папкам и системная фоновая задача. Эти проверки не заменяют реальную массовую загрузку с аккаунта владельца.

Обновление проверяется поверх опубликованной 0.8.4 (1064), имя пакета и постоянная release-подпись сохранены. Коллекция и диагностические запуски должны сохраниться без удаления приложения.

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
