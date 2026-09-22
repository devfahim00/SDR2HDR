package com.devfahim00.sdr2hdr

import org.json.JSONObject
import java.util.Locale

/** Target HDR transfer function of the converted output. */
enum class HdrFormat(val key: String, val label: String) {
    PQ("pq", "HDR10 · PQ"),
    HLG("hlg", "HLG · BT.2100");

    companion object {
        fun fromKey(k: String?): HdrFormat = entries.firstOrNull { it.key == k } ?: PQ
    }
}

/** Optional dynamic metadata carried inside the HEVC stream (software x265 only). */
enum class DynamicMeta(val key: String, val label: String) {
    NONE("none", "None"),
    HDR10PLUS("hdr10plus", "HDR10+"),
    DOLBY_VISION("dv81", "Dolby Vision 8.1");

    companion object {
        fun fromKey(k: String?): DynamicMeta = entries.firstOrNull { it.key == k } ?: NONE
    }
}

/** Output video codec / encoder implementation. */
enum class VideoCodec(val key: String, val label: String) {
    HEVC_X265("hevc", "HEVC · libx265"),
    HEVC_HW("hevchw", "HEVC · MediaCodec"),
    H264_HW("h264hw", "H.264 · MediaCodec"),
    VP9("vp9", "VP9 · libvpx"),
    AV1("av1", "AV1");

    companion object {
        fun fromKey(k: String?): VideoCodec = entries.firstOrNull { it.key == k } ?: HEVC_X265
    }
}

/** Hardware decoder used before the filter chain. */
enum class GpuDecode(val key: String, val label: String) {
    OFF("off", "CPU"),
    MEDIACODEC("mediacodec", "MediaCodec"),
    VULKAN("vulkan", "Vulkan");

    companion object {
        fun fromKey(k: String?): GpuDecode = entries.firstOrNull { it.key == k } ?: OFF
    }
}

/** Output frame size: keep the source geometry or rescale (up or down) to a standard. */
enum class OutputResolution(val key: String, val label: String, val w: Int, val h: Int) {
    SOURCE("source", "Original", 0, 0),
    HD("720p", "720p", 1280, 720),
    FHD("1080p", "1080p", 1920, 1080),
    QHD("2k", "2K", 2560, 1440),
    UHD("4k", "4K", 3840, 2160),
    UHD8K("8k", "8K", 7680, 4320);

    companion object {
        fun fromKey(k: String?): OutputResolution = entries.firstOrNull { it.key == k } ?: SOURCE
    }
}

/** Tone-mapping operator applied while converting. */
enum class ToneMapOp(val key: String, val label: String) {
    NONE("none", "Linear"),
    REINHARD("reinhard", "Reinhard"),
    HABLE("hable", "Hable"),
    MOBIUS("mobius", "Mobius");

    companion object {
        fun fromKey(k: String?): ToneMapOp = entries.firstOrNull { it.key == k } ?: NONE
    }
}

/**
 * Everything the engine needs to build an ffmpeg command. Serialised to JSON so it can be
 * passed through Intent extras into the foreground conversion service.
 */
data class ConvertConfig(
    val exposure: Double = 0.25,
    val highlight: Int = 240,
    val saturation: Double = 1.25,
    val preset: String = "fast",
    val platform: String = "none",
    val stripMeta: Boolean = false,
    val format: HdrFormat = HdrFormat.PQ,
    val dynamicMeta: DynamicMeta = DynamicMeta.NONE,
    val codec: VideoCodec = VideoCodec.HEVC_X265,
    val gpu: GpuDecode = GpuDecode.OFF,
    val threads: Int = 0, // 0 = auto
    // Advanced grading controls (only applied when != neutral)
    val contrast: Double = 1.0,
    val gamma: Double = 1.0,
    val brightness: Double = 0.0,
    val temperatureK: Int = 6500,
    val tint: Double = 0.0,
    val toneMap: ToneMapOp = ToneMapOp.NONE,
    val allowHdrInput: Boolean = false,
    val resolution: OutputResolution = OutputResolution.SOURCE,
    /** 0 = off; 1..100 maps to an unsharp luma amount of 0.00..1.50 */
    val sharpness: Int = 0,
    /** On-device AI upscale factor applied before the HDR conversion: 0 = off, else 2, 3 or 4. */
    val aiUpscale: Int = 0,
    /** Run the AI upscaler on the Vulkan GPU when available (else CPU). */
    val aiGpu: Boolean = true,
    /** channel key ("master","r","g","b") -> normalised control points (x,y in 0..1) */
    val curves: Map<String, List<Pair<Double, Double>>> = emptyMap()
) {

    fun isAdvancedNeutral(): Boolean =
        contrast == 1.0 && gamma == 1.0 && brightness == 0.0 &&
            temperatureK == 6500 && tint == 0.0 && curves.values.all { it.isNeutral() }

    fun hasCurves(): Boolean = curves.values.any { !it.isNeutral() }

    fun toJson(): String {
        val o = JSONObject()
        o.put("exposure", exposure)
        o.put("highlight", highlight)
        o.put("saturation", saturation)
        o.put("preset", preset)
        o.put("platform", platform)
        o.put("strip", stripMeta)
        o.put("format", format.key)
        o.put("dyn", dynamicMeta.key)
        o.put("codec", codec.key)
        o.put("gpu", gpu.key)
        o.put("threads", threads)
        o.put("contrast", contrast)
        o.put("gamma", gamma)
        o.put("brightness", brightness)
        o.put("temperature", temperatureK)
        o.put("tint", tint)
        o.put("tonemap", toneMap.key)
        o.put("allowHdrInput", allowHdrInput)
        o.put("resolution", resolution.key)
        o.put("sharpness", sharpness)
        o.put("aiUpscale", aiUpscale)
        o.put("aiGpu", aiGpu)
        if (curves.isNotEmpty()) {
            val c = JSONObject()
            for ((k, pts) in curves) {
                val arr = org.json.JSONArray()
                for ((x, y) in pts) {
                    arr.put(org.json.JSONArray().put(x).put(y))
                }
                c.put(k, arr)
            }
            o.put("curves", c)
        }
        return o.toString()
    }

    companion object {

        fun fromJson(json: String?): ConvertConfig {
            if (json.isNullOrBlank()) return ConvertConfig()
            return try {
                val o = JSONObject(json)
                val curves = HashMap<String, List<Pair<Double, Double>>>()
                val c = o.optJSONObject("curves")
                if (c != null) {
                    for (k in c.keys()) {
                        val arr = c.optJSONArray(k) ?: continue
                        val pts = ArrayList<Pair<Double, Double>>(arr.length())
                        for (i in 0 until arr.length()) {
                            val p = arr.optJSONArray(i) ?: continue
                            if (p.length() >= 2) {
                                pts.add(Pair(p.optDouble(0), p.optDouble(1)))
                            }
                        }
                        if (pts.size >= 2) curves[k] = pts
                    }
                }
                ConvertConfig(
                    exposure = o.optDouble("exposure", 0.25),
                    highlight = o.optInt("highlight", 240),
                    saturation = o.optDouble("saturation", 1.25),
                    preset = o.optString("preset", "fast").ifBlank { "fast" },
                    platform = o.optString("platform", "none").ifBlank { "none" },
                    stripMeta = o.optBoolean("strip", false),
                    format = HdrFormat.fromKey(o.optString("format")),
                    dynamicMeta = DynamicMeta.fromKey(o.optString("dyn")),
                    codec = VideoCodec.fromKey(o.optString("codec")),
                    gpu = GpuDecode.fromKey(o.optString("gpu")),
                    threads = o.optInt("threads", 0),
                    contrast = o.optDouble("contrast", 1.0),
                    gamma = o.optDouble("gamma", 1.0),
                    brightness = o.optDouble("brightness", 0.0),
                    temperatureK = o.optInt("temperature", 6500),
                    tint = o.optDouble("tint", 0.0),
                    toneMap = ToneMapOp.fromKey(o.optString("tonemap")),
                    allowHdrInput = o.optBoolean("allowHdrInput", false),
                    resolution = OutputResolution.fromKey(o.optString("resolution")),
                    sharpness = o.optInt("sharpness", 0).coerceIn(0, 100),
                    aiUpscale = o.optInt("aiUpscale", 0).let { if (it == 2 || it == 3 || it == 4) it else 0 },
                    aiGpu = o.optBoolean("aiGpu", true),
                    curves = curves
                )
            } catch (_: Exception) {
                ConvertConfig()
            }
        }
    }
}

private fun List<Pair<Double, Double>>.isNeutral(): Boolean =
    size == 2 && this[0].first == 0.0 && this[0].second == 0.0 &&
        this[1].first == 1.0 && this[1].second == 1.0
