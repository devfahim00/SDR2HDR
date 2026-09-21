package com.devfahim00.sdr2hdr

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Checks devfahim00/SDR2HDR GitHub releases for a newer APK.
 * Runs on a background thread (blocking HTTP) and returns a [CheckResult]:
 *  - [CheckResult.Ok]       — GitHub answered. [CheckResult.Ok.release] is null when
 *                             the repo has no published releases yet (a 404 on
 *                             /releases/latest is NOT a connectivity problem).
 *  - [CheckResult.Error]    — request actually failed (timeout / no route / HTTP 5xx).
 */
object UpdateChecker {

    private const val REPO = "devfahim00/SDR2HDR"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_ALL = "https://api.github.com/repos/$REPO/releases"
    private const val PAGE_URL = "https://github.com/$REPO/releases"
    private const val PREFS = "sdr2hdr_prefs"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val CACHE_HOURS = 6L

    /** Outcome of one check. `Ok(null)` = reachable, but no releases published. */
    sealed class CheckResult {
        data class Ok(val release: Release?) : CheckResult()
        class Error(val reason: String) : CheckResult()
    }

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
    fun check(): CheckResult {
        // 1) /releases/latest — 404 means "no release published", not "offline".
        when (val latest = request(API_LATEST)) {
            is OkResponse -> {
                val parsed = parse(latest.body)
                return if (parsed != null) CheckResult.Ok(parsed)
                else CheckResult.Error("GitHub returned an unreadable release.")
            }
            is HttpError -> {
                // fall through to the list endpoint for 404/429/5xx —
                // /releases answers 200 with [] when nothing was published.
            }
            is NetworkError -> return CheckResult.Error("No connection to GitHub (${latest.reason}).")
        }

        return when (val all = request(API_ALL)) {
            is OkResponse -> {
                val first = parseList(all.body)
                CheckResult.Ok(first) // null when the list is empty
            }
            is HttpError -> CheckResult.Error("GitHub answered HTTP ${all.code}.")
            is NetworkError -> CheckResult.Error("No connection to GitHub (${all.reason}).")
        }
    }

    // ── tiny response wrappers ────────────────────────────────────────────────

    private sealed class Resp
    private class OkResponse(val body: String) : Resp()
    private class HttpError(val code: Int) : Resp()
    private class NetworkError(val reason: String) : Resp()

    private fun request(url: String): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SDR2HDR-Android")
            }
            val code = conn.responseCode
            if (code == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { r ->
                    val sb = StringBuilder()
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    sb.toString()
                }
                OkResponse(body)
            } else {
                HttpError(code)
            }
        } catch (e: Exception) {
            NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    private fun parse(body: String): Release? = tryOrNull { parseRelease(JSONObject(body)) }

    private fun parseList(body: String): Release? = tryOrNull {
        val arr = org.json.JSONArray(body)
        if (arr.length() == 0) null
        else arr.optJSONObject(0)?.let { parseRelease(it) }
    }

    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private fun parseRelease(o: JSONObject): Release? {
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
        return Release(tag = tag, name = name, notes = notes, apkUrl = apk, pageUrl = page)
    }
}
