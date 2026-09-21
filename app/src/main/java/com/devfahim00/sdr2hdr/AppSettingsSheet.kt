package com.devfahim00.sdr2hdr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import com.devfahim00.sdr2hdr.databinding.DialogAppSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * The "settings" sheet opened from the home header (next to the day/night toggle):
 * GitHub / Telegram links, the hide-folder-explorer home layout switch, the local
 * network web server, a manual update check and the About dialog.
 */
class AppSettingsSheet(
    private val activity: Activity,
    /** Invoked whenever the home layout needs to re-evaluate (hide folders toggled). */
    private val onHomeLayoutChanged: () -> Unit
) : BottomSheetDialog(activity) {

    private companion object {
        const val URL_REPO = "https://github.com/devfahim00/SDR2HDR"
        const val URL_OWNER = "https://t.me/droxilen"
        const val URL_CHANNEL = "https://t.me/projectredfox"
    }

    private lateinit var binding: DialogAppSettingsBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_app_settings, null)
        binding = DialogAppSettingsBinding.bind(view)
        setContentView(view)
        wire()
        super.show()
    }

    private fun wire() {
        binding.textSheetVersion.text = UpdateChecker.currentVersionName(activity)

        binding.rowGithub.setOnClickListener { openUrl(URL_REPO) }
        binding.rowOwner.setOnClickListener { openUrl(URL_OWNER) }
        binding.rowChannel.setOnClickListener { openUrl(URL_CHANNEL) }

        // Hide folder explorer on the home page
        binding.switchHideFolders.isChecked = AppPrefs.hideFolderExplorer(activity)
        binding.switchHideFolders.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setHideFolderExplorer(activity, checked)
            onHomeLayoutChanged()
        }

        // Local network web server
        binding.rowServer.setOnClickListener {
            if (WebServerService.isRunning) {
                WebServerService.stop(activity)
                // the service stops asynchronously; reflect it immediately
                postDelayed(400) { refreshServerRow() }
            } else {
                WebServerService.start(activity)
                // the port is chosen on the background accept thread; poll briefly
                postDelayed(600) { refreshServerRow() }
                postDelayed(1600) { refreshServerRow() }
            }
        }
        binding.rowServer.setOnLongClickListener {
            val url = WebServerService.url
            if (url != null) {
                copyToClipboard(url)
                true
            } else false
        }
        refreshServerRow()

        // Manual update check (bypasses the 6h rate limit)
        binding.rowUpdate.setOnClickListener {
            binding.textUpdateRowSub.text = "Checking GitHub..."
            executor.execute {
                UpdateChecker.markChecked(activity)
                val release = UpdateChecker.check()
                activity.runOnUiThread {
                    if (isShowing) showUpdateResult(release)
                    binding.textUpdateRowSub.text =
                        "Compare with the latest GitHub release"
                }
            }
        }

        binding.rowAbout.setOnClickListener { showAbout() }
    }

    private fun refreshServerRow() {
        if (WebServerService.isRunning) {
            binding.textServerTitle.text = "Stop Local Server"
            binding.textServerSub.text =
                "${WebServerService.url ?: ""}  ·  long-press to copy"
            binding.iconServerState.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    activity.resources.getColor(R.color.green, activity.theme)
                )
        } else {
            binding.textServerTitle.text = "Start Local Server"
            binding.textServerSub.text =
                "Use every feature from any browser on this network"
            binding.iconServerState.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    activity.resources.getColor(R.color.blue, activity.theme)
                )
        }
    }

    private fun showUpdateResult(release: UpdateChecker.Release?) {
        val current = UpdateChecker.currentVersionName(activity)
        if (release == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Update check")
                .setMessage("Could not reach GitHub. Check your connection and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (UpdateChecker.isNewer(current, release.tag)) {
            val notes = release.notes.ifBlank { "No release notes." }.take(2000)
            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle("Update available · ${release.tag}")
                .setMessage("Installed: $current\n\n$notes")
                .setNegativeButton("Later", null)
                .setNeutralButton("GitHub") { _, _ -> openUrl(release.pageUrl) }
            if (release.apkUrl != null) {
                builder.setPositiveButton("Download APK") { _, _ -> openUrl(release.apkUrl!!) }
            } else {
                builder.setPositiveButton("View release") { _, _ -> openUrl(release.pageUrl) }
            }
            builder.show()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Up to date")
                .setMessage(
                    "Installed: $current\nLatest release: ${release.tag}\n\n" +
                        "You are running the newest version."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showAbout() {
        val version = UpdateChecker.currentVersionName(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("SDR2HDR $version")
            .setMessage(
                "SDR to HDR10 / HLG / HDR10+ / Dolby Vision 8.1 video converter, " +
                    "powered by FFmpeg.\n\n" +
                    "Features: background conversion with pause & resume, " +
                    "advanced grading (contrast, gamma, brightness, temperature, tint), " +
                    "RGB curves editor, tone-mapping operators, resolution upscaling " +
                    "up to 8K, sharpness control, hardware encoders (HEVC / H.264 / AV1), " +
                    "AV1 & VP9 presets, before/after preview and a local network web server.\n\n" +
                    "Owner: droxilen (t.me/droxilen)\n" +
                    "Channel: Project Red Fox (t.me/projectredfox)\n" +
                    "Source: github.com/devfahim00/SDR2HDR"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(activity, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SDR2HDR server", text))
            Toast.makeText(activity, "URL copied: $text", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun postDelayed(delayMs: Long, action: () -> Unit) {
        binding.rowServer.postDelayed(Runnable(action), delayMs)
    }

    override fun dismiss() {
        executor.shutdown()
        super.dismiss()
    }
}
