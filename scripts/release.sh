#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CODA_SIGNING_STORE_FILE=${CODA_SIGNING_STORE_FILE:-"$HOME/.android/coda-release.p12"}
CODA_SIGNING_KEY_ALIAS=${CODA_SIGNING_KEY_ALIAS:-coda}
ECHO_DISABLED=0

restore_terminal() {
    if [ "$ECHO_DISABLED" -eq 1 ]; then
        stty echo
        ECHO_DISABLED=0
        printf '\n'
    fi
}

trap restore_terminal EXIT HUP INT TERM

if [ ! -f "$CODA_SIGNING_STORE_FILE" ]; then
    printf 'Release keystore not found: %s\n' "$CODA_SIGNING_STORE_FILE" >&2
    exit 1
fi

if [ -z "${CODA_SIGNING_STORE_PASSWORD:-}" ]; then
    if [ ! -t 0 ]; then
        printf 'Set CODA_SIGNING_STORE_PASSWORD when running without a terminal.\n' >&2
        exit 1
    fi
    printf 'Coda release keystore password: '
    stty -echo
    ECHO_DISABLED=1
    IFS= read -r CODA_SIGNING_STORE_PASSWORD
    stty echo
    ECHO_DISABLED=0
    printf '\n'
fi

if [ -z "$CODA_SIGNING_STORE_PASSWORD" ]; then
    printf 'The keystore password cannot be empty.\n' >&2
    exit 1
fi

CODA_SIGNING_KEY_PASSWORD=${CODA_SIGNING_KEY_PASSWORD:-$CODA_SIGNING_STORE_PASSWORD}
export CODA_SIGNING_STORE_FILE
export CODA_SIGNING_STORE_PASSWORD
export CODA_SIGNING_KEY_ALIAS
export CODA_SIGNING_KEY_PASSWORD

"$ROOT_DIR/scripts/gradle.sh" :app:testReleaseUnitTest :app:assembleRelease
