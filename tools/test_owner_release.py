import datetime as dt
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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
            info=dict(versionName='0.11.0',applicationId='app.alcovaibe.vkmusicexport',minSdk=owner.configured_min_sdk(),versionCode=1243,
                      tag='v0.11.0-build.1243',commit='a'*40,apk='app.apk')
            asset=dict(name='app.apk',state='uploaded',size=7,digest='sha256:'+hashlib.sha256(b'fixture').hexdigest())
            release=dict(target_commitish='a'*40,assets=[asset,{'name':'SHA256SUMS.txt'}])
            owner.validate_assets(info,release,apk,{'tag_name':'v0.10.5-build.1236'})
            with self.assertRaises(AssertionError):
                owner.validate_assets(dict(info,minSdk=info['minSdk']+1),release,apk,{'tag_name':'v0.10.5-build.1236'})
            for tag in ['v0.11.0-build.1244','unknown']:
                with self.assertRaises(AssertionError): owner.validate_assets(info,release,apk,{'tag_name':tag})
            apk.write_bytes(b'changed')
            with self.assertRaises(AssertionError): owner.validate_assets(info,release,apk,{'tag_name':'v0.10.5-build.1236'})

    def test_pr50_requires_its_own_branch_marker_version_and_unexpired_request(self):
        request = dict(authorization='owner_requested_latest', manual_acceptance='pending', repository='alcovaibe/vk-music-export',
                       branch='codex/music-followups', pull_request=50, version_name='0.11.2', expires_at='2026-11-02T23:59:59Z')
        env = dict(GITHUB_REPOSITORY=request['repository'], GITHUB_REF='refs/heads/' + request['branch'], GITHUB_EVENT_NAME='push', GITHUB_SHA='b'*40)
        event = {'head_commit': {'message': 'Release 0.11.2 [publish-pr-50]'}}
        now = dt.datetime(2026,10,31,tzinfo=dt.timezone.utc)
        owner.validate_context(request,env,event,now)
        self.assertEqual(owner.request_path(env),Path('docs/release-requests/pr-50.json'))
        for key,value in [('branch','codex/music-library-redesign'),('version_name','0.11.0'),('pull_request',51)]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                owner.validate_context(dict(request,**{key:value}),env,event,now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,{'head_commit':{'message':'[publish-pr-48]'}},now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,event,now+dt.timedelta(days=4))
        with self.assertRaises(AssertionError): owner.request_path(dict(env,GITHUB_REF='refs/heads/unknown'))

    def test_pr50_assets_require_the_requested_new_version(self):
        with tempfile.TemporaryDirectory() as d:
            apk=Path(d)/'app.apk';apk.write_bytes(b'fixture')
            info=dict(versionName='0.11.2',applicationId='app.alcovaibe.vkmusicexport',minSdk=owner.configured_min_sdk(),versionCode=1255,
                      tag='v0.11.2-build.1255',commit='b'*40,apk='app.apk')
            asset=dict(name='app.apk',state='uploaded',size=7,digest='sha256:'+hashlib.sha256(b'fixture').hexdigest())
            release=dict(target_commitish='b'*40,assets=[asset,{'name':'SHA256SUMS.txt'}])
            owner.validate_assets(info,release,apk,{'tag_name':'v0.11.0-build.1244'},'0.11.2')
            with self.assertRaises(AssertionError): owner.validate_assets(info,release,apk,{'tag_name':'v0.11.0-build.1244'},'0.11.0')

    def test_main_release_needs_its_own_marker_version_and_owner_request(self):
        request = dict(authorization='owner_requested_latest', manual_acceptance='pending', repository='alcovaibe/vk-music-export',
                       branch='main', pull_request=55, version_name='0.11.6', expires_at='2026-11-02T23:59:59Z')
        env = dict(GITHUB_REPOSITORY=request['repository'], GITHUB_REF='refs/heads/main', GITHUB_EVENT_NAME='push', GITHUB_SHA='c'*40)
        event = {'head_commit': {'message': 'Release 0.11.6 [publish-main-0.11.6]'}}
        now = dt.datetime(2026,11,1,tzinfo=dt.timezone.utc)
        owner.validate_context(request,env,event,now)
        self.assertEqual(owner.request_path(env),Path('docs/release-requests/pr-55.json'))
        for key,value in [('branch','codex/artists-duplicates-about'),('version_name','0.11.2')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                owner.validate_context(dict(request,**{key:value}),env,event,now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,{'head_commit':{'message':'[publish-pr-52]'}},now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,{'head_commit':{'message':'[publish-main-0.11.3]'}},now)
        with self.assertRaises(AssertionError): owner.validate_context(dict(request,pull_request=52),env,event,now)
        with self.assertRaises(AssertionError): owner.validate_context(request,env,event,now+dt.timedelta(days=3))

    def test_branch_cleanup_deletes_only_exact_requested_nonmain_heads(self):
        request = dict(repository='alcovaibe/vk-music-export',branch='main',pull_request=53,delete_other_branches=True,
                       branches_to_delete={'codex/fix':'b'*40})
        refs = [{'ref':'refs/heads/main','object':{'sha':'a'*40}}, {'ref':'refs/heads/codex/fix','object':{'sha':'b'*40}}]
        with patch.object(owner,'api',side_effect=[{'default_branch':'main'},refs,None,refs[:1]]) as api:
            owner.delete_requested_branches(request)
            self.assertEqual(api.call_args_list[2].args,('repos/alcovaibe/vk-music-export/git/refs/heads/codex/fix','DELETE'))
        for changed in (refs[:1],refs[:1]+[{'ref':'refs/heads/codex/fix','object':{'sha':'c'*40}}]):
            with patch.object(owner,'api',side_effect=[{'default_branch':'main'},changed]) as api:
                with self.assertRaises(AssertionError): owner.delete_requested_branches(request)
                self.assertFalse(any(len(call.args)>1 and call.args[1]=='DELETE' for call in api.call_args_list))
        with patch.object(owner,'api') as api:
            with self.assertRaises(AssertionError): owner.delete_requested_branches(dict(request,branches_to_delete={'main':'a'*40}))
            self.assertFalse(any(len(call.args)>1 and call.args[1]=='DELETE' for call in api.call_args_list))
