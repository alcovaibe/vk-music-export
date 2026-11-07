"""Record declared Actions and observed build tools; no environment/credential dump."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib


def inventory(root, sdk, run=subprocess.check_output):
    actions={}
    for workflow in sorted((root/'.github/workflows').glob('*.yml')):
        for ref in re.findall(r'^\s*-?\s*uses:\s*[\'"]?([^\s\'"#]+)',workflow.read_text(),re.M):
            if ref.startswith('./'):continue
            match=re.fullmatch(r'([\w.-]+/[\w.-]+)(/[^@]+)?@([a-f0-9]{40})',ref)
            if not match:raise ValueError('Unpinned or unsupported Action: '+ref)
            repo,subpath,sha=match.groups();actions[ref]={'repo':repo,'commit':sha,'path':subpath or ''}
    if not actions:raise ValueError('Empty Actions inventory')
    props=(root/'gradle/wrapper/gradle-wrapper.properties').read_text()
    digest=re.search(r'^distributionSha256Sum=([a-f0-9]{64})$',props,re.M)
    url=re.search(r'^distributionUrl=(.+)$',props,re.M)
    if not digest or not url:raise ValueError('Unverified Gradle distribution')
    packages={}
    for key,path in [('build-tools','build-tools/36.0.0'),('platform','platforms/android-37.0'),
                     ('command-line-tools','cmdline-tools/latest'),('platform-tools','platform-tools')]:
        source=sdk/path/'source.properties'
        if not source.is_file():raise ValueError('Missing observed SDK package: '+key)
        packages[key]=dict(line.split('=',1) for line in source.read_text().splitlines() if '=' in line and not line.startswith('#'))
    return {'schema':1,'source_commit':run(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
        'actions':actions,'gradle':{'distribution':url[1].replace('\\:',':'),'sha256':digest[1]},
        'java':run(['java','-version'],stderr=subprocess.STDOUT,text=True).strip(),
        'sdk':packages,'declared_versions':tomllib.loads((root/'gradle/libs.versions.toml').read_text())['versions'],
        'coverage':{'maven_plugins':'resolved graph in build-tools.txt','actions':'pinned source commits',
            'gradle_jdk_sdk':'inventory only; not covered by Maven OSV queries',
            'runner_os_and_action_transitives':'outside this inventory; not claimed clean'}}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='dependency-reports/toolchain.json');args=parser.parse_args()
    result=inventory(Path('.'),Path(os.environ['ANDROID_HOME']))
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
