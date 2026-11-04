"""Public, pinned signed APK fixtures; no production signing key or release writes."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from channel_public import download_apk, verify_apk

output=Path('channel-evidence')
candidate=json.loads((output/'manifest.json').read_text())
baseline={'schemaVersion':1,'platform':'android','channel':'stable','applicationId':'app.alcovaibe.vkmusicexport',
          'release':{'versionName':'0.12.0','versionCode':1343,'minSdk':29,'tag':'v0.12.0-build.1343',
          'apk':'VK-Music-Export-0.12.0-1343.apk','size':5899726,
          'sha256':'8b939e1cba8549480263b9208ca160414ef0d149dc24d075d06cfddec731dc64'}}
if candidate['release']['versionCode']<=baseline['release']['versionCode']:
    raise ValueError('Updater integration requires a strictly newer candidate than its pinned baseline')
previous=download_apk(baseline,output)
verify_apk(previous,baseline)
shutil.copyfile(previous,output/'baseline.apk')
assets=Path('app/src/androidTest/assets/update-fixtures');assets.mkdir(parents=True,exist_ok=True)
shutil.copyfile(output/candidate['release']['apk'],assets/'candidate.apk')
shutil.copyfile(output/'manifest.json',assets/'manifest.json')
# Ephemeral negative-test key. Never use or export the app's release key.
import os
signer=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0/apksigner'
with tempfile.TemporaryDirectory() as directory:
    key=Path(directory)/'fixture.jks'
    subprocess.run(['keytool','-genkeypair','-keystore',str(key),'-storepass','fixture-only','-keypass','fixture-only',
                    '-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Negative updater test'],check=True,capture_output=True)
    subprocess.run([str(signer),'sign','--ks',str(key),'--ks-pass','pass:fixture-only','--out',str(assets/'wrong-signer.apk'),
                    str(assets/'candidate.apk')],check=True)
print('Prepared real signed public APKs and an ephemeral wrong-signer negative fixture.')
