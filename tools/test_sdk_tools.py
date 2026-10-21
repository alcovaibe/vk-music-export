from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('pin-sdk-tools.sh')


class SdkToolsTest(unittest.TestCase):
    def prepare(self, root, revision='22.0'):
        tools = root / 'cmdline-tools'
        old = tools / 'latest'
        pinned = tools / '15859902'
        for directory, value in [(old, '12.0'), (pinned, revision)]:
            (directory / 'bin').mkdir(parents=True)
            (directory / 'source.properties').write_text(f'Pkg.Revision={value}\n')
            for name in ('sdkmanager', 'avdmanager'):
                binary = directory / 'bin' / name
                binary.write_text(f'#!/bin/sh\necho {value}\n')
                binary.chmod(0o755)
        return old, pinned

    def run_script(self, root):
        return subprocess.run(['bash', str(SCRIPT), str(root)], capture_output=True, text=True)

    def test_old_canonical_directory_is_preserved_and_replaced_with_pinned_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old, pinned = self.prepare(root)
            result = self.run_script(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(old.is_symlink())
            self.assertEqual(old.resolve(), pinned)
            self.assertIn('22.0', result.stdout)
            self.assertEqual((root / 'cmdline-tools/vkm-runner-original/source.properties').read_text(), 'Pkg.Revision=12.0\n')

    def test_repeated_setup_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.prepare(root)
            self.assertEqual(self.run_script(root).returncode, 0)
            self.assertEqual(self.run_script(root).returncode, 0)

    def test_wrong_pinned_version_does_not_touch_old_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old, _ = self.prepare(root, '16.0')
            self.assertNotEqual(self.run_script(root).returncode, 0)
            self.assertFalse(old.is_symlink())
            self.assertIn('12.0', (old / 'source.properties').read_text())

    def test_missing_pinned_avdmanager_does_not_touch_old_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old, pinned = self.prepare(root)
            (pinned / 'bin/avdmanager').unlink()
            self.assertNotEqual(self.run_script(root).returncode, 0)
            self.assertFalse(old.is_symlink())


if __name__ == '__main__':
    unittest.main()
