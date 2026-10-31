import datetime as dt
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('owner_release', Path(__file__).with_name('promote-owner-release.py'))
owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)


class OwnerReleaseTest(unittest.TestCase):
    def test_only_the_explicit_same_repository_pr_push_can_publish(self):
        request = dict(authorization='owner_requested_latest', manual_acceptance='pending', repository='alcovaibe/vk-music-export',
                       branch='codex/music-library-redesign', pull_request=48, version_name='0.11.0', expires_at='2026-11-01T00:00:00Z')
        env = dict(GITHUB_REPOSITORY=request['repository'], GITHUB_REF='refs/heads/' + request['branch'], GITHUB_EVENT_NAME='push', GITHUB_SHA='a'*40)
        event = {'head_commit': {'message': 'Publish [publish-pr-48]'}}
        now = dt.datetime(2026,10,31,tzinfo=dt.timezone.utc)
        owner.validate_context(request,env,event,now)
        for key,value in [('GITHUB_EVENT_NAME','pull_request'),('GITHUB_REF','refs/heads/main'),('GITHUB_REPOSITORY','other/repository')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                owner.validate_context(request,dict(env,**{key:value}),event,now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,{'head_commit':{'message':'Ordinary fix'}},now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,event,now+dt.timedelta(days=2))

    def test_publication_requires_exact_uploaded_bytes_and_never_rolls_latest_back(self):
        with tempfile.TemporaryDirectory() as d:
            apk=Path(d)/'app.apk';apk.write_bytes(b'fixture')
            info=dict(versionName='0.11.0',applicationId='app.alcovaibe.vkmusicexport',minSdk=36,versionCode=1243,
                      tag='v0.11.0-build.1243',commit='a'*40,apk='app.apk')
            asset=dict(name='app.apk',state='uploaded',size=7,digest='sha256:'+hashlib.sha256(b'fixture').hexdigest())
            release=dict(target_commitish='a'*40,assets=[asset,{'name':'SHA256SUMS.txt'}])
            owner.validate_assets(info,release,apk,{'tag_name':'v0.10.5-build.1236'})
            for tag in ['v0.11.0-build.1244','unknown']:
                with self.assertRaises(AssertionError): owner.validate_assets(info,release,apk,{'tag_name':tag})
            apk.write_bytes(b'changed')
            with self.assertRaises(AssertionError): owner.validate_assets(info,release,apk,{'tag_name':'v0.10.5-build.1236'})
