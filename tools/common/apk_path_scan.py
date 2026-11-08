#!/usr/bin/env python3
"""Fail if an APK/AAR (or any ZIP) contains the host's home path in any entry.

Preview and release check: a native library once embedded the build host's configure line, so every
file of the archive is searched, binary entries and nested ZIP/JAR entries included, for
  * the current user's home directory ($HOME, as the OS reports it), and
  * the macOS user-folder prefix `/Users/` in general (any user, any host).
Entries are read with zipfile, so stored and deflated ones are both covered. The output lists the entry name
(nested entries as `outer!inner`) and the kind of match only, never the matched bytes, the home path or the
user name. Exit status: 0 clean, 1 findings, 2 usage/read error.

Usage: python3 tools/common/apk_path_scan.py <file.apk|file.aar|file.zip> [...]
"""
import io
import os
import sys
import zipfile

ZIP_SUFFIXES = (".zip", ".jar", ".aar", ".apk", ".apex")
GENERIC = b"/Users/"
MAX_DEPTH = 4


def needles(home=None):
    """[(kind, bytes)]: the home path (if it is a real directory path) and the generic /Users/ prefix."""
    home = os.path.expanduser("~") if home is None else home
    found = []
    if len(home.rstrip("/")) > 1:
        found.append(("home", home.rstrip("/").encode("utf-8", "surrogateescape")))
    found.append(("users-prefix", GENERIC))
    return found


def scan_zip(data, label, pats, depth=0):
    """Return [(entry label, kind)] for every entry of the ZIP in `data` that contains a needle."""
    hits = []
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = f"{label}!{info.filename}" if label else info.filename
            content = zf.read(info)
            for kind, pat in pats:
                if pat in content or pat in info.filename.encode("utf-8", "replace"):
                    hits.append((name, kind))
            if depth < MAX_DEPTH and info.filename.lower().endswith(ZIP_SUFFIXES) and content[:2] == b"PK":
                try:
                    hits.extend(scan_zip(content, name, pats, depth + 1))
                except zipfile.BadZipFile:
                    pass
    return hits


def main(argv):
    if not argv:
        print(__doc__.split("\n\n")[0], file=sys.stderr)
        return 2
    pats = needles()
    total = 0
    for path in argv:
        try:
            with open(path, "rb") as fh:
                hits = scan_zip(fh.read(), "", pats)
        except (OSError, zipfile.BadZipFile) as exc:
            print(f"apk_path_scan: cannot read {os.path.basename(path)}: {exc.__class__.__name__}", file=sys.stderr)
            return 2
        for name, kind in hits:
            print(f"{os.path.basename(path)}: {name}: {kind}")
        total += len(hits)
    if total:
        print(f"apk_path_scan: {total} finding(s)", file=sys.stderr)
        return 1
    print("apk_path_scan: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
