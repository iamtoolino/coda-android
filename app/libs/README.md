# Self-built native decoder

`media3-ffmpeg-mp3-1.11.1.aar` is built from pinned Media3 1.11.1 and FFmpeg 6.0
sources by [`tools/ffmpeg-ext/build.sh`](../../tools/ffmpeg-ext/build.sh).
It accepts MP3 only, contains arm64-v8a shared libraries, and is LGPL-2.1-or-later
(FFmpeg) / Apache-2.0 (Media3). Licence texts and NOTICE are inside the AAR and
visible in Settings → Licences. The adjacent SHA-256 file verifies the artifact.

Full source hashes, configure flags and replacement/distribution instructions:
[`tools/ffmpeg-ext/README.md`](../../tools/ffmpeg-ext/README.md).
The app supplies the matching Media3 Java dependencies; a file AAR has no POM.
