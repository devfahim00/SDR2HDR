package com.devfahim00.sdr2hdr

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/** A video file plus whatever cheap metadata we could get without running ffprobe. */
data class VideoItem(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val addedSec: Long
)

data class FolderInfo(val path: String, val videoCount: Int)

object VideoRepository {

    private val STANDARD_ROOTS = listOf("DCIM", "Download", "Movies", "Pictures")

    private fun toItem(f: File) = VideoItem(
        path = f.absolutePath,
        name = f.name,
        sizeBytes = f.length(),
        durationMs = 0L,
        width = 0,
        height = 0,
        addedSec = f.lastModified() / 1000
    )

    /** Videos directly inside [dir] (no recursion), excluding hidden / trashed / our own output. */
    fun listFolder(dir: File): List<VideoItem> =
        dir.listFiles { f -> VideoUtils.isVideoFile(f) && !VideoUtils.isExcluded(f.name) }
            ?.map { toItem(it) }
            ?: emptyList()

    /**
     * Folders under /sdcard/{DCIM,Download,Movies,Pictures} (depth <= 2) that contain at
     * least one convertible video, with their video counts. Sorted by path.
     */
    fun scanFolders(): List<FolderInfo> {
        val sdcard = Environment.getExternalStorageDirectory()
        val found = sortedMapOf<String, Int>()
        for (rootName in STANDARD_ROOTS) {
            val root = File(sdcard, rootName)
            if (!root.isDirectory) continue
            val rootCount = countVideos(root)
            if (rootCount > 0) found[root.absolutePath] = rootCount
            val subs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: continue
            for (sub in subs) {
                val c = countVideos(sub)
                if (c > 0) found[sub.absolutePath] = c
            }
        }
        return found.map { FolderInfo(it.key, it.value) }
    }

    private fun countVideos(dir: File): Int =
        dir.listFiles { f -> VideoUtils.isVideoFile(f) && !VideoUtils.isExcluded(f.name) }?.size ?: 0

    /**
     * Every video on the device, newest first.
     *
     * Primary source is MediaStore (fast, and gives duration / resolution for free). It is
     * unioned with the standard folders so anything MediaStore has not indexed yet still
     * shows up; if MediaStore yields nothing at all we fall back to walking shared storage.
     */
    fun queryAll(context: Context): List<VideoItem> {
        val byPath = LinkedHashMap<String, VideoItem>()

        try {
            for (item in queryMediaStore(context)) byPath[item.path] = item
        } catch (_: Exception) {
            // permission / provider problems -> fall through to file scanning
        }

        // Anything in the standard folders that MediaStore missed
        val sdcard = Environment.getExternalStorageDirectory()
        for (rootName in STANDARD_ROOTS) {
            val root = File(sdcard, rootName)
            if (!root.isDirectory) continue
            for (item in listFolder(root)) byPath.putIfAbsent(item.path, item)
            root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { sub ->
                for (item in listFolder(sub)) byPath.putIfAbsent(item.path, item)
            }
        }

        if (byPath.isEmpty()) {
            val walked = ArrayList<VideoItem>()
            walk(sdcard, 0, walked)
            for (item in walked) byPath[item.path] = item
        }

        return byPath.values.sortedByDescending { it.addedSec }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStore(context: Context): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val file = File(path)
                val name = c.getString(iName) ?: file.name
                if (VideoUtils.isExcluded(name) || isRestricted(path)) continue
                if (!file.isFile) continue // stale MediaStore row
                val size = c.getLong(iSize).takeIf { it > 0 } ?: file.length()
                out.add(
                    VideoItem(
                        path = path,
                        name = name,
                        sizeBytes = size,
                        durationMs = c.getLong(iDur),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        addedSec = c.getLong(iAdded)
                    )
                )
            }
        }
        return out
    }

    /** Other apps' private dirs and hidden folders (.thumbnails, .Trash, ...). */
    private fun isRestricted(path: String): Boolean {
        val p = path.lowercase(Locale.US)
        return p.contains("/android/data/") || p.contains("/android/obb/") || p.contains("/.")
    }

    private fun walk(dir: File, depth: Int, out: MutableList<VideoItem>) {
        if (depth > 6) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n.equals("Android", ignoreCase = true)) continue
                walk(f, depth + 1, out)
            } else if (VideoUtils.isVideoFile(f) && !VideoUtils.isExcluded(n)) {
                out.add(toItem(f))
            }
        }
    }
}
