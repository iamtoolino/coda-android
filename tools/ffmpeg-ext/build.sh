#!/usr/bin/env bash
# Rebuild the LGPL-only arm64 MP3 AAR. Run alone: this invokes one Gradle build.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/build/ffmpeg-ext"
SDK="${ANDROID_HOME:?set ANDROID_HOME to the Android SDK first}"
NDK_VERSION=26.1.10909125 # r26b, documented by Media3 1.11.1
NDK="${CODA_NDK_PATH:-$WORK/sdk/ndk/$NDK_VERSION}"
mkdir -p "$WORK" "$ROOT/app/libs"
fetch() {
  local url=$1 dest=$2 sha=$3
  if [[ ! -f "$dest" ]]; then
    curl --fail --location --retry 2 --max-time 300 "$url" -o "$dest.part"
    mv "$dest.part" "$dest"
  fi
  printf '%s  %s\n' "$sha" "$dest" | shasum -a 256 -c -
}
fetch https://codeload.github.com/androidx/media/tar.gz/refs/tags/1.11.1 \
  "$WORK/media-1.11.1.tar.gz" 8fd245561097aad1e274c1cf8b60cc241e8df7b67e15c68b2983ad84e675cf88
fetch https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz \
  "$WORK/ffmpeg-6.0.tar.xz" 57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082
if [[ ! -f "$NDK/source.properties" ]]; then
  mkdir -p "$WORK/sdk/licenses"
  cp "$SDK"/licenses/* "$WORK/sdk/licenses/"
  sdkmanager --sdk_root="$WORK/sdk" "ndk;$NDK_VERSION"
fi
grep -Fqx "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties"
case $(uname -s) in
  Darwin) HOST=darwin-x86_64 ;;
  Linux) HOST=linux-x86_64 ;;
  *) echo 'Unsupported build host' >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
# Fresh extraction ensures an earlier build or local source change cannot enter the AAR.
rm -rf "$WORK/media-1.11.1" "$WORK/ffmpeg-6.0" "$WORK/native" "$WORK/jniLibs" "$WORK/install"
tar -xzf "$WORK/media-1.11.1.tar.gz" -C "$WORK" \
  media-1.11.1/libraries/decoder_ffmpeg media-1.11.1/LICENSE
tar -xJf "$WORK/ffmpeg-6.0.tar.xz" -C "$WORK"
(cd "$WORK/media-1.11.1" && patch -p1 < "$ROOT/tools/ffmpeg-ext/mp3-only.patch")
mkdir -p "$WORK/native" "$WORK/jniLibs/arm64-v8a"
cd "$WORK/native"
# FFmpeg embeds its full configure line (avcodec_configuration()), so the configure arguments must not
# contain a host path: the tools are bare names found through PATH, --prefix is neutral (headers are
# staged with DESTDIR) and -ffile-prefix-map, which would itself end up in the configure line as an
# --extra-cflags value, lives in two tiny compiler wrappers instead.
mkdir -p "$WORK/bin"
for tool in clang clang++; do
  printf '#!/bin/sh\nexec "%s/aarch64-linux-android21-%s" -ffile-prefix-map="%s=." "$@"\n' \
    "$TOOLCHAIN" "$tool" "$WORK" > "$WORK/bin/aarch64-linux-android21-$tool"
  chmod +x "$WORK/bin/aarch64-linux-android21-$tool"
done
(
export PATH="$WORK/bin:$TOOLCHAIN:$PATH"
# Shared FFmpeg libraries allow replacement/relinking independently of the JNI wrapper.
# Disable autodetection as well as GPL/nonfree/version3: host packages cannot enter the build.
"$WORK/ffmpeg-6.0/configure" \
  --prefix=/ffmpeg --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile --cc=aarch64-linux-android21-clang \
  --cxx=aarch64-linux-android21-clang++ \
  --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip \
  --enable-shared --disable-static --enable-pic --disable-doc --disable-programs \
  --disable-everything --disable-autodetect --disable-gpl --disable-nonfree --disable-version3 \
  --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
  --disable-symver --disable-network --disable-v4l2-m2m --disable-vulkan \
  --enable-swresample --enable-decoder=mp3 --enable-decoder=mp3float \
  --extra-ldflags='-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1'
# Treat configuration as an acceptance gate, not merely a list of requested flags.
grep -q '^#define CONFIG_GPL 0$' config.h
grep -q '^#define CONFIG_NONFREE 0$' config.h
grep -q '^#define CONFIG_VERSION3 0$' config.h
python3 "$ROOT/tools/ffmpeg-ext/verify-config.py" "$PWD"
make -j2
make install-headers DESTDIR="$WORK/install"
)
for lib in avcodec avutil swresample; do
  cp "lib$lib/lib$lib.so" "$WORK/jniLibs/arm64-v8a/"
done
"$TOOLCHAIN/aarch64-linux-android21-clang++" -std=c++11 -shared -fPIC -O2 -nostdlib++ \
  -fvisibility=hidden -fvisibility-inlines-hidden -ffile-prefix-map="$WORK"=. \
  -I"$WORK/install/ffmpeg/include" \
  "$WORK/media-1.11.1/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc" \
  -L"$WORK/jniLibs/arm64-v8a" -lavcodec -lswresample -lavutil -landroid -llog \
  -Wl,--no-undefined -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
  -o "$WORK/jniLibs/arm64-v8a/libffmpegJNI.so"
for lib in "$WORK/jniLibs/arm64-v8a/"*.so; do "$TOOLCHAIN/llvm-strip" --strip-unneeded "$lib"; done
cd "$ROOT"
./gradlew --no-daemon -p tools/ffmpeg-ext assembleRelease
python3 tools/ffmpeg-ext/package-aar.py \
  tools/ffmpeg-ext/build/outputs/aar/coda-mp3-ffmpeg-release.aar app/libs/media3-ffmpeg-mp3-1.11.1.aar
# Gate: no host path (home directory, /Users/) in any AAR entry, nested archives included.
python3 -I tools/common/apk_path_scan.py app/libs/media3-ffmpeg-mp3-1.11.1.aar
(cd app/libs && shasum -a 256 media3-ffmpeg-mp3-1.11.1.aar > media3-ffmpeg-mp3-1.11.1.sha256)
cat app/libs/media3-ffmpeg-mp3-1.11.1.sha256
