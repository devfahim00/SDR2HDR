package com.devfahim00.sdr2hdr

import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFmpegSessionCompleteCallback
import com.antonkarpenko.ffmpegkit.LogCallback
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import com.devfahim00.sdr2hdr.databinding.ActivityConvertBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ConvertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConvertBinding
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var sessionId: Long = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var durationSec = 0.0
    private var totalFrames = 0L
    private var outFile: File? = null
    private var stopRequested = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val inputPath = intent.getStringExtra("path")
        if (inputPath == null) {
            fail("No input file specified.")
            return
        }
        val exposure = intent.getStringExtra("exposure") ?: "0.25"
        val highlight = intent.getStringExtra("highlight") ?: "240"
        val saturation = intent.getStringExtra("saturation") ?: "1.25"
        val preset = intent.getStringExtra("preset") ?: "fast"
        val platform = intent.getStringExtra("platform") ?: "none"
        val strip = intent.getBooleanExtra("strip", false)

        val input = File(inputPath)
        if (!input.isFile) {
            fail("Input file not found.")
            return
        }

        // Output: /sdcard/Movies/HDR10_Converted/output_HDR10[N].mp4
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "HDR10_Converted"
        )
        outDir.mkdirs()
        File(outDir, ".nomedia").delete()
        var name = "output_HDR10.mp4"
        var count = 1
        var out = File(outDir, name)
        while (out.exists()) {
            name = "output_HDR10_$count.mp4"
            out = File(outDir, name)
            count++
        }
        outFile = out

        binding.textSummary.text = buildString {
            append("Selected : ").append(input.name).append('\n')
            append("Output   : ").append(out.name).append('\n')
            append("Target   : ").append(platform)
            append("  |  Meta: ").append(if (strip) "Stripped" else "Preserved").append('\n')
            append("Grading  : Exp: ").append(exposure)
            append(" | High: ").append(highlight).append("nits")
            append(" | Sat: ").append(saturation).append('\n')
            append("Preset   : ").append(preset)
        }

        binding.btnStop.setOnClickListener {
            if (sessionId >= 0 && !stopRequested) {
                stopRequested = true
                binding.btnStop.isEnabled = false
                binding.textStatus.text = "Stopping..."
                FFmpegKit.cancel(sessionId)
            }
        }

        binding.textStatus.text = "Analyzing input..."

        executor.execute {
            val meta = VideoUtils.probeVideo(inputPath)
            main.post {
                if (isFinishing || isDestroyed) return@post
                when {
                    meta == null -> {
                        failUi("Could not read video metadata (ffprobe failed).")
                    }
                    meta.isHdr -> {
                        binding.textStatus.text = "Skipped"
                        AlertDialog.Builder(this)
                            .setTitle("Already HDR")
                            .setMessage("Input video is already HDR. Skipping conversion.")
                            .setPositiveButton("OK") { d, _ ->
                                d.dismiss()
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    else -> {
                        durationSec = meta.durationSec ?: 0.0
                        totalFrames = when (platform) {
                            "tiktok" -> (durationSec * 60).toLong()
                            "instagram" -> (durationSec * 30).toLong()
                            else -> meta.frames?.toLong()
                                ?: (durationSec * (meta.fps ?: 0.0)).toLong()
                        }
                        startEncode(
                            inputPath, out.absolutePath,
                            exposure, highlight, saturation,
                            preset, platform, strip
                        )
                    }
                }
            }
        }
    }

    private fun startEncode(
        input: String,
        output: String,
        exposure: String,
        highlight: String,
        saturation: String,
        preset: String,
        platform: String,
        stripMeta: Boolean
    ) {
        started = true
        acquireWake()
        binding.textStatus.text = "Encoding..."
        val command = FfmpegEngine.buildCommand(
            input, output, exposure, highlight, saturation, preset, platform, stripMeta
        )
        val session = FFmpegKit.executeAsync(
            command,
            FFmpegSessionCompleteCallback { s -> main.post { onFinished(s) } },
            LogCallback { },
            StatisticsCallback { st -> main.post { onStats(st) } }
        )
        sessionId = session.sessionId
    }

    private fun onStats(st: Statistics) {
        if (!started || isFinishing || isDestroyed) return
        val processedSec = st.time / 1000.0
        var pct = if (durationSec > 0) (processedSec / durationSec * 100).toInt() else 0
        if (pct < 0) pct = 0
        if (pct > 100) pct = 100
        binding.progressBar.progress = pct

        val frames = st.videoFrameNumber.toLong()
        val fps = st.videoFps.toDouble()
        val remaining = (durationSec - processedSec).coerceAtLeast(0.0)
        val speed = st.speed
        var etaSec = -1.0
        if (speed > 0.01) {
            etaSec = remaining / speed
        } else if (fps > 0.5 && totalFrames > frames) {
            etaSec = (totalFrames - frames) / fps
        }
        val etaStr = if (etaSec >= 0) {
            String.format(Locale.US, "%02d:%02d", (etaSec / 60).toInt(), (etaSec % 60).toInt())
        } else {
            "--:--"
        }
        binding.textStatus.text = String.format(
            Locale.US,
            "%d%%  |  %d/%d frames  |  %.1f fps  |  ETA %s",
            pct, frames, totalFrames, fps, etaStr
        )
    }

    private fun onFinished(session: FFmpegSession) {
        if (isFinishing || isDestroyed) return
        started = false
        releaseWake()
        binding.btnStop.isEnabled = false
        val rc = session.returnCode
        when {
            ReturnCode.isSuccess(rc) -> {
                binding.progressBar.progress = 100
                binding.textStatus.text = "Complete"
                outFile?.let {
                    MediaScannerConnection.scanFile(
                        this, arrayOf(it.absolutePath), arrayOf("video/mp4"), null
                    )
                }
                binding.textResult.visibility = View.VISIBLE
                binding.textResult.text = "[DONE] Saved to Movies/HDR10_Converted"
            }
            ReturnCode.isCancel(rc) || stopRequested -> {
                outFile?.delete()
                binding.textResult.visibility = View.VISIBLE
                binding.textResult.text = "[!] Conversion stopped by user"
            }
            else -> {
                outFile?.delete()
                val logs = session.allLogsAsString ?: ""
                binding.textStatus.text = "ERROR"
                binding.textError.visibility = View.VISIBLE
                binding.textError.text = logs.takeLast(1500)
                    .ifEmpty { "FFmpeg exited with code ${rc?.value ?: "unknown"}" }
            }
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sdr2hdr:convert")
                .also { it.acquire(3 * 60 * 60 * 1000L) }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun failUi(msg: String) {
        started = false
        binding.btnStop.isEnabled = false
        binding.textStatus.text = "ERROR"
        binding.textError.visibility = View.VISIBLE
        binding.textError.text = msg
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (started) {
            AlertDialog.Builder(this)
                .setTitle("Conversion running")
                .setMessage("Encoding is in progress. Keep the app open until it finishes. Stop and exit now?")
                .setPositiveButton("Stop & exit") { _, _ ->
                    stopRequested = true
                    if (sessionId >= 0) FFmpegKit.cancel(sessionId)
                    finish()
                }
                .setNegativeButton("Stay", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWake()
        executor.shutdown()
        if (started && sessionId >= 0) FFmpegKit.cancel(sessionId)
    }
}
