"""Archive releases older than 0.10.0 without deleting their files or tags."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

REPO = 'alcovaibe/vk-music-export'
CUTOFF = (0, 10, 0)


def version(tag):
    match = re.fullmatch(r'v(\d+)\.(\d+)\.(\d+)-build\.\d+', tag)
    return tuple(map(int, match.groups())) if match else None


def selected(releases):
    return [r for r in releases if version(r['tag_name']) is not None and version(r['tag_name']) < CUTOFF]


def api(path, method='GET', data=None):
    command = ['gh', 'api', '--method', method, path]
    kwargs = {}
    if data is not None:
        command += ['--input', '-']
        kwargs['input'] = json.dumps(data)
    output = subprocess.check_output(command, text=True, **kwargs)
    return json.loads(output) if output.strip() else None


def pages(path, key=None):
    items = []
    for page in range(1, 1001):
        value = api(f'{path}?per_page=100&page={page}')
        batch = value[key] if key else value
        items.extend(batch)
        if len(batch) < 100:
            return items
    raise ValueError('Pagination did not finish')


def tag_object(tag):
    return api(f'repos/{REPO}/git/ref/tags/{tag}')['object']


def assets(release):
    return sorted((a['id'], a['name'], a['size'], a.get('digest')) for a in release['assets'])


def source_version(sha):
    for path, expression in (
        ('version.properties', r'(?m)^versionName=(\d+)\.(\d+)\.(\d+)\s*$'),
        ('app/build.gradle.kts', r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"'),
    ):
        try:
            result = api(f'repos/{REPO}/contents/{path}?ref={sha}')
        except subprocess.CalledProcessError:
            continue
        text = base64.b64decode(result['content']).decode()
        match = re.search(expression, text)
        if match:
            return tuple(map(int, match.groups()))
    raise ValueError(f'Cannot determine APK version for commit {sha}')


def download(path, file):
    for attempt in range(3):
        try:
            with file.open('wb') as output:
                subprocess.run(['gh', 'api', path, '-H', 'Accept: application/json'],
                               stdout=output, stderr=subprocess.PIPE, check=True)
            return
        except subprocess.CalledProcessError as error:
            message = (error.stderr or b'').decode(errors='replace')
            if attempt == 2 or not re.search(r'HTTP (502|503|504)\b', message):
                raise
            time.sleep(2 * (attempt + 1))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def upload(release_id, name, file):
    # Upload only to GitHub's official asset endpoint; never forward a credential on redirects.
    url = f'https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?name={urllib.parse.quote(name)}'
    request = urllib.request.Request(url, data=file.read_bytes(), method='POST', headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Content-Type': 'application/zip', 'Accept': 'application/vnd.github+json',
    })
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=120) as response:
        return json.load(response)


def main(apply=False):
    assert os.environ['GH_REPO'] == REPO
    out = Path('release-archive-audit'); out.mkdir(exist_ok=True)
    releases = pages(f'repos/{REPO}/releases')
    targets = selected(releases)
    keep = [r for r in releases if r not in targets]
    latest = api(f'repos/{REPO}/releases/latest')
    assert latest['id'] not in {r['id'] for r in targets}, 'Refusing to hide Latest'
    assert all(not r.get('immutable') for r in targets), 'An immutable release cannot become a draft'
    report = {'cutoff_exclusive': '0.10.0', 'apply': apply, 'before': releases,
              'latest_before': latest['tag_name'], 'hidden': [], 'artifacts': []}
    def save():
        (out/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    save()
    try:
        for release in targets:
            tag = release['tag_name']
            before_tag = tag_object(tag)
            if apply and not release['draft']:
                api(f'repos/{REPO}/releases/{release["id"]}', 'PATCH', {'tag_name': tag, 'draft': True})
            after = api(f'repos/{REPO}/releases/{release["id"]}')
            assert not apply or after['draft'], f'Release is still public: {tag}'
            assert after['tag_name'] == tag and after['target_commitish'] == release['target_commitish']
            assert assets(after) == assets(release), f'Release assets changed: {tag}'
            assert tag_object(tag) == before_tag, f'Tag changed: {tag}'
            report['hidden'].append({'id': release['id'], 'tag': tag, 'draft': after['draft']})
            save()
            print(('HIDDEN ' if apply else 'PLAN ') + tag, flush=True)
        assert targets, 'No matching releases'
        # Keep old APK artifacts privately as ZIP attachments to an archived release.
        archive_release = max(targets, key=lambda r: (version(r['tag_name']), r['id']))
        cache = {}
        for artifact in pages(f'repos/{REPO}/actions/artifacts', 'artifacts'):
            if artifact['expired']:
                continue
            # These workflow artifacts contain reports/screenshots/metadata only.
            if artifact['name'] in {'Android-reports', 'Dependency-evidence', 'Release-archive-audit',
                                    'Android-interface-API-34', 'Android-interface-API-35',
                                    'Android-interface-API-36', 'Android-interface-API-37'}:
                continue
            sha = artifact.get('workflow_run', {}).get('head_sha')
            assert sha and re.fullmatch(r'[0-9a-f]{40}', sha), 'Artifact has no source commit'
            if sha not in cache:
                cache[sha] = source_version(sha)
            if cache[sha] >= CUTOFF:
                continue
            with tempfile.TemporaryDirectory() as temporary:
                file = Path(temporary)/'artifact.zip'
                download(f'repos/{REPO}/actions/artifacts/{artifact["id"]}/zip', file)
                with zipfile.ZipFile(file) as archive:
                    has_apk = any(name.lower().endswith('.apk') for name in archive.namelist())
                if not has_apk:
                    continue
                digest = 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
                if artifact.get('digest'):
                    assert artifact['digest'] == digest, 'Artifact digest mismatch'
                record = {'id': artifact['id'], 'name': artifact['name'], 'sha': sha,
                          'version': '.'.join(map(str, cache[sha])), 'sha256': digest, 'archived': False}
                report['artifacts'].append(record); save()
                if apply:
                    name = f'actions-artifact-{artifact["id"]}.zip'
                    existing = api(f'repos/{REPO}/releases/{archive_release["id"]}')
                    assert existing['draft']
                    asset = next((a for a in existing['assets'] if a['name'] == name), None)
                    if asset is None:
                        asset = upload(archive_release['id'], name, file)
                    assert asset['state'] == 'uploaded' and asset['size'] == file.stat().st_size
                    assert asset['digest'] == digest, 'Private backup differs from the original artifact'
                    record.update(archived=True, release_id=archive_release['id'], asset_id=asset['id'])
                    save()
                    # The exact contents are now retained in a private draft before public access is removed.
                    api(f'repos/{REPO}/actions/artifacts/{artifact["id"]}', 'DELETE')
                    record['public_artifact_removed'] = True; save()
                print(('ARCHIVED APK artifact ' if apply else 'PLAN APK artifact ') + str(artifact['id']), flush=True)
        for release in keep:
            after = api(f'repos/{REPO}/releases/{release["id"]}')
            assert after['draft'] == release['draft'] and assets(after) == assets(release)
        assert api(f'repos/{REPO}/releases/latest')['id'] == latest['id']
        report['status'] = 'passed'; save()
    finally:
        save()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    main(parser.parse_args().apply)
