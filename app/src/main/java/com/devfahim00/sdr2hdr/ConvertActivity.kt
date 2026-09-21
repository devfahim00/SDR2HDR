package com.devfahim00.sdr2hdr

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivityConvertBinding
import com.devfahim00.sdr2hdr.databinding.DialogCompareBinding
import com.devfahim00.sdr2hdr.databinding.ItemKvBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shows the live state of the conversion owned by [ConvertService]. The activity can be
 * closed at any moment — the conversion keeps running in the background (foreground
 * service) and this screen simply re-attaches to the shared state flow.
 */
class ConvertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConvertBinding
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var config: ConvertConfig = ConvertConfig()
    private var inputPath: String? = null
    private var outUri: Uri? = null
    private var lastState: ConversionState? = null
    private var sourceRowAdded = false
    private var alreadyHdrShown = false
    private var resultShown = false
    private var compareBusy = false
    private var pulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        inputPath = intent.getStringExtra("path")
        if (inputPath == null) {
            fail("No input file specified.")
            return
        }
        config = ConvertConfig.fromJson(intent.getStringExtra("config"))
        val input = File(inputPath!!)

        Glide.with(binding.imageThumb)
            .load(input)
            .frame(1_000_000L)
            .centerCrop()
            .into(binding.imageThumb)
        binding.textSourceName.text = input.name

        // Summary rows from the config (the service fills in the "Source" row post-probe)
        addKv("Target", when (config.platform) {
            "tiktok" -> "TikTok"
            "instagram" -> "Instagram"
            else -> "None"
        })
        addKv("Format", formatLabel())
        addKv("Codec", config.codec.label)
        addKv("Grading", "Exp ${fmt2(config.exposure)}  ·  High ${config.highlight}nits  ·  Sat ${fmt2(config.saturation)}")
        addKv("Preset", config.preset)
        if (config.gpu != GpuDecode.OFF) addKv("Decode", config.gpu.label)
        if (config.threads > 0) addKv("Threads", config.threads.toString())
        addKv("Metadata", if (config.stripMeta) "Stripped" else "Preserved")

        binding.btnPause.setOnClickListener {
            val paused = lastState?.phase == Phase.PAUSED
            ConvertService.send(
                this,
                if (paused) ConvertService.ACTION_RESUME else ConvertService.ACTION_PAUSE
            )
        }
        binding.btnStop.setOnClickListener {
            ConvertService.send(this, ConvertService.ACTION_STOP)
        }
        binding.btnDone.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { playResult() }
        binding.btnCompare.setOnClickListener { showCompare() }
        binding.btnCopyLog.setOnClickListener {
            val log = lastState?.logTail ?: ""
            if (log.isNotBlank()) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("SDR2HDR log", log))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }

        setStatus("Preparing...")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConvertService.state.collect { state -> render(state) }
            }
        }
    }

    // ── state rendering ─────────────────────────────────────────────────────────

    private fun render(state: ConversionState) {
        if (isFinishing || isDestroyed) return
        lastState = state

        if (!sourceRowAdded && state.sourceInfo.isNotEmpty()) {
            sourceRowAdded = true
            addKv("Source", state.sourceInfo)
        }

        when (state.phase) {
            Phase.IDLE -> {
                setStatus("No conversion in progress.", R.color.amber)
                binding.btnDone.visibility = View.VISIBLE
                binding.btnStop.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
            }

            Phase.PROBING -> {
                startPulse()
                setStatus(state.message, R.color.amber)
                binding.btnStop.isEnabled = true
                binding.btnStop.visibility = View.VISIBLE
                binding.btnPause.visibility = View.GONE
                binding.btnDone.visibility = View.GONE
            }

            Phase.RUNNING -> {
                startPulse()
                setStatus(state.message)
                binding.progressBar.setProgressCompat(state.progress, true)
                binding.textPercent.text = "${state.progress}%"
                binding.btnPause.visibility = View.VISIBLE
                setPauseButton(paused = false)
                binding.btnStop.isEnabled = true
                binding.btnStop.visibility = View.VISIBLE
                binding.btnDone.visibility = View.GONE
                updateStats(state)
            }

            Phase.PAUSED -> {
                stopPulse()
                setStatus(state.message, R.color.amber)
                binding.btnPause.visibility = View.VISIBLE
                setPauseButton(paused = true)
                binding.btnStop.isEnabled = true
                binding.btnStop.visibility = View.VISIBLE
                binding.btnDone.visibility = View.GONE
            }

            Phase.FINALIZING -> {
                startPulse()
                setStatus(state.message)
                binding.progressBar.setProgressCompat(100, true)
                binding.textPercent.text = "100%"
                binding.btnPause.visibility = View.GONE
                binding.btnStop.isEnabled = false
            }

            Phase.DONE -> {
                stopPulse()
                if (!resultShown) {
                    resultShown = true
                    binding.progressBar.setProgressCompat(100, true)
                    binding.textPercent.text = "100%"
                    binding.progressBar.setIndicatorColor(ContextCompat.getColor(this, R.color.green))
                    setStatus("Complete")
                    val outName = state.output?.let { File(it).name } ?: ""
                    showResult(ok = true, text = "Saved to Movies/HDR10_Converted/$outName")
                    binding.btnPlay.visibility = View.VISIBLE
                    binding.btnCompare.visibility = View.VISIBLE
                    scanResult(state.output)
                }
                binding.btnStop.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
                binding.btnDone.visibility = View.VISIBLE
            }

            Phase.FAILED -> {
                stopPulse()
                if (state.alreadyHdr && !alreadyHdrShown) {
                    alreadyHdrShown = true
                    setStatus("Skipped", R.color.amber)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Already HDR")
                        .setMessage("Input video is already HDR. Skipping conversion.\n\nEnable 'Allow HDR input' in advanced mode to regrade it.")
                        .setPositiveButton("OK") { d, _ ->
                            d.dismiss()
                            finish()
                        }
                        .setCancelable(false)
                        .show()
                } else if (!resultShown) {
                    resultShown = true
                    setStatus("ERROR", R.color.red)
                    val summary = state.message.ifBlank { "Conversion failed." }
                    showError(summary, state.logTail)
                }
                binding.btnStop.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
                binding.btnDone.visibility = View.VISIBLE
            }

            Phase.CANCELLED -> {
                stopPulse()
                if (!resultShown) {
                    resultShown = true
                    setStatus("Process stopped · incomplete video deleted.", R.color.amber)
                    showResult(ok = false, text = "Conversion stopped by user")
                }
                binding.btnStop.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
                binding.btnDone.visibility = View.VISIBLE
            }
        }
    }

    private fun updateStats(state: ConversionState) {
        val frames = state.frames
        binding.textFrames.text =
            if (state.totalFrames > 0) "$frames / ${state.totalFrames}" else frames.toString()
        binding.textFps.text = String.format(Locale.US, "%.1f", state.fps)
        binding.textEta.text = if (state.etaSec >= 0) {
            String.format(Locale.US, "%02d:%02d", (state.etaSec / 60).toInt(), (state.etaSec % 60).toInt())
        } else {
            "--:--"
        }
    }

    private fun setPauseButton(paused: Boolean) {
        if (paused) {
            binding.btnPause.text = "Resume"
            binding.btnPause.setIconResource(R.drawable.ic_play)
        } else {
            binding.btnPause.text = "Pause"
            binding.btnPause.setIconResource(R.drawable.ic_pause)
        }
    }

    private fun formatLabel(): String = when {
        config.dynamicMeta == DynamicMeta.DOLBY_VISION -> "Dolby Vision 8.1"
        config.dynamicMeta == DynamicMeta.HDR10PLUS -> "HDR10+ (dynamic)"
        config.format == HdrFormat.HLG -> "HLG · BT.2100"
        else -> "HDR10 · PQ"
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    // ── result / compare / play ─────────────────────────────────────────────────

    private fun scanResult(path: String?) {
        if (path == null) return
        try {
            MediaScannerConnection.scanFile(
                this, arrayOf(path), arrayOf("video/mp4")
            ) { _, uri ->
                main.post {
                    if (!isFinishing && !isDestroyed && uri != null) outUri = uri
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun playResult() {
        val path = lastState?.output
        val uri = outUri ?: Uri.fromFile(File(path ?: return))
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

    /** Side-by-side compare of the same frame from the source and the converted file. */
    private fun showCompare() {
        val input = inputPath ?: return
        val output = lastState?.output ?: return
        if (compareBusy) return
        compareBusy = true
        Toast.makeText(this, "Rendering comparison...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                val dur = lastState?.durationSec ?: 0.0
                val t = if (dur > 0) minOf(1.0, dur / 3.0) else 1.0
                val left = File(cacheDir, "cmp_left.png")
                val right = File(cacheDir, "cmp_right.png")
                left.delete(); right.delete()

                val okL = runFfmpeg(FfmpegEngine.buildPlainFrameCommand(input, t, left.absolutePath))
                val okR = runFfmpeg(
                    FfmpegEngine.buildOutputFrameCommand(output, config.format, t, right.absolutePath)
                )
                val bmL = if (okL) decodeScaled(left) else null
                val bmR = if (okR) decodeScaled(right) else null

                main.post {
                    if (!isFinishing && !isDestroyed) {
                        if (bmL != null && bmR != null) {
                            val view = DialogCompareBinding.inflate(layoutInflater)
                            view.textCompareTitle.text = "SDR vs ${formatLabel()}"
                            view.imgLeft.setImageBitmap(bmL)
                            view.imgRight.setImageBitmap(bmR)
                            view.labelLeft.text = "SDR SOURCE"
                            view.labelRight.text = "HDR OUTPUT"
                            view.textCompareHint.text =
                                "Output tonemapped for this screen · open the file on an HDR display for the real thing"
                            MaterialAlertDialogBuilder(this)
                                .setView(view.root)
                                .setPositiveButton("Close", null)
                                .show()
                        } else {
                            Toast.makeText(this, "Comparison render failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                compareBusy = false
            }
        }
    }

    private fun runFfmpeg(cmd: String): Boolean = try {
        val s = com.antonkarpenko.ffmpegkit.FFmpegKit.execute(cmd)
        com.antonkarpenko.ffmpegkit.ReturnCode.isSuccess(s.returnCode)
    } catch (_: Exception) {
        false
    }

    private fun decodeScaled(file: File, targetWidth: Int = 1280): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── small UI helpers ────────────────────────────────────────────────────────

    private fun setStatus(text: String, @ColorRes color: Int = R.color.green) {
        binding.textStatus.text = text
        binding.textStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofFloat(binding.textStatus, "alpha", 1f, 0.45f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        binding.textStatus.alpha = 1f
    }

    private fun addKv(key: String, value: String) {
        val row = ItemKvBinding.inflate(layoutInflater, binding.containerSummary, false)
        row.textKey.text = key
        row.textValue.text = value
        binding.containerSummary.addView(row.root)
    }

    private fun showResult(ok: Boolean, text: String) {
        binding.cardResult.visibility = View.VISIBLE
        binding.textResult.text = text
        if (!ok) {
            binding.iconResult.setImageResource(R.drawable.ic_error)
            binding.iconResult.setColorFilter(ContextCompat.getColor(this, R.color.amber))
            binding.cardResult.setCardBackgroundColor(ContextCompat.getColor(this, R.color.amber_bg))
            binding.cardResult.strokeColor = ContextCompat.getColor(this, R.color.amber_stroke)
        }
    }

    private fun showError(summary: String, log: String) {
        binding.cardError.visibility = View.VISIBLE
        binding.textErrorSummary.text = summary
        binding.textError.text = log
        binding.textError.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
        binding.btnCopyLog.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val phase = lastState?.phase
        if (phase == Phase.PROBING || phase == Phase.RUNNING ||
            phase == Phase.PAUSED || phase == Phase.FINALIZING
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Conversion running")
                .setMessage(
                    "The conversion continues in the background (with progress, pause & stop in the notification). " +
                        "What do you want to do?"
                )
                .setPositiveButton("Keep in background") { _, _ -> finish() }
                .setNegativeButton("Stay", null)
                .setNeutralButton("Stop & exit") { _, _ ->
                    ConvertService.send(this, ConvertService.ACTION_STOP)
                    finish()
                }
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        executor.shutdown()
    }
}
