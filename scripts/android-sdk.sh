#!/bin/sh

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    :
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
elif [ -d /opt/homebrew/share/android-commandlinetools/platforms ]; then
    export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi

if [ -n "${ANDROID_HOME:-}" ]; then
    [ ! -d "$ANDROID_HOME/platform-tools" ] || PATH="$ANDROID_HOME/platform-tools:$PATH"
    [ ! -d "$ANDROID_HOME/cmdline-tools/latest/bin" ] ||
        PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
    LATEST_BUILD_TOOLS=
    for CANDIDATE in "$ANDROID_HOME"/build-tools/*; do
        [ -d "$CANDIDATE" ] || continue
        LATEST_BUILD_TOOLS=$CANDIDATE
    done
    [ -z "$LATEST_BUILD_TOOLS" ] || PATH="$LATEST_BUILD_TOOLS:$PATH"
    export PATH
fi
