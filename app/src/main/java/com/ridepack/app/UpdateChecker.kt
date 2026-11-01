package com.ridepack.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** One published GitHub release of the app. */
data class ReleaseInfo(
    val versionName: String,   // e.g. "3.13.0"
    val versionCode: Int,
    val body: String,           // release notes / changelog text
    val htmlUrl: String,        // release page on GitHub
    val apkUrl: String          // direct download URL of the APK asset
)

/**
 * Checks GitHub for the latest published release of RidePack and, when it is newer than the
 * installed version, offers a one-tap install of the downloaded APK. The release notes are
 * shown so the rider knows what changed.
 */
object UpdateChecker {
    val latest = mutableStateOf<ReleaseInfo?>(null)
    val checking = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val open = mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())

    fun check(ctx: Context) {
        Log.d("UpdateChecker", "check() entered")
        checking.value = true
        error.value = null
        Thread {
            Log.d("UpdateChecker", "check thread start")
            val rel = try { fetchLatest() } catch (e: Exception) { Log.e("UpdateChecker", "fetch failed", e); null }
            val inst = try { installed(ctx) } catch (e: Exception) { Log.e("UpdateChecker", "installed failed", e); null }
            latest.value = rel
            if (rel == null) { error.value = "Could not reach GitHub to check for updates"; checking.value = false; return@Thread }
            checking.value = false
            Log.d("UpdateChecker", "latest=${rel.versionName} installed=${inst?.versionName} newer=${isNewer(rel, inst)}")
            if (isNewer(rel, inst)) { Log.d("UpdateChecker", "NEWER -> open dialog"); open.value = true }
        }.start()
    }

    private fun installed(ctx: Context): ReleaseInfo? = try {
        val pi: PackageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        ReleaseInfo(pi.versionName ?: "0", pi.versionCode, "", "", "")
    } catch (e: Exception) { null }

    /** Version strings like "3.13.0" -> comparable list of ints, so 3.13.0 > 3.12.0. */
    private fun vTuple(v: String): List<Int> = v.split(".").map { it.toIntOrNull() ?: 0 }

    /** True when [l] is newer than the installed [i]. */
    private fun isNewer(l: ReleaseInfo, i: ReleaseInfo?): Boolean {
        val lt = vTuple(l.versionName); val it = i?.let { vTuple(it.versionName) } ?: listOf(0, 0, 0)
        val n = maxOf(lt.size, it.size)
        for (k in 0 until n) {
            val x = lt.getOrNull(k) ?: 0; val y = it.getOrNull(k) ?: 0
            if (x > y) return true
            if (x < y) return false
        }
        return false
    }

    private fun fetchLatest(): ReleaseInfo {
        val c = URL("https://api.github.com/repos/Adarsh-ER/RidePack/releases/latest").openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "RidePack-Android"); c.setRequestProperty("Accept", "application/vnd.github+json")
        val t = c.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(t)
        var apkUrl = ""
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
            }
        }
        val tag = o.optString("tag_name")
        val vn = tag.removePrefix("v")
        return ReleaseInfo(vn, 0, o.optString("body").trim(), o.optString("html_url"), apkUrl)
    }

    /** Download the latest APK from GitHub and hand it to the system installer. */
    fun install(ctx: Context) {
        val url = latest.value?.apkUrl ?: return
        Thread {
            try {
                val f = File(ctx.cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = 30_000; c.readTimeout = 90_000
                c.setRequestProperty("User-Agent", "RidePack-Android")
                c.instanceFollowRedirects = true
                c.connect()
                if (c.responseCode != HttpURLConnection.HTTP_OK) {
                    error.value = "Download failed (HTTP ${c.responseCode})"
                    return@Thread
                }
                FileOutputStream(f).use { o -> c.inputStream.copyTo(o) }
                // Verify it's a real APK (ZIP magic), not an error page.
                val magic = f.readBytes().take(4)
                if (magic.size < 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                    error.value = "Downloaded file is not a valid APK"
                    return@Thread
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.ridepack.app.fileprovider", f)
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    type = "application/vnd.android.package-archive"
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Log.e("UpdateChecker", "install failed", e)
                error.value = "Could not download the update: ${e.message}"
            }
        }.start()
    }
}
