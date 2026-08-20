#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

"$ROOT_DIR/scripts/gradle.sh" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest

COMMIT=$(git -C "$ROOT_DIR" rev-parse --short=12 HEAD)
if [ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]; then
    TREE_STATE=dirty
else
    TREE_STATE=clean
fi
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "Known-good debug artifact"
echo "  commit: $COMMIT"
echo "  tree:   $TREE_STATE"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK"
else
    shasum -a 256 "$APK"
fi
