package com.devfahim00.sdr2hdr

/**
 * Builds the SDR -> HDR10 ffmpeg command. Byte-for-byte parity with the
 * original Termux sdr2hdr.sh filter chain and x265 HDR10 signaling.
 */
object FfmpegEngine {

    fun buildCommand(
        input: String,
        output: String,
        exposure: String,
        highlight: String,
        saturation: String,
        preset: String,
        platform: String,
        stripMeta: Boolean
    ): String {
        val vfBase = "scale=trunc(iw/2)*2:trunc(ih/2)*2" +
            ",eq=saturation=$saturation" +
            ",zscale=rin=tv:r=full:d=none" +
            ",format=gbrpf32le" +
            ",exposure=$exposure" +
            ",zscale=primaries=bt2020:transfer=smpte2084:matrix=bt2020nc:npl=203" +
            ",format=yuv420p10le"

        val vf = when (platform) {
            "tiktok" -> "fps=60,$vfBase"
            "instagram" -> "fps=30,$vfBase"
            else -> vfBase
        }

        val sb = StringBuilder("-y -hide_banner -loglevel error")
        sb.append(" -i \"").append(input).append("\"")
        sb.append(" -vf \"").append(vf).append("\"")
        sb.append(" -c:v libx265 -preset ").append(preset).append(" -crf 20")
        when (platform) {
            "tiktok" -> sb.append(" -maxrate 16M -bufsize 32M")
            "instagram" -> sb.append(" -maxrate 14M -bufsize 28M")
        }
        sb.append(" -tag:v hvc1")
        sb.append(" -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc")
        sb.append(" -x265-params \"hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084" +
            ":colormatrix=bt2020nc" +
            ":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)" +
            ":max-cll=$highlight,$highlight\"")
        if (platform == "tiktok" || platform == "instagram") {
            sb.append(" -c:a aac -b:a 192k -ar 48000")
        } else {
            sb.append(" -c:a copy")
        }
        if (stripMeta) {
            sb.append(" -map_metadata -1")
        }
        sb.append(" -movflags +faststart \"").append(output).append("\"")
        return sb.toString()
    }
}
