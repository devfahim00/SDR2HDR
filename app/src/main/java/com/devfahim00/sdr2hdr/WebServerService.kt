package com.devfahim00.sdr2hdr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs the local-network control server: a tiny HTTP server on a random free port that
 * serves the bundled web UI (assets/web/index.html) and a JSON API wired to the same
 * [ConvertService] the app UI uses. Any browser on the same Wi-Fi can then pick a video,
 * configure the conversion, and start / pause / resume / stop it remotely.
 *
 * The service runs in the foreground with a persistent notification showing the URL so
 * it keeps working while the app is in the background.
 */
class WebServerService : Service() {

    companion object {
        const val ACTION_START = "com.devfahim00.sdr2hdr.SERVER_START"
        const val ACTION_STOP = "com.devfahim00.sdr2hdr.SERVER_STOP"

        private const val CHANNEL_ID = "webserver"
        private const val NOTIF_ID = 51

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
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

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

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine()
            if (requestLine == null) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0 && contentLength < 1 shl 20) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }

            val (status, mime, payload) = route(method, path, body)
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
            val head = "HTTP/1.1 $status\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
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
                val files = VideoRepository.queryAll(this)
                val arr = JSONArray()
                for (f in files.take(400)) {
                    val o = JSONObject()
                    o.put("path", f.path)
                    o.put("name", f.name)
                    o.put("size", f.sizeBytes)
                    o.put("duration", f.durationMs)
                    o.put("width", f.width)
                    o.put("height", f.height)
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
