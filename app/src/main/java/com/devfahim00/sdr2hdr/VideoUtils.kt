package com.devfahim00.sdr2hdr

import com.antonkarpenko.ffmpegkit.FFprobeKit
import java.io.File
import java.util.Locale

data class VideoMeta(
    val width: Int,
    val height: Int,
    val frames: Int?,
    val fps: Double?,
    val durationSec: Double?,
    val isHdr: Boolean
)

object VideoUtils {

    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm")

    fun isVideoFile(f: File): Boolean =
        f.isFile && f.extension.lowercase(Locale.US) in VIDEO_EXTENSIONS

    fun isExcluded(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.startsWith("trashed-") || n.startsWith("output_hdr10")
    }

    fun classifyQuality(w: Int, h: Int): String {
        val maxDim = if (w > h) w else h
        val minDim = if (w < h) w else h
        return when {
            maxDim >= 3840 || minDim >= 2160 -> "UHD"
            maxDim >= 1920 || minDim >= 1080 -> "FHD"
            maxDim >= 1280 || minDim >= 720 -> "HD"
            else -> "SD"
        }
    }

    fun parseFps(raw: String?): Double? {
        if (raw.isNullOrEmpty() || raw == "N/A") return null
        val parts = raw.split("/")
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val den = parts.getOrNull(1)?.toDoubleOrNull()
        return if (den != null && den != 0.0) num / den else num
    }

    fun formatDuration(sec: Double?): String {
        if (sec == null || sec <= 0) return "N/A"
        val total = sec.toInt()
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    fun formatSize(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    /**
     * Probes a video with ffprobe. Mirrors the Termux script:
     * width/height/nb_frames/r_frame_rate from stream 0 and duration from format.
     */
    fun probeVideo(path: String): VideoMeta? {
        return try {
            val cmd = "-v error -select_streams v:0 " +
                "-show_entries stream=width,height,nb_frames,r_frame_rate,color_transfer,color_primaries,color_space " +
                "-show_entries format=duration " +
                "-of default=noprint_wrappers=1 \"$path\""
            val session = FFprobeKit.execute(cmd) ?: return null
            val out = session.allLogsAsString ?: ""
            if (out.isBlank()) return null
            val map = HashMap<String, String>()
            for (line in out.lines()) {
                val idx = line.indexOf('=')
                if (idx > 0) {
                    map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
            if (map.isEmpty()) return null
            val w = map["width"]?.toIntOrNull() ?: 0
            val h = map["height"]?.toIntOrNull() ?: 0
            val frames = map["nb_frames"]?.let { if (it == "N/A") null else it.toIntOrNull() }
            val fps = parseFps(map["r_frame_rate"])
            val duration = map["duration"]?.toDoubleOrNull()
            val hdr = listOf("color_transfer", "color_primaries", "color_space").any { key ->
                val v = map[key].orEmpty()
                v.contains("smpte2084", ignoreCase = true) ||
                    v.contains("arib-std-b67", ignoreCase = true)
            }
            VideoMeta(w, h, frames, fps, duration, hdr)
        } catch (e: Exception) {
            null
        }
    }
}
