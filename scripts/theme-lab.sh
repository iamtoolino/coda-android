#!/bin/sh

set -eu

if [ "$#" -lt 1 ]; then
    echo "usage: $0 <emulator-serial> [--port <port>] [--no-open]" >&2
    exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "Python 3 is required to run the Coda theme lab" >&2
    exit 1
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec python3 "$ROOT_DIR/tools/theme_lab.py" "$@"
