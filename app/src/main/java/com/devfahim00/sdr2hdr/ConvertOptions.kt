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
