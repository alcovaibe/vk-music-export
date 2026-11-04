"""The exact stable JSON contract consumed by UpdateProtocol.latest()."""
import hashlib
import re

REPO = 'alcovaibe/vk-music-export'
PACKAGE = 'app.alcovaibe.vkmusicexport'
CHANNEL_PATH = 'docs/updates/android.json'
PUBLIC_CHANNEL = f'https://raw.githubusercontent.com/{REPO}/main/{CHANNEL_PATH}'
MAX_APK = 100 * 1024 * 1024
MAX_MANIFEST = 16384
TOP_FIELDS = {'schemaVersion', 'platform', 'channel', 'applicationId', 'release'}
RELEASE_FIELDS = {'versionName', 'versionCode', 'minSdk', 'tag', 'apk', 'size', 'sha256'}


def release_code(tag):
    match = re.fullmatch(r'v[0-9]{1,6}(?:\.[0-9]{1,6}){1,3}-build\.([1-9][0-9]{0,9})', tag)
    if not match or not 0 < int(match[1]) <= 2_100_000_000:
        raise ValueError('Invalid release tag')
    return int(match[1])


def validate_manifest(manifest):
    if (type(manifest) is not dict or set(manifest) != TOP_FIELDS
            or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1
            or manifest['platform'] != 'android' or manifest['channel'] != 'stable'
            or manifest['applicationId'] != PACKAGE):
        raise ValueError('Unsupported stable channel schema/package/fields')
    item = manifest['release']
    if type(item) is not dict or set(item) != RELEASE_FIELDS:
        raise ValueError('Unexpected release fields')
    version = item['versionName']
    if not isinstance(version, str) or not re.fullmatch(r'[0-9]{1,6}(?:\.[0-9]{1,6}){1,3}', version):
        raise ValueError('Invalid versionName')
    for key, maximum in [('versionCode', 2_100_000_000), ('minSdk', 10000), ('size', MAX_APK)]:
        if type(item[key]) is not int or not 0 < item[key] <= maximum:
            raise ValueError(f'Invalid {key}')
    if (item['tag'] != f"v{version}-build.{item['versionCode']}"
            or item['apk'] != f"VK-Music-Export-{version}-{item['versionCode']}.apk"
            or not isinstance(item['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', item['sha256'])):
        raise ValueError('Invalid tag, APK name or SHA-256')
    return item


def file_hash(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            value.update(chunk)
    return value.hexdigest()
