"""Stateful failure/retry tests. GitHub is simulated; no remote writes occur."""
import base64
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from update_channel import channel_manifest, publish_channel, PACKAGE, CHANNEL_PATH, REPO


class UpdateChannelTest(unittest.TestCase):
    BASE, HEAD = 'a'*40, 'b'*40

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.apk = self.directory/'fixture.apk'; self.apk.write_bytes(b'checked APK fixture')
        self.output = self.directory/'output'
        self.info = {'applicationId': PACKAGE, 'versionName': '0.12.1', 'versionCode': 1433,
                     'minSdk': 29, 'tag': 'v0.12.1-build.1433', 'apk': 'VK-Music-Export-0.12.1-1433.apk'}
        import hashlib
        self.release = {'tag_name': self.info['tag'], 'draft': False, 'prerelease': False,
                        'assets': [{'name': self.info['apk'], 'state': 'uploaded',
                        'size': self.apk.stat().st_size, 'digest': 'sha256:'+hashlib.sha256(self.apk.read_bytes()).hexdigest()}]}
        self.manifest = channel_manifest(self.info, self.release, self.apk)
        self.old = copy.deepcopy(self.manifest)
        self.old['release'].update(versionName='0.12.0', versionCode=1343, tag='v0.12.0-build.1343', apk='VK-Music-Export-0.12.0-1343.apk')
        self.branch = 'release/update-channel-1433'
        self.head = None; self.prs = []; self.writes = []; self.dispatches = []
        self.values = {self.BASE: self.old, self.HEAD: self.manifest}
        self.files = [{'filename': CHANNEL_PATH, 'status': 'modified'}]
        self.fail_after = None
        for target, kwargs in [('update_channel.api', {'side_effect': self.api}),
                               ('update_channel.write_api', {'side_effect': self.write}),
                               ('update_channel.subprocess.run', {'side_effect': self.command}),
                               ('update_channel.verify_apk', {'return_value': {}})]:
            p = patch(target, **kwargs); p.start(); self.addCleanup(p.stop)
        p = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''}); p.start(); self.addCleanup(p.stop)

    def pr(self):
        return {'state':'open', 'number':17, 'html_url':'https://github.com/'+REPO+'/pull/17',
                'base':{'ref':'main'}, 'head':{'ref':self.branch, 'sha':self.head, 'repo':{'full_name':REPO}}}

    def api(self, path):
        prefix = 'repos/'+REPO+'/'
        self.assertTrue(path.startswith(prefix)); path = path[len(prefix):]
        if path == 'releases/latest': return copy.deepcopy(self.release)
        if path == 'git/ref/heads/main': return {'object':{'sha':self.BASE}}
        if path.startswith('git/matching-refs/'):
            return ([{'ref':'refs/heads/'+self.branch,'object':{'sha':self.head}}] if self.head else []) + [
                {'ref':'refs/heads/'+self.branch+'-other','object':{'sha':'c'*40}}]
        if path.startswith('contents/'):
            value = self.values[path.split('?ref=')[1]]
            return {'sha':'blob-'+path.split('?ref=')[1], 'content':base64.b64encode(json.dumps(value).encode()).decode()}
        if path.startswith('compare/'): return {'files':copy.deepcopy(self.files), 'status':'ahead', 'behind_by':0}
        if path.startswith('pulls?'): return copy.deepcopy(self.prs)
        if path == 'pulls/17/files?per_page=100': return copy.deepcopy(self.files)
        raise AssertionError('Unexpected API read: '+path)

    def failed(self, operation):
        if self.fail_after == operation:
            self.fail_after = None
            raise subprocess.CalledProcessError(1, operation)

    def write(self, path, method, payload):
        self.writes.append((method, copy.deepcopy(payload)))
        if method == 'POST':
            self.assertEqual(payload, {'ref':'refs/heads/'+self.branch, 'sha':self.BASE})
            self.assertIsNone(self.head); self.head = self.BASE
        elif method == 'PUT':
            self.assertEqual(payload['branch'], self.branch)
            self.assertEqual(payload['sha'], 'blob-'+self.head)
            self.values[self.HEAD] = json.loads(base64.b64decode(payload['content']))
            self.head = self.HEAD
        elif method == 'PATCH':
            self.assertNotIn('heads/main', path); self.assertFalse(payload['force']); self.head = self.BASE
        else: raise AssertionError(method)
        self.failed(method)
        return {}

    def command(self, command, **kwargs):
        if command[:3] == ['gh', 'pr', 'create']:
            self.assertEqual(command[command.index('--base')+1], 'main')
            self.assertEqual(command[command.index('--head')+1], self.branch)
            self.assertIn('pending delivery', Path(command[command.index('--body-file')+1]).read_text())
            self.assertFalse(self.prs); self.prs = [self.pr()]; self.failed('PR')
        elif command[:3] == ['gh', 'workflow', 'run']:
            self.assertIn('expected_sha='+self.HEAD, command)
            self.assertIn('channel=checks', command)
            self.dispatches.append(command); self.failed('dispatch')
        else: raise AssertionError(command)
        return subprocess.CompletedProcess(command, 0)

    def publish(self): return publish_channel(self.info, self.apk, self.output)
    def receipt(self): return json.loads((self.output/'channel-delivery.json').read_text())

    def test_new_channel_is_pending_review_not_delivered(self):
        self.assertEqual(self.publish()['state'], 'pending_review')
        self.assertEqual([m for m,p in self.writes], ['POST','PUT'])
        self.assertEqual(len(self.prs),1); self.assertEqual(len(self.dispatches),1)
        self.assertEqual(self.old['release']['versionCode'],1343)

    def test_retry_after_each_remote_success_then_connection_failure(self):
        for operation in ('POST','PUT','PR','dispatch'):
            with self.subTest(operation=operation):
                self.head = None; self.prs = []; self.writes = []; self.dispatches = []
                self.fail_after = operation
                with self.assertRaises(subprocess.CalledProcessError): self.publish()
                self.assertEqual(self.receipt()['state'],'failed')
                self.assertEqual(self.publish()['state'],'pending_review')
                self.assertEqual(sum(m=='POST' for m,p in self.writes),1)
                self.assertEqual(sum(m=='PUT' for m,p in self.writes),1)
                self.assertEqual(len(self.prs),1)

    def test_existing_pr_is_revalidated_and_rechecked_without_writes(self):
        self.head = self.HEAD; self.prs = [self.pr()]
        self.publish(); self.assertFalse(self.writes); self.assertEqual(len(self.dispatches),1)

    def test_closed_pr_blocks_before_branch_mutation(self):
        self.prs = [{'state':'closed'}]
        with self.assertRaisesRegex(ValueError,'closed'): self.publish()
        self.assertFalse(self.writes); self.assertFalse(self.dispatches)

    def test_unrelated_changes_and_changed_pr_identity_block_dispatch(self):
        self.head = self.HEAD
        for files in ([], self.files+[{'filename':'app/code.kt','status':'modified'}]):
            with self.subTest(files=files):
                with patch.object(self,'files',files), self.assertRaises(ValueError): self.publish()
        for key,value in (('ref','wrong'),('sha','f'*40),('repo',{'full_name':'another/repo'})):
            self.prs = [self.pr()]; self.prs[0]['head'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.publish()
        self.assertFalse(self.dispatches)

    def test_superseded_release_does_not_write(self):
        self.release['tag_name']='v0.12.2-build.1434'
        self.assertEqual(self.publish()['state'],'superseded'); self.assertFalse(self.writes)

    def test_committed_channel_still_requires_public_verification(self):
        self.values[self.BASE] = self.manifest
        self.assertEqual(self.publish()['state'],'committed_waiting_public_verification')
        self.assertFalse(self.writes); self.assertFalse(self.dispatches)

    def test_same_version_cannot_replace_bytes(self):
        self.values[self.BASE] = copy.deepcopy(self.manifest)
        self.values[self.BASE]['release']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'different channel metadata'): self.publish()
        self.assertFalse(self.writes)

    def test_bad_actual_signature_blocks_all_remote_writes(self):
        with patch('update_channel.verify_apk',side_effect=ValueError('signature')):
            with self.assertRaisesRegex(ValueError,'signature'): self.publish()
        self.assertFalse(self.writes)

    def test_invalid_assets_identity_and_types_never_generate_channel(self):
        for changes in ({'draft':True}, {'prerelease':True}, {'assets':[]}, {'assets':self.release['assets']*2}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                channel_manifest(self.info,dict(self.release,**changes),self.apk)
        for changes in ({'applicationId':'wrong'}, {'minSdk':0}, {'versionCode':True}, {'apk':'another.apk'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                channel_manifest(dict(self.info,**changes),self.release,self.apk)
        self.release['assets'][0]['digest']='sha256:'+'0'*64
        with self.assertRaises(ValueError): channel_manifest(self.info,self.release,self.apk)
