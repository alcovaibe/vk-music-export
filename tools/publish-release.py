"""Publish once, after checks. A rerun never silently replaces an existing release APK."""
import json
import os
import subprocess
from pathlib import Path

info = json.loads(Path('release-output/build-info.json').read_text())
tag = info['tag']
existing = subprocess.run(['gh', 'release', 'view', tag, '--json', 'targetCommitish,assets'], capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    assert release['targetCommitish'] == os.environ['GITHUB_SHA'], 'Tag points to another source revision'
    assert info['apk'] in [a['name'] for a in release['assets']], 'Existing release is incomplete; inspect it manually'
    print(f'Release {tag} already exists; assets were not replaced.')
else:
    notes = Path(os.environ['RUNNER_TEMP']) / 'release-notes.md'
    notes.write_text(f'''VK Music Export {info['versionName']} · сборка {info['versionCode']}

Android 16+ (API 36). Постоянная release-подпись, установка поверх предыдущих release-сборок.

Скачайте APK и подтвердите обновление в Android. Коллекция и данные приложения сохраняются; удалять приложение не нужно. ВК может отдельно попросить повторный вход.

**Переход с прежних debug APK 0.1/0.2:** их временная подпись отличается. Один раз сохраните ZIP, удалите старый debug APK и установите этот release. ZIP остаётся внешней копией: импорта ZIP в приложение пока нет. Последующие release APK устанавливаются поверх.

Проверено автоматически: unit-тесты, парсер, lint, интерфейс Android 16, установка release APK поверх release APK с меньшим versionCode и сохранение тестовой коллекции.
Реальный вход в ВК и Android 17 ещё требуют проверки на телефоне владельца.

Исходный коммит: `{os.environ['GITHUB_SHA']}`.
Файлы: APK, SHA-256, публичный сертификат подписи, сведения о сборке и результат проверки обновления.
''')
    assets = [str(p) for p in sorted(Path('release-output').iterdir()) if p.is_file()]
    subprocess.run(['gh', 'release', 'create', tag, *assets, '--target', os.environ['GITHUB_SHA'],
                    '--title', f"VK Music Export {info['versionName']} · {info['versionCode']}",
                    '--notes-file', str(notes), '--latest'], check=True)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
    summary.write(f"## Signed release\n\nhttps://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/tag/{tag}\n\n"
                  'Release upgrade and collection preservation: **passed**.\n')
