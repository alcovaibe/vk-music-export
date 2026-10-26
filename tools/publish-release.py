"""Publish once, after checks. A rerun never silently replaces an existing release APK."""
import json
import os
import re
import subprocess
from pathlib import Path
from update_channel import publish_channel

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

Кнопка обновлений перенесена в нижний правый угол главного экрана. Корзина у последних сохранённых файлов открывает нижнюю панель с подтверждением и красной рамкой кнопки «Удалить». Очищается только история; файлы, очередь и учёт скачанного сохраняются. Новые сохранения снова появляются в списке.

Android 16+ (API 36). Срок токена хранится по данным ВК; отсутствующий срок показывается как неизвестный. После нового входа через Chrome приложение сохраняет зашифрованный материал нативного обмена и пробует повторно выпускать музыкальный токен. Новый доступ заменяет старый только после проверки музыки и аккаунта. Если ВК отклоняет обмен, требуется повторный вход. Работа повторного выпуска на реальном аккаунте владельца требует проверки.

Обновления приложения открываются кнопкой со стрелками на главном экране и показываются в нижней панели, как в Bluetooth Disable. Пункт обновлений из настроек удалён. Проверка раз в сутки, скачивание по кнопке, проверка APK и подтверждение установки Android сохранены.

Проверки CI: JVM, lint, release/R8 и инструментальные тесты на API 36/37. Подписанное обновление проверяется на Android 16 поверх опубликованной 0.9.5 (1087), с сохранением прежних данных. Реальный вход в ВК и загрузка всей библиотеки владельца CI не подтверждаются.

Исходный коммит APK: `{source_sha}`.
Коммит релиза и проверки: `{release_commit}`.
Файлы релиза: APK, SHA-256 и публичный сертификат подписи. build-info.json и upgrade-check.txt сохраняются в артефактах Actions.
''')
    # An older recovery/rerun must never move the Latest link backwards.
    latest = subprocess.run(['gh', 'release', 'view', '--json', 'tagName'], capture_output=True, text=True)
    make_latest = True
    if latest.returncode == 0:
        latest_tag = json.loads(latest.stdout)['tagName']
        match = re.search(r'-build\.(\d+)$', latest_tag)
        assert match, 'Latest release has an unknown version code; inspect it before publishing'
        make_latest = info['versionCode'] > int(match[1])
    assets = [str(Path('release-output') / name) for name in (info['apk'], 'SHA256SUMS.txt', 'release-certificate.pem')]
    subprocess.run(['gh', 'release', 'create', tag, *assets, '--target', release_commit,
                    '--title', f"VK Music Export {info['versionName']} · {info['versionCode']}",
                    '--notes-file', str(notes), '--latest=' + str(make_latest).lower()], check=True)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
    summary.write(f"## Signed release\n\nhttps://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/tag/{tag}\n\n"
                  'Release upgrade, music home and existing data preservation: **passed**.\n')

# Update the discovery channel only after the release is publicly available and verified.
publish_channel(info, Path('release-output') / info['apk'])
