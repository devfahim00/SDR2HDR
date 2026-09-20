package com.devfahim00.sdr2hdr

/**
 * Colorimetry of the *source* video, expressed as zscale option values.
 *
 * Phone videos are frequently untagged (or tagged with a bogus "gbr" matrix). zscale then
 * fails with `code 1026: YUV color family cannot have RGB matrix coefficients` or
 * `code 3074: no path between colorspaces`. Telling zscale explicitly what the source is
 * removes the dependence on those tags.
 */
data class SourceColor(
    val matrix: String,
    val primaries: String,
    val transfer: String,
    val fullRange: Boolean
) {
    companion object {
        /** Safe default: BT.709 limited range (what virtually all HD phone video is). */
        val BT709 = SourceColor("bt709", "bt709", "bt709", false)

        /** Build from probed metadata; unknown / bogus tags fall back to sensible defaults. */
        fun from(meta: VideoMeta?): SourceColor {
            if (meta == null) return BT709

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

            val trc = meta.colorTransfer?.lowercase()
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
 * Builds the SDR -> HDR10 ffmpeg command. Same filter chain and x265 HDR10 signaling as the
 * original Termux sdr2hdr.sh, plus explicit source colorimetry so untagged / oddly tagged
 * phone videos convert instead of failing inside zscale.
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
        stripMeta: Boolean,
        source: SourceColor = SourceColor.BT709,
        forceFrameProps: Boolean = false
    ): String {
        // Step 1: YUV (whatever the source is) -> full-range RGB float, with the source
        // colorimetry stated explicitly on both the input and the output side.
        val toRgb = "zscale=rin=${if (source.fullRange) "full" else "tv"}:r=full" +
            ":min=${source.matrix}:pin=${source.primaries}:tin=${source.transfer}" +
            ":m=gbr:p=${source.primaries}:t=${source.transfer}:d=none"

        // Step 2: RGB float -> BT.2020 / PQ / 10-bit YUV
        val toHdr = "zscale=rin=full:min=gbr:pin=${source.primaries}:tin=${source.transfer}" +
            ":primaries=bt2020:transfer=smpte2084:matrix=bt2020nc:npl=203"

        // Optional second line of defence (used on retry): also overwrite the colour tags on the
        // decoded frames themselves, so zscale never sees a bogus "gbr" / unspecified tag.
        val frameProps = if (forceFrameProps) {
            val trc = when (source.transfer) {
                "2020_10" -> "bt2020-10"
                "2020_12" -> "bt2020-12"
                else -> source.transfer
            }
            ",setparams=colorspace=${source.matrix}:color_primaries=${source.primaries}" +
                ":color_trc=$trc:range=${if (source.fullRange) "pc" else "tv"}"
        } else {
            ""
        }

        val vfBase = "scale=trunc(iw/2)*2:trunc(ih/2)*2" +
            ",eq=saturation=$saturation" +
            frameProps +
            ",$toRgb" +
            ",format=gbrpf32le" +
            ",exposure=$exposure" +
            ",$toHdr" +
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
