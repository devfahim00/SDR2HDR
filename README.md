# SDR2HDR

An Android app (Kotlin) that converts SDR videos to **HDR10** (BT.2020 primaries + SMPTE ST 2084 PQ,
x265 with HDR10 signaling and static metadata) entirely on-device using FFmpeg.

This is a port of the original Termux `sdr2hdr.sh` script into a standalone app — same filter chain,
same defaults, same output behavior.

## Features

- **All videos** button: lists every video on the device in one place (MediaStore, with a
  file-scan fallback), with search and sorting (newest / name / largest / longest)
- Folder browser scanning `DCIM`, `Download`, `Movies`, `Pictures` (depth ≤ 2) with per-folder
  video counts + manual path entry
- Video list with thumbnails, size / quality (SD/HD/FHD/UHD) / frame count / duration, HDR detection
- **HDR10 Studio** UI: light / dark themes with a neumorphic sun-moon toggle, rounded cards, pill tabs,
  gradient sliders, glowing convert button, progress stat tiles and result/error cards
- Grading sliders: **Exposure** −3.0…3.0 (default 0.25), **Highlight / MaxCLL nits** 100…1000
  (default 240), **Saturation** 0.0…3.0 (default 1.25)
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

## Colour-space handling (zscale error fix)

Many phone videos are untagged or carry an odd colour-matrix tag (e.g. `gbr`). The original
filter chain let `zscale` guess the source colorimetry, which failed with

```
code 1026: YUV color family cannot have RGB matrix coefficients
code 3074: no path between colorspaces
```

The app now reads `color_space / color_primaries / color_transfer / color_range` with ffprobe and
passes them to `zscale` explicitly (`min/pin/tin/rin` on input, `m=gbr:p:t` on output). Unknown or
bogus tags fall back to BT.709 (HD) / BT.601 (SD). For correctly tagged BT.709 sources the output is
bit-identical to the old chain. If a colour error still happens, the app retries once with a plain
BT.709 profile and force-overwritten frame colour tags (`setparams`) before reporting a failure.

## Differences vs the Termux script

- No pause/resume (the script used SIGSTOP); the app offers Stop only
- Everything else — filter chain, defaults, output naming, HDR skip, platform presets — is the same

## Credits / licensing

UI fonts (bundled, SIL Open Font License 1.1): [Titan One](https://fonts.google.com/specimen/Titan+One)
and [DM Mono](https://fonts.google.com/specimen/DM+Mono).

FFmpeg (`full-gpl` build with libx265) is bundled via the maintained
[ffmpeg-kit](https://github.com/sk3llo/ffmpeg-kit-flutter) fork
(`com.antonkarpenko:ffmpeg-kit-full-gpl`), FFmpeg v8.1.1. The full-gpl variant makes this
project GPL — source is published in this repository.
