#!/bin/sh

set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 <emulator-or-device-serial>" >&2
    exit 2
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEVICE_SERIAL=$1

"$ROOT_DIR/scripts/adb.sh" -s "$DEVICE_SERIAL" get-state >/dev/null

ANDROID_SERIAL="$DEVICE_SERIAL" exec "$ROOT_DIR/scripts/gradle.sh" \
  :app:connectedDebugAndroidTest \
  "-Pandroid.injected.device.serial=$DEVICE_SERIAL"
