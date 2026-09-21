package com.devfahim00.sdr2hdr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivitySettingsBinding
import com.devfahim00.sdr2hdr.databinding.DialogCompareBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private companion object {
        // Slider ranges (same as the HDR10 Studio web UI)
        const val EXP_MIN = -3.0
        const val EXP_STEP = 0.05
        const val HL_MIN = 100
        const val HL_STEP = 10
        const val SAT_STEP = 0.05

        // Defaults (same as the original sdr2hdr.sh script)
        const val DEF_EXP = 0.25
        const val DEF_HL = 240
        const val DEF_SAT = 1.25
    }

    private lateinit var binding: ActivitySettingsBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var inputMeta: VideoMeta? = null
    private var curvesMap: Map<String, List<Pair<Double, Double>>> = emptyMap()
    @Volatile private var previewBusy = false

    // ── basic sliders ───────────────────────────────────────────────────────────

    private fun exposure(): Double = EXP_MIN + binding.slExposure.progress * EXP_STEP
    private fun highlight(): Int = HL_MIN + binding.slHighlight.progress * HL_STEP
    private fun saturation(): Double = binding.slSaturation.progress * SAT_STEP

    // ── advanced sliders ────────────────────────────────────────────────────────

    private fun contrast(): Double = 0.5 + binding.slContrast.progress * 0.05
    private fun gamma(): Double = 0.5 + binding.slGamma.progress * 0.05
    private fun brightness(): Double = (binding.slBrightness.progress - 25) * 0.01
    private fun temperatureK(): Int = 2500 + binding.slTemperature.progress * 100
    private fun tint(): Double = (binding.slTint.progress - 25) * 0.01
    private fun sharpness(): Int = binding.slSharpness.progress

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("path")
        val file = File(path ?: "")
        binding.textFile.text = file.name
        binding.textFileMeta.text = VideoUtils.formatSize(file.length())

        // Same frame for the small file card and the 16:9 stage preview
        Glide.with(binding.imageThumb)
            .load(file)
            .frame(1_000_000L)
            .centerCrop()
            .into(binding.imageThumb)
        Glide.with(binding.imagePreview)
            .load(file)
            .frame(1_000_000L)
            .centerCrop()
            .into(binding.imagePreview)

        // Fill in quality / duration / fps once ffprobe has looked at the file
        executor.execute {
            val m = VideoUtils.probeVideo(file.absolutePath)
            runOnUiThread {
                if (isFinishing || isDestroyed || m == null) return@runOnUiThread
                inputMeta = m
                val parts = ArrayList<String>()
                parts.add(VideoUtils.formatSize(file.length()))
                if (m.width > 0 && m.height > 0) {
                    parts.add("${m.width}x${m.height} ${VideoUtils.classifyQuality(m.width, m.height)}")
                }
                m.fps?.let { parts.add(String.format(Locale.US, "%.0f fps", it)) }
                if (m.durationSec != null) parts.add(VideoUtils.formatDuration(m.durationSec))
                binding.textFileMeta.text = parts.joinToString("  ·  ")
                updateHints()
            }
        }

        binding.btnBack.setOnClickListener { finish() }

        // Encoder speed / platform pills
        binding.groupPreset.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupPlatform.setOnCheckedStateChangeListener { _, _ -> updateHints() }

        // Fine-tune accordion
        binding.btnFineToggle.setOnClickListener {
            val open = binding.fineContent.visibility != View.VISIBLE
            TransitionManager.beginDelayedTransition(
                binding.contentRoot,
                AutoTransition().setDuration(240)
            )
            binding.fineContent.visibility = if (open) View.VISIBLE else View.GONE
            binding.iconFineChevron.animate()
                .rotation(if (open) 180f else 0f)
                .setDuration(240)
                .start()
        }

        // Basic sliders
        setDefaults()
        bindSlider(binding.slExposure)
        bindSlider(binding.slHighlight)
        bindSlider(binding.slSaturation)

        // Advanced sliders
        bindSlider(binding.slContrast)
        bindSlider(binding.slGamma)
        bindSlider(binding.slBrightness)
        bindSlider(binding.slTemperature)
        bindSlider(binding.slTint)
        bindSlider(binding.slSharpness)
        refreshValues()

        // Advanced mode reveal
        binding.switchAdvanced.setOnCheckedChangeListener { _, checked ->
            TransitionManager.beginDelayedTransition(
                binding.contentRoot,
                AutoTransition().setDuration(240)
            )
            binding.advancedContent.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Output format / dynamic metadata / codec / gpu / threads / tone-map chips
        binding.groupFormat.setOnCheckedStateChangeListener { _, _ -> updateHints(); updateStartLabel() }
        binding.groupResolution.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupDyn.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupCodec.setOnCheckedStateChangeListener { _, _ -> updateDynGating(); updateHints() }
        binding.groupGpu.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupThreads.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupTonemap.setOnCheckedStateChangeListener { _, _ -> updateHints() }

        // Curves editor wiring
        binding.groupCurveChannel.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            when (id) {
                R.id.curve_r -> binding.curvesView.setChannel(CurvesView.Channel.RED)
                R.id.curve_g -> binding.curvesView.setChannel(CurvesView.Channel.GREEN)
                R.id.curve_b -> binding.curvesView.setChannel(CurvesView.Channel.BLUE)
                else -> binding.curvesView.setChannel(CurvesView.Channel.MASTER)
            }
        }
        binding.curvesView.onCurvesChanged = { map -> curvesMap = map }
        binding.btnCurvesReset.setOnClickListener { binding.curvesView.resetActive() }

        // Before / after preview
        binding.btnPreview.setOnClickListener { renderPreview() }

        binding.btnReset.setOnClickListener {
            setDefaults()
            binding.groupPreset.check(R.id.preset_fast)
            binding.groupPlatform.check(R.id.platform_none)
            binding.switchStrip.isChecked = false
            // reset advanced controls
            binding.slContrast.progress = 10
            binding.slGamma.progress = 10
            binding.slBrightness.progress = 25
            binding.slTemperature.progress = 40
            binding.slTint.progress = 25
            binding.slSharpness.progress = 0
            binding.groupTonemap.check(R.id.tm_none)
            binding.switchHdrInput.isChecked = false
            binding.curvesView.resetAll()
            binding.groupFormat.check(R.id.fmt_pq)
            binding.groupResolution.check(R.id.res_source)
            binding.groupDyn.check(R.id.dyn_none)
            binding.groupCodec.check(R.id.codec_hevc)
            binding.groupGpu.check(R.id.gpu_off)
            binding.groupThreads.check(R.id.thr_auto)
            refreshValues()
            updateStartLabel()
        }

        // Enable / disable capability-gated options once the FFmpeg probe finishes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Capability.readyFlow.collect { ready ->
                    if (ready) applyCapabilityGating()
                }
            }
        }
        applyCapabilityGating()
        updateHints()
        updateStartLabel()

        binding.btnStart.setOnClickListener {
            if (path == null) return@setOnClickListener
            if (ConvertService.isActive) {
                Toast.makeText(
                    this, "A conversion is already running in the background",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val config = buildConfig()
            ConvertService.start(this, path, config)
            startActivity(
                Intent(this, ConvertActivity::class.java)
                    .putExtra("path", path)
                    .putExtra("config", config.toJson())
            )
        }
    }

    // ── config ──────────────────────────────────────────────────────────────────

    private fun buildConfig(): ConvertConfig {
        val preset = when (binding.groupPreset.checkedChipId) {
            R.id.preset_ultrafast -> "ultrafast"
            R.id.preset_medium -> "medium"
            R.id.preset_slow -> "slow"
            else -> "fast"
        }
        val platform = when (binding.groupPlatform.checkedChipId) {
            R.id.platform_tiktok -> "tiktok"
            R.id.platform_instagram -> "instagram"
            else -> "none"
        }
        val format = when (binding.groupFormat.checkedChipId) {
            R.id.fmt_hlg -> HdrFormat.HLG
            else -> HdrFormat.PQ
        }
        val dyn = when (binding.groupDyn.checkedChipId) {
            R.id.dyn_hdr10p -> DynamicMeta.HDR10PLUS
            R.id.dyn_dv -> DynamicMeta.DOLBY_VISION
            else -> DynamicMeta.NONE
        }
        val codec = when (binding.groupCodec.checkedChipId) {
            R.id.codec_hevchw -> VideoCodec.HEVC_HW
            R.id.codec_h264hw -> VideoCodec.H264_HW
            R.id.codec_vp9 -> VideoCodec.VP9
            R.id.codec_av1 -> VideoCodec.AV1
            else -> VideoCodec.HEVC_X265
        }
        val gpu = when (binding.groupGpu.checkedChipId) {
            R.id.gpu_mediacodec -> GpuDecode.MEDIACODEC
            R.id.gpu_vulkan -> GpuDecode.VULKAN
            else -> GpuDecode.OFF
        }
        val resolution = when (binding.groupResolution.checkedChipId) {
            R.id.res_720 -> OutputResolution.HD
            R.id.res_1080 -> OutputResolution.FHD
            R.id.res_2k -> OutputResolution.QHD
            R.id.res_4k -> OutputResolution.UHD
            R.id.res_8k -> OutputResolution.UHD8K
            else -> OutputResolution.SOURCE
        }
        val threads = when (binding.groupThreads.checkedChipId) {
            R.id.thr_2 -> 2
            R.id.thr_4 -> 4
            R.id.thr_6 -> 6
            R.id.thr_8 -> 8
            else -> 0
        }
        val toneMap = when (binding.groupTonemap.checkedChipId) {
            R.id.tm_reinhard -> ToneMapOp.REINHARD
            R.id.tm_hable -> ToneMapOp.HABLE
            R.id.tm_mobius -> ToneMapOp.MOBIUS
            else -> ToneMapOp.NONE
        }
        return ConvertConfig(
            exposure = exposure(),
            highlight = highlight(),
            saturation = saturation(),
            preset = preset,
            platform = platform,
            stripMeta = binding.switchStrip.isChecked,
            format = format,
            dynamicMeta = dyn,
            codec = codec,
            gpu = gpu,
            threads = threads,
            contrast = contrast(),
            gamma = gamma(),
            brightness = brightness(),
            temperatureK = temperatureK(),
            tint = tint(),
            toneMap = toneMap,
            allowHdrInput = binding.switchHdrInput.isChecked,
            resolution = resolution,
            sharpness = sharpness(),
            curves = curvesMap
        )
    }

    // ── capability gating ───────────────────────────────────────────────────────

    private fun applyCapabilityGating() {
        binding.codecAv1.isEnabled = Capability.av1Encoder != null
        binding.codecHevchw.isEnabled = Capability.hevcHwEncoder
        binding.codecH264hw.isEnabled = Capability.h264HwEncoder
        binding.codecVp9.isEnabled = Capability.vp9Encoder
        binding.gpuMediacodec.isEnabled = Capability.mediacodecHwaccel
        binding.gpuVulkan.isEnabled = Capability.vulkanHwaccel
        // auto-select CPU if the probed option vanished while selected
        if (!binding.gpuMediacodec.isEnabled && binding.groupGpu.checkedChipId == R.id.gpu_mediacodec) {
            binding.groupGpu.check(R.id.gpu_off)
        }
        if (!binding.gpuVulkan.isEnabled && binding.groupGpu.checkedChipId == R.id.gpu_vulkan) {
            binding.groupGpu.check(R.id.gpu_off)
        }
        if (!binding.codecAv1.isEnabled && binding.groupCodec.checkedChipId == R.id.codec_av1) {
            binding.groupCodec.check(R.id.codec_hevc)
        }
        updateDynGating()
        updateHints()
    }

    private fun updateDynGating() {
        val x265 = binding.groupCodec.checkedChipId == R.id.codec_hevc
        binding.dynHdr10p.isEnabled = x265 && Capability.hdr10PlusX265
        binding.dynDv.isEnabled = x265 && Capability.dolbyVisionX265
        val checked = binding.groupDyn.checkedChipId
        if ((checked == R.id.dyn_hdr10p && !binding.dynHdr10p.isEnabled) ||
            (checked == R.id.dyn_dv && !binding.dynDv.isEnabled)
        ) {
            binding.groupDyn.check(R.id.dyn_none)
        }
    }

    // ── hints & labels ──────────────────────────────────────────────────────────

    private fun updateStartLabel() {
        binding.btnStart.text = when (binding.groupFormat.checkedChipId) {
            R.id.fmt_hlg -> "Convert to HLG"
            else -> "Convert to HDR10"
        }
    }

    private fun updateHints() {
        binding.textPresetHint.text = when (binding.groupPreset.checkedChipId) {
            R.id.preset_ultrafast -> "Fastest render, largest file"
            R.id.preset_medium -> "Better compression, slower render"
            R.id.preset_slow -> "Highest quality, slowest render"
            else -> "Balanced speed and file size (default)"
        }
        binding.textPlatformHint.text = when (binding.groupPlatform.checkedChipId) {
            R.id.platform_tiktok -> "60 fps CFR  ·  16M bitrate cap  ·  AAC 192k / 48 kHz"
            R.id.platform_instagram -> "30 fps CFR  ·  14M bitrate cap  ·  AAC 192k / 48 kHz"
            else -> "Keep original frame rate and bitrate"
        }

        binding.textFmtHint.text = when (binding.groupFormat.checkedChipId) {
            R.id.fmt_hlg -> "ARIB STD-B67 · broadcast-friendly, scene-referred, no metadata needed"
            else -> "SMPTE 2084 · universal HDR10 signalling (default)"
        }

        val x265 = binding.groupCodec.checkedChipId == R.id.codec_hevc
        binding.textDynHint.text = when (binding.groupDyn.checkedChipId) {
            R.id.dyn_hdr10p ->
                if (x265 && Capability.hdr10PlusX265) "ST.2094-40 dynamic metadata · validated on this device"
                else "Requires software x265 with HDR10+ support"
            R.id.dyn_dv ->
                if (x265 && Capability.dolbyVisionX265) "Dolby Vision RPU 8.1 · HDR10-compatible fallback"
                else "Requires software x265 built with Dolby Vision"
            else -> "Static HDR10 signalling only"
        }

        binding.textResHint.text = when (binding.groupResolution.checkedChipId) {
            R.id.res_720 -> "Down/upscale to 1280x720 · lanczos · aspect preserved"
            R.id.res_1080 -> "Down/upscale to 1920x1080 · lanczos · aspect preserved"
            R.id.res_2k -> "Upscale to 2560x1440 (2K) · lanczos · aspect preserved"
            R.id.res_4k -> "Upscale to 3840x2160 (4K UHD) · lanczos · aspect preserved"
            R.id.res_8k -> "Upscale to 7680x4320 (8K) · very slow, huge files"
            else -> "Keep the source resolution (default)"
        }

        binding.textCodecHint.text = when (binding.groupCodec.checkedChipId) {
            R.id.codec_hevchw ->
                if (!Capability.hevcHwEncoder) "MediaCodec HEVC encoder not available on this device"
                else if (Capability.hevcHw10Bit) "MediaCodec HW encode · 10-bit Main10 HDR"
                else "MediaCodec HW encode · 8-bit (10-bit unsupported)"
            R.id.codec_h264hw ->
                if (!Capability.h264HwEncoder) "MediaCodec H.264 encoder not available on this device"
                else "Fastest path · 8-bit only, HDR signalling is limited"
            R.id.codec_vp9 ->
                if (!Capability.vp9Encoder) "libvpx-vp9 not present in the bundled FFmpeg"
                else "libvpx-vp9 · 10-bit profile 2 with HDR colour tags"
            R.id.codec_av1 ->
                "AV1 (${Capability.av1Encoder ?: "no encoder in bundled FFmpeg"})"
            else -> "Software x265 · best HDR10 / DV / HDR10+ quality (default)"
        }

        binding.textGpuHint.text = when (binding.groupGpu.checkedChipId) {
            R.id.gpu_mediacodec ->
                if (!Capability.mediacodecHwaccel) "MediaCodec hwaccel not available in this build"
                else "Hardware decode for H.264 / HEVC inputs · filters still run on CPU"
            R.id.gpu_vulkan ->
                if (!Capability.vulkanHwaccel) "Vulkan hwaccel not available in this build"
                else "Experimental Vulkan decode · frames downloaded for filtering"
            else -> "Software decode · maximum compatibility"
        }

        binding.textThreadsHint.text =
            if (binding.groupThreads.checkedChipId == R.id.thr_auto)
                "Auto lets ffmpeg use every core"
            else "Encoder + decoder thread count fixed"

        binding.textTonemapHint.text = when (binding.groupTonemap.checkedChipId) {
            R.id.tm_reinhard -> "Soft highlight compression · natural look"
            R.id.tm_hable -> "Filmic (Uncharted 2) curve · deep shadows"
            R.id.tm_mobius -> "Punchy rational curve · strong mid contrast"
            else -> "Straight lift into HDR (default)"
        } + "  ·  on HDR inputs the ffmpeg tonemap filter is used at linear light"
    }

    // ── before / after preview ──────────────────────────────────────────────────

    private fun renderPreview() {
        val path = intent.getStringExtra("path") ?: return
        if (previewBusy) return
        previewBusy = true
        binding.btnPreview.isEnabled = false
        binding.btnPreview.text = "Rendering preview..."

        executor.execute {
            try {
                val dur = inputMeta?.durationSec ?: 0.0
                val t = if (dur > 0) minOf(1.0, dur / 3.0) else 0.0
                val source = SourceColor.from(inputMeta)
                val config = buildConfig()
                val left = File(cacheDir, "preview_left.png")
                val right = File(cacheDir, "preview_right.png")
                left.delete(); right.delete()

                val okLeft = runFfmpeg(FfmpegEngine.buildPlainFrameCommand(path, t, left.absolutePath))
                val cmdRight = FfmpegEngine.buildGradedPreviewCommand(
                    config, path, source, t, right.absolutePath
                )
                val okRight = runFfmpeg(cmdRight)

                val bmL = if (okLeft) decodeScaled(left) else null
                val bmR = if (okRight) decodeScaled(right) else null

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (bmL != null && bmR != null) {
                            showCompareDialog(
                                title = "Before / After Preview",
                                left = bmL,
                                right = bmR,
                                rightLabel = "SIMULATED ${if (config.format == HdrFormat.HLG) "HLG" else "HDR10"}",
                                hint = "Graded frame tonemapped for this screen · shows exposure, colour & curve changes"
                            )
                        } else {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Preview render failed — check your grading settings",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.btnPreview.isEnabled = true
                        binding.btnPreview.text = "Render before / after preview"
                    }
                }
                previewBusy = false
            }
        }
    }

    fun showCompareDialog(
        title: String,
        left: Bitmap,
        right: Bitmap,
        rightLabel: String,
        hint: String
    ) {
        val view = DialogCompareBinding.inflate(layoutInflater)
        view.textCompareTitle.text = title
        view.imgLeft.setImageBitmap(left)
        view.imgRight.setImageBitmap(right)
        view.labelLeft.text = "SDR SOURCE"
        view.labelRight.text = rightLabel
        view.textCompareHint.text = hint
        MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun runFfmpeg(cmd: String): Boolean = try {
        val s = FFmpegKit.execute(cmd)
        ReturnCode.isSuccess(s.returnCode)
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

    // ── sliders ─────────────────────────────────────────────────────────────────

    private fun setDefaults() {
        binding.slExposure.progress = Math.round((DEF_EXP - EXP_MIN) / EXP_STEP).toInt()
        binding.slHighlight.progress = (DEF_HL - HL_MIN) / HL_STEP
        binding.slSaturation.progress = Math.round(DEF_SAT / SAT_STEP).toInt()
    }

    private fun bindSlider(bar: SeekBar) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshValues()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Don't let the page scroll while a slider is being dragged
                seekBar?.parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun refreshValues() {
        binding.valExposure.text = fmt2(exposure())
        binding.valHighlight.text = highlight().toString()
        binding.valSaturation.text = fmt2(saturation())
        binding.badgeNits.text = "${highlight()} Nits"
        binding.valContrast.text = fmt2(contrast())
        binding.valGamma.text = fmt2(gamma())
        binding.valBrightness.text = fmt2(brightness())
        binding.valTemperature.text = temperatureK().toString()
        binding.valTint.text = fmt2(tint())
        binding.valSharpness.text = if (sharpness() == 0) "Off" else sharpness().toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
