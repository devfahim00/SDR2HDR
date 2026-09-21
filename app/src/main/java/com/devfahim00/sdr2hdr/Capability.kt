package com.devfahim00.sdr2hdr

import android.content.Context
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Probes the bundled FFmpeg build once (app start, background thread) so the UI can enable /
 * disable options that actually exist on this device: MediaCodec encoders, Vulkan, libvpx /
 * libaom AV1, x265 Dolby Vision & HDR10+ support, and the optional colour filters.
 *
 * Everything defaults to the conservative "software" assumption and is corrected after the
 * probe finishes ([readyFlow]).
 */
object Capability {

    @Volatile var mediacodecHwaccel: Boolean = false
    @Volatile var vulkanHwaccel: Boolean = false

    @Volatile var hevcHwEncoder: Boolean = false
    @Volatile var h264HwEncoder: Boolean = false
    @Volatile var hevcHw10Bit: Boolean = false

    @Volatile var vp9Encoder: Boolean = false
    /** Name of the best available AV1 software encoder (libaom-av1 / libsvtav1) or null. */
    @Volatile var av1Encoder: String? = null

    @Volatile var dolbyVisionX265: Boolean = false
    @Volatile var hdr10PlusX265: Boolean = false

    @Volatile var filterColorTemperature: Boolean = false
    @Volatile var filterColorBalance: Boolean = false
    @Volatile var filterCurves: Boolean = false
    @Volatile var filterTonemap: Boolean = false

    val readyFlow = MutableStateFlow(false)
    @Volatile var ready: Boolean = false

    @Volatile private var started = false

    fun initAsync(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            try {
                probe(context.applicationContext)
            } catch (_: Exception) {
                // keep conservative defaults
            } finally {
                ready = true
                readyFlow.value = true
            }
        }, "capability-probe").start()
    }

    private fun probe(context: Context) {
        val encoders = listOutput("-hide_banner -encoders")
        val decoders = listOutput("-hide_banner -decoders")
        val hwaccels = listOutput("-hide_banner -hwaccels")
        val filters = listOutput("-hide_banner -filters")

        fun has(list: String, name: String): Boolean =
            list.lineSequence().any { it.trimStart().startsWith(name) || it.contains(" $name ") }

        vp9Encoder = has(encoders, "libvpx-vp9")
        av1Encoder = when {
            has(encoders, "libaom-av1") -> "libaom-av1"
            has(encoders, "libsvtav1") -> "libsvtav1"
            has(encoders, "av1_mediacodec") -> "av1_mediacodec"
            else -> null
        }

        mediacodecHwaccel = hwaccels.contains("mediacodec", ignoreCase = true) ||
            has(decoders, "h264_mediacodec") || has(decoders, "hevc_mediacodec")
        vulkanHwaccel = hwaccels.contains("vulkan", ignoreCase = true)

        hevcHwEncoder = has(encoders, "hevc_mediacodec")
        h264HwEncoder = has(encoders, "h264_mediacodec")

        filterColorTemperature = filters.lineSequence().any {
            val t = it.trimStart()
            t.startsWith("colortemperature") || (t.startsWith("T") && t.contains(" colortemperature "))
        }
        filterColorBalance = filters.lineSequence().any {
            val t = it.trimStart()
            t.startsWith("colorbalance") || (t.startsWith("TS") && t.contains(" colorbalance "))
        }
        filterCurves = filters.lineSequence().any {
            val t = it.trimStart()
            t.startsWith("curves") || (t.startsWith("T") && t.contains(" curves "))
        }
        filterTonemap = filters.lineSequence().any {
            val t = it.trimStart()
            t.startsWith("tonemap") || (t.startsWith("T") && t.contains(" tonemap "))
        }

        val cache = File(context.cacheDir, "probe")
        cache.mkdirs()
        try {
            dolbyVisionX265 = testEncode(
                cache, "dv", "-pix_fmt yuv420p10le -c:v libx265 -preset ultrafast " +
                    "-x265-params hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084" +
                    ":colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)" +
                    "WP(15635,16450)L(10000000,1):max-cll=240,240:dolby-vision-profile=8.1"
            )
            val json = File(cache, "hdr10plus.json")
            FfmpegEngine.writeHdr10PlusJson(json, 240)
            hdr10PlusX265 = testEncode(
                cache, "dhdr10", "-pix_fmt yuv420p10le -c:v libx265 -preset ultrafast " +
                    "-x265-params hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084" +
                    ":colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)" +
                    "WP(15635,16450)L(10000000,1):max-cll=240,240" +
                    ":dhdr10-info=" + json.absolutePath + ":dhdr10-opt=1"
            )
            if (hevcHwEncoder) {
                hevcHw10Bit = testEncode(
                    cache, "hevc10", "-pix_fmt p010le -c:v hevc_mediacodec -b:v 500k"
                )
                if (!hevcHw10Bit) {
                    // 8-bit fallback check keeps the chip enabled but flags limited signalling
                    hevcHwEncoder = testEncode(
                        cache, "hevc8", "-pix_fmt nv12 -c:v hevc_mediacodec -b:v 500k"
                    )
                }
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun listOutput(cmd: String): String {
        return try {
            val s = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(s.returnCode)) s.allLogsAsString ?: "" else ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Encodes 3 frames of a tiny synthetic clip: a fast, safe way to validate params. */
    private fun testEncode(dir: File, name: String, encoderArgs: String): Boolean {
        val out = File(dir, "probe_$name.mp4")
        return try {
            val cmd = "-y -hide_banner -loglevel error " +
                "-f lavfi -i color=c=gray:s=256x144:r=12:d=1 " +
                "-frames:v 3 $encoderArgs -f mp4 \"${out.absolutePath}\""
            val s = FFmpegKit.execute(cmd)
            val ok = ReturnCode.isSuccess(s.returnCode) && out.isFile && out.length() > 0
            out.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }
}
