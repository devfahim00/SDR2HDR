package com.devfahim00.sdr2hdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AI upscale pre-stage. Turns the source into an upscaled SDR intermediate (Matroska) that the
 * normal HDR conversion then consumes as its input.
 *
 * Works in short chunks (~1 s): ffmpeg extracts RGB frames -> [AiUpscaler] -> JPEG -> ffmpeg
 * encodes a chunk. Only one chunk of frames is ever on disk. Blocking: call [run] from a
 * background thread; [pause] / [resume] / [cancel] may be called from any thread.
 */
class AiUpscalePipeline(
    private val context: Context,
    private val input: String,
    private val meta: VideoMeta,
    private val source: SourceColor,
    private val scale: Int,
    private val useGpu: Boolean,
    private val workDir: File,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(message: String)
        fun onProgress(done: Long, total: Long, fps: Double, etaSec: Double)
    }

    sealed class Result {
        data class Success(val file: File, val width: Int, val height: Int) : Result()
        object Cancelled : Result()
        data class Failed(val message: String, val log: String = "") : Result()
    }

    companion object {
        /** Largest output we accept (UHD 3840x2160) — bigger frames explode memory and time. */
        const val MAX_OUTPUT_PIXELS = 3840L * 2160L + 4096L
        private const val JPEG_QUALITY = 98
    }

    @Volatile private var cancelled = false
    @Volatile private var paused = false
    private val pauseLock = Object()

    fun pause() {
        paused = true
    }

    fun resume() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    fun cancel() {
        cancelled = true
        AiUpscaler.setCancel(true)
        try {
            FFmpegKit.cancel()
        } catch (_: Exception) {
        }
        resume()
    }

    private var pausedMs = 0L

    /** Blocks while paused; returns false when cancelled. */
    private fun checkpoint(): Boolean {
        if (cancelled) return false
        if (paused) {
            val t0 = SystemClock.elapsedRealtime()
            synchronized(pauseLock) {
                while (paused && !cancelled) pauseLock.wait(500)
            }
            pausedMs += SystemClock.elapsedRealtime() - t0
        }
        return !cancelled
    }

    private class Ff(val ok: Boolean, val log: String)

    private fun ffmpeg(cmd: String): Ff {
        val s = FFmpegKit.execute(cmd)
        return Ff(ReturnCode.isSuccess(s.returnCode), s.allLogsAsString ?: "")
    }

    fun run(): Result {
        AiUpscaler.setCancel(false)
        return try {
            runInternal()
        } catch (e: OutOfMemoryError) {
            Result.Failed("Ran out of memory while upscaling. Try a lower AI factor or a smaller video.")
        } catch (e: Throwable) {
            if (cancelled) Result.Cancelled else Result.Failed("AI upscale failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            AiUpscaler.release()
            AiUpscaler.setCancel(false)
        }
    }

    private fun runInternal(): Result {
        if (!AiUpscaler.isSupported) {
            return Result.Failed("AI upscale is not available on this device (64-bit ARM build required).")
        }

        var fps = meta.fps ?: 30.0
        if (fps < 1.0 || fps > 120.0) fps = 30.0
        val duration = meta.durationSec ?: 0.0
        val expected = if (duration > 0.0) max(1L, (duration * fps).roundToInt().toLong())
        else max(1L, (meta.frames ?: 1).toLong())
        val chunkFrames = fps.roundToInt().coerceIn(12, 30)

        val aiDir = File(workDir, "ai").apply { deleteRecursively(); mkdirs() }
        val inDir = File(aiDir, "in").apply { mkdirs() }
        val upDir = File(aiDir, "up").apply { mkdirs() }
        val chunks = ArrayList<File>()

        listener.onStatus("Loading AI model...")
        val backend = AiUpscaler.load(context, scale, useGpu)
        if (backend == AiUpscaler.Backend.FAILED) {
            return Result.Failed("Could not load the AI upscale model.")
        }
        val device = AiUpscaler.device()
        val label = "AI upscale ${scale}x · ${if (backend == AiUpscaler.Backend.GPU) "GPU" else "CPU"}"
        listener.onStatus(label)

        var outBmp: Bitmap? = null
        var outW = 0
        var outH = 0
        var done = 0L
        var startMs = SystemClock.elapsedRealtime()
        pausedMs = 0L

        try {
            var chunkIndex = 0
            while (true) {
                if (!checkpoint()) return Result.Cancelled

                inDir.listFiles()?.forEach { it.delete() }
                upDir.listFiles()?.forEach { it.delete() }

                val startSec = done / fps
                val ex = ffmpeg(
                    AiUpscaleCommands.extractFrames(
                        input, startSec, chunkFrames, fps,
                        source.matrix, source.primaries, source.transfer, source.fullRange,
                        File(inDir, AiUpscaleCommands.FRAME_IN).absolutePath
                    )
                )
                if (cancelled) return Result.Cancelled
                val files = inDir.listFiles { f -> f.name.startsWith("in_") && f.name.endsWith(".png") }
                    ?.sortedBy { it.name } ?: emptyList()
                if (files.isEmpty()) {
                    // End of stream — unless decoding broke long before the expected end.
                    if (chunks.isEmpty() || (!ex.ok && done < expected - 2)) {
                        return Result.Failed("Could not decode frames from the video.", ex.log.takeLast(1500))
                    }
                    break
                }

                for (f in files) {
                    if (!checkpoint()) return Result.Cancelled

                    val inBmp = BitmapFactory.decodeFile(
                        f.absolutePath,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    ) ?: return Result.Failed("Could not read extracted frame.")

                    if (outBmp == null) {
                        outW = inBmp.width * scale
                        outH = inBmp.height * scale
                        if (outW.toLong() * outH > MAX_OUTPUT_PIXELS) {
                            val msg = "AI upscale ${scale}x would produce ${outW}x$outH — over 4K. " +
                                "Use a lower factor or a smaller source."
                            inBmp.recycle()
                            return Result.Failed(msg)
                        }
                        val estBytes = (expected * outW.toLong() * outH * 0.03).toLong()
                        val need = estBytes * 2 + 400L * 1024 * 1024
                        val free = context.cacheDir.usableSpace
                        if (free < need) {
                            inBmp.recycle()
                            return Result.Failed(
                                "Not enough free storage: about ${need / 1048576} MB needed, ${free / 1048576} MB free."
                            )
                        }
                        outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        AiUpscaler.setTile(inBmp.width, inBmp.height)
                        listener.onStatus("$label · ${inBmp.width}x${inBmp.height} → ${outW}x$outH · $device")
                        startMs = SystemClock.elapsedRealtime()
                        pausedMs = 0L
                    }

                    val bmp = outBmp!!
                    val rc = AiUpscaler.upscale(inBmp, bmp)
                    inBmp.recycle()
                    if (rc == -2 || cancelled) return Result.Cancelled
                    if (rc != 0) return Result.Failed("AI upscale failed on a frame (code $rc).")

                    val n = f.name.removePrefix("in_").removeSuffix(".png")
                    FileOutputStream(File(upDir, "up_$n.jpg")).use { os ->
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) {
                            return Result.Failed("Could not write upscaled frame.")
                        }
                    }
                    f.delete()

                    done++
                    val elapsed = (SystemClock.elapsedRealtime() - startMs - pausedMs) / 1000.0
                    val rate = if (elapsed > 0.5) done / elapsed else 0.0
                    val eta = if (rate > 0.0) max(0.0, (expected - done) / rate) else -1.0
                    listener.onProgress(done, max(expected, done), rate, eta)
                }

                // Encode this chunk
                val chunk = File(aiDir, String.format("chunk_%04d.mp4", chunkIndex))
                val en = ffmpeg(
                    AiUpscaleCommands.encodeChunk(
                        File(upDir, AiUpscaleCommands.FRAME_OUT).absolutePath, fps,
                        source.matrix, source.primaries, source.transfer, chunk.absolutePath
                    )
                )
                if (cancelled) return Result.Cancelled
                if (!en.ok || !chunk.isFile || chunk.length() < 512) {
                    return Result.Failed("Encoding the upscaled frames failed.", en.log.takeLast(1500))
                }
                chunks.add(chunk)
                chunkIndex++

                if (files.size < chunkFrames) break // stream ended inside this chunk
            }
        } finally {
            outBmp?.recycle()
            inDir.deleteRecursively()
            upDir.deleteRecursively()
        }

        if (chunks.isEmpty()) return Result.Failed("No frames were upscaled.")
        if (!checkpoint()) return Result.Cancelled

        listener.onStatus("Joining upscaled video...")
        val list = File(aiDir, "chunks.txt")
        list.writeText(chunks.joinToString("\n") { AiUpscaleCommands.concatLine(it.absolutePath) } + "\n")
        val merged = File(aiDir, "upscaled.mkv")
        val mx = ffmpeg(AiUpscaleCommands.concatMux(list.absolutePath, input, merged.absolutePath))
        if (cancelled) return Result.Cancelled
        if (!mx.ok || !merged.isFile || merged.length() < 1024) {
            return Result.Failed("Joining the upscaled video failed.", mx.log.takeLast(1500))
        }
        chunks.forEach { it.delete() }
        list.delete()
        return Result.Success(merged, outW, outH)
    }
}
