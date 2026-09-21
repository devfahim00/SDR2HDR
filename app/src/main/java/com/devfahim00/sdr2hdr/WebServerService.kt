package com.devfahim00.sdr2hdr

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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs the local-network control server: a tiny HTTP server on a random free port that
 * serves the bundled web UI (assets/web/index.html) and a JSON API wired to the same
 * [ConvertService] the app UI uses. Any browser on the same Wi-Fi can then pick a video
 * (or upload one from that device), configure the conversion, and start / pause /
 * resume / stop it remotely — or render on the connecting PC itself (the web UI ships a
 * WebCodecs pipeline; the phone then only serves bytes).
 *
 * Binary-safe: the request head is parsed byte-wise, so large raw-body uploads
 * (POST /api/upload) and file downloads (GET /api/download) never pass through a
 * text Reader.
 */
class WebServerService : Service() {

    companion object {
        const val ACTION_START = "com.devfahim00.sdr2hdr.SERVER_START"
        const val ACTION_STOP = "com.devfahim00.sdr2hdr.SERVER_STOP"

        private const val CHANNEL_ID = "webserver"
        private const val NOTIF_ID = 51
        private const val MAX_JSON_BODY = 1 shl 20
        private const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024 * 1024 // 8 GB guard
        private const val MAX_HEAD_BYTES = 64 * 1024

        /** "http://192.168.1.4:39127" while the server runs, null otherwise. */
        @Volatile
        var url: String? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, WebServerService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }

        /** First site-local IPv4 address (typical phone Wi-Fi address). */
        fun localIp(): String? = try {
            val all = NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
            all.firstOrNull {
                it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress
            }?.hostAddress
        } catch (_: Exception) {
            null
        }

        /** Where uploaded remote videos land (public when All-Files access is granted). */
        fun uploadDir(context: Context): File {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "SDR2HDR_Uploads"
            )
            if (publicDir.mkdirs() || publicDir.isDirectory) {
                // mkdirs() returning false is fine when the dir already exists; verify
                // by writing nothing and trusting isDirectory
                return publicDir
            }
            val fallback = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "SDR2HDR_Uploads"
            )
            fallback.mkdirs()
            return fallback
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(6)

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            CHANNEL_ID, "Local server", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Local network web server for remote control" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                // must enter the foreground immediately after startForegroundService()
                startForegroundInternal()
                if (!isRunning) startServer()
                // the URL may have just changed - refresh the notification
                if (isRunning) startForegroundInternal()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ── server lifecycle ───────────────────────────────────────────────────────

    private fun startServer() {
        try {
            // port 0 = let the OS pick a random free port; bind all interfaces so any
            // device on the LAN can reach it
            val socket = ServerSocket(0, 8)
            serverSocket = socket
            isRunning = true
            url = "http://${localIp() ?: "localhost"}:${socket.localPort}"
            acceptThread = Thread({ acceptLoop(socket) }, "web-server").apply { start() }
        } catch (e: Exception) {
            url = null
            isRunning = false
            stopSelf()
        }
    }

    private fun shutdown() {
        isRunning = false
        url = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        stopForeground(true)
        stopSelf()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning && !socket.isClosed) {
            try {
                val client = socket.accept()
                pool.execute { handle(client) }
            } catch (_: Exception) {
                // socket closed or transient accept error
            }
        }
    }

    // ── HTTP plumbing (binary-safe) ─────────────────────────────────────────────

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val raw: InputStream,
        val contentLength: Long
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    /** Reads the request line + headers byte-wise, leaving the body unread in [raw]. */
    private fun readHead(input: InputStream): Request? {
        val head = ByteArray(MAX_HEAD_BYTES)
        var len = 0
        while (len < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            head[len++] = b.toByte()
            // found terminating \r\n\r\n?
            if (len >= 4) {
                val p = len - 4
                if (head[p] == 13.toByte() && head[p + 1] == 10.toByte() &&
                    head[p + 2] == 13.toByte() && head[p + 3] == 10.toByte()
                ) break
            }
        }
        val text = String(head, 0, len, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.getOrNull(0) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, String>()
        val q = target.substringAfter('?', "")
        if (q.isNotEmpty()) {
            for (pair in q.split('&')) {
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                if (k.isNotEmpty()) query[k] = URLDecoder.decode(v, "UTF-8")
            }
        }
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", true) }
            ?.value?.toLongOrNull() ?: 0L
        return Request(parts[0], path, query, headers, input, contentLength)
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val req = readHead(input) ?: return

            // file streaming has its own responder (no full buffering)
            if (req.method == "GET" && req.path == "/api/download") {
                streamDownload(req, client)
                return
            }

            // raw-body upload: bytes go straight to disk, never into a String
            if (req.method == "POST" && req.path == "/api/upload") {
                val (status, json) = receiveUpload(req)
                respondJson(client, status, json)
                return
            }

            val body = if (req.contentLength > 0 && req.contentLength < MAX_JSON_BODY) {
                val buf = ByteArray(req.contentLength.toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

            val (status, mime, payload) = route(req.method, req.path, body)
            respond(client, status, mime, payload)
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respond(client: Socket, status: String, mime: String, payload: ByteArray) {
        try {
            val out: OutputStream = client.getOutputStream()
            writeHead(out, status, mime, payload.size.toLong(), null)
            out.write(payload)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respondJson(client: Socket, status: String, json: String) {
        respond(client, status, "application/json", json.toByteArray(Charsets.UTF_8))
    }

    private fun writeHead(
        out: OutputStream,
        status: String,
        mime: String,
        length: Long,
        extra: Map<String, String>?
    ) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Content-Length: $length\r\n")
        sb.append("Cache-Control: no-store\r\n")
        extra?.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    // ── upload / download ───────────────────────────────────────────────────────

    private fun receiveUpload(req: Request): Pair<String, String> {
        try {
            if (req.contentLength <= 0) {
                return Pair("411 Length Required", """{"ok":false,"error":"Content-Length required"}""")
            }
            if (req.contentLength > MAX_UPLOAD_BYTES) {
                return Pair("413 Payload Too Large", """{"ok":false,"error":"Upload larger than 8 GB"}""")
            }
            val rawName = URLDecoder.decode(req.header("X-Filename") ?: "", "UTF-8")
            val safe = sanitizeFilename(rawName)
                .ifEmpty { "upload_${System.currentTimeMillis()}.mp4" }
            val dir = uploadDir(this)
            if (!dir.isDirectory) {
                return Pair("500 Internal Server Error", """{"ok":false,"error":"Upload storage unavailable"}""")
            }

            var out = File(dir, safe)
            var n = 1
            val base = safe.substringBeforeLast('.')
            val ext = safe.substringAfterLast('.', "mp4")
            while (out.exists()) {
                out = File(dir, "${base}($n).$ext")
                n++
            }

            val target = File(out.absolutePath + ".part")
            FileOutputStream(target).use { fos ->
                var remaining = req.contentLength
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val got = req.raw.read(buf, 0, want)
                    if (got < 0) throw java.io.IOException("connection closed mid-upload")
                    fos.write(buf, 0, got)
                    remaining -= got
                }
                fos.fd.sync()
            }
            if (!target.renameTo(out)) {
                target.delete()
                return Pair("500 Internal Server Error", """{"ok":false,"error":"Could not save upload"}""")
            }

            // make it visible to MediaStore (gallery / the phone file pickers)
            try {
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(out.absolutePath), arrayOf("video/mp4")
                ) { _, _ -> }
            } catch (_: Exception) {
            }

            val meta = VideoUtils.probeVideo(out.absolutePath)
            val o = JSONObject()
            o.put("ok", true)
            o.put("path", out.absolutePath)
            o.put("name", out.name)
            o.put("size", out.length())
            o.put("uploaded", true)
            o.put("duration", ((meta?.durationSec ?: 0.0) * 1000).toLong())
            o.put("width", meta?.width ?: 0)
            o.put("height", meta?.height ?: 0)
            return Pair("200 OK", o.toString())
        } catch (e: Exception) {
            val o = JSONObject()
            o.put("ok", false)
            o.put("error", "Upload failed: ${e.message ?: "I/O error"}")
            return Pair("500 Internal Server Error", o.toString())
        }
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace('\\', '_')
            .replace('/', '_')
            .replace("..", "_")
            .replace(Regex("[\\u0000\\n\\r\"<>|?*]"), "_")
            .trim()
            .take(120)
        if (cleaned.isBlank()) return ""
        val ext = cleaned.substringAfterLast('.', "").lowercase(Locale.US)
        val okExt = ext in VideoUtils.VIDEO_EXTENSIONS || ext == "mp4"
        return if (okExt) cleaned else "$cleaned.mp4"
    }

    /** Streams a file straight to the browser — used to fetch inputs and results. */
    private fun streamDownload(req: Request, client: Socket) {
        try {
            val path = req.query["path"] ?: ""
            val file = File(path)
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val appFiles = getExternalFilesDir(null)?.absolutePath ?: ""
            val allowed = file.isFile && file.canRead() &&
                (file.absolutePath.startsWith(storageRoot) ||
                    file.absolutePath.startsWith(appFiles))
            if (!allowed) {
                respondJson(client, "404 Not Found", """{"ok":false,"error":"File not found"}""")
                return
            }
            val out = client.getOutputStream()
            val mime = when (file.extension.lowercase(Locale.US)) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                else -> "application/octet-stream"
            }
            val extras = HashMap<String, String>()
            val nameEnc = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            val disposition = req.query["inline"] == "1" ? "inline" : "attachment"
            extras["Content-Disposition"] = "$disposition; filename*=UTF-8''$nameEnc"
            extras["Accept-Ranges"] = "none"
            writeHead(out, "200 OK", mime, file.length(), extras)
            FileInputStreamOrNull(file)?.use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun FileInputStreamOrNull(f: File): java.io.FileInputStream? =
        try {
            java.io.FileInputStream(f)
        } catch (_: Exception) {
            null
        }

    // ── routing ─────────────────────────────────────────────────────────────────

    private fun route(
        method: String,
        path: String,
        body: String
    ): Triple<String, String, ByteArray> {
        try {
            if (path == "/" || path == "/index.html") {
                val html = assets.open("web/index.html").use { it.readBytes() }
                return Triple("200 OK", "text/html; charset=utf-8", html)
            }
            if (path == "/favicon.ico") return Triple("204 No Content", "image/x-icon", ByteArray(0))

            if (path == "/api/info" && method == "GET") {
                val o = JSONObject()
                o.put("app", "SDR2HDR")
                o.put("version", UpdateChecker.currentVersionName(this))
                o.put("device", Build.MODEL)
                o.put("serverUrl", url ?: JSONObject.NULL)
                o.put("uploadDir", uploadDir(this).absolutePath)
                o.put("serverTime", System.currentTimeMillis())
                o.put("caps", JSONObject().apply {
                    put("av1", Capability.av1Encoder != null)
                    put("hevcHw", Capability.hevcHwEncoder)
                    put("h264Hw", Capability.h264HwEncoder)
                    put("vp9", Capability.vp9Encoder)
                    put("mediacodec", Capability.mediacodecHwaccel)
                    put("vulkan", Capability.vulkanHwaccel)
                    put("dolbyVision", Capability.dolbyVisionX265)
                    put("hdr10plus", Capability.hdr10PlusX265)
                })
                return Triple("200 OK", "application/json", o.toString().toByteArray())
            }

            if (path == "/api/files" && method == "GET") {
                val files = VideoRepository.queryAll(this).toMutableList()
                // merge freshly uploaded files (MediaStore scan can lag a beat)
                val upDir = uploadDir(this)
                val known = files.map { it.path }.toHashSet()
                for (f in upDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) {
                    if (VideoUtils.isVideoFile(f) && !VideoUtils.isExcluded(f.name) &&
                        f.absolutePath !in known
                    ) {
                        files.add(
                            0,
                            VideoItem(
                                path = f.absolutePath,
                                name = f.name,
                                sizeBytes = f.length(),
                                durationMs = 0L,
                                width = 0,
                                height = 0,
                                addedSec = f.lastModified() / 1000
                            )
                        )
                    }
                }
                val arr = JSONArray()
                for (f in files.take(500)) {
                    val o = JSONObject()
                    o.put("path", f.path)
                    o.put("name", f.name)
                    o.put("size", f.sizeBytes)
                    o.put("duration", f.durationMs)
                    o.put("width", f.width)
                    o.put("height", f.height)
                    o.put("uploaded", f.path.startsWith(upDir.absolutePath))
                    arr.put(o)
                }
                val wrap = JSONObject().put("files", arr)
                return Triple("200 OK", "application/json", wrap.toString().toByteArray())
            }

            if (path == "/api/state" && method == "GET") {
                return Triple("200 OK", "application/json", stateJson().toByteArray())
            }

            if (path == "/api/start" && method == "POST") {
                if (ConvertService.isActive) {
                    return Triple(
                        "409 Conflict", "application/json",
                        """{"ok":false,"error":"A conversion is already running"}"""
                            .toByteArray()
                    )
                }
                val o = JSONObject(body)
                val input = o.optString("path")
                val file = File(input)
                if (input.isBlank() || !file.isFile) {
                    return Triple(
                        "400 Bad Request", "application/json",
                        """{"ok":false,"error":"File not found on the device"}""".toByteArray()
                    )
                }
                val config = ConvertConfig.fromJson(o.optJSONObject("config")?.toString())
                ConvertService.start(this, input, config)
                return Triple(
                    "200 OK", "application/json", """{"ok":true}""".toByteArray()
                )
            }

            if (method == "POST") {
                when (path) {
                    "/api/pause" -> ConvertService.send(this, ConvertService.ACTION_PAUSE)
                    "/api/resume" -> ConvertService.send(this, ConvertService.ACTION_RESUME)
                    "/api/stop" -> ConvertService.send(this, ConvertService.ACTION_STOP)
                    "/api/stop_server" -> mainHandler.post { shutdown() }
                    else -> return Triple(
                        "404 Not Found", "application/json",
                        """{"ok":false,"error":"Unknown endpoint"}""".toByteArray()
                    )
                }
                return Triple("200 OK", "application/json", """{"ok":true}""".toByteArray())
            }

            return Triple(
                "404 Not Found", "application/json",
                """{"ok":false,"error":"Not found"}""".toByteArray()
            )
        } catch (e: Exception) {
            return Triple(
                "500 Internal Server Error", "application/json",
                """{"ok":false,"error":"${e.message ?: "server error"}"}""".toByteArray()
            )
        }
    }

    private fun stateJson(): String {
        val s = ConvertService.state.value
        val o = JSONObject()
        o.put("phase", s.phase.name)
        o.put("active", ConvertService.isActive)
        o.put("input", s.inputName)
        o.put("message", s.message)
        o.put("progress", s.progress)
        o.put("processedSec", Math.round(s.processedSec))
        o.put("durationSec", Math.round(s.durationSec))
        o.put("fps", String.format(Locale.US, "%.1f", s.fps))
        o.put("etaSec", Math.round(s.etaSec.coerceAtLeast(0.0)))
        o.put("frames", s.frames)
        o.put("format", s.formatLabel)
        o.put("output", s.output ?: JSONObject.NULL)
        o.put("segments", s.segments)
        return o.toString()
    }

    // ── notification ─────────────────────────────────────────────────────────────

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startForegroundInternal() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle("SDR2HDR server running")
            .setContentText("Open ${url ?: ""} in any browser on this network")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(0, "Stop", stopPi())
            .build()

    private fun stopPi(): PendingIntent = PendingIntent.getForegroundService(
        this, 1,
        Intent(this, WebServerService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
