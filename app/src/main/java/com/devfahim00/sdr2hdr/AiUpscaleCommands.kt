package com.devfahim00.sdr2hdr

import java.util.Locale

/**
 * ffmpeg commands for the AI-upscale pre-stage. Pure string logic (no Android classes) so it
 * can be unit-tested on a desktop.
 *
 * The pre-stage works on short chunks: frames are extracted as RGB PNGs, upscaled by
 * [AiUpscaler], written as JPEGs and encoded into a high-quality intermediate chunk. The
 * colorimetry stays the source's (same matrix / primaries / transfer, limited range) so the
 * regular HDR chain can consume the intermediate exactly as if it were the original.
 */
object AiUpscaleCommands {

    const val FRAME_IN = "in_%05d.png"
    const val FRAME_OUT = "up_%05d.jpg"

    private fun num(v: Double): String = String.format(Locale.US, "%.5f", v)

    /** zscale transfer name -> ffmpeg `-color_trc` name. */
    fun containerTrc(transfer: String): String = when (transfer) {
        "2020_10" -> "bt2020-10"
        "2020_12" -> "bt2020-12"
        "bt470bg" -> "gamma28"
        "bt470m" -> "gamma22"
        else -> transfer
    }

    /**
     * Decodes [frames] frames starting at [startSec] into full-range RGB24 PNGs.
     * (`format=gbrp` before `rgb24` matters: without it zscale negotiates a YUV output and
     * fails with "YUV color family cannot have RGB matrix coefficients".)
     */
    fun extractFrames(
        input: String,
        startSec: Double,
        frames: Int,
        fps: Double,
        matrix: String,
        primaries: String,
        transfer: String,
        fullRange: Boolean,
        outPattern: String
    ): String {
        val sb = StringBuilder("-y -hide_banner -loglevel error")
        if (startSec > 0.0005) sb.append(" -ss ").append(num(startSec))
        sb.append(" -i \"").append(input).append("\"")
        sb.append(" -map 0:v:0 -frames:v ").append(frames)
        sb.append(" -vf \"fps=").append(num(fps))
        sb.append(",zscale=rin=").append(if (fullRange) "full" else "tv").append(":r=full")
            .append(":min=").append(matrix).append(":pin=").append(primaries).append(":tin=").append(transfer)
            .append(":m=gbr:p=").append(primaries).append(":t=").append(transfer).append(":d=none")
        sb.append(",format=gbrp,format=rgb24\"")
        sb.append(" -c:v png -compression_level 1 -start_number 1 \"").append(outPattern).append("\"")
        return sb.toString()
    }

    /** Encodes upscaled JPEG frames into one intermediate chunk (H.264, near-lossless, limited range). */
    fun encodeChunk(
        framesPattern: String,
        fps: Double,
        matrix: String,
        primaries: String,
        transfer: String,
        out: String
    ): String =
        "-y -hide_banner -loglevel error -framerate ${num(fps)} -start_number 1 -i \"$framesPattern\"" +
            " -vf \"format=gbrp,scale=trunc(iw/2)*2:trunc(ih/2)*2," +
            "zscale=rin=full:min=gbr:pin=$primaries:tin=$transfer:m=$matrix:p=$primaries:t=$transfer:r=tv," +
            "format=yuv420p\"" +
            " -c:v libx264 -preset superfast -crf 14 -pix_fmt yuv420p" +
            " -color_primaries $primaries -color_trc ${containerTrc(transfer)} -colorspace $matrix" +
            " -color_range tv -an \"$out\""

    /** Joins the chunks and adds the original audio + metadata (Matroska accepts any audio codec). */
    fun concatMux(listFile: String, original: String, out: String): String =
        "-y -hide_banner -loglevel error -f concat -safe 0 -i \"$listFile\" -i \"$original\"" +
            " -map 0:v:0 -map 1:a? -c:v copy -c:a copy -map_metadata 1 \"$out\""

    /** Line for the concat demuxer list file. */
    fun concatLine(path: String): String = "file '" + path.replace("'", "'\\''") + "'"
}
