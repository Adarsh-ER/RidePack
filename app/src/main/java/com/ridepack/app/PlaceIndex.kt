package com.ridepack.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.min

/**
 * A local index of place names around the rider, so typing a name shows nearby matches instantly:
 * no network round-trip per keystroke, and it also works in offline mode.
 *
 * The master index covers about 200 km, built in small tiles (nearest first, saved as it goes, and
 * resumed if interrupted). Once the master index exists, later refreshes only cover 50 km, which is
 * much lighter on the public OpenStreetMap servers.
 */
object PlaceIndex {
    data class Entry(val name: String, val lat: Double, val lon: Double)
    private data class Tile(val s: Double, val w: Double, val n: Double, val e: Double)

    private const val FILE = "place-index.txt"
    const val MASTER_RADIUS_M = 200_000.0
    const val REFRESH_RADIUS_M = 50_000.0
    private const val MASTER_MOVE_M = 50_000.0
    private const val REFRESH_MOVE_M = 25_000.0
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val TILE_DEG = 0.5          // about 55 km per tile
    private const val MAX_ENTRIES = 150_000

    /** Overpass mirrors, tried in order; the public servers are often busy. */
    private val endpoints = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    @Volatile var entries: List<Entry> = emptyList(); private set
    @Volatile var center: P? = null; private set
    @Volatile var builtAt: Long = 0; private set
    /** True once a full ~200 km master index has been built. */
    @Volatile var masterDone = false; private set
    @Volatile private var tilesDone = 0
    @Volatile private var loaded = false
    private val guard = AtomicBoolean(false)

    /** True while a build is running (Compose state, so the UI can show progress). */
    val isBuilding = mutableStateOf(false)

    /** Bumped whenever the index changes, so open search screens can refresh their suggestions. */
    val version = mutableStateOf(0)

    /** Radius used for the next automatic build. */
    val nextRadiusM: Double get() = if (masterDone) REFRESH_RADIUS_M else MASTER_RADIUS_M

    /** Read the cached index once. Safe to call from any thread; only the first call does the work. */
    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.isEmpty()) return
            val head = lines.first().removePrefix("#").split("|")
            builtAt = head.getOrNull(0)?.toLongOrNull() ?: 0
            val cla = head.getOrNull(1)?.toDoubleOrNull()
            val clo = head.getOrNull(2)?.toDoubleOrNull()
            if (cla != null && clo != null) center = P(cla, clo)
            masterDone = head.getOrNull(3) == "1"
            tilesDone = head.getOrNull(4)?.toIntOrNull() ?: 0
            // Caches written before the master/refresh split have no flag; a large one is a full 200 km index.
            if (head.size < 5 && lines.size >= 3_000) masterDone = true
            val list = ArrayList<Entry>(lines.size)
            for (i in 1 until lines.size) {
                val parts = lines[i].split("|")
                if (parts.size < 3) continue
                val la = parts[0].toDoubleOrNull() ?: continue
                val lo = parts[1].toDoubleOrNull() ?: continue
                val nm = parts.drop(2).joinToString("|")
                if (nm.isNotBlank()) list.add(Entry(nm, la, lo))
            }
            entries = list
            version.value++
        } catch (e: Exception) {
            entries = emptyList()
        }
    }

    private fun save(ctx: Context) {
        try {
            val sb = StringBuilder()
            sb.append('#').append(builtAt).append('|')
                .append(center?.lat ?: 0.0).append('|').append(center?.lon ?: 0.0).append('|')
                .append(if (masterDone) 1 else 0).append('|').append(tilesDone).append('\n')
            entries.forEach { sb.append(it.lat).append('|').append(it.lon).append('|').append(it.name).append('\n') }
            File(ctx.filesDir, FILE).writeText(sb.toString())
        } catch (e: Exception) {}
    }

    /** Places whose name contains [q], nearest to [near] first. */
    fun search(q: String, near: P?, limit: Int = 8): List<Entry> {
        val qq = q.trim().lowercase()
        if (qq.length < 2) return emptyList()
        val hits = entries.filter { it.name.lowercase().contains(qq) }
        if (hits.isEmpty()) return emptyList()
        val sorted = if (near == null) hits else hits.sortedBy { Geo.dist(near, P(it.lat, it.lon)) }
        return sorted.take(limit)
    }

    /** Build the index when it is missing, stale, or the rider has moved away from the cached area. */
    fun ensure(ctx: Context, near: P, onMsg: (String) -> Unit = {}) {
        if (!loaded) return   // the startup loader is still reading the cache; check again next launch
        val stale = entries.isEmpty() || System.currentTimeMillis() - builtAt > MAX_AGE_MS
        val moved = center?.let { Geo.dist(it, near) > (if (masterDone) REFRESH_MOVE_M else MASTER_MOVE_M) } ?: true
        if (stale || moved) build(ctx, near, nextRadiusM, onMsg)
    }

    /**
     * Fetch place names within [radiusM] of [near] and cache them. Runs on a background thread.
     * Work is split into small tiles, nearest first, so results appear quickly and an interrupted
     * master build resumes where it stopped instead of starting over.
     */
    fun build(ctx: Context, near: P, radiusM: Double = MASTER_RADIUS_M, onMsg: (String) -> Unit = {}) {
        if (!guard.compareAndSet(false, true)) return
        isBuilding.value = true
        onMsg(if (radiusM >= MASTER_RADIUS_M) "Building nearby place index (up to 200 km)…" else "Refreshing nearby place index (50 km)…")
        Thread {
            try {
                val resume = !masterDone && radiusM >= MASTER_RADIUS_M && entries.isNotEmpty() &&
                    center?.let { Geo.dist(it, near) < 10_000 } == true && tilesDone > 0
                val refreshing = radiusM < MASTER_RADIUS_M
                val base = if (resume) center!! else near
                val tiles = tiles(base, radiusM)
                val found = LinkedHashMap<String, Entry>()
                if (resume) {
                    entries.forEach { found[key(it)] = it }
                } else if (refreshing) {
                    // A small refresh tops up the master index instead of replacing it.
                    entries.filter { Geo.dist(near, P(it.lat, it.lon)) <= MASTER_RADIUS_M }
                        .forEach { found[key(it)] = it }
                }
                var failed = 0
                var i = if (resume) tilesDone.coerceAtMost(tiles.size) else 0
                while (i < tiles.size) {
                    val got = fetchBox(tiles[i], base)
                    if (got.isEmpty()) failed++ else got.forEach { e -> found[key(e)] = e }
                    i++
                    tilesDone = i
                    entries = found.values.toList()
                    version.value++
                    if (i % 3 == 0 || i == tiles.size) {
                        center = base; builtAt = System.currentTimeMillis(); save(ctx)
                    }
                    onMsg("Place index: ${found.size} places… (${i}/${tiles.size} areas)")
                    if (i < tiles.size) Thread.sleep(700)
                }
                entries = found.values.filter { Geo.dist(near, P(it.lat, it.lon)) <= MASTER_RADIUS_M }.take(MAX_ENTRIES)
                center = near
                builtAt = System.currentTimeMillis()
                if (radiusM >= MASTER_RADIUS_M) {
                    masterDone = failed <= tiles.size / 3
                    if (masterDone) tilesDone = 0
                }
                save(ctx); version.value++
                onMsg(if (entries.isEmpty()) "Place index: no places returned, will retry later"
                      else "Nearby place index ready: ${entries.size} places")
            } catch (e: Exception) {
                onMsg("Place index failed: ${e.message}")
            } finally {
                isBuilding.value = false
                guard.set(false)
            }
        }.start()
    }

    private fun key(e: Entry) = "%.4f|%.4f|%s".format(e.lat, e.lon, e.name.lowercase())

    /** Small boxes covering [radiusM] around [near], nearest first. */
    private fun tiles(near: P, radiusM: Double): List<Tile> {
        val dLat = radiusM / 111_320.0
        val dLon = dLat / cos(Math.toRadians(near.lat)).coerceAtLeast(0.2)
        val out = mutableListOf<Tile>()
        var s = near.lat - dLat
        while (s < near.lat + dLat - 1e-9) {
            val n = min(s + TILE_DEG, near.lat + dLat)
            var w = near.lon - dLon
            while (w < near.lon + dLon - 1e-9) {
                val e = min(w + TILE_DEG, near.lon + dLon)
                out.add(Tile(s, w, n, e))
                w = e
            }
            s = n
        }
        return out.sortedBy { t -> Geo.dist(near, P((t.s + t.n) / 2, (t.w + t.e) / 2)) }
    }

    /** One tile, trying each mirror until one answers with data. */
    private fun fetchBox(t: Tile, near: P): List<Entry> {
        val q = """[out:csv(::lat,::lon,name;false)][timeout:90];""" +
            """node(${t.s},${t.w},${t.n},${t.e})[place~"^(city|town|village|hamlet|suburb|locality)${'$'}"]["name"];out;"""
        for (ep in endpoints) {
            val r = try { fetch(ep, q, near) } catch (ex: Exception) { emptyList() }
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun fetch(endpoint: String, query: String, near: P): List<Entry> {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 120_000
            setRequestProperty("User-Agent", "RidePack/1.0")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) return emptyList()
        val reader = if (c.contentEncoding?.contains("gzip", true) == true)
            GZIPInputStream(c.inputStream).bufferedReader() else c.inputStream.bufferedReader()
        val out = ArrayList<Entry>(2_000)
        reader.use { r ->
            r.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#") || line.startsWith("<")) return@forEachLine
                val sep = if (line.contains('\t')) '\t' else '|'
                val parts = line.split(sep)
                if (parts.size < 3) return@forEachLine
                val la = parts[0].trim('"').toDoubleOrNull() ?: return@forEachLine
                val lo = parts[1].trim('"').toDoubleOrNull() ?: return@forEachLine
                val nm = parts.drop(2).joinToString(sep.toString()).trim('"').replace("\"\"", "\"")
                if (nm.isNotBlank() && Geo.dist(near, P(la, lo)) <= MASTER_RADIUS_M) out.add(Entry(nm, la, lo))
            }
        }
        return out
    }
}
