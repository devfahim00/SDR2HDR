package com.devfahim00.sdr2hdr

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.devfahim00.sdr2hdr.databinding.ActivityVideoListBinding
import com.devfahim00.sdr2hdr.databinding.ItemVideoBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class VideoEntry(val file: File) {
    @Volatile
    var meta: String = "Reading info..."

    @Volatile
    var isHdr: Boolean = false
}

class VideoAdapter(
    private val entries: List<VideoEntry>,
    private val onClick: (VideoEntry) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoHolder>() {

    class VideoHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder =
        VideoHolder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val e = entries[position]
        holder.binding.textName.text = e.file.name
        holder.binding.textMeta.text = e.meta
        val color = if (e.isHdr) R.color.yellow else R.color.white
        holder.binding.textName.setTextColor(
            ContextCompat.getColor(holder.binding.root.context, color)
        )
        holder.binding.root.setOnClickListener { onClick(e) }
    }
}

class VideoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoListBinding
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var entries: MutableList<VideoEntry>
    private lateinit var videoAdapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dirPath = intent.getStringExtra("dir")
        val dir = File(dirPath ?: "")
        if (dirPath == null || !dir.isDirectory) {
            Toast.makeText(this, "Folder not available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val base = Environment.getExternalStorageDirectory().absolutePath + "/"
        binding.textFolderPath.text = dirPath.removePrefix(base)

        val files = dir.listFiles { f -> VideoUtils.isVideoFile(f) }
            ?.filter { !VideoUtils.isExcluded(it.name) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?: emptyList()

        if (files.isEmpty()) {
            binding.emptyVideos.visibility = View.VISIBLE
        }

        entries = files.map { VideoEntry(it) }.toMutableList()
        videoAdapter = VideoAdapter(entries) { e ->
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("path", e.file.absolutePath)
            )
        }
        binding.recyclerVideos.layoutManager = LinearLayoutManager(this)
        binding.recyclerVideos.adapter = videoAdapter

        executor.execute {
            for (i in entries.indices) {
                val m = VideoUtils.probeVideo(entries[i].file.absolutePath)
                entries[i].meta = metaLine(entries[i], m)
                entries[i].isHdr = m?.isHdr ?: false
                runOnUiThread {
                    if (!isFinishing) videoAdapter.notifyItemChanged(i)
                }
            }
        }
    }

    private fun metaLine(e: VideoEntry, m: VideoMeta?): String {
        if (m == null) return "Metadata unavailable"
        val framesStr = when {
            m.frames != null && m.frames > 0 -> "${m.frames} frames"
            m.durationSec != null && m.fps != null && m.durationSec > 0 && m.fps > 0 ->
                "${(m.durationSec * m.fps).toInt()} frames"
            else -> "N/A frames"
        }
        val hdrTag = if (m.isHdr) "  [HDR]" else ""
        return VideoUtils.formatSize(e.file.length()) +
            "  |  " + VideoUtils.classifyQuality(m.width, m.height) + hdrTag +
            "  |  " + framesStr +
            "  |  d: " + VideoUtils.formatDuration(m.durationSec)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
