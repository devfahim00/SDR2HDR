package com.devfahim00.sdr2hdr

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivitySettingsBinding
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("path")
        val file = File(path ?: "")
        binding.textFile.text = file.name
        binding.textFileMeta.text = VideoUtils.formatSize(file.length())

        Glide.with(binding.imageThumb)
            .load(file)
            .frame(1_000_000L)
            .centerCrop()
            .into(binding.imageThumb)

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
                m.fps?.let { parts.add(String.format(java.util.Locale.US, "%.0f fps", it)) }
                if (m.durationSec != null) parts.add(VideoUtils.formatDuration(m.durationSec))
                binding.textFileMeta.text = parts.joinToString("  ·  ")
            }
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.groupPreset.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        binding.groupPlatform.setOnCheckedStateChangeListener { _, _ -> updateHints() }
        updateHints()

        binding.inputExposure.doAfterTextChanged { binding.layoutExposure.error = null }
        binding.inputHighlight.doAfterTextChanged { binding.layoutHighlight.error = null }
        binding.inputSaturation.doAfterTextChanged { binding.layoutSaturation.error = null }

        binding.btnReset.setOnClickListener {
            binding.inputExposure.setText("0.25")
            binding.inputHighlight.setText("240")
            binding.inputSaturation.setText("1.25")
            binding.groupPreset.check(R.id.preset_fast)
            binding.groupPlatform.check(R.id.platform_none)
            binding.switchStrip.isChecked = false
        }

        binding.btnStart.setOnClickListener {
            val expStr = binding.inputExposure.text.toString().trim().ifEmpty { "0.25" }
            val hlStr = binding.inputHighlight.text.toString().trim().ifEmpty { "240" }
            val satStr = binding.inputSaturation.text.toString().trim().ifEmpty { "1.25" }

            val exp = expStr.toDoubleOrNull()
            if (exp == null || exp < -3.0 || exp > 3.0) {
                binding.layoutExposure.error = "Enter a value between -3.0 and 3.0"
                return@setOnClickListener
            }
            val hl = hlStr.toIntOrNull()
            if (hl == null || hl < 1 || hl > 10000) {
                binding.layoutHighlight.error = "Enter nits between 1 and 10000 (e.g. 240)"
                return@setOnClickListener
            }
            val sat = satStr.toDoubleOrNull()
            if (sat == null || sat < 0.0 || sat > 3.0) {
                binding.layoutSaturation.error = "Enter a value between 0.0 and 3.0"
                return@setOnClickListener
            }

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
            val strip = binding.switchStrip.isChecked

            startActivity(
                Intent(this, ConvertActivity::class.java)
                    .putExtra("path", path)
                    .putExtra("exposure", expStr)
                    .putExtra("highlight", hlStr)
                    .putExtra("saturation", satStr)
                    .putExtra("preset", preset)
                    .putExtra("platform", platform)
                    .putExtra("strip", strip)
            )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
