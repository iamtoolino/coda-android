#!/usr/bin/env python3
"""Canonical ZIP ordering/timestamps, including classes.jar, for stable AAR hashes."""
import io
import sys
import zipfile
from pathlib import Path


def canonical(data):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name in sorted(source.namelist()):
            if name.endswith('/'):
                continue
            content = source.read(name)
            if name == 'classes.jar':
                content = canonical(content)
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            out.writestr(info, content)
    return result.getvalue()


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(canonical(Path(sys.argv[1]).read_bytes()))
