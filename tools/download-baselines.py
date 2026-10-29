"""Download immutable published or archived APK baselines, never a rebuilt surrogate."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

out=Path('upgrade-input');out.mkdir(exist_ok=True)
release=json.loads(subprocess.check_output(['gh','release','view','--json','tagName,assets'],text=True))
tag=release['tagName']
version=re.fullmatch(r'v([0-9.]+)-build\.([0-9]+)',tag)
assert version, 'Latest is not a stable Android release'
names=[a['name'] for a in release['assets'] if re.fullmatch(r'VK-Music-Export-[0-9.]+-[0-9]+\.apk',a['name'])]
assert len(names)==1, 'Stable APK is missing or ambiguous'
assert names[0]==f'VK-Music-Export-{version[1]}-{version[2]}.apk', 'Stable asset does not match its release tag'
# The oldest baseline may be a private draft. Access it by immutable release ID
# with the existing release job token instead of requiring public downloads.
subprocess.run(['gh','release','download',tag,'--pattern',names[0],'--dir',str(out),'--clobber'],check=True)
(out/names[0]).replace(out/'previous.apk')
repo=os.environ.get('GH_REPO','alcovaibe/vk-music-export')
assert repo=='alcovaibe/vk-music-export'
oldest=json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/releases/423526572'],text=True))
assert oldest['id']==423526572 and oldest['tag_name']=='v0.9.5-build.1087', 'Oldest baseline identity changed'
oldest_name='VK-Music-Export-0.9.5-1087.apk'
assets=[a for a in oldest['assets'] if a['name']==oldest_name]
assert len(assets)==1 and assets[0]['id']==675560408, 'Oldest APK asset identity changed'
asset=assets[0]
with (out/'oldest.apk').open('wb') as output:
    subprocess.run(['gh','api',f'repos/{repo}/releases/assets/{asset["id"]}',
                    '-H','Accept: application/octet-stream'],stdout=output,check=True)
assert 'sha256:'+hashlib.sha256((out/'oldest.apk').read_bytes()).hexdigest()==asset['digest'], 'Oldest APK digest mismatch'
(out/'baselines.json').write_text(json.dumps({'stable':tag,'oldest':'v0.9.5-build.1087'},indent=2)+'\n')
