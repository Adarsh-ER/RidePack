package com.ridepack.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Version of the MapLibre SDK in build.gradle.kts. Keep in step with that dependency. */
const val MAPLIBRE_VERSION = "11.5.2"

/** Thresholds for diagnostic warnings. They only label evidence for review; they never change navigation. */
data class DiagThresholds(
    val progressJumpM: Double = 150.0,   // one accepted update moved progress forward by more than this
    val backwardM: Double = 25.0,        // progress moved backward by more than this
    val offRouteM: Double = 60.0,        // GPS fix further than this from the matched route segment
    val poorAccuracyM: Double = 25.0,    // GPS accuracy worse than this
    val maxWarnings: Int = 200
)

/** Values shown on the debug overlay and in screenshot captions. */
data class DiagLive(
    val accuracyM: Double? = null,
    val speedKmh: Double? = null,
    val progressM: Double? = null,
    val offRouteM: Double? = null,
    val segment: Int? = null,
    val fallback: Boolean = false,
    val follow: Boolean = false
)

/** Builds one JSON record. Non-finite numbers become null so every line stays valid JSON. */
object DiagJson {
    fun build(type: String, session: String?, fields: Map<String, Any?>, nowMs: Long): JSONObject {
        val o = JSONObject()
        o.put("t", Instant.ofEpochMilli(nowMs).toString())
        o.put("session", session ?: JSONObject.NULL)
        o.put("type", type)
        for ((k, v) in fields) o.put(k, safe(v))
        return o
    }

    private fun safe(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Double -> if (v.isFinite()) v else JSONObject.NULL
        is Float -> if (v.isFinite()) v.toDouble() else JSONObject.NULL
        else -> v
    }
}

/** Local, size-limited storage for one session. Writes run on a background thread in batches. */
class DiagStore(private val root: File, private val maxFileBytes: Long = 2_000_000L) {
    companion object {
        const val LOG = "gps_log.jsonl"
        const val ROTATED = "gps_log.1.jsonl"
        const val SHOTS = "screenshots"
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-io").apply { isDaemon = true } }
    private val buffer = ArrayList<String>()
    private var lastFlushMs = 0L
    @Volatile var sessionId: String? = null; private set
    @Volatile var lastError: String? = null; private set

    val sessionDir: File? get() = sessionId?.let { File(root, it) }
    val shotDir: File? get() = sessionDir?.let { File(it, SHOTS) }
    private fun logFile(): File? = sessionDir?.let { File(it, LOG) }

    /** Creates or reopens a session folder. Returns false (and records [lastError]) on storage failure. */
    fun open(id: String): Boolean = try {
        val d = File(root, id)
        File(d, SHOTS).mkdirs()
        if (!d.isDirectory) throw java.io.IOException("cannot create ${d.path}")
        sessionId = id; lastError = null
        true
    } catch (e: Exception) {
        lastError = "storage: ${e.message}"
        false
    }

    /** Queues one JSON line. Lines are written in batches (20 lines or every 2 s), never on the caller's thread. */
    fun append(line: String) {
        val batch = synchronized(buffer) {
            buffer.add(line)
            if (buffer.size >= 20 || System.currentTimeMillis() - lastFlushMs > 2000) takeBatch() else null
        }
        batch?.let { (f, lines) -> io.execute { write(f, lines) } }
    }

    /** Writes everything queued so far and waits for it. Call from a background thread only. */
    fun flush() {
        val batch = synchronized(buffer) { takeBatch() }
        io.submit { batch?.let { (f, lines) -> write(f, lines) } }.get()
    }

    /** Must be called while holding the lock on [buffer]. */
    private fun takeBatch(): Pair<File, List<String>>? {
        if (buffer.isEmpty()) return null
        val lines = ArrayList(buffer)
        buffer.clear()
        lastFlushMs = System.currentTimeMillis()
        val f = logFile() ?: return null
        return f to lines
    }

    private fun write(target: File, lines: List<String>) {
        try {
            // Rotate: keep one previous file so the total log stays at about twice maxFileBytes.
            if (target.exists() && target.length() > maxFileBytes) {
                File(target.parentFile, ROTATED).delete()
                target.renameTo(File(target.parentFile, ROTATED))
            }
            FileOutputStream(target, true).bufferedWriter().use { w ->
                lines.forEach { w.write(it); w.write("\n") }
            }
            lastError = null
        } catch (e: Exception) {
            lastError = "log write failed: ${e.message}"
        }
    }

    /** Deletes every session. Call from a background thread. */
    fun deleteAll(): Boolean {
        synchronized(buffer) { buffer.clear() }
        sessionId = null
        return !root.exists() || root.deleteRecursively()
    }
}

/** Reads the record stream and flags events worth a look. Warnings are diagnostic labels, not confirmed bugs. */
class DiagAnalyzer(private val th: DiagThresholds = DiagThresholds()) {
    data class Warning(val time: String, val kind: String, val detail: String)

    val warnings = ArrayList<Warning>()
    val counts = LinkedHashMap<String, Int>()
    var firstTime: String? = null; private set
    var lastTime: String? = null; private set
    var routePoints: Int? = null; private set
    var routeTotalM: Double? = null; private set
    private var poorAccuracy = false
    private var inFallback = false

    private fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }

    private fun warn(time: String, kind: String, detail: String) {
        count("warning:$kind")
        if (warnings.size < th.maxWarnings) warnings.add(Warning(time, kind, detail))
    }

    fun onRecord(rec: JSONObject) {
        val t = rec.optString("t")
        val type = rec.optString("type")
        if (firstTime == null) firstTime = t
        lastTime = t
        count(type)
        when (type) {
            "gps" -> onGps(t, rec)
            "route" -> if (rec.optString("action") == "cleared") {
                routePoints = null; routeTotalM = null
            } else {
                routePoints = rec.num("points")?.toInt(); routeTotalM = rec.num("total_m")
            }
            "gps_rejected" -> count("rejected:" + rec.optString("reason"))
            "error" -> warn(t, "exception", "${rec.optString("where")}: ${rec.optString("message")}")
        }
    }

    private fun onGps(t: String, rec: JSONObject) {
        val delta = rec.num("progress_delta_m")
        if (delta != null) {
            if (delta > th.progressJumpM) warn(t, "progress_jump", "progress +${f0(delta)} m in one update")
            if (delta < -th.backwardM) warn(t, "progress_backward", "progress ${f0(delta)} m in one update")
        }
        val off = rec.num("distance_to_route_m")
        if (off != null && off > th.offRouteM) warn(t, "far_from_route", "fix ${f0(off)} m from the matched route")
        val acc = rec.num("acc_m")
        if (acc != null) {
            val poor = acc > th.poorAccuracyM
            if (poor && !poorAccuracy) warn(t, "poor_accuracy", "GPS accuracy ${f0(acc)} m")
            poorAccuracy = poor
        }
        val fallback = rec.has("fallback") && rec.optBoolean("fallback")
        if (fallback) {
            count("matcher_fallback")
            if (!inFallback) warn(t, "matcher_fallback", "nearest-segment fallback used")
        }
        inFallback = fallback
    }

    fun summaryLines(): List<String> {
        val out = mutableListOf<String>()
        out += "Records by type:"
        counts.filterKeys { !it.startsWith("warning:") }.forEach { (k, v) -> out += "  $k: $v" }
        out += "Diagnostic warnings (labels for review, not confirmed bugs):"
        val kinds = counts.filterKeys { it.startsWith("warning:") }
        if (kinds.isEmpty()) out += "  none"
        kinds.forEach { (k, v) -> out += "  ${k.removePrefix("warning:")}: $v" }
        out += "Thresholds: progress jump > ${th.progressJumpM} m, backward > ${th.backwardM} m, " +
            "off route > ${th.offRouteM} m, poor accuracy > ${th.poorAccuracyM} m"
        warnings.take(50).forEach { out += "  [${it.time}] ${it.kind}: ${it.detail}" }
        if (warnings.size > 50) out += "  ... ${warnings.size - 50} more in the log"
        return out
    }

    private fun JSONObject.num(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
}

/** Writes a shareable ZIP: the session's logs, its screenshots, and a summary. */
object DiagReport {
    fun writeZip(sessionDir: File, summary: String, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { z ->
            z.putNextEntry(ZipEntry("summary.txt"))
            z.write(summary.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            sessionDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }?.forEach { z.addFile(it.name, it) }
            File(sessionDir, DiagStore.SHOTS).listFiles()?.filter { it.isFile }
                ?.sortedBy { it.name }?.forEach { z.addFile("screenshots/${it.name}", it) }
        }
    }

    private fun ZipOutputStream.addFile(name: String, f: File) {
        putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}

/** Session logic: start, stop, records and analysis. Pure Kotlin, so it is unit tested without a device. */
class DiagRecorder(root: File, private val th: DiagThresholds = DiagThresholds()) {
    val store = DiagStore(root)
    var analyzer = DiagAnalyzer(th); private set
    @Volatile var recording = false; private set
    var startedMs = 0L; private set
    var stoppedMs: Long? = null; private set
    /** Number of GPS records written in the current session. */
    var samples = 0; private set

    /** Starts a session, or continues [id] after an app restart (then [resumed] is true). */
    @Synchronized fun start(
        id: String,
        device: Map<String, Any?> = emptyMap(),
        resumed: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (recording) return true
        if (!store.open(id)) return false
        analyzer = DiagAnalyzer(th)
        startedMs = nowMs; stoppedMs = null; samples = 0
        recording = true
        event(if (resumed) "app_restart" else "session_start", device, nowMs)
        return true
    }

    @Synchronized fun stop(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!recording) return false
        event("session_stop", emptyMap(), nowMs)
        recording = false
        stoppedMs = nowMs
        return true
    }

    /** Records one event. Does nothing while logging is off. */
    @Synchronized fun event(type: String, fields: Map<String, Any?> = emptyMap(), nowMs: Long = System.currentTimeMillis()) {
        if (!recording) return
        val rec = DiagJson.build(type, store.sessionId, fields, nowMs)
        analyzer.onRecord(rec)
        if (type == "gps") samples++
        store.append(rec.toString())
    }

    /** Human-readable summary: session, timing, device, route, counts and warnings. */
    @Synchronized fun summary(device: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        sb.appendLine("RidePack debug report")
        sb.appendLine("PRIVACY: this report contains your precise location history. Share it only with people you trust.")
        sb.appendLine()
        sb.appendLine("Session: ${store.sessionId ?: "none"}")
        sb.appendLine("Logging started: ${startedMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: "n/a"}")
        sb.appendLine("Logging ended: ${stoppedMs?.let { Instant.ofEpochMilli(it).toString() } ?: if (recording) "still running" else "n/a"}")
        sb.appendLine("GPS samples recorded: $samples")
        device.forEach { (k, v) -> sb.appendLine("$k: $v") }
        sb.appendLine("Route points (last loaded): ${analyzer.routePoints ?: "n/a"}")
        sb.appendLine("Route distance (last loaded): ${analyzer.routeTotalM?.let { String.format(Locale.US, "%.0f m", it) } ?: "n/a"}")
        sb.appendLine("Report created: ${Instant.ofEpochMilli(nowMs)}")
        sb.appendLine()
        analyzer.summaryLines().forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun flushSync() = store.flush()
}

/** App-facing diagnostics: session control, overlay state, screenshots and report export. */
object Diag {
    const val MAX_SHOTS = 20
    private const val TAG = "RidePackDiag"

    /** True while a session is recording. Compose reads this for the REC indicator. */
    val recording = mutableStateOf(false)
    /** True when the on-map overlay is switched on. */
    val overlay = mutableStateOf(false)
    /** Latest matcher values for the overlay and screenshot captions. */
    val live = mutableStateOf(DiagLive())
    val sampleCount = mutableStateOf(0)

    private lateinit var appCtx: Context
    private lateinit var rec: DiagRecorder
    private val main = Handler(Looper.getMainLooper())
    private val ready: Boolean get() = ::rec.isInitialized

    // Last values logged for the camera, so only meaningful changes are written.
    private var lastFollow: Boolean? = null
    private var lastCamLat = Double.NaN
    private var lastCamLon = 0.0
    private var lastZoom = 0.0
    private var lastBearing = 0.0

    /** Call once from MainActivity.onCreate after Store.init. Resumes logging if it was running before a restart. */
    fun init(c: Context) {
        appCtx = c.applicationContext
        rec = DiagRecorder(File(c.filesDir, "diag"))
        overlay.value = Store.debugOverlay
        val active = Store.debugSession
        if (active != null) {
            if (rec.start(active, deviceInfo(), resumed = true)) recording.value = true
            else Store.debugSession = null
        }
    }

    /** Whether per-fix diagnostics need to be computed (logging or the overlay is on). */
    fun wantLive(): Boolean = overlay.value || recording.value

    fun deviceInfo(): Map<String, Any?> {
        val version = try {
            @Suppress("DEPRECATION")
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).let { it.versionName to it.versionCode }
        } catch (e: Exception) { null to null }
        return mapOf(
            "app_version" to version.first,
            "app_version_code" to version.second,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "maplibre" to MAPLIBRE_VERSION
        )
    }

    /** Starts a new session. Returns an error message, or null on success. */
    fun start(): String? {
        if (!ready) return "Debug logging is not ready yet."
        if (recording.value) return null
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(4)
        if (!rec.start(id, deviceInfo())) return "Could not start logging: ${rec.store.lastError ?: "storage error"}"
        Store.debugSession = id
        lastFollow = null; lastCamLat = Double.NaN
        sampleCount.value = 0
        recording.value = true
        return null
    }

    /** Stops the session and writes out anything still buffered, in the background. */
    fun stop() {
        if (!ready || !rec.stop()) return
        Store.debugSession = null
        recording.value = false
        Thread { rec.flushSync() }.start()
    }

    fun setOverlay(on: Boolean) {
        overlay.value = on
        Store.debugOverlay = on
    }

    fun hasSession(): Boolean = ready && rec.store.sessionDir?.isDirectory == true

    private fun ev(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (ready) rec.event(type, fields)
    }

    /** One GPS sample with its matcher details (see MapHolder). */
    fun gps(fields: Map<String, Any?>) {
        ev("gps", fields)
        main.post { sampleCount.value = if (ready) rec.samples else 0 }
    }

    /** A location fix that was ignored, with the reason. */
    fun rejected(l: Location, reason: String) {
        ev("gps_rejected", mapOf(
            "reason" to reason,
            "lat" to l.latitude,
            "lon" to l.longitude,
            "acc_m" to (if (l.hasAccuracy()) l.accuracy.toDouble() else null),
            "speed_ms" to (if (l.hasSpeed()) l.speed.toDouble() else null)
        ))
    }

    fun route(action: String, points: Int?, totalM: Double?) {
        ev("route", mapOf("action" to action, "points" to points, "total_m" to totalM))
    }

    fun event(type: String, fields: Map<String, Any?> = emptyMap()) = ev(type, fields)

    /** Logs a camera change, but only when it moved, zoomed or turned meaningfully. */
    fun camera(lat: Double, lon: Double, zoom: Double, bearing: Double, follow: Boolean) {
        if (!recording.value) return
        if (lastFollow != follow) {
            lastFollow = follow
            ev("camera_follow", mapOf("follow" to follow))
        }
        val turn = abs(((bearing - lastBearing + 540.0) % 360.0) - 180.0)
        val moved = lastCamLat.isNaN() ||
            Geo.dist(P(lastCamLat, lastCamLon), P(lat, lon)) > 5.0 ||
            abs(zoom - lastZoom) > 0.05 || turn > 2.0
        if (moved) {
            lastCamLat = lat; lastCamLon = lon; lastZoom = zoom; lastBearing = bearing
            ev("camera", mapOf("lat" to lat, "lon" to lon, "zoom" to zoom, "bearing" to bearing))
        }
    }

    fun error(where: String, e: Throwable) {
        Log.e(TAG, "$where failed", e)
        ev("error", mapOf("where" to where, "message" to (e.message ?: e.javaClass.simpleName)))
    }

    private fun <T> reply(onDone: (Result<T>) -> Unit, r: Result<T>) {
        main.post { onDone(r) }
    }

    /** Captures the rendered window (map, rider marker, route lines and overlays) into the session folder. */
    fun screenshot(activity: Activity, onDone: (Result<File>) -> Unit) {
        if (!recording.value) {
            reply(onDone, Result.failure<File>(IllegalStateException("Start logging first, then capture."))); return
        }
        val dir = rec.store.shotDir
        if (dir == null) {
            reply(onDone, Result.failure<File>(IllegalStateException("No logging session folder."))); return
        }
        if ((dir.listFiles()?.size ?: 0) >= MAX_SHOTS) {
            reply(onDone, Result.failure<File>(IllegalStateException("Screenshot limit reached ($MAX_SHOTS per session)."))); return
        }
        val window = activity.window
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) {
            reply(onDone, Result.failure<File>(IllegalStateException("The screen is not ready, try again."))); return
        }
        val bmp = try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            reply(onDone, Result.failure<File>(IllegalStateException("Not enough memory for a screenshot."))); return
        }
        try {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    Thread { saveShot(bmp, dir, onDone) }.start()
                } else {
                    reply(onDone, Result.failure<File>(IllegalStateException("Capture failed (code $result).")))
                }
            }, main)
        } catch (e: IllegalArgumentException) {
            reply(onDone, Result.failure<File>(IllegalStateException("Capture is not possible here: ${e.message}")))
        }
    }

    private fun saveShot(bmp: Bitmap, dir: File, onDone: (Result<File>) -> Unit) {
        try {
            drawCaption(bmp)
            val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            ev("screenshot", mapOf("file" to name))
            reply(onDone, Result.success(f))
        } catch (e: Exception) {
            reply(onDone, Result.failure<File>(e))
        }
    }

    /** Timestamp, progress, accuracy and matcher state, burned into the bottom of the screenshot. */
    private fun drawCaption(bmp: Bitmap) {
        val l = live.value
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val lines = listOf(
            "RidePack debug · $now · session ${if (ready) rec.store.sessionId ?: "-" else "-"}",
            "Progress ${l.progressM?.let { "%.0f m".format(Locale.US, it) } ?: "-"} · off route ${l.offRouteM?.let { "%.0f m".format(Locale.US, it) } ?: "-"} · GPS ±${l.accuracyM?.let { "%.0f".format(Locale.US, it) } ?: "-"} m",
            "Speed ${l.speedKmh?.let { "%.0f".format(Locale.US, it) } ?: "-"} km/h · segment ${l.segment ?: "-"} · fallback ${if (l.fallback) "yes" else "no"} · follow ${if (l.follow) "on" else "off"}"
        )
        val density = appCtx.resources.displayMetrics.density
        val pad = 8 * density
        val lineH = 20 * density
        val top = bmp.height - (pad * 2 + lineH * lines.size)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, top, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply { color = Color.argb(200, 0, 0, 0) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * density }
        lines.forEachIndexed { i, s -> canvas.drawText(s, pad, top + pad + lineH * (i + 1) - 4 * density, text) }
    }

    /** Writes the session logs, screenshots and summary into a ZIP for sharing. */
    fun export(onDone: (Result<File>) -> Unit) {
        val dir = if (ready) rec.store.sessionDir else null
        if (dir == null || !dir.isDirectory) {
            reply(onDone, Result.failure<File>(IllegalStateException("No logging session yet. Start logging first."))); return
        }
        Thread {
            try {
                val name = "RidePack-debug-${dir.name}.zip"
                ev("report_exported", mapOf("file" to name))
                rec.flushSync()
                val out = File(File(appCtx.cacheDir, "diag-export"), name)
                DiagReport.writeZip(dir, rec.summary(deviceInfo()), out)
                reply(onDone, Result.success(out))
            } catch (e: Exception) {
                reply(onDone, Result.failure<File>(e))
            }
        }.start()
    }

    /** Opens the Android share sheet for the ZIP through the app's FileProvider (no storage permission). */
    fun share(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, "com.ridepack.app.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(send, "Share RidePack debug report"))
    }

    /** Deletes all stored sessions. Refused while logging is running. */
    fun clearAll(onDone: (Result<Unit>) -> Unit) {
        if (recording.value || !ready) {
            reply(onDone, Result.failure<Unit>(IllegalStateException("Stop logging before clearing."))); return
        }
        Thread {
            val ok = try { rec.store.deleteAll() } catch (e: Exception) { false }
            Store.debugSession = null
            main.post { sampleCount.value = 0 }
            reply(onDone, if (ok) Result.success(Unit) else Result.failure<Unit>(IllegalStateException("Could not delete the logs.")))
        }.start()
    }
}
