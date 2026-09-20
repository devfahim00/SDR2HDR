package com.devfahim00.sdr2hdr

import android.content.Intent
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivitySettingsBinding
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

    private fun exposure(): Double = EXP_MIN + binding.slExposure.progress * EXP_STEP
    private fun highlight(): Int = HL_MIN + binding.slHighlight.progress * HL_STEP
    private fun saturation(): Double = binding.slSaturation.progress * SAT_STEP

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
                val parts = ArrayList<String>()
                parts.add(VideoUtils.formatSize(file.length()))
                if (m.width > 0 && m.height > 0) {
                    parts.add("${m.width}x${m.height} ${VideoUtils.classifyQuality(m.width, m.height)}")
                }
                m.fps?.let { parts.add(String.format(Locale.US, "%.0f fps", it)) }
                if (m.durationSec != null) parts.add(VideoUtils.formatDuration(m.durationSec))
                binding.textFileMeta.text = parts.joinToString("  ·  ")
            }
        }

        binding.btnBack.setOnClickListener { finish() }

        // Encoder speed / platform pills
        binding.groupPreset.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupPlatform.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        updateHints()

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

        // Sliders
        setDefaults()
        bindSlider(binding.slExposure)
        bindSlider(binding.slHighlight)
        bindSlider(binding.slSaturation)
        refreshValues()

        binding.btnReset.setOnClickListener {
            setDefaults()
            binding.groupPreset.check(R.id.preset_fast)
            binding.groupPlatform.check(R.id.platform_none)
            binding.switchStrip.isChecked = false
            refreshValues()
        }

        binding.btnStart.setOnClickListener {
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

            startActivity(
                Intent(this, ConvertActivity::class.java)
                    .putExtra("path", path)
                    .putExtra("exposure", fmt2(exposure()))
                    .putExtra("highlight", highlight().toString())
                    .putExtra("saturation", fmt2(saturation()))
                    .putExtra("preset", preset)
                    .putExtra("platform", platform)
                    .putExtra("strip", binding.switchStrip.isChecked)
            )
        }
    }

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
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
