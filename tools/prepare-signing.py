"""Restore the ONE permanent signing key from an Actions secret; never generate a key in CI."""
import base64
import json
import os
from pathlib import Path

raw = os.environ.get('ANDROID_SIGNING', '')
if not raw:
    raise SystemExit('Missing ANDROID_SIGNING: refusing to publish with a temporary key')
secret = json.loads(raw)
password = secret['password']
alias = secret['alias']
if not password or not alias or any(c in password + alias for c in '\r\n'):
    raise SystemExit('Invalid signing configuration')
# Mask decoded fields too: GitHub only automatically masks the original JSON secret.
for value in (password, alias, secret['keystoreBase64']):
    print('::add-mask::' + value.replace('%', '%25'))
key = Path(os.environ['RUNNER_TEMP']) / 'vk-music-export-release.p12'
key.write_bytes(base64.b64decode(secret['keystoreBase64'], validate=True))
key.chmod(0o600)
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write(f'RELEASE_KEYSTORE_PATH={key}\nRELEASE_STORE_PASSWORD={password}\nRELEASE_KEY_ALIAS={alias}\n')
print('Permanent signing key restored; private material stays outside the workspace.')
