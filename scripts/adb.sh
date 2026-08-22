#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if command -v adb >/dev/null 2>&1; then
    exec adb "$@"
fi

. "$ROOT_DIR/scripts/android-sdk.sh"

if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    exec "$ANDROID_HOME/platform-tools/adb" "$@"
fi

echo "adb was not found; add Android SDK platform-tools to PATH or set ANDROID_HOME" >&2
exit 1
