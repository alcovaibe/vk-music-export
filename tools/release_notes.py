"""Version-specific release notes. Missing notes block publication."""
import re
from pathlib import Path


def notes_for(version, path=Path('CHANGELOG.md')):
    text = path.read_text()
    match = re.search(r'^## \[' + re.escape(version) + r'\][^\n]*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError(f'No CHANGELOG entry for {version}')
    return match[1].strip()
