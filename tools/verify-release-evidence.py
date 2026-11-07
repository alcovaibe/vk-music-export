"""Verify a public evidence ZIP locally, without CI access or archive extraction.

Hashes bind content, not authorship. Supply a trusted APK/expected digest and source SHA
when checking a downloaded archive; ZIP-internal hashes alone are not an attestation.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

MAX_BYTES=500*1024*1024


def verify(archive, apk=None, expected_commit=None, expected_apk_sha256=None):
    with zipfile.ZipFile(archive) as z:
        entries=z.infolist();names=[i.filename for i in entries]
        if len(names)!=len(set(names)) or len(names)>20001:raise ValueError('Duplicate or excessive ZIP members')
        if sum(i.file_size for i in entries)>MAX_BYTES:raise ValueError('Evidence exceeds size bound')
        for i in entries:
            path=PurePosixPath(i.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in i.filename or i.is_dir():raise ValueError('Unsafe evidence path')
            mode=(i.external_attr>>16)&0o170000
            if mode not in (0,0o100000):raise ValueError('Non-regular evidence member')
        manifest=json.loads(z.read('evidence-manifest.json'))
        if manifest.get('schema') not in (1,2) or not re.fullmatch('[a-f0-9]{40}',manifest.get('source_commit','')):
            raise ValueError('Invalid evidence identity')
        files=manifest.get('files')
        if not isinstance(files,dict) or set(names)!=set(files)|{'evidence-manifest.json'}:
            raise ValueError('Evidence membership differs from manifest')
        for name,record in files.items():
            content=z.read(name)
            if len(content)!=record.get('size') or hashlib.sha256(content).hexdigest()!=record.get('sha256'):
                raise ValueError('Evidence content differs from manifest: '+name)
        for name in ('build-info.json','SHA256SUMS.txt','sbom.cdx.json','osv.json','upgrade-check.txt','release-certificate.pem','LICENSE','NOTICE'):
            if 'release/'+name not in files:raise ValueError('Missing core evidence: '+name)
        subject=manifest.get('apk',{})
        if (not re.fullmatch('[a-f0-9]{64}',subject.get('sha256','')) or type(subject.get('size')) is not int
                or subject['size']<=0 or PurePosixPath(subject.get('name','')).name!=subject.get('name')):
            raise ValueError('Invalid APK subject')
        info=json.loads(z.read('release/build-info.json'))
        if (info['commit']!=manifest['source_commit'] or info['versionCode']!=manifest['version_code']
                or info['tag']!=manifest['tag'] or info['apk']!=subject['name']):
            raise ValueError('build-info has another subject')
        sums=z.read('release/SHA256SUMS.txt').decode().split()
        if sums!=[subject['sha256'],subject['name']]:raise ValueError('APK checksum subject differs')
        if not z.read('release/upgrade-check.txt').startswith(b'PASS:'):raise ValueError('Upgrade not confirmed')
        sbom=json.loads(z.read('release/sbom.cdx.json'));osv=json.loads(z.read('release/osv.json'))
        if sbom.get('bomFormat')!='CycloneDX' or not sbom.get('components'):raise ValueError('Empty SBOM')
        if osv.get('findings')!=[]:raise ValueError('Missing or non-clean OSV result')
        if manifest['schema']==2:
            origin=json.loads(z.read('ci/ci-origin.json'))
            if str(origin['verification_run'])!=manifest['verification_run'] or origin['verification_commit']!=info.get('releaseCommit',info['commit']):
                raise ValueError('CI origin has another source/run')
        if expected_commit is not None and expected_commit!=manifest['source_commit']:raise ValueError('Unexpected source commit')
        if expected_apk_sha256 is not None and expected_apk_sha256!=subject['sha256']:raise ValueError('Unexpected APK digest')
        if apk is not None:
            with apk.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
            if apk.stat().st_size!=subject['size'] or digest!=subject['sha256']:raise ValueError('Downloaded APK differs')
        return manifest


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path);parser.add_argument('--apk',type=Path)
    parser.add_argument('--expected-commit');parser.add_argument('--expected-apk-sha256');args=parser.parse_args()
    result=verify(args.archive,args.apk,args.expected_commit,args.expected_apk_sha256)
    print('PASS: archive contents and APK binding verified; source '+result['source_commit'])
