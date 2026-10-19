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

Android 16+ (API 36). Обновления приложения из приватных GitHub Releases.

Изменения:
- Настройки → «Обновления приложения»: ручная проверка и автоматическая проверка при открытии, не чаще раза в сутки. Автоматические ошибки не открывают диалог; «Закрыть» откладывает эту версию, ручная проверка показывает её снова.
- Доступ через введённый на телефоне fine-grained GitHub token для alcovaibe/vk-music-export с Contents: Read-only. В APK нет встроенного токена. AES-GCM с ключом Android Keystore, файл в noBackup; удаление/замена доступа в интерфейсе. Текст токена не попадает в диагностику.
- Загрузка запускается нажатием, показывает прогресс, допускает отмену/повтор. Сохраняется при сворачивании и повороте через ViewModel. После завершения процесса загрузка начинается заново; фоновый сервис/расписание не добавлены.
- Токен передаётся только фиксированным API endpoints GitHub. При HTTPS-редиректе на разрешённый GitHub CDN авторизация не передаётся. Cookie-free клиент, ограниченные размеры, число редиректов и таймауты.
- Проверяются реальные metadata/digest опубликованных assets и build-info.json. Перед предложением установки и на каждом нажатии «Установить» проверяются SHA-256, размер, пакет, точные версия/minSdk, отсутствие debuggable и совпадение текущих сертификатов подписи.
- Установку подтверждает Android. При необходимости выдать разрешение этому приложению, вернуться и снова нажать «Установить». Отмена разрешения/установки сохраняет проверенный APK для повтора.

Первое подключение:
1. Один раз установить этот APK вручную поверх текущего приложения, без удаления.
2. Настройки → «Обновления приложения» → «Настроить доступ GitHub».
3. Создать fine-grained token: Resource owner alcovaibe, Only select repositories → vk-music-export, Repository permissions → Contents: Read-only. Выбрать срок действия. Ввести токен только в приложении.
4. «Сохранить и проверить». Если более новой совместимой версии нет, это обычный результат. Следующий опубликованный релиз появится при проверке; «Скачать обновление» → «Установить».

Нативные проверки и тестовые HTTP/JSON ответы не доказывают успешную авторизованную загрузку на телефоне: её нужно проверить с токеном владельца при следующем обновлении. Токены с телефона в CI не используются. Пакет и постоянная release-подпись сохранены; обновление проверяется поверх опубликованной 0.5.3 (1047) с сохранностью коллекции и журналов.

Диагностика E6 сохранена: «Сбор из Chrome» → «Копировать диагностику разметки» → отдельная закладка VKM Layout → проблемный плейлист → «Скачать диагностику» → прислать vk-layout.json в чат. Этот выпуск не заявляет исправление распознавания плейлиста. WebView отсутствует; вход остаётся в Chrome.
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
