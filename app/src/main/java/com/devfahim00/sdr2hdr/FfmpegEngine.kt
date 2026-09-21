package com.devfahim00.sdr2hdr

import java.io.File
import java.util.Locale

/**
 * Colorimetry of the *source* video, expressed as zscale option values.
 *
 * Phone videos are frequently untagged (or tagged with a bogus "gbr" matrix). zscale then
 * fails with `code 1026: YUV color family cannot have RGB matrix coefficients` or
 * `code 3074: no path between colorspaces`. Telling zscale explicitly what the source is
 * removes the dependence on those tags.
 *
 * HDR sources (PQ / HLG transfer) are kept in the BT.2020 family so the engine can regrade
 * them (HDR -> HDR / HDR -> HLG) instead of refusing them.
 */
data class SourceColor(
    val matrix: String,
    val primaries: String,
    val transfer: String,
    val fullRange: Boolean
) {
    /** True when the source itself is HDR (PQ or HLG transfer). */
    val isHdrSource: Boolean
        get() = transfer == "smpte2084" || transfer == "arib-std-b67"

    companion object {
        /** Safe default: BT.709 limited range (what virtually all HD phone video is). */
        val BT709 = SourceColor("bt709", "bt709", "bt709", false)

        /** Build from probed metadata; unknown / bogus tags fall back to sensible defaults. */
        fun from(meta: VideoMeta?): SourceColor {
            if (meta == null) return BT709

            val trc = meta.colorTransfer?.lowercase()
            if (trc == "smpte2084" || trc == "arib-std-b67") {
                val m = meta.colorSpace?.lowercase()
                val matrix = when (m) {
                    "bt2020nc", "bt2020c" -> m
                    else -> "bt2020nc"
                }
                return SourceColor(matrix, "bt2020", trc, false)
            }

            // Same heuristic as mpv/ffmpeg: SD => BT.601, everything else => BT.709
            val hd = meta.width > 1024 || meta.height > 576

            val cs = meta.colorSpace?.lowercase()
            val matrix = when (cs) {
                "bt709", "smpte170m", "bt470bg", "bt2020nc" -> cs
                else -> if (hd) "bt709" else "smpte170m"
            }

            val pri = meta.colorPrimaries?.lowercase()
            val primaries = when (pri) {
                "bt709", "smpte170m", "bt470bg", "bt2020" -> pri
                else -> when (matrix) {
                    "bt2020nc" -> "bt2020"
                    else -> matrix
                }
            }

            val transfer = when (trc) {
                "bt709", "smpte170m", "bt470bg", "bt470m", "iec61966-2-1" -> trc
                "bt2020-10" -> "2020_10"
                "bt2020-12" -> "2020_12"
                else -> when (matrix) {
                    "bt2020nc" -> "2020_10"
                    "bt709" -> "bt709"
                    else -> "smpte170m"
                }
            }

            val range = meta.colorRange?.lowercase()
            val full = range == "pc" || range == "full" || range == "jpeg"

            return SourceColor(matrix, primaries, transfer, full)
        }
    }
}

/**
 * Builds every ffmpeg command the app needs:
 *
 *  - the SDR -> HDR10 / HDR10+ / Dolby Vision 8.1 / HLG conversion pipeline,
 *  - AV1 / VP9 / MediaCodec hardware encoder variants,
 *  - paused-and-resumed conversion segments (fragmented MP4) plus the final concat,
 *  - single-frame side-by-side preview renders.
 *
 * The classic SDR -> HDR10 software path stays byte-identical to the original Termux
 * sdr2hdr.sh chain unless the new options are engaged.
 */
object FfmpegEngine {

    private const val MASTER_DISPLAY =
        "G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)"

    /** Fragmented MP4 so a cancelled (paused) segment remains a valid, readable file. */
    private const val SEGMENT_MOVFLAGS =
        "+frag_keyframe+empty_moov+default_base_moof"

    private fun f(v: Double): String = String.format(Locale.US, "%.2f", v)

    // ─────────────────────────────────────────────────────────────────────────────
    // Full conversion command (one segment; [resumeFromSec] > 0 seeks the input)
    // ─────────────────────────────────────────────────────────────────────────────

    fun buildSegmentCommand(
        config: ConvertConfig,
        input: String,
        source: SourceColor,
        meta: VideoMeta?,
        segmentPath: String,
        resumeFromSec: Double,
        forceFrameProps: Boolean = false,
        hdr10PlusJson: File? = null
    ): String {
        val pixFmt = when {
            config.codec == VideoCodec.HEVC_HW && Capability.hevcHw10Bit -> "p010le"
            config.codec == VideoCodec.HEVC_HW || config.codec == VideoCodec.H264_HW -> "nv12"
            config.codec == VideoCodec.AV1 && Capability.av1Encoder == "av1_mediacodec" -> "nv12"
            else -> "yuv420p10le"
        }

        val sb = StringBuilder("-y -hide_banner -loglevel error")
        if (config.threads > 0) sb.append(" -threads ").append(config.threads)

        when (config.gpu) {
            GpuDecode.MEDIACODEC ->
                if (Capability.mediacodecHwaccel && isHwDecodable(meta)) {
                    // frames come back in system memory (nv12) — filters work as usual
                    sb.append(" -hwaccel mediacodec")
                }
            GpuDecode.VULKAN ->
                if (Capability.vulkanHwaccel) sb.append(" -hwaccel vulkan")
            GpuDecode.OFF -> {}
        }

        if (resumeFromSec > 0.05) {
            sb.append(" -ss ").append(String.format(Locale.US, "%.3f", resumeFromSec))
        }
        sb.append(" -i \"").append(input).append("\"")

        sb.append(" -vf \"")
            .append(filterChain(config, source, forceFrameProps, forPreview = false, finalPixFmt = pixFmt))
            .append("\"")

        sb.append(encoderArgs(config, meta, hdr10PlusJson))

        // Container colour signalling for every codec / format combination
        val targetTrc = if (config.format == HdrFormat.HLG) "arib-std-b67" else "smpte2084"
        if (config.codec == VideoCodec.HEVC_X265 || config.codec == VideoCodec.HEVC_HW) {
            sb.append(" -tag:v hvc1")
        }
        sb.append(" -color_primaries bt2020 -color_trc ").append(targetTrc)
            .append(" -colorspace bt2020nc")

        when (config.platform) {
            "tiktok", "instagram" -> sb.append(" -c:a aac -b:a 192k -ar 48000")
            else -> sb.append(" -c:a copy")
        }
        if (config.stripMeta) sb.append(" -map_metadata -1")

        // Segmented (fragmented) output keeps a cancelled partial file readable, which is
        // what makes pause / resume possible; the final file is remuxed with +faststart.
        sb.append(" -movflags ").append(SEGMENT_MOVFLAGS)
            .append(" -frag_duration 2000000 \"").append(segmentPath).append("\"")
        return sb.toString()
    }

    /** Re-encodes nothing: merges all pause segments into the final +faststart MP4. */
    fun buildConcatCommand(listFile: String, finalPath: String): String =
        "-y -hide_banner -loglevel error -f concat -safe 0 -i \"$listFile\"" +
            " -c copy -movflags +faststart \"$finalPath\""

    /** Single uninterrupted conversion: just remux the one segment with +faststart. */
    fun buildRemuxCommand(segment: String, finalPath: String): String =
        "-y -hide_banner -loglevel error -i \"$segment\" -c copy -movflags +faststart \"$finalPath\""

    /**
     * After a pause-cancel, the tail fragment of the partial segment may be truncated.
     * Copying it through ffmpeg again (into another fragmented file, which is valid even
     * without a trailer) reliably drops whatever could not be parsed.
     */
    fun buildSanitizeCommand(segment: String, sanitizedPath: String): String =
        "-y -hide_banner -loglevel error -i \"$segment\" -c copy -movflags $SEGMENT_MOVFLAGS" +
            " \"$sanitizedPath\""

    // ─────────────────────────────────────────────────────────────────────────────
    // Filter chain
    // ─────────────────────────────────────────────────────────────────────────────

    private fun filterChain(
        config: ConvertConfig,
        source: SourceColor,
        forceFrameProps: Boolean,
        forPreview: Boolean,
        finalPixFmt: String
    ): String {
        val fs = mutableListOf<String>()

        // Vulkan keeps frames in device memory -> download before filtering.
        if (!forPreview && config.gpu == GpuDecode.VULKAN && Capability.vulkanHwaccel) {
            fs += "hwdownload"
            fs += "format=nv12"
        }
        if (!forPreview) {
            when (config.platform) {
                "tiktok" -> fs += "fps=60"
                "instagram" -> fs += "fps=30"
            }
        }

        fs += "scale=trunc(iw/2)*2:trunc(ih/2)*2"

        // YUV-domain grade (eq). Only non-neutral parameters are emitted.
        val eq = mutableListOf<String>()
        eq += "saturation=${f(config.saturation)}"
        if (config.contrast != 1.0) eq += "contrast=${f(config.contrast)}"
        if (config.gamma != 1.0) eq += "gamma=${f(config.gamma)}"
        if (config.brightness != 0.0) eq += "brightness=${f(config.brightness)}"
        fs += "eq=${eq.joinToString(":")}"

        if (forceFrameProps) {
            val trc = when (source.transfer) {
                "2020_10" -> "bt2020-10"
                "2020_12" -> "bt2020-12"
                else -> source.transfer
            }
            fs += "setparams=colorspace=${source.matrix}:color_primaries=${source.primaries}" +
                ":color_trc=$trc:range=${if (source.fullRange) "pc" else "tv"}"
        }

        // Step 1: YUV (whatever the source is) -> full-range RGB float, with the source
        // colorimetry stated explicitly on both the input and the output side.
        fs += "zscale=rin=${if (source.fullRange) "full" else "tv"}:r=full" +
            ":min=${source.matrix}:pin=${source.primaries}:tin=${source.transfer}" +
            ":m=gbr:p=${source.primaries}:t=${source.transfer}:d=none"

        // Advanced RGB controls run in a 16-bit planar RGB island.
        val userCurves =
            if (config.hasCurves() && Capability.filterCurves) buildCurvesFilter(config) else null
        val toneCurve =
            if (config.toneMap != ToneMapOp.NONE && !source.isHdrSource && Capability.filterCurves)
                buildToneCurveFilter(config.toneMap) else null
        val useTint = config.tint != 0.0 && Capability.filterColorBalance
        val useTemp = config.temperatureK != 6500 && Capability.filterColorTemperature

        if (useTint || useTemp || userCurves != null || toneCurve != null) {
            fs += "format=gbrp16le"
            if (useTint) fs += "colorbalance=gm=${String.format(Locale.US, "%.3f", config.tint)}"
            if (useTemp) fs += "colortemperature=temperature=${config.temperatureK}"
            if (userCurves != null) fs += userCurves
            if (toneCurve != null) fs += toneCurve
            fs += "format=gbrpf32le"
        } else {
            fs += "format=gbrpf32le"
        }

        // HDR input + selected operator: real tonemap filter at linear light.
        val useTonemapFilter = source.isHdrSource && config.toneMap != ToneMapOp.NONE
        if (useTonemapFilter) {
            fs += "zscale=rin=full:min=gbr:pin=bt2020:tin=${source.transfer}" +
                ":transfer=linear:npl=100"
            fs += "tonemap=${config.toneMap.key}:desat=0"
        }

        fs += "exposure=${f(config.exposure)}"

        // Step 2: RGB float -> BT.2020 / PQ (or HLG) / 10-bit YUV
        val targetTransfer = if (config.format == HdrFormat.HLG) "arib-std-b67" else "smpte2084"
        val npl = if (config.format == HdrFormat.HLG) 1000 else 203
        val tinAfter = if (useTonemapFilter) "linear" else source.transfer
        fs += "zscale=rin=full:min=gbr:pin=${source.primaries}:tin=$tinAfter" +
            ":primaries=bt2020:transfer=$targetTransfer:matrix=bt2020nc:npl=$npl"

        fs += "format=$finalPixFmt"
        return fs.joinToString(",")
    }

    private fun encoderArgs(config: ConvertConfig, meta: VideoMeta?, hdr10PlusJson: File?): String {
        val sb = StringBuilder()
        when (config.codec) {
            VideoCodec.HEVC_X265 -> {
                sb.append(" -c:v libx265 -preset ").append(config.preset).append(" -crf 20")
                when (config.platform) {
                    "tiktok" -> sb.append(" -maxrate 16M -bufsize 32M")
                    "instagram" -> sb.append(" -maxrate 14M -bufsize 28M")
                }
                sb.append(" -x265-params \"")
                    .append(x265Params(config, hdr10PlusJson))
                    .append("\"")
            }

            VideoCodec.HEVC_HW -> sb.append(" -c:v hevc_mediacodec -b:v ${hwBitrateMbps(config, meta)}M")

            VideoCodec.H264_HW -> sb.append(" -c:v h264_mediacodec -b:v ${hwBitrateMbps(config, meta)}M")

            VideoCodec.VP9 -> {
                val cpuUsed = when (config.preset) {
                    "ultrafast" -> 8; "medium" -> 3; "slow" -> 1; else -> 5
                }
                val crf = when (config.preset) {
                    "ultrafast" -> 34; "medium" -> 28; "slow" -> 25; else -> 31
                }
                sb.append(" -c:v libvpx-vp9 -profile:v 2 -crf $crf -b:v 0")
                    .append(" -deadline good -cpu-used $cpuUsed -row-mt 1")
            }

            VideoCodec.AV1 -> when (Capability.av1Encoder) {
                "libaom-av1" -> {
                    val cpuUsed = when (config.preset) {
                        "ultrafast" -> 8; "medium" -> 4; "slow" -> 2; else -> 6
                    }
                    val crf = when (config.preset) {
                        "ultrafast" -> 34; "medium" -> 27; "slow" -> 24; else -> 30
                    }
                    sb.append(" -c:v libaom-av1 -crf $crf -b:v 0 -usage good")
                        .append(" -cpu-used $cpuUsed -row-mt 1")
                }
                "libsvtav1" -> {
                    val svt = when (config.preset) {
                        "ultrafast" -> 10; "medium" -> 5; "slow" -> 3; else -> 7
                    }
                    val crf = when (config.preset) {
                        "ultrafast" -> 34; "medium" -> 27; "slow" -> 24; else -> 30
                    }
                    sb.append(" -c:v libsvtav1 -crf $crf -preset $svt")
                }
                else -> sb.append(" -c:v av1_mediacodec -b:v ${hwBitrateMbps(config, meta)}M")
            }
        }
        return sb.toString()
    }

    private fun x265Params(config: ConvertConfig, hdr10PlusJson: File?): String {
        val parts = mutableListOf<String>()
        val isHlg = config.format == HdrFormat.HLG
        if (!isHlg) parts += "hdr10=1"
        parts += "repeat-headers=1"
        parts += "colorprim=bt2020"
        parts += "transfer=${if (isHlg) "arib-b67" else "smpte2084"}"
        parts += "colormatrix=bt2020nc"
        if (!isHlg) parts += "master-display=$MASTER_DISPLAY"
        parts += "max-cll=${config.highlight},${config.highlight}"
        when (config.dynamicMeta) {
            DynamicMeta.DOLBY_VISION -> if (Capability.dolbyVisionX265) parts += "dolby-vision-profile=8.1"
            DynamicMeta.HDR10PLUS -> if (hdr10PlusJson != null && hdr10PlusJson.isFile) {
                parts += "dhdr10-info=${hdr10PlusJson.absolutePath}"
                parts += "dhdr10-opt=1"
            }
            DynamicMeta.NONE -> {}
        }
        if (config.threads > 0) parts += "pools=${config.threads}"
        return parts.joinToString(":")
    }

    private fun hwBitrateMbps(config: ConvertConfig, meta: VideoMeta?): Int {
        val maxDim = maxOf(meta?.width ?: 1920, meta?.height ?: 1080)
        val base = when {
            maxDim >= 3600 -> 45
            maxDim >= 2500 -> 30
            maxDim >= 1800 -> 20
            maxDim >= 1200 -> 12
            else -> 8
        }
        return when (config.platform) {
            "tiktok" -> minOf(16, base)
            "instagram" -> minOf(14, base)
            else -> base
        }
    }

    private fun isHwDecodable(meta: VideoMeta?): Boolean {
        val c = meta?.codecName?.lowercase()
        return c == "h264" || c == "hevc"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Curves / tone curve helpers
    // ─────────────────────────────────────────────────────────────────────────────

    fun buildCurvesFilter(config: ConvertConfig): String? {
        val parts = mutableListOf<String>()
        for (key in listOf("master", "r", "g", "b")) {
            val pts = config.curves[key] ?: continue
            if (pts.isNeutralCurve()) continue
            parts += "$key='${pointsString(pts)}'"
        }
        return if (parts.isEmpty()) null else "curves=${parts.joinToString(":")}"
    }

    private fun pointsString(pts: List<Pair<Double, Double>>): String =
        pts.joinToString(" ") { (x, y) ->
            String.format(Locale.US, "%.4f/%.4f", x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        }

    private fun List<Pair<Double, Double>>.isNeutralCurve(): Boolean =
        size == 2 && this[0].first == 0.0 && this[0].second == 0.0 &&
            this[1].first == 1.0 && this[1].second == 1.0

    /**
     * For SDR sources the chosen tone-mapping operator is baked into a `curves` LUT applied
     * before the HDR expansion (the `tonemap` filter itself is an HDR->SDR operator and
     * cannot be applied to an SDR signal). Endpoints are pinned to (0,0)/(1,1).
     */
    fun buildToneCurveFilter(op: ToneMapOp): String? =
        "curves=master='${pointsString(toneCurvePoints(op))}'"

    fun toneCurvePoints(op: ToneMapOp): List<Pair<Double, Double>> {
        val fn: (Double) -> Double = when (op) {
            ToneMapOp.REINHARD -> { x -> x / (x + 0.30 * (1.0 - x)) }
            ToneMapOp.HABLE -> { x -> (hable(x) / hable(1.0)) }
            ToneMapOp.MOBIUS -> { x -> (x * (1.0 + x)) / (1.0 + 0.55 * x) / (2.0 / 1.55) }
            ToneMapOp.NONE -> { x -> x }
        }
        return (0..32).map { i ->
            val x = i / 32.0
            Pair(x, fn(x).coerceIn(0.0, 1.0))
        }
    }

    private fun hable(x: Double): Double {
        val a = 0.15; val b = 0.50; val c = 0.10
        val d = 0.20; val e = 0.02; val f = 0.30
        return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Preview (single frame) commands
    // ─────────────────────────────────────────────────────────────────────────────

    /** Raw source frame, untouched. */
    fun buildPlainFrameCommand(input: String, timeSec: Double, outPng: String): String =
        "-y -hide_banner -loglevel error -ss ${String.format(Locale.US, "%.3f", timeSec)}" +
            " -i \"$input\" -frames:v 1 \"$outPng\""

    /**
     * The converted look rendered onto the phone screen: full grading chain, then the HDR
     * result tonemapped back down to SDR so the difference is actually visible.
     */
    fun buildGradedPreviewCommand(
        config: ConvertConfig,
        input: String,
        source: SourceColor,
        timeSec: Double,
        outPng: String
    ): String {
        val chain = filterChain(config, source, forceFrameProps = false, forPreview = true, finalPixFmt = "yuv420p10le")
        val targetTrc = if (config.format == HdrFormat.HLG) "arib-std-b67" else "smpte2084"
        val dm = displayMap(targetTrc)
        return "-y -hide_banner -loglevel error -ss ${String.format(Locale.US, "%.3f", timeSec)}" +
            " -i \"$input\" -frames:v 1 -vf \"$chain,$dm\" \"$outPng\""
    }

    /** Frame from an already converted (tagged) HDR file, mapped for screen display. */
    fun buildOutputFrameCommand(
        input: String,
        format: HdrFormat,
        timeSec: Double,
        outPng: String
    ): String {
        val targetTrc = if (format == HdrFormat.HLG) "arib-std-b67" else "smpte2084"
        return "-y -hide_banner -loglevel error -ss ${String.format(Locale.US, "%.3f", timeSec)}" +
            " -i \"$input\" -frames:v 1 -vf \"${displayMap(targetTrc)}\" \"$outPng\""
    }

    /** PQ / HLG -> screen: linearise, tonemap (hable), gamut compress to BT.709, rgb24 PNG. */
    private fun displayMap(targetTrc: String): String =
        "zscale=min=bt2020nc:pin=bt2020:tin=$targetTrc:transfer=linear:npl=100," +
            "format=gbrpf32le," +
            "zscale=rin=full:min=gbr:pin=bt2020:tin=linear:primaries=bt709," +
            "tonemap=hable:desat=0," +
            "zscale=rin=full:min=gbr:pin=bt709:tin=linear:primaries=bt709:transfer=bt709:r=full," +
            "format=rgb24"

    // ─────────────────────────────────────────────────────────────────────────────
    // HDR10+ dynamic metadata JSON (x265 dhdr10-info format)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates the JSON x265's `dhdr10-info` expects. A single scene covers the whole
     * clip with L1 percentile statistics derived from the chosen MaxCLL, giving the stream
     * genuine ST.2094-40 SEI dynamic metadata signalling.
     */
    fun writeHdr10PlusJson(out: File, highlightNits: Int) {
        val h = highlightNits.toDouble()
        val n1 = String.format(Locale.US, "%.1f", h * 0.02)
        val n5 = String.format(Locale.US, "%.1f", h * 0.08)
        val n10 = String.format(Locale.US, "%.1f", h * 0.15)
        val n25 = String.format(Locale.US, "%.1f", h * 0.28)
        val n50 = String.format(Locale.US, "%.1f", h * 0.42)
        val n75 = String.format(Locale.US, "%.1f", h * 0.60)
        val n99 = String.format(Locale.US, "%.1f", h * 0.88)
        val avg = String.format(Locale.US, "%.1f", h * 0.30)
        val fall = maxOf(1, (h / 2.5).toInt())
        val json = """
[
  {
    "Description": "SDR2HDR generated static scene",
    "MasteringDisplay": {
      "PrimariesX": [34000, 13250, 7500],
      "PrimariesY": [16000, 34500, 3000],
      "WhitePointX": 15635,
      "WhitePointY": 16450,
      "LuminanceMax": 1000,
      "LuminanceMin": 1
    },
    "MaxCLL": $highlightNits,
    "MaxFALL": $fall,
    "Scene": [
      {
        "StartFrame": 0,
        "EndFrame": 100000000,
        "L1": {
          "MaxRGB": $h,
          "Average": $avg,
          "Percentiles": [
            {"Percentile": 1.0, "Luminance": $n1},
            {"Percentile": 5.0, "Luminance": $n5},
            {"Percentile": 10.0, "Luminance": $n10},
            {"Percentile": 25.0, "Luminance": $n25},
            {"Percentile": 50.0, "Luminance": $n50},
            {"Percentile": 75.0, "Luminance": $n75},
            {"Percentile": 99.0, "Luminance": $n99}
          ]
        }
      }
    ]
  }
]
""".trimIndent()
        out.parentFile?.mkdirs()
        out.writeText(json)
    }
}
