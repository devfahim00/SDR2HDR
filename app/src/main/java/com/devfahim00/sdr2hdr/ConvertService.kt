package com.devfahim00.sdr2hdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFmpegSessionCompleteCallback
import com.antonkarpenko.ffmpegkit.LogCallback
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Lifecycle of one background conversion. */
enum class Phase { IDLE, PROBING, RUNNING, PAUSED, FINALIZING, DONE, FAILED, CANCELLED }

data class ConversionState(
    val phase: Phase = Phase.IDLE,
    val inputName: String = "",
    val message: String = "",
    val progress: Int = 0,
    val processedSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val frames: Long = 0,
    val totalFrames: Long = 0,
    val fps: Double = 0.0,
    val etaSec: Double = -1.0,
    val sourceInfo: String = "",
    val output: String? = null,
    val alreadyHdr: Boolean = false,
    val logTail: String = "",
    val formatLabel: String = "HDR10",
    val segments: Int = 0
)

/**
 * Foreground service that owns the whole conversion: probing, encoding, pause/resume
 * (via segmented, fragmented-MP4 encoding), segment merging, and completion notification.
 * The app can be closed at any time — the conversion keeps running.
 *
 * UI observes [ConvertService.state] (a StateFlow) and sends commands through
 * [start] / [send] (pause / resume / stop); the notification buttons do the same.
 */
class ConvertService : Service() {

    companion object {
        const val ACTION_START = "com.devfahim00.sdr2hdr.START"
        const val ACTION_PAUSE = "com.devfahim00.sdr2hdr.PAUSE"
        const val ACTION_RESUME = "com.devfahim00.sdr2hdr.RESUME"
        const val ACTION_STOP = "com.devfahim00.sdr2hdr.STOP"
        const val EXTRA_INPUT = "input"
        const val EXTRA_CONFIG = "config"

        private const val CHANNEL_ID = "conversion"
        private const val NOTIF_ID = 41
        private const val DONE_NOTIF_ID = 42

        /** The single source of truth for the UI. */
        val state = MutableStateFlow(ConversionState())

        @Volatile
        var isActive: Boolean = false

        fun start(context: Context, input: String, config: ConvertConfig) {
            val intent = Intent(context, ConvertService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_CONFIG, config.toJson())
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            try {
                context.startService(
                    Intent(context, ConvertService::class.java).setAction(action)
                )
            } catch (_: Exception) {
                // app in background without the FGS running — nothing to command
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var config: ConvertConfig = ConvertConfig()
    private var inputPath: String = ""
    private var inputName: String = ""
    private var meta: VideoMeta? = null
    private var source: SourceColor = SourceColor.BT709
    private var finalFile: File? = null
    private var workDir: File? = null
    private var hdr10PlusJson: File? = null
    private val segments = ArrayList<File>()

    private var durationSec = 0.0
    private var totalFrames = 0L
    private var resumeOffsetSec = 0.0
    private var framesBase = 0L
    private var lastStatsSec = 0.0
    private var lastStatsFrame = 0L

    private var sessionId = -1L
    private var pauseRequested = false
    private var stopRequested = false
    private var active = false
    private var retried = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var lastNotifUpdate = 0L

    // ── lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            CHANNEL_ID, "Conversions", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background HDR conversion progress" }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                if (active) return START_NOT_STICKY // busy — ignore
                val input = intent.getStringExtra(EXTRA_INPUT)
                val cfg = ConvertConfig.fromJson(intent.getStringExtra(EXTRA_CONFIG))
                if (input == null || !File(input).isFile) {
                    setState { it.copy(phase = Phase.FAILED, message = "Input file not found.") }
                    finishService(false)
                    return START_NOT_STICKY
                }
                inputPath = input
                config = cfg
                begin()
            }
            ACTION_PAUSE -> requestPause()
            ACTION_RESUME -> requestResume()
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (active && sessionId >= 0) {
            try {
                FFmpegKit.cancel(sessionId)
            } catch (_: Exception) {
            }
        }
        releaseWake()
        executor.shutdownNow()
    }

    // ── conversion flow ─────────────────────────────────────────────────────────

    private fun begin() {
        active = true
        isActive = true
        synchronized(lock) {
            pauseRequested = false
            stopRequested = false
        }
        retried = false
        segments.clear()
        resumeOffsetSec = 0.0
        framesBase = 0
        inputName = File(inputPath).name

        val fmtLabel = when {
            config.dynamicMeta == DynamicMeta.DOLBY_VISION -> "Dolby Vision 8.1"
            config.format == HdrFormat.HLG -> "HLG"
            else -> "HDR10"
        }
        setState {
            ConversionState(
                phase = Phase.PROBING,
                inputName = inputName,
                formatLabel = fmtLabel,
                message = "Analyzing input..."
            )
        }
        acquireWake()
        pushNotification()

        executor.execute {
            val m = VideoUtils.probeVideo(inputPath)
            onProbed(m)
        }
    }

    private fun onProbed(m: VideoMeta?) {
        if (!active) return
        if (m == null) {
            fail("Could not read video metadata (ffprobe failed).")
            return
        }
        if (m.isHdr && !config.allowHdrInput) {
            cleanupAll()
            setState {
                it.copy(
                    phase = Phase.FAILED,
                    alreadyHdr = true,
                    message = "Input video is already HDR."
                )
            }
            finishService(false)
            return
        }

        meta = m
        source = SourceColor.from(m)
        durationSec = m.durationSec ?: 0.0
        totalFrames = when (config.platform) {
            "tiktok" -> (durationSec * 60).toLong()
            "instagram" -> (durationSec * 30).toLong()
            else -> m.frames?.toLong() ?: (durationSec * (m.fps ?: 0.0)).toLong()
        }

        // Output: /sdcard/Movies/HDR10_Converted/output_HDR10[N].mp4 (or _HLG / _DV)
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "HDR10_Converted"
        )
        outDir.mkdirs()
        File(outDir, ".nomedia").delete()
        val base = when {
            config.dynamicMeta == DynamicMeta.DOLBY_VISION -> "output_DV"
            config.format == HdrFormat.HLG -> "output_HLG"
            else -> "output_HDR10"
        }
        var name = "$base.mp4"
        var count = 1
        var out = File(outDir, name)
        while (out.exists()) {
            name = "${base}_$count.mp4"
            out = File(outDir, name)
            count++
        }
        finalFile = out

        workDir = File(cacheDir, "convert_${System.currentTimeMillis()}")
        workDir?.mkdirs()

        if (config.dynamicMeta == DynamicMeta.HDR10PLUS) {
            hdr10PlusJson = File(workDir, "hdr10plus.json")
            FfmpegEngine.writeHdr10PlusJson(hdr10PlusJson!!, config.highlight)
        }

        setState {
            it.copy(
                phase = Phase.RUNNING,
                durationSec = durationSec,
                totalFrames = totalFrames,
                sourceInfo = "${source.matrix}  ·  ${if (source.fullRange) "full" else "limited"} range" +
                    if (source.isHdrSource) "  ·  HDR in" else "",
                message = "Encoding ${it.formatLabel} BT.2020 frames..."
            )
        }
        startNextSegment()
    }

    private fun startNextSegment(forceFrameProps: Boolean = false) {
        if (!active) return
        val wd = workDir ?: return
        val segFile = File(wd, String.format(Locale.US, "seg_%03d.mp4", segments.size))
        lastStatsSec = 0.0
        lastStatsFrame = 0L
        synchronized(lock) { pauseRequested = false }

        val cmd = FfmpegEngine.buildSegmentCommand(
            config, inputPath, source, meta, segFile.absolutePath,
            resumeOffsetSec, forceFrameProps, hdr10PlusJson
        )
        acquireWake()
        val session = FFmpegKit.executeAsync(
            cmd,
            FFmpegSessionCompleteCallback { s -> onSegmentFinished(s, segFile) },
            LogCallback { },
            StatisticsCallback { st -> onStats(st) }
        )
        sessionId = session.sessionId
    }

    private fun onStats(st: Statistics) {
        if (!active) return
        val segSec = st.time / 1000.0
        lastStatsSec = segSec
        lastStatsFrame = st.videoFrameNumber.toLong()
        val total = resumeOffsetSec + segSec
        var pct = if (durationSec > 0) (total / durationSec * 100).toInt() else 0
        if (pct < 0) pct = 0
        if (pct > 99) pct = 99
        val frames = framesBase + lastStatsFrame
        val fps = st.videoFps.toDouble()
        val remaining = (durationSec - total).coerceAtLeast(0.0)
        var etaSec = -1.0
        if (st.speed > 0.01) {
            etaSec = remaining / st.speed
        } else if (fps > 0.5 && totalFrames > frames) {
            etaSec = (totalFrames - frames) / fps
        }
        setState {
            it.copy(
                progress = pct,
                processedSec = total,
                frames = frames,
                fps = fps,
                etaSec = etaSec
            )
        }
    }

    private fun onSegmentFinished(session: FFmpegSession, segFile: File) {
        if (!active) return
        val rc = session.returnCode
        val logs = session.allLogsAsString ?: ""

        when {
            ReturnCode.isSuccess(rc) -> {
                if (segFile.isFile && segFile.length() > 2048) {
                    segments.add(segFile)
                    framesBase += lastStatsFrame
                } else {
                    segFile.delete()
                }
                finalizeConversion()
            }

            ReturnCode.isCancel(rc) && stopRequested -> {
                segFile.delete()
                cancelled()
            }

            ReturnCode.isCancel(rc) && pauseRequested -> {
                handlePause(segFile)
            }

            else -> {
                // A zscale colour-space failure: retry once with a safe profile and the
                // colour tags of the decoded frames force-overwritten, before giving up.
                if (!retried && isColorError(logs)) {
                    retried = true
                    segFile.delete()
                    source = if (source.isHdrSource) {
                        SourceColor("bt2020nc", "bt2020", source.transfer, false)
                    } else {
                        SourceColor.BT709
                    }
                    setState { it.copy(message = "Retrying with safe colour profile...") }
                    startNextSegment(forceFrameProps = true)
                } else {
                    segFile.delete()
                    val logTail = logs.takeLast(1500)
                        .ifEmpty { "FFmpeg exited with code ${rc?.value ?: "unknown"}" }
                    fail("FFmpeg exited with code ${rc?.value ?: "unknown"}.", logTail)
                }
            }
        }
    }

    private fun handlePause(segFile: File) {
        releaseWake()
        val pausedAt = resumeOffsetSec + lastStatsSec
        resumeOffsetSec = pausedAt
        framesBase += lastStatsFrame

        executor.execute {
            // A cancel can leave the final fragment truncated — copy the partial segment
            // through ffmpeg again (fragmented output) to guarantee a clean file.
            val sanitized = File(segFile.parentFile, segFile.nameWithoutExtension + "_s.mp4")
            val ok = runSync(
                FfmpegEngine.buildSanitizeCommand(segFile.absolutePath, sanitized.absolutePath)
            )
            if (ok && sanitized.isFile && sanitized.length() > 2048) {
                segFile.delete()
                segments.add(sanitized)
            } else {
                sanitized.delete()
                if (segFile.isFile && segFile.length() > 2048) {
                    segments.add(segFile)
                } else {
                    segFile.delete()
                }
            }
            if (stopRequested) {
                cancelled()
                return@execute
            }
            setState {
                it.copy(
                    phase = Phase.PAUSED,
                    message = "Paused at ${formatTime(pausedAt)} — resume anytime",
                    segments = segments.size
                )
            }
            pushNotification()
        }
    }

    private fun finalizeConversion() {
        releaseWake()
        setState {
            it.copy(phase = Phase.FINALIZING, message = "Merging segments & finalising...", progress = 100)
        }
        pushNotification()
        executor.execute {
            val final = finalFile
            if (final == null || segments.isEmpty()) {
                fail("Nothing was produced — conversion failed.")
                return@execute
            }
            val ok: Boolean = if (segments.size == 1) {
                runSync(FfmpegEngine.buildRemuxCommand(segments[0].absolutePath, final.absolutePath))
            } else {
                val listFile = File(workDir, "concat.txt")
                listFile.writeText(
                    segments.joinToString("\n") { "file '${it.absolutePath}'" } + "\n"
                )
                runSync(FfmpegEngine.buildConcatCommand(listFile.absolutePath, final.absolutePath))
            }
            if (ok && final.isFile && final.length() > 0) {
                cleanupWork()
                try {
                    MediaScannerConnection.scanFile(
                        applicationContext, arrayOf(final.absolutePath), arrayOf("video/mp4")
                    ) { _, _ -> }
                } catch (_: Exception) {
                }
                setState {
                    it.copy(
                        phase = Phase.DONE,
                        message = "Complete",
                        output = final.absolutePath,
                        progress = 100
                    )
                }
                finishService(true)
            } else {
                final.delete()
                fail("Merging segments failed.")
            }
        }
    }

    // ── commands ────────────────────────────────────────────────────────────────

    private fun requestPause() {
        if (!active) return
        if (state.value.phase != Phase.RUNNING) return
        synchronized(lock) { pauseRequested = true }
        if (sessionId >= 0) {
            try {
                FFmpegKit.cancel(sessionId)
            } catch (_: Exception) {
            }
        }
    }

    private fun requestResume() {
        if (!active) return
        if (state.value.phase != Phase.PAUSED) return
        synchronized(lock) { stopRequested = false }
        setState { it.copy(phase = Phase.RUNNING, message = "Encoding ${it.formatLabel} BT.2020 frames...") }
        startNextSegment()
    }

    private fun requestStop() {
        if (!active) return
        synchronized(lock) { stopRequested = true }
        val phase = state.value.phase
        when (phase) {
            Phase.PAUSED -> cancelled()
            Phase.PROBING -> cancelled()
            else -> if (sessionId >= 0) {
                try {
                    FFmpegKit.cancel(sessionId)
                } catch (_: Exception) {
                }
                // if no live callback arrives, hard-stop after a grace period
                main.postDelayed({ if (active) cancelled() }, 2500)
            }
        }
    }

    private fun cancelled() {
        if (!active) return
        cleanupAll()
        setState {
            it.copy(
                phase = Phase.CANCELLED,
                message = "Process stopped · incomplete video deleted."
            )
        }
        finishService(false)
    }

    private fun fail(msg: String, logTail: String = "") {
        if (!active) return
        cleanupAll()
        setState {
            it.copy(phase = Phase.FAILED, message = msg, logTail = logTail, output = null)
        }
        finishService(false)
    }

    private fun finishService(success: Boolean) {
        active = false
        isActive = false
        releaseWake()
        stopForeground(true)
        if (success) {
            val s = state.value
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle("Conversion complete")
                .setContentText("${s.formatLabel} · ${s.output?.let { File(it).name } ?: ""}")
                .setAutoCancel(true)
                .setContentIntent(mainActivityPi())
                .build()
            try {
                notificationManager?.notify(DONE_NOTIF_ID, n)
            } catch (_: Exception) {
            }
        }
        stopSelf()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun runSync(cmd: String): Boolean = try {
        val s = FFmpegKit.execute(cmd)
        ReturnCode.isSuccess(s.returnCode)
    } catch (_: Exception) {
        false
    }

    private fun isColorError(logs: String): Boolean =
        logs.contains("zscale", ignoreCase = true) ||
            logs.contains("color family", ignoreCase = true) ||
            logs.contains("no path between colorspaces", ignoreCase = true)

    private fun setState(transform: (ConversionState) -> ConversionState) {
        state.value = transform(state.value)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifUpdate > 700) {
            lastNotifUpdate = now
            pushNotification()
        }
    }

    private fun formatTime(sec: Double): String =
        String.format(Locale.US, "%02d:%02d", (sec / 60).toInt(), (sec % 60).toInt())

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sdr2hdr:convert")
                .also { it.acquire(3 * 60 * 60 * 1000L) }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun cleanupWork() {
        workDir?.takeIf { it.isDirectory }?.deleteRecursively()
        workDir = null
    }

    private fun cleanupAll() {
        cleanupWork()
        finalFile?.delete()
    }

    // ── notification ────────────────────────────────────────────────────────────

    private fun startForegroundInternal() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun mainActivityPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun actionPi(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, ConvertService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val s = state.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle("SDR2HDR · ${s.inputName.ifEmpty { "Converting" }}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPi())

        when (s.phase) {
            Phase.PROBING -> {
                builder.setContentText("Analyzing input...")
                    .setProgress(0, 0, true)
                    .addAction(0, "Stop", actionPi(ACTION_STOP, 3))
            }
            Phase.RUNNING -> {
                builder.setContentText("${s.progress}% · ${s.formatLabel} · %.1fx".format(Locale.US, s.fps / 30.0))
                    .setProgress(100, s.progress, false)
                    .addAction(0, "Pause", actionPi(ACTION_PAUSE, 1))
                    .addAction(0, "Stop", actionPi(ACTION_STOP, 3))
            }
            Phase.PAUSED -> {
                builder.setContentText("Paused — ${s.message}")
                    .setProgress(100, s.progress, false)
                    .addAction(0, "Resume", actionPi(ACTION_RESUME, 2))
                    .addAction(0, "Stop", actionPi(ACTION_STOP, 3))
            }
            Phase.FINALIZING -> {
                builder.setContentText("Merging segments...")
                    .setProgress(0, 0, true)
            }
            else -> {
                builder.setContentText(s.message.ifEmpty { "Preparing..." })
                    .setProgress(0, 0, s.progress == 0)
            }
        }
        return builder.build()
    }

    private fun pushNotification() {
        try {
            notificationManager?.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
