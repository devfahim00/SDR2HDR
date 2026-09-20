# SDR2HDR

An Android app (Kotlin) that converts SDR videos to **HDR10** (BT.2020 primaries + SMPTE ST 2084 PQ,
x265 with HDR10 signaling and static metadata) entirely on-device using FFmpeg.

This is a port of the original Termux `sdr2hdr.sh` script into a standalone app — same filter chain,
same defaults, same output behavior.

## Features

- Folder browser scanning `DCIM`, `Download`, `Movies`, `Pictures` (depth ≤ 2) + manual path entry
- Video list with size / quality (SD/HD/FHD/UHD) / frame count / duration metadata, HDR detection
- Grading settings: **Exposure** (default 0.25), **Highlight / MaxCLL nits** (default 240),
  **Saturation** (default 1.25)
- Encoder presets: ultrafast / fast (default) / medium / slow (libx265, CRF 20)
- Platform optimization: none (default) / TikTok (60 fps CFR, 16M cap, AAC 192k 48k) /
  Instagram (30 fps CFR, 14M cap, AAC 192k 48k)
- Metadata cleaning: preserve (default) or strip
- HDR10 static metadata: `master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)`,
  `max-cll` set from the Highlight nits value
- Already-HDR inputs (smpte2084 / arib-std-b67) are detected and skipped, like the script
- Live progress: %, frames, fps and ETA, with a Stop button; screen kept on + partial wake lock
  while encoding
- Output: `/sdcard/Movies/HDR10_Converted/output_HDR10[N].mp4`, auto-named to avoid overwrites,
  media-scanned on completion

The exact FFmpeg filter chain (identical to the script):

```
scale=trunc(iw/2)*2:trunc(ih/2)*2,eq=saturation=<sat>,zscale=rin=tv:r=full:d=none,
format=gbrpf32le,exposure=<exp>,zscale=primaries=bt2020:transfer=smpte2084:matrix=bt2020nc:npl=203,
format=yuv420p10le
```

## Building

GitHub Actions builds a debug APK on every push (`.github/workflows/build.yml`).
Download it from the run's **Artifacts** (`SDR2HDR-debug-apk`) or from **Releases**.

Locally:

```
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 34).

## Usage

1. Install the APK (allow "unknown sources" for your browser/file manager)
2. Open the app and grant **All files access** (needed to browse shared storage and write to
   `Movies/HDR10_Converted`)
3. Pick folder → pick video → adjust grading → start
4. Keep the app open while encoding; the result appears in `Movies/HDR10_Converted`

## Differences vs the Termux script

- No pause/resume (the script used SIGSTOP); the app offers Stop only
- Everything else — filter chain, defaults, output naming, HDR skip, platform presets — is the same

## Credits / licensing

FFmpeg (`full-gpl` build with libx265) is bundled via the maintained
[ffmpeg-kit](https://github.com/sk3llo/ffmpeg-kit-flutter) fork
(`com.antonkarpenko:ffmpeg-kit-full-gpl`), FFmpeg v8.1.1. The full-gpl variant makes this
project GPL — source is published in this repository.
