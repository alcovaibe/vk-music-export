"""Download immutable public APK baselines, never a locally rebuilt surrogate."""
import json
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
for release_tag,name,target in ((tag,names[0],'previous.apk'),
        ('v0.9.5-build.1087','VK-Music-Export-0.9.5-1087.apk','oldest.apk')):
    subprocess.run(['gh','release','download',release_tag,'--pattern',name,'--dir',str(out),'--clobber'],check=True)
    (out/name).replace(out/target)
(out/'baselines.json').write_text(json.dumps({'stable':tag,'oldest':'v0.9.5-build.1087'},indent=2)+'\n')
