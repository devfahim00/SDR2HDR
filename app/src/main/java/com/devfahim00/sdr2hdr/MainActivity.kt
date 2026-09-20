package com.devfahim00.sdr2hdr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter
    private val executor = Executors.newSingleThreadExecutor()

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
        val granted = hasStorageAccess()
        binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        binding.cardAllVideos.visibility = if (granted) View.VISIBLE else View.GONE
        binding.textFoldersLabel.visibility = if (granted) View.VISIBLE else View.GONE
        binding.btnManualPath.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            folderAdapter.submit(emptyList())
            binding.emptyHint.visibility = View.GONE
            return
        }
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
