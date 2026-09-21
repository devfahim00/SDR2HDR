package com.devfahim00.sdr2hdr

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Checks devfahim00/SDR2HDR GitHub releases for a newer APK.
 * Runs on a background thread (blocking HTTP) and returns a parsed [Release].
 */
object UpdateChecker {

    private const val REPO = "devfahim00/SDR2HDR"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PAGE_URL = "https://github.com/$REPO/releases"
    private const val PREFS = "sdr2hdr_prefs"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val CACHE_HOURS = 6L

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String
    )

    fun currentVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    /** True when enough time has passed since the last check. */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CACHE_HOURS * 3600_000L
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    /**
     * Lenient semver-ish compare: numeric on dotted segments, otherwise "different = newer".
     */
    fun isNewer(current: String, tag: String): Boolean {
        val c = current.trim().removePrefix("v").removePrefix("V")
        val t = tag.trim().removePrefix("v").removePrefix("V")
        if (c == t) return false
        val cs = c.split('.').mapNotNull { it.toIntOrNull() }
        val ts = t.split('.').mapNotNull { it.toIntOrNull() }
        if (cs.size == c.split('.').size && ts.size == t.split('.').size &&
            cs.isNotEmpty() && ts.isNotEmpty()
        ) {
            for (i in 0 until maxOf(cs.size, ts.size)) {
                val a = cs.getOrNull(i) ?: 0
                val b = ts.getOrNull(i) ?: 0
                if (b != a) return b > a
            }
            return false
        }
        return true
    }

    /** Blocking network call — must run off the main thread. */
    fun check(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SDR2HDR-Android")
            }
            val code = conn.responseCode
            if (code != 200) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { r ->
                val sb = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                sb.toString()
            }
            parse(body)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Release? {
        return try {
            val o = JSONObject(body)
            val tag = o.optString("tag_name", "")
            if (tag.isBlank()) return null
            val name = o.optString("name", "").ifBlank { tag }
            val notes = o.optString("body", "").trim()
            val page = o.optString("html_url", PAGE_URL).ifBlank { PAGE_URL }
            var apk: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val an = a.optString("name", "").lowercase(Locale.US)
                    if (an.endsWith(".apk") || an.endsWith(".aab")) {
                        apk = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            Release(tag = tag, name = name, notes = notes, apkUrl = apk, pageUrl = page)
        } catch (_: Exception) {
            null
        }
    }
}
