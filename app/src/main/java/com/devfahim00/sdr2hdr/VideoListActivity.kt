package com.devfahim00.sdr2hdr

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.devfahim00.sdr2hdr.databinding.ActivityVideoListBinding
import com.devfahim00.sdr2hdr.databinding.ItemVideoBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

class VideoEntry(val item: VideoItem) {
    val file = File(item.path)

    @Volatile
    var meta: VideoMeta? = null

    @Volatile
    var probed: Boolean = false

    @Volatile
    var queued: Boolean = false

    /** Best duration we know of: ffprobe result if available, else MediaStore's. */
    fun durationSec(): Double? =
        meta?.durationSec ?: item.durationMs.takeIf { it > 0 }?.let { it / 1000.0 }

    fun isHdr(): Boolean = meta?.isHdr ?: false

    fun metaLine(): String {
        val parts = ArrayList<String>()
        parts.add(VideoUtils.formatSize(item.sizeBytes))
        val m = meta
        val w = m?.width ?: item.width
        val h = m?.height ?: item.height
        if (w > 0 && h > 0) parts.add(VideoUtils.classifyQuality(w, h))
        if (m != null) {
            val frames = when {
                m.frames != null && m.frames > 0 -> m.frames
                m.durationSec != null && m.fps != null && m.durationSec > 0 && m.fps > 0 ->
                    (m.durationSec * m.fps).toInt()
                else -> null
            }
            if (frames != null) parts.add("$frames frames")
        } else if (probed) {
            parts.add("metadata unavailable")
        }
        return parts.joinToString("  ·  ")
    }
}

private const val PAYLOAD_META = "meta"

class VideoAdapter(
    private val onClick: (VideoEntry) -> Unit,
    private val requestProbe: (VideoEntry) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoHolder>() {

    class VideoHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    private var list: List<VideoEntry> = emptyList()

    fun submit(newList: List<VideoEntry>) {
        list = newList
        notifyDataSetChanged()
    }

    fun indexOf(e: VideoEntry): Int = list.indexOf(e)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder =
        VideoHolder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val e = list[position]
        holder.binding.textName.text = e.item.name
        bindMeta(holder, e)
        holder.binding.root.setOnClickListener { onClick(e) }

        // Thumbnail: a frame ~1s into the video (Glide decodes local video files natively)
        Glide.with(holder.binding.imageThumb)
            .load(e.file)
            .frame(1_000_000L)
            .centerCrop()
            .into(holder.binding.imageThumb)

        if (!e.probed) requestProbe(e)
    }

    override fun onBindViewHolder(holder: VideoHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_META)) {
            bindMeta(holder, list[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindMeta(holder: VideoHolder, e: VideoEntry) {
        holder.binding.textMeta.text = e.metaLine()
        holder.binding.badgeHdr.visibility = if (e.isHdr()) View.VISIBLE else View.GONE
        val d = e.durationSec()
        if (d != null && d > 0) {
            holder.binding.textDuration.text = VideoUtils.formatDuration(d)
            holder.binding.textDuration.visibility = View.VISIBLE
        } else {
            holder.binding.textDuration.visibility = View.GONE
        }
    }

    fun notifyMetaChanged(index: Int) = notifyItemChanged(index, PAYLOAD_META)
}

class VideoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoListBinding
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var videoAdapter: VideoAdapter

    private var allEntries: List<VideoEntry> = emptyList()
    private var query = ""
    private var sortMode = R.id.sort_name
    private var allMode = false

    // Lazily probe only the rows that get bound; most recently shown first (LIFO)
    private val probeQueue = LinkedBlockingDeque<VideoEntry>()
    private var probeThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        allMode = intent.getBooleanExtra("all", false)
        val dirPath = intent.getStringExtra("dir")
        val dir = File(dirPath ?: "")
        if (!allMode && (dirPath == null || !dir.isDirectory)) {
            Toast.makeText(this, "Folder not available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (allMode) {
            binding.textTitle.text = "All videos"
            binding.textFolderPath.text = "Everything on this device"
            sortMode = R.id.sort_newest
        } else {
            val base = Environment.getExternalStorageDirectory().absolutePath + "/"
            binding.textTitle.text = "Select a video"
            binding.textFolderPath.text = dirPath!!.removePrefix(base)
            sortMode = R.id.sort_name
        }
        binding.groupSort.check(sortMode)

        videoAdapter = VideoAdapter(
            onClick = { e ->
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra("path", e.file.absolutePath)
                )
            },
            requestProbe = { e -> requestProbe(e) }
        )
        binding.recyclerVideos.layoutManager = LinearLayoutManager(this)
        binding.recyclerVideos.adapter = videoAdapter
        (binding.recyclerVideos.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.inputSearch.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            applyFilter()
        }
        binding.groupSort.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            sortMode = id
            applyFilter()
        }

        startProbeWorker()

        binding.loadProgress.visibility = View.VISIBLE
        executor.execute {
            val items = if (allMode) {
                VideoRepository.queryAll(applicationContext)
            } else {
                VideoRepository.listFolder(dir)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.loadProgress.visibility = View.GONE
                allEntries = items.map { VideoEntry(it) }
                if (allMode) {
                    binding.textFolderPath.text = "${items.size} videos on this device"
                }
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.lowercase(Locale.US)
        var list = if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { it.item.name.lowercase(Locale.US).contains(q) }
        }
        list = when (sortMode) {
            R.id.sort_name -> list.sortedBy { it.item.name.lowercase(Locale.US) }
            R.id.sort_size -> list.sortedByDescending { it.item.sizeBytes }
            R.id.sort_duration -> list.sortedByDescending { it.durationSec() ?: 0.0 }
            else -> list.sortedByDescending { it.item.addedSec }
        }
        videoAdapter.submit(list)
        binding.textCount.text = if (list.size == 1) "1 video" else "${list.size} videos"
        binding.emptyVideos.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyVideos.text =
            if (allEntries.isEmpty()) "No videos found." else "No videos match your search."
    }

    private fun requestProbe(e: VideoEntry) {
        if (e.probed || e.queued) return
        e.queued = true
        probeQueue.addFirst(e)
    }

    private fun startProbeWorker() {
        probeThread = thread(name = "probe-worker", isDaemon = true) {
            try {
                while (true) {
                    val e = probeQueue.takeFirst()
                    if (e.probed) continue
                    e.meta = VideoUtils.probeVideo(e.file.absolutePath)
                    e.probed = true
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val idx = videoAdapter.indexOf(e)
                            if (idx >= 0) videoAdapter.notifyMetaChanged(idx)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // activity closed
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        probeThread?.interrupt()
        executor.shutdown()
    }
}
