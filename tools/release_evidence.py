"""Public, durable release evidence bound to exact APK bytes; never include keys/APKs."""
import hashlib
import json
from pathlib import Path
import os
import shutil
import zipfile

CORE=('SHA256SUMS.txt','build-info.json','upgrade-check.txt','release-certificate.pem','sbom.cdx.json','osv.json','LICENSE','NOTICE')
ALLOWED={'.sarif','.json','.xml','.txt','.ndjson','.png','.html','.css','.js','.csv'}


def bundle(info, apk, output=Path('release-output'), ci=Path('release-evidence-input'), upgrades=Path('upgrade-results')):
    files={}
    for name in CORE:
        path=output/name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Release evidence is incomplete: '+name)
        files['release/'+name]=path
    if (output/'toolchain.json').is_file():files['release/toolchain.json']=output/'toolchain.json'
    reviewed=Path('config/sast-reviewed.json')
    if reviewed.is_file():files['release/sast-reviewed.json']=reviewed
    # Account acceptance is never inferred from a CI job or an owner exception.
    acceptance=Path('docs/release-acceptance/current.json')
    acceptance_state='not_recorded'
    if acceptance.is_file():
        files['acceptance/'+acceptance.name]=acceptance
        from release_gate import validate_acceptance
        try:
            validate_acceptance(json.loads(acceptance.read_text()),info,apk)
            acceptance_state='passed_for_exact_apk'
        except (ValueError,KeyError,TypeError):
            acceptance_state='pending_or_other_apk'
    for prefix,root in [('ci',ci),('upgrade',upgrades)]:
        if root.exists():
            for path in sorted(root.rglob('*')):
                if path.is_symlink(): raise ValueError('Symlink in public evidence')
                if path.is_file() and path.suffix in ALLOWED:
                    files[prefix+'/'+path.relative_to(root).as_posix()]=path
    if os.environ.get('GITHUB_ACTIONS')=='true':
        if 'release/toolchain.json' not in files:raise ValueError('Missing durable toolchain inventory')
        origin=ci/'ci-origin.json'
        if not origin.is_file():raise ValueError('Missing durable CI origin')
        origin_data=json.loads(origin.read_text())
        if str(origin_data['verification_run'])!=str(info.get('verificationRun',os.environ.get('GITHUB_RUN_ID',''))) or origin_data['verification_commit']!=info.get('releaseCommit',info['commit']):
            raise ValueError('CI origin does not match release source/run')
        from channel_contract import validate_manifest
        checked_path=ci/'Update-channel-fixtures/channel-verification.json'
        if not checked_path.is_file():
            raise ValueError('Missing durable channel verification and API 29–37 evidence')
        checked=json.loads(checked_path.read_text())
        subject=validate_manifest(checked['manifest'])
        if (checked['state']!='candidate_verified' or checked['apk_verification']['sha256']!=subject['sha256']
                or checked['apk_verification']['versionCode']!=subject['versionCode']):
            raise ValueError('Channel signature evidence has another subject')
        for api in range(29,38):
            if not any(name.startswith(f'ci/Android-interface-API-{api}/') and name.endswith('.xml') for name in files):
                raise ValueError(f'Missing durable interface evidence API {api}')
            if not any(name==f'ci/Update-delivery-API-{api}/result.json' for name in files):
                raise ValueError(f'Missing durable updater evidence API {api}')
            proof=json.loads((ci/f'Update-delivery-API-{api}/result.json').read_text())
            if (proof['api']!=api or proof['to']!=subject['versionCode'] or proof['sha256']!=subject['sha256']
                    or proof['system_installer'] is not True or proof['private_marker_preserved'] is not True
                    or proof['process_restart'] is not True or proof['offline_restore'] is not True):
                raise ValueError(f'Inconsistent updater evidence API {api}')
        for api in (29,36,37):
            if f'ci/R8-playback-API-{api}/r8-evidence/playback-api-{api}.json' not in files:
                raise ValueError(f'Missing durable R8 evidence API {api}')
        for language in ('java-kotlin','python','actions'):
            if not any(name.startswith(f'ci/SAST-{language}/') and name.endswith('.sarif') for name in files):
                raise ValueError('Missing durable SAST evidence: '+language)
        if not any(name.startswith('ci/Dependency-evidence/') for name in files):raise ValueError('Missing resolved dependency evidence')
        if not any(name.startswith('ci/Android-reports/') and name.endswith('.xml') for name in files):
            raise ValueError('Missing durable unit/lint/build evidence')
    if len(files)>20000 or sum(p.stat().st_size for p in files.values())>500*1024*1024:
        raise ValueError('Evidence exceeds archive bounds')
    # Explicitly close file handles, including on Windows.
    def hash_file(path):
        with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()
    manifest={'schema':2 if os.environ.get('GITHUB_ACTIONS')=='true' else 1,'source_commit':info['commit'],'tag':info['tag'],'version_code':info['versionCode'],
        'apk':{'name':apk.name,'size':apk.stat().st_size,'sha256':hash_file(apk)},
        'build_run':str(info.get('buildRun','')),'verification_run':str(info.get('verificationRun',os.environ.get('GITHUB_RUN_ID',''))),
        'manual_acceptance':acceptance_state,
        'files':{name:{'size':p.stat().st_size,'sha256':hash_file(p)} for name,p in files.items()}}
    target=output/'release-evidence.zip';temporary=target.with_suffix('.zip.part')
    try:
        with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED) as archive:
            def entry(name):
                item=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
                item.compress_type=zipfile.ZIP_DEFLATED
                item.external_attr=0o100644 << 16
                return item
            archive.writestr(entry('evidence-manifest.json'),json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
            for name,path in sorted(files.items()):
                with path.open('rb') as source,archive.open(entry(name),'w') as target_file:
                    shutil.copyfileobj(source,target_file)
        temporary.replace(target)
    finally: temporary.unlink(missing_ok=True)
    return target
