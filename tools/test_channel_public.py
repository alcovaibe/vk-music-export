import copy
import hashlib
import io
import json
from email.message import Message
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.request
from channel_contract import validate_manifest, MAX_MANIFEST
from channel_public import read_public, SafeRedirect, verify_apk, public_manifest, download_apk


class ChannelPublicTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.file=Path(self.tmp.name)/'candidate.apk'; self.data=b'actual fixture bytes'; self.file.write_bytes(self.data)
        self.manifest={'schemaVersion':1,'platform':'android','channel':'stable','applicationId':'app.alcovaibe.vkmusicexport',
            'release':{'versionName':'0.12.1','versionCode':1433,'minSdk':29,'tag':'v0.12.1-build.1433',
            'apk':'VK-Music-Export-0.12.1-1433.apk','size':len(self.data),'sha256':hashlib.sha256(self.data).hexdigest()}}

    def response(self, data, length=None, status=200, content_type='text/plain; charset=utf-8'):
        class Response(io.BytesIO): pass
        value=Response(data); value.status=status; value.headers=Message()
        value.headers['Content-Type']=content_type
        if length is not None: value.headers['Content-Length']=str(length)
        return value

    def test_exact_fields_and_json_types_match_android_contract(self):
        for target in ('root','release'):
            value=copy.deepcopy(self.manifest)
            (value if target=='root' else value['release'])['unexpected']='ignored by older checker'
            with self.assertRaises(ValueError): validate_manifest(value)
        for key in ('schemaVersion','release'):
            value=copy.deepcopy(self.manifest); value[key]=True
            with self.assertRaises(ValueError): validate_manifest(value)
        with self.assertRaises(ValueError): validate_manifest([])

    def test_live_check_reads_raw_public_url_and_rejects_oversize(self):
        with patch('channel_public.read_public',return_value=json.dumps(self.manifest).encode()) as reader:
            self.assertEqual(public_manifest(),self.manifest)
            self.assertEqual(reader.call_args.args[0], 'https://raw.githubusercontent.com/alcovaibe/vk-music-export/main/docs/updates/android.json')
            self.assertEqual(reader.call_args.args[1],MAX_MANIFEST)
        for data, length in ((b'x'*11,None),(b'x',11)):
            with patch('urllib.request.build_opener') as build:
                build.return_value.open.return_value=self.response(data,length)
                with self.assertRaises(ValueError): read_public('https://raw.githubusercontent.com/example',10)

    def test_partial_tampered_and_http_error_never_replace_good_file(self):
        expected=hashlib.sha256(self.data).hexdigest()
        for data,length,status in ((self.data[:-1],None,200),(b'z'*len(self.data),None,200),
                                    (self.data,len(self.data)+1,200),(self.data,None,503)):
            with self.subTest(status=status,length=length), patch('urllib.request.build_opener') as build:
                build.return_value.open.return_value=self.response(data,length,status)
                with self.assertRaises(ValueError): read_public('https://github.com/example',100,self.file,len(self.data),expected)
                self.assertEqual(self.file.read_bytes(),self.data)
                self.assertFalse(self.file.with_name(self.file.name+'.part').exists())

    def test_successful_download_is_bounded_and_atomic(self):
        self.file.unlink()
        with patch('urllib.request.build_opener') as build:
            build.return_value.open.return_value=self.response(self.data,len(self.data))
            read_public('https://github.com/example',100,self.file,len(self.data),hashlib.sha256(self.data).hexdigest())
            request=build.return_value.open.call_args.args[0]
            self.assertNotIn('Authorization',request.headers); self.assertNotIn('Cookie',request.headers)
            self.assertEqual(self.file.read_bytes(),self.data)

    def test_apk_download_retries_transport_errors_but_not_integrity_failures(self):
        with patch('channel_public.read_public',
                   side_effect=[ValueError('Public download unavailable'), self.file]) as reader, \
             patch('channel_public.time.sleep') as sleep:
            self.assertEqual(download_apk(self.manifest, Path(self.tmp.name)), self.file)
            self.assertEqual(reader.call_count, 2)
            sleep.assert_called_once_with(5)
        with patch('channel_public.read_public',
                   side_effect=ValueError('Public download integrity mismatch')) as reader, \
             patch('channel_public.time.sleep') as sleep:
            with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
                download_apk(self.manifest, Path(self.tmp.name))
            reader.assert_called_once()
            sleep.assert_not_called()

    def test_only_bounded_https_cdn_redirects_are_allowed(self):
        request=urllib.request.Request('https://github.com/example')
        good='https://release-assets.githubusercontent.com/asset?token=temporary'
        handler=SafeRedirect(True)
        for _ in range(5): self.assertIsNotNone(handler.redirect_request(request,None,302,'',{},good))
        with self.assertRaises(ValueError): handler.redirect_request(request,None,302,'',{},good)
        for url in ('http://release-assets.githubusercontent.com/x','https://evil.test/x',
                    'https://release-assets.githubusercontent.com.evil.test/x',
                    'https://user@release-assets.githubusercontent.com/x','https://release-assets.githubusercontent.com:444/x',
                    good+'#fragment',good+'\\bad',good+'\n'):
            with self.subTest(url=url),self.assertRaises(ValueError): SafeRedirect(True).redirect_request(request,None,302,'',{},url)
        with self.assertRaises(ValueError): SafeRedirect(False).redirect_request(request,None,302,'',{},good)

    def test_apk_signature_actual_manifest_and_hash_are_all_required(self):
        cert=b'fixture DER'; digest=hashlib.sha256(cert).hexdigest()
        badging="package: name='app.alcovaibe.vkmusicexport' versionCode='1433' versionName='0.12.1'\nsdkVersion:'29'\n"
        def commands(command,**kwargs):
            if command[0].endswith('apksigner'): return 'Signer #1 certificate SHA-256 digest: '+digest+'\n'
            if command[0]=='openssl': return cert
            if command[0].endswith('aapt'): return badging
            raise AssertionError(command)
        with patch('subprocess.check_output',side_effect=commands) as run:
            self.assertEqual(verify_apk(self.file,self.manifest,Path('/sdk'))['certificate_sha256'],digest)
            self.assertIn('verify',run.call_args_list[0].args[0])
            for changed in (badging.replace("'29'","'30'"),badging+'application-debuggable\n',badging.replace("'1433'","'1434'")):
                with patch('subprocess.check_output',side_effect=[f'Signer #1 certificate SHA-256 digest: {digest}\n',cert,changed]):
                    with self.assertRaises(ValueError): verify_apk(self.file,self.manifest,Path('/sdk'))
            with patch('subprocess.check_output',side_effect=['Signer #1 certificate SHA-256 digest: '+'0'*64+'\n',cert]):
                with self.assertRaisesRegex(ValueError,'certificate'): verify_apk(self.file,self.manifest,Path('/sdk'))
            self.file.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError,'bytes'): verify_apk(self.file,self.manifest,Path('/sdk'))
