package com.devfahim00.sdr2hdr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.devfahim00.sdr2hdr.databinding.ActivityMainBinding
import com.devfahim00.sdr2hdr.databinding.ItemFolderBinding
import com.devfahim00.sdr2hdr.databinding.ItemHeaderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

private sealed interface Row

private class HeaderRow(val title: String) : Row

private class FolderRow(val title: String, val path: String, val count: Int) : Row

private class HeaderHolder(private val binding: ItemHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(title: String) {
        binding.textHeader.text = title.uppercase()
    }
}

private class FolderHolder(private val binding: ItemFolderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(row: FolderRow, onClick: (String) -> Unit) {
        binding.textFolder.text = row.title
        binding.textCount.text = if (row.count == 1) "1 video" else "${row.count} videos"
        binding.root.setOnClickListener { onClick(row.path) }
    }
}

private class FolderAdapter(private val onFolderClick: (String) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()

    fun submit(folders: List<FolderInfo>) {
        rows.clear()
        val base = Environment.getExternalStorageDirectory().absolutePath + "/"
        var currentGroup: String? = null
        for (folder in folders) {
            val path = folder.path
            val rel = if (path.startsWith(base)) path.substring(base.length) else path
            val group = rel.substringBefore('/')
            val sub = if (rel.contains('/')) rel.substringAfter('/') else "(Root)"
            if (group != currentGroup) {
                rows.add(HeaderRow(group))
                currentGroup = group
            }
            rows.add(FolderRow(sub, path, folder.videoCount))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HeaderRow) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            FolderHolder(ItemFolderBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HeaderRow -> (holder as HeaderHolder).bind(row.title)
            is FolderRow -> (holder as FolderHolder).bind(row) { p -> onFolderClick(p) }
        }
    }
}

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "sdr2hdr_prefs"
        const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingRelease: UpdateChecker.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderAdapter = FolderAdapter { path ->
            startActivity(
                Intent(this, VideoListActivity::class.java).putExtra("dir", path)
            )
        }
        binding.recyclerFolders.layoutManager = LinearLayoutManager(this)
        binding.recyclerFolders.adapter = folderAdapter

        binding.cardAllVideos.setOnClickListener {
            startActivity(
                Intent(this, VideoListActivity::class.java).putExtra("all", true)
            )
        }
        binding.btnManualPath.setOnClickListener { showManualPathDialog() }
        binding.btnGrant.setOnClickListener { requestStorage() }
        binding.btnSettings.setOnClickListener {
            AppSettingsSheet(this) { applyHomeMode() }.show()
        }
        setupThemeToggle()
        requestNotificationPermissionIfNeeded()
        maybeCheckForUpdates()
        binding.cardUpdate.setOnClickListener { showUpdateDialog() }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Neumorphic sun / moon switch: slides the glowing thumb, then applies the new theme. */
    private fun setupThemeToggle() {
        val night = isNight()
        val travel = 38f * resources.displayMetrics.density
        binding.toggleThumb.translationX = if (night) travel else 0f
        binding.symbolSun.alpha = if (night) 0f else 1f
        binding.symbolMoon.alpha = if (night) 1f else 0f

        binding.themeToggle.setOnClickListener { toggle ->
            val toNight = !night
            toggle.isClickable = false
            toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.toggleThumb.animate()
                .translationX(if (toNight) travel else 0f)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
            binding.symbolSun.animate().alpha(if (toNight) 0f else 1f).setDuration(220).start()
            binding.symbolMoon.animate().alpha(if (toNight) 1f else 0f).setDuration(220).start()
            toggle.postDelayed({
                val mode = if (toNight) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                ThemePrefs.save(this, mode)
                AppCompatDelegate.setDefaultNightMode(mode)
            }, 340)
        }
    }

    // ── GitHub release update check ─────────────────────────────────────────

    /** Silent background check (rate limited) against the repo's latest release. */
    private fun maybeCheckForUpdates() {
        if (!UpdateChecker.shouldAutoCheck(this)) return
        executor.execute {
            UpdateChecker.markChecked(this)
            val release = UpdateChecker.check() ?: return@execute
            val current = UpdateChecker.currentVersionName(this)
            if (UpdateChecker.isNewer(current, release.tag)) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pendingRelease = release
                    binding.textUpdateSub.text = "${release.tag} · tap to view the release"
                    binding.cardUpdate.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showUpdateDialog() {
        val release = pendingRelease ?: run {
            binding.cardUpdate.visibility = View.GONE
            return
        }
        val notes = release.notes.ifBlank { "No release notes." }.take(2000)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Update available · ${release.tag}")
            .setMessage(
                "Installed: ${UpdateChecker.currentVersionName(this)}\n\n$notes"
            )
            .setNegativeButton("Later", null)
            .setNeutralButton("GitHub") { _, _ -> openUrl(release.pageUrl) }
        if (release.apkUrl != null) {
            builder.setPositiveButton("Download APK") { _, _ -> openUrl(release.apkUrl!!) }
        } else {
            builder.setPositiveButton("View release") { _, _ -> openUrl(release.pageUrl) }
        }
        builder.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android 13+ needs an explicit grant before the conversion notification shows. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val asked = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_NOTIF_ASKED, false)
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!asked && !granted) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }
    }

    private fun refresh() {
        applyHomeMode()
        val granted = hasStorageAccess()
        binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        if (!granted) {
            folderAdapter.submit(emptyList())
            binding.emptyHint.visibility = View.GONE
            binding.scanProgress.visibility = View.GONE
            binding.cardAllVideos.visibility = View.GONE
            binding.textFoldersLabel.visibility = View.GONE
            binding.btnManualPath.visibility = View.GONE
            return
        }

        // "Hide folder explorer" mode: only the centered pick-a-video card
        if (AppPrefs.hideFolderExplorer(this)) {
            binding.cardAllVideos.visibility = View.VISIBLE
            binding.textFoldersLabel.visibility = View.GONE
            binding.btnManualPath.visibility = View.GONE
            binding.scanProgress.visibility = View.GONE
            binding.emptyHint.visibility = View.GONE
            binding.recyclerFolders.visibility = View.GONE
            folderAdapter.submit(emptyList())
            return
        }

        binding.cardAllVideos.visibility = View.VISIBLE
        binding.textFoldersLabel.visibility = View.VISIBLE
        binding.btnManualPath.visibility = View.VISIBLE
        binding.recyclerFolders.visibility = View.VISIBLE
        binding.scanProgress.visibility = View.VISIBLE
        executor.execute {
            val dirs = VideoRepository.scanFolders()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.scanProgress.visibility = View.GONE
                folderAdapter.submit(dirs)
                binding.emptyHint.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Centered "pick a video" (hide folder explorer) vs. the full explorer layout. */
    private fun applyHomeMode() {
        val hide = AppPrefs.hideFolderExplorer(this)
        val lp = binding.allVideosContainer.layoutParams as LinearLayout.LayoutParams
        if (hide) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
        }
        binding.allVideosContainer.layoutParams = lp
        val cardLp = binding.cardAllVideos.layoutParams as FrameLayout.LayoutParams
        cardLp.gravity = if (hide) Gravity.CENTER else Gravity.TOP
        binding.cardAllVideos.layoutParams = cardLp
    }

    private fun showManualPathDialog() {
        val input = EditText(this)
        input.hint = "/storage/emulated/0/YourFolder"
        input.setSingleLine()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this)
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter folder path")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val dir = File(path)
                    if (dir.isDirectory) {
                        startActivity(
                            Intent(this, VideoListActivity::class.java).putExtra("dir", path)
                        )
                    } else {
                        Toast.makeText(this, "Directory does not exist", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
