package com.devfahim00.sdr2hdr

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.devfahim00.sdr2hdr.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("path")
        binding.textFile.text = "File: ${File(path ?: "").name}"

        binding.btnStart.setOnClickListener {
            val expStr = binding.inputExposure.text.toString().trim().ifEmpty { "0.25" }
            val hlStr = binding.inputHighlight.text.toString().trim().ifEmpty { "240" }
            val satStr = binding.inputSaturation.text.toString().trim().ifEmpty { "1.25" }

            val exp = expStr.toDoubleOrNull()
            if (exp == null || exp < -3.0 || exp > 3.0) {
                binding.inputExposure.error = "Enter a value between -3.0 and 3.0"
                return@setOnClickListener
            }
            val hl = hlStr.toIntOrNull()
            if (hl == null || hl < 1 || hl > 10000) {
                binding.inputHighlight.error = "Enter nits (e.g. 240)"
                return@setOnClickListener
            }
            val sat = satStr.toDoubleOrNull()
            if (sat == null || sat < 0.0 || sat > 3.0) {
                binding.inputSaturation.error = "Enter a value between 0.0 and 3.0"
                return@setOnClickListener
            }

            val preset = when (binding.groupPreset.checkedRadioButtonId) {
                R.id.preset_ultrafast -> "ultrafast"
                R.id.preset_medium -> "medium"
                R.id.preset_slow -> "slow"
                else -> "fast"
            }
            val platform = when (binding.groupPlatform.checkedRadioButtonId) {
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
}
