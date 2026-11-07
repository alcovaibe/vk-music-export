import base64
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import stat
import tempfile
import unittest
from unittest.mock import patch

class SigningSecurityTest(unittest.TestCase):
    def run_fixture(self,root):
        env={'ANDROID_SIGNING':json.dumps({'password':'fixture-password','alias':'fixture-alias','keystoreBase64':base64.b64encode(b'encrypted container fixture').decode()}),
             'RUNNER_TEMP':str(root),'GITHUB_ENV':str(root/'runner-env')}
        with patch.dict(os.environ,env,clear=True),contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(Path(__file__).with_name('prepare-signing.py')))
    def test_key_is_created_private_and_existing_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.run_fixture(root);key=root/'vk-music-export-release.p12'
            self.assertEqual(stat.S_IMODE(key.stat().st_mode),0o600)
            with self.assertRaises(FileExistsError):self.run_fixture(root)
            self.assertEqual(key.read_bytes(),b'encrypted container fixture')
    def test_preexisting_symlink_is_never_followed(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);other=root/'keep';other.write_text('unchanged')
            (root/'vk-music-export-release.p12').symlink_to(other)
            with self.assertRaises(FileExistsError):self.run_fixture(root)
            self.assertEqual(other.read_text(),'unchanged')
