#!/bin/sh

set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 <emulator-serial> [output-html]" >&2
    exit 2
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEVICE_SERIAL=$1
OUTPUT_HTML=${2:-/tmp/coda-artwork-color-comparison.html}
TEST_CLASS=io.github.iamtoolino.coda.ui.theme.ArtworkColorComparisonTest
TEST_RUNNER=io.github.iamtoolino.coda.test/androidx.test.runner.AndroidJUnitRunner
REPORT_PATH=cache/artwork-color-comparison/report.html

case "$DEVICE_SERIAL" in
    emulator-*) ;;
    *)
        echo "refusing non-emulator serial: $DEVICE_SERIAL" >&2
        exit 2
        ;;
esac

"$ROOT_DIR/scripts/adb.sh" -s "$DEVICE_SERIAL" get-state >/dev/null

ANDROID_SERIAL="$DEVICE_SERIAL" "$ROOT_DIR/scripts/gradle.sh" \
    :app:installDebug :app:installDebugAndroidTest \
    "-Pandroid.injected.device.serial=$DEVICE_SERIAL"

"$ROOT_DIR/scripts/adb.sh" -s "$DEVICE_SERIAL" shell am instrument -w \
    -e class "$TEST_CLASS" \
    -e codaColorComparison true \
    "$TEST_RUNNER"

"$ROOT_DIR/scripts/adb.sh" -s "$DEVICE_SERIAL" exec-out \
    run-as io.github.iamtoolino.coda cat "$REPORT_PATH" > "$OUTPUT_HTML"

echo "$OUTPUT_HTML"
