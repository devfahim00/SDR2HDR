package com.devfahim00.sdr2hdr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFmpegSessionCompleteCallback
import com.antonkarpenko.ffmpegkit.LogCallback
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivityConvertBinding
import com.devfahim00.sdr2hdr.databinding.ItemKvBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ConvertActivity : AppCompatActivity() {

    private class Params(
        val input: String,
        val output: String,
        val exposure: String,
        val highlight: String,
        val saturation: String,
        val preset: String,
        val platform: String,
        val strip: Boolean
    )

    private lateinit var binding: ActivityConvertBinding
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var sessionId: Long = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var durationSec = 0.0
    private var totalFrames = 0L
    private var outFile: File? = null
    private var outUri: Uri? = null
    private var stopRequested = false
    private var started = false

    private var params: Params? = null
    private var inputMeta: VideoMeta? = null
    private var retried = false
    private var lastLogs = ""

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
        params = Params(
            inputPath, out.absolutePath, exposure, highlight, saturation, preset, platform, strip
        )

        Glide.with(binding.imageThumb)
            .load(input)
            .frame(1_000_000L)
            .centerCrop()
            .into(binding.imageThumb)
        binding.textSourceName.text = input.name

        addKv("Output", out.name)
        addKv(
            "Target",
            when (platform) {
                "tiktok" -> "TikTok"
                "instagram" -> "Instagram"
                else -> "None"
            }
        )
        addKv("Metadata", if (strip) "Stripped" else "Preserved")
        addKv("Grading", "Exp $exposure  ·  High ${highlight}nits  ·  Sat $saturation")
        addKv("Preset", preset)

        binding.btnStop.setOnClickListener {
            if (sessionId >= 0 && !stopRequested) {
                stopRequested = true
                binding.btnStop.isEnabled = false
                binding.textStatus.text = "Stopping..."
                FFmpegKit.cancel(sessionId)
            }
        }
        binding.btnDone.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { playResult() }
        binding.btnCopyLog.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("SDR2HDR log", lastLogs))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
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
                        MaterialAlertDialogBuilder(this)
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
                        inputMeta = meta
                        durationSec = meta.durationSec ?: 0.0
                        totalFrames = when (platform) {
                            "tiktok" -> (durationSec * 60).toLong()
                            "instagram" -> (durationSec * 30).toLong()
                            else -> meta.frames?.toLong()
                                ?: (durationSec * (meta.fps ?: 0.0)).toLong()
                        }
                        val src = SourceColor.from(meta)
                        addKv(
                            "Source",
                            "${src.matrix}  ·  ${if (src.fullRange) "full" else "limited"} range"
                        )
                        startEncode(src)
                    }
                }
            }
        }
    }

    private fun addKv(key: String, value: String) {
        val row = ItemKvBinding.inflate(layoutInflater, binding.containerSummary, false)
        row.textKey.text = key
        row.textValue.text = value
        binding.containerSummary.addView(row.root)
    }

    private fun startEncode(source: SourceColor) {
        val p = params ?: return
        started = true
        stopRequested = false
        binding.btnStop.isEnabled = true
        acquireWake()
        binding.textStatus.text = "Encoding..."
        val command = FfmpegEngine.buildCommand(
            p.input, p.output, p.exposure, p.highlight, p.saturation,
            p.preset, p.platform, p.strip, source
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
        binding.progressBar.setProgressCompat(pct, true)
        binding.textPercent.text = "$pct%"

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
        binding.textStatus.text = "Encoding..."
        binding.textFrames.text =
            if (totalFrames > 0) "$frames / $totalFrames" else frames.toString()
        binding.textFps.text = String.format(Locale.US, "%.1f fps", fps)
        binding.textEta.text = etaStr
    }

    private fun onFinished(session: FFmpegSession) {
        if (isFinishing || isDestroyed) return
        val rc = session.returnCode
        val logs = session.allLogsAsString ?: ""

        val failed = !ReturnCode.isSuccess(rc) && !ReturnCode.isCancel(rc) && !stopRequested

        // A zscale colour-space failure with the probed profile: retry once with a plain
        // BT.709 profile before giving up.
        if (failed && !retried && isColorError(logs) &&
            SourceColor.from(inputMeta) != SourceColor.BT709
        ) {
            retried = true
            outFile?.delete()
            binding.textStatus.text = "Retrying with safe colour profile..."
            startEncode(SourceColor.BT709)
            return
        }

        started = false
        releaseWake()
        binding.btnStop.isEnabled = false
        binding.btnStop.visibility = View.GONE
        binding.btnDone.visibility = View.VISIBLE

        when {
            ReturnCode.isSuccess(rc) -> {
                binding.progressBar.setProgressCompat(100, true)
                binding.textPercent.text = "100%"
                binding.textStatus.text = "Complete"
                outFile?.let {
                    MediaScannerConnection.scanFile(
                        this, arrayOf(it.absolutePath), arrayOf("video/mp4")
                    ) { _, uri ->
                        main.post {
                            if (!isFinishing && !isDestroyed && uri != null) {
                                outUri = uri
                                binding.btnPlay.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                showResult(
                    ok = true,
                    text = "Saved to Movies/HDR10_Converted/${outFile?.name ?: ""}"
                )
            }
            ReturnCode.isCancel(rc) || stopRequested -> {
                outFile?.delete()
                binding.textStatus.text = "Stopped"
                showResult(ok = false, text = "Conversion stopped by user")
            }
            else -> {
                outFile?.delete()
                lastLogs = logs.takeLast(1500)
                    .ifEmpty { "FFmpeg exited with code ${rc?.value ?: "unknown"}" }
                binding.textStatus.text = "ERROR"
                val summary = if (isColorError(logs)) {
                    "The colour information of this video could not be processed, even after " +
                        "retrying with a safe profile. Please copy the log below and report it."
                } else {
                    "FFmpeg exited with code ${rc?.value ?: "unknown"}."
                }
                showError(summary, lastLogs)
            }
        }
    }

    private fun isColorError(logs: String): Boolean =
        logs.contains("zscale", ignoreCase = true) ||
            logs.contains("color family", ignoreCase = true) ||
            logs.contains("no path between colorspaces", ignoreCase = true)

    private fun showResult(ok: Boolean, text: String) {
        binding.cardResult.visibility = View.VISIBLE
        binding.textResult.text = text
        if (!ok) {
            binding.iconResult.setImageResource(R.drawable.ic_error)
            binding.iconResult.setColorFilter(ContextCompat.getColor(this, R.color.yellow))
            binding.cardResult.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg))
            binding.cardResult.strokeColor = ContextCompat.getColor(this, R.color.outline)
        }
    }

    private fun showError(summary: String, log: String) {
        lastLogs = log
        binding.cardError.visibility = View.VISIBLE
        binding.textErrorSummary.text = summary
        binding.textError.text = log
        binding.textError.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
        binding.btnCopyLog.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
    }

    private fun playResult() {
        val uri = outUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
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
        binding.btnStop.visibility = View.GONE
        binding.btnDone.visibility = View.VISIBLE
        binding.textStatus.text = "ERROR"
        showError(msg, "")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (started) {
            MaterialAlertDialogBuilder(this)
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
