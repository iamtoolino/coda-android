#!/usr/bin/env python3
"""Fail closed if FFmpeg ever enables another codec or a restricted licence tier."""
import re
import sys
from pathlib import Path
native = Path(sys.argv[1])
headers = '\n'.join(p.read_text() for p in (native / 'config.h', native / 'config_components.h'))
enabled = set(re.findall(r'^#define CONFIG_(\w+)_DECODER 1$', headers, re.M))
if enabled != {'MP3', 'MP3FLOAT'}:
    raise SystemExit(f'Unexpected decoder inventory: {sorted(enabled)}')
for flag in ('GPL', 'NONFREE', 'VERSION3'):
    if not re.search(rf'^#define CONFIG_{flag} 0$', headers, re.M):
        raise SystemExit(f'Restricted licence flag: {flag}')
print('Configuration verified: LGPL 2.1, only MP3/MP3FLOAT decoders')
