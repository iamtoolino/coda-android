# MP3-only Media3 FFmpeg decoder

Coda builds this AAR itself; no community prebuilt is used. Only `audio/mpeg`
is accepted by the preferred extension renderer. Other audio stays with MediaCodec.
The native FFmpeg decoder inventory is exactly `mp3`, `mp3float`.

## Inputs and build

- Media3 **1.11.1**, unmodified JNI/Java except `mp3-only.patch` (MIME guard).
  [Source archive](https://codeload.github.com/androidx/media/tar.gz/refs/tags/1.11.1),
  SHA-256 `8fd245561097aad1e274c1cf8b60cc241e8df7b67e15c68b2983ad84e675cf88`.
- FFmpeg **6.0**, unmodified release source.
  [Source archive](https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz),
  SHA-256 `57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082`.
- Android NDK **r26b / 26.1.10909125**, as documented by the
  [Media3 1.11.1 instructions](https://github.com/androidx/media/blob/1.11.1/libraries/decoder_ffmpeg/README.md).
  Installed with `sdkmanager` in ignored `build/ffmpeg-ext/sdk` (the shared SDK
  need not be writable). Android API 21 native target, compatible with minSdk 36.
- AGP **9.4.1**, SDK/build-tools **36 / 37.0.0**, repository Gradle **9.8.2**, JDK 17.
  `arm64-v8a` only, matching the app. x86_64 is not built or shipped.

```sh
source scripts/lib/env.sh
./tools/ffmpeg-ext/build.sh
(cd app/libs && shasum -a 256 -c media3-ffmpeg-mp3-1.11.1.sha256)
```

This uses two native compiler jobs and **one** Gradle invocation, with
`--no-daemon`, one worker and a 1 GiB heap. Run it without another Gradle build
in this worktree. The standalone library project is not part of ordinary app
builds. The committed AAR lets those builds run without an NDK or network fetch
of native sources. `CODA_NDK_PATH` may point at an existing r26b installation.
Downloads are SHA-256 checked before extraction; fresh extraction and native
configuration prevent a stale source/codec configuration from entering the AAR.
The script verifies licence macros and the exact decoder inventory before make.
It canonicalizes ZIP ordering/timestamps, including `classes.jar`, and records
the resulting AAR SHA-256 in `app/libs/`. FFmpeg embeds its whole configure line in
the libraries (`avcodec_configuration()`), so the line contains no host path:
tools are bare names found through `PATH`, `--prefix` is neutral and
`-ffile-prefix-map` is applied by two tiny compiler wrappers in `build/ffmpeg-ext/bin`.
The build ends with `tools/common/apk_path_scan.py` over the AAR (no home directory
and no `/Users/` in any entry). No checkout path remains in the result;
the toolchain version is still part of the hash.

## Exact FFmpeg configuration

`WORK=build/ffmpeg-ext` (absolute), `TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin`
on macOS (`linux-x86_64` on Linux). The script puts `$WORK/bin` (the two compiler
wrappers that add `-ffile-prefix-map=$WORK=.`) and `$TOOLCHAIN` on `PATH` for the
configure/make step only and passes:

```text
--prefix=/ffmpeg --target-os=android --arch=aarch64 --cpu=armv8-a
--enable-cross-compile --cc=aarch64-linux-android21-clang
--cxx=aarch64-linux-android21-clang++
--nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip
--enable-shared --disable-static --enable-pic --disable-doc --disable-programs
--disable-everything --disable-autodetect --disable-gpl --disable-nonfree --disable-version3
--disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
--disable-symver --disable-network --disable-v4l2-m2m --disable-vulkan
--enable-swresample --enable-decoder=mp3 --enable-decoder=mp3float
--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1
```

Headers are staged with `make install-headers DESTDIR=$WORK/install`.

The JNI wrapper uses C++11, `-shared -fPIC -O2 -nostdlib++`, hidden visibility,
`-Wl,--no-undefined -Wl,-Bsymbolic`, 16 KiB ELF alignment and a SHA-1 build ID.
It has no C++ runtime references. Installed FFmpeg headers avoid the release
archive's lowercase `version` file shadowing libc++'s `<version>` header on a
case-insensitive macOS filesystem. All `.so` files are stripped. The AAR has
`libffmpegJNI.so`, `libavcodec.so`, `libavutil.so`, `libswresample.so`; no static
FFmpeg code is linked into the wrapper. ELF dependency/alignment checks are in
the report. No `--enable-gpl` or `--enable-nonfree` is used.

## Licences and replacement

FFmpeg reports **LGPL version 2.1 or later**. The AAR bundles its full LGPL text,
copyright/source NOTICE and Media3 Apache-2.0 text in `assets/licenses/`.
Settings → Licences → FFmpeg displays the NOTICE and LGPL offline.
`FFmpeg-LGPL-2.1.txt` is the NOTICE followed by `FFmpeg-COPYING.LGPLv2.1.txt`.

The FFmpeg libraries are dynamically replaceable. To modify FFmpeg, obtain the
pinned corresponding source above, apply your changes, run the configure/make
commands in `build.sh`, replace the three FFmpeg `.so` files in the AAR, rebuild
Coda and install with your own signing key. Coda permits reverse engineering
for debugging LGPL library modifications. No original signing key is required.
For distribution, accompany the AAR/APK with the corresponding source archive
and this build recipe/patch (the exact downloaded archives are retained under
`build/ffmpeg-ext/`). The upstream source links and checksums identify those
archives. The source of each
release is this repository at its release tag.
