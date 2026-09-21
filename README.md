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

## v0.0.1 features

- **Resolution / upscaling** — keep the original resolution or rescale to 720p / 1080p /
  2K (2560×1440) / 4K (3840×2160) / 8K (7680×4320). Lanczos scaling with
  `force_original_aspect_ratio=decrease` so aspect ratio is preserved (portrait videos come
  out 2160×3840 etc.). Rescaling happens at the end of the chain, so grading runs at source
  speed and only the final frames are scaled. Hardware-encoder bitrates are re-tiered for
  the *target* resolution.
- **Sharpness control** — 0…100 luma-only `unsharp` mask (amount 0.00–1.50), applied at the
  final resolution so it survives upscaling; 0 = off
- **Settings sheet on the home page** (gear button next to the day/night toggle):
  - GitHub repository, owner ([t.me/droxilen](https://t.me/droxilen)) and channel
    ([t.me/projectredfox](https://t.me/projectredfox)) links
  - **Hide folder explorer** switch — the home page shows only a centered
    "Pick a video to convert" card
  - **Start Local Server** — runs a tiny HTTP server on a *random free port* on the Wi-Fi
    LAN; open the shown URL (`http://<phone-ip>:<port>`) in any browser to use every
    conversion feature remotely: pick a video, all format / codec / resolution / tone-map /
    grading chips and sliders, an interactive RGB curves editor, then start / pause /
    resume / stop with live progress. Runs as a foreground service with a persistent
    notification (long-press the row to copy the URL)
  - **Check for Updates** (manual, bypasses the 6 h auto-check limit) and **About**
- **Curves editor fix** — dragging curve points no longer scrolls the settings page
  (parent touch-interception disabled during drags, bigger canvas, larger grab radius)
- **New modern launcher icon** — adaptive icon with a duotone violet-blue HDR sun, play
  glyph, gradient background and an Android 13+ themed (monochrome) variant
- App version reset to **0.0.1** (versionCode 3) to match the GitHub release tag

## v1.1 features

- **Background conversion** — the conversion now runs inside a foreground service
  (`ConvertService`, `dataSync` type) with a persistent progress notification; you can close the
  app, and pause / resume / stop straight from the notification
- **FFmpeg pause / resume** — pause cancels the current *segment* (written as fragmented MP4 so
  the partial file stays valid), resume seeks the input to the pause point and encodes the next
  segment; segments are merged losslessly (`-c copy` concat) into the final `+faststart` MP4
- **Dolby Vision 8.1** (`dolby-vision-profile=8.1` RPU signaling, x265) and **HDR10+**
  (`dhdr10-info` ST.2094-40 dynamic metadata) output options, auto-gated by a startup
  capability probe (tiny test encode) so unsupported builds fall back to HDR10
- **HLG output** (ARIB STD-B67 transfer) and **HLG / PQ input regrading** — enable *Allow HDR
  input* in advanced mode to convert HDR sources instead of skipping them
- **AV1 / VP9 encoding presets** (libaom / libvpx-vp9, 10-bit) in addition to HEVC
- **Hardware encoding**: HEVC MediaCodec (10-bit Main10 where supported) and H.264 MediaCodec
- **GPU decode**: MediaCodec (and experimental Vulkan) hwaccel, probe-gated
- **Advanced mode**: Contrast / Gamma / Brightness / Temperature (K) / Tint sliders,
  tone-mapping operators (Reinhard / Hable / Mobius — real `tonemap` filter at linear light for
  HDR inputs, baked curve LUT for SDR), and an interactive **RGB curves editor** (monotone-cubic
  spline, per-channel, exported to the ffmpeg `curves` filter)
- **Before / after preview** — renders one graded frame (tonemapped for the phone screen) next
  to the source frame before converting, and a **side-by-side compare** of source vs converted
  file after conversion
- **Multi-threading control** (`-threads` + x265 `pools`): Auto / 2 / 4 / 6 / 8
- **In-app update check** against this repo's GitHub releases (banner + release notes +
  one-tap APK download)

The exact FFmpeg filter chain (identical to the script):

```
scale=trunc(iw/2)*2:trunc(ih/2)*2,eq=saturation=<sat>,zscale=rin=tv:r=full:d=none,
format=gbrpf32le,exposure=<exp>,zscale=primaries=bt2020:transfer=smpte2084:matrix=bt2020nc:npl=203,
format=yuv420p10le
```

## Building

GitHub Actions builds an **installable release APK** (debug-key signed for sideloading) on
every push (`.github/workflows/build.yml`). Download it from the run's **Artifacts**
(`SDR2HDR-release-apk`) or from **Releases**.

Locally:

```
./gradlew assembleRelease
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

- Pause/resume is implemented differently (segmented fragmented-MP4 encoding + lossless concat,
  because apps cannot SIGSTOP the bundled ffmpeg); the script used SIGSTOP
- HDR input regrading, HLG / DV / HDR10+ / AV1 / VP9 / hardware encoders, GPU decode and the
  advanced grading controls are app-only additions
- Everything else — filter chain, defaults, output naming, platform presets — is the same

## Credits / licensing

UI fonts (bundled, SIL Open Font License 1.1): [Titan One](https://fonts.google.com/specimen/Titan+One)
and [DM Mono](https://fonts.google.com/specimen/DM+Mono).

FFmpeg (`full-gpl` build with libx265) is bundled via the maintained
[ffmpeg-kit](https://github.com/sk3llo/ffmpeg-kit-flutter) fork
(`com.antonkarpenko:ffmpeg-kit-full-gpl`), FFmpeg v8.1.1. The full-gpl variant makes this
project GPL — source is published in this repository.
