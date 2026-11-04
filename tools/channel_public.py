"""Anonymous, bounded public downloads and verification of the exact advertised APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

from apk_manifest import release_manifest
from channel_contract import MAX_APK, MAX_MANIFEST, PUBLIC_CHANNEL, REPO, file_hash, validate_manifest


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, apk):
        self.apk = apk
        self.hops = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.hops += 1
        url = urllib.parse.urlsplit(newurl)
        if (not self.apk or self.hops > 5 or url.scheme != 'https'
                or url.hostname not in {'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}
                or url.username is not None or url.password is not None or url.fragment
                or url.port not in (None, 443) or any(ord(c) <= 32 or ord(c) >= 127 or c == '\\' for c in newurl)):
            raise ValueError('Unsafe public download redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def read_public(url, limit, target=None, expected_size=None, expected_hash=None):
    apk = target is not None
    req = urllib.request.Request(url, headers={'Accept': 'application/octet-stream' if apk else 'application/json',
                                               'Cache-Control': 'no-cache', 'User-Agent': 'VK-Music-Export-Channel-Check/1'})
    opener = urllib.request.build_opener(SafeRedirect(apk))
    temporary = target.with_name(target.name+'.part') if apk else None
    count = 0
    digest = hashlib.sha256()
    chunks = []
    try:
        with opener.open(req, timeout=30) as response:
            if response.status != 200:
                raise ValueError('Public download did not return HTTP 200')
            length = response.headers.get('Content-Length')
            if length is not None and (int(length) > limit or expected_size is not None and int(length) != expected_size):
                raise ValueError('Public download length mismatch')
            if not apk:
                if (response.headers.get_content_type() not in {'application/json', 'text/plain'}
                        or response.headers.get_content_charset('utf-8').lower() not in {'utf-8', 'utf8'}):
                    raise ValueError('Unsupported public channel content type')
            output = temporary.open('wb') if apk else None
            try:
                while True:
                    chunk = response.read(65536 if apk else 4096)
                    if not chunk:
                        break
                    count += len(chunk)
                    if count > limit or expected_size is not None and count > expected_size:
                        raise ValueError('Public download exceeds its bound')
                    digest.update(chunk)
                    if output:
                        output.write(chunk)
                    else:
                        chunks.append(chunk)
            finally:
                if output:
                    output.close()
        if expected_size is not None and count != expected_size or expected_hash is not None and digest.hexdigest() != expected_hash:
            raise ValueError('Public download integrity mismatch')
        if apk:
            temporary.replace(target)
            return target
        return b''.join(chunks)
    except (OSError, urllib.error.HTTPError) as error:
        raise ValueError('Public download unavailable') from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def public_manifest():
    manifest = json.loads(read_public(PUBLIC_CHANNEL, MAX_MANIFEST).decode('utf-8', errors='strict'))
    validate_manifest(manifest)
    return manifest


def download_apk(manifest, directory):
    item = validate_manifest(manifest)
    directory.mkdir(parents=True, exist_ok=True)
    url = f"https://github.com/{REPO}/releases/download/{item['tag']}/{item['apk']}"
    return read_public(url, MAX_APK, directory/item['apk'], item['size'], item['sha256'])


def verify_apk(path, manifest, tools=None, certificate=Path('config/release-certificate.pem')):
    item = validate_manifest(manifest)
    if path.stat().st_size != item['size'] or file_hash(path) != item['sha256']:
        raise ValueError('APK bytes differ from the channel')
    tools = tools or Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    signing = subprocess.check_output([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    actual = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$', signing, re.MULTILINE)
    der = subprocess.check_output(['openssl', 'x509', '-in', str(certificate), '-outform', 'DER'])
    expected = hashlib.sha256(der).hexdigest()
    if actual != [expected]:
        raise ValueError('APK signing certificate differs from the pinned certificate')
    badging = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(path)], text=True)
    identity = release_manifest(badging)
    if any(identity[key] != item[key] for key in ['versionName', 'versionCode', 'minSdk']):
        raise ValueError('APK manifest differs from the public channel')
    return dict(identity, sha256=file_hash(path), size=path.stat().st_size, certificate_sha256=expected)
