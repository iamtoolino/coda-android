#!/bin/sh

set -eu

if command -v adb >/dev/null 2>&1; then
    exec adb "$@"
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    exec "$ANDROID_HOME/platform-tools/adb" "$@"
fi

if [ -x /opt/homebrew/share/android-commandlinetools/platform-tools/adb ]; then
    exec /opt/homebrew/share/android-commandlinetools/platform-tools/adb "$@"
fi

echo "adb was not found; add Android SDK platform-tools to PATH or set ANDROID_HOME" >&2
exit 1
