package com.ridepack.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.*

object MapHolder {
    var map: MapLibreMap? = null
    var appContext: android.content.Context? = null
    private var style: Style? = null
    val follow = mutableStateOf(true)
    val dropPinMode = mutableStateOf(false)
    private var centered = false
    private var lastPois: List<Poi>? = null
    /** Dark map used while navigating to a destination (OpenFreeMap "dark" style). */
    const val NAV_STYLE = "https://tiles.openfreemap.org/styles/dark"
    var currentStyle = ""
    /** Top inset in px: pushes the map centre down so the position marker sits lower in landscape. */
    var topPadding = 0
    fun resetCenter() { centered = false }
    /** Initial view before the first GPS fix: India, zoomed to country level. */
    private val defaultCenter = LatLng(22.0, 79.0)
    private const val defaultZoom = 4.0
    private val palette = listOf("#e8590c", "#16a34a", "#9333ea", "#db2777", "#0891b2")

    fun applyStyle(url: String) {
        // While a new style loads, the old Style object must not be touched: MapLibre throws
        // "Calling getSourceAs when a newer style is loading/has loaded". The callback re-pushes.
        if (map != null) currentStyle = url
        val dark = url == NAV_STYLE
        val routeMain = if (dark) "#22d3ee" else "#4285f4"
        val routeCase = if (dark) "#0e7490" else "#1a4fb8"
        style = null
        map?.setStyle(Style.Builder().fromUri(url)) { s ->
            style = s
            lastPois = null
            s.addSource(GeoJsonSource("route")); s.addSource(GeoJsonSource("traveled")); s.addSource(GeoJsonSource("pois")); s.addSource(GeoJsonSource("riders")); s.addSource(GeoJsonSource("me"))
            s.addSource(GeoJsonSource("dest")); s.addSource(GeoJsonSource("preview"))
            s.addSource(GeoJsonSource("stops")); s.addSource(GeoJsonSource("stopsline"))
            s.addLayer(LineLayer("traveled-l", "traveled").withProperties(lineColor("#9ca3af"), roadWidth(0.8f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            s.addLayer(LineLayer("route-casing", "route").withProperties(lineColor(routeCase), roadWidth(1.35f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            s.addLayer(LineLayer("route-l", "route").withProperties(lineColor(routeMain), roadWidth(1f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            s.addLayer(CircleLayer("pois-l", "pois").withProperties(circleRadius(7f), circleColor("#e8590c"), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
            s.addLayer(CircleLayer("riders-l", "riders").withProperties(circleRadius(9f), circleColor(Expression.get("color")), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
            s.addLayer(SymbolLayer("riders-t", "riders").withProperties(textField(Expression.get("name")), textSize(12f),
                textOffset(arrayOf(0f, 1.6f)), textFont(arrayOf("Noto Sans Bold")), textHaloColor("#ffffff"), textHaloWidth(1.5f)))
            // Stops: grey connector line (A->B->C) plus lettered markers.
            s.addLayer(LineLayer("stopsline-l", "stopsline").withProperties(lineColor("#9ca3af"), lineWidth(3f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            s.addLayer(CircleLayer("stops-l", "stops").withProperties(circleRadius(10f), circleColor("#ffffff"), circleStrokeColor("#6b7280"), circleStrokeWidth(2.5f)))
            s.addLayer(SymbolLayer("stops-t", "stops").withProperties(textField(Expression.get("label")), textSize(11f), textColor("#374151"), textFont(arrayOf("Noto Sans Bold"))))
            // Hide the style's 3D building extrusions; only the map itself is tilted.
            s.getLayer("building-3d")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE))
            // Google-Maps-style blue location dot for "You".
            s.addLayer(CircleLayer("me-l", "me").withProperties(circleRadius(10f), circleColor("#2563eb"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
            // Destination marker (red) and route preview (blue).
            s.addLayer(CircleLayer("dest-l", "dest").withProperties(circleRadius(10f), circleColor("#ef4444"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
            s.addLayer(LineLayer("preview-casing", "preview").withProperties(lineColor(routeCase), roadWidth(1.35f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            s.addLayer(LineLayer("preview-l", "preview").withProperties(lineColor(routeMain), roadWidth(1f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
            push()
            // Show India until the first fix arrives; once we have a location, push() follows the rider.
            if (!centered && RideState.me.value == null) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultCenter, defaultZoom))
        }
    }

    /** Road-like line width that grows with the zoom level; scale 1 = route width. */
    private fun roadWidth(scale: Float) = lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
        Expression.stop(10f, 4f * scale), Expression.stop(14f, 8f * scale), Expression.stop(17f, 12f * scale), Expression.stop(19f, 16f * scale)))
    private fun fc(f: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(f)).toString()
    private fun pt(lat: Double, lon: Double, name: String, color: String) = JSONObject().put("type", "Feature")
        .put("properties", JSONObject().put("name", name).put("color", color))
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
    private fun line(pts: List<P>) = JSONObject().put("type", "Feature").put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray(pts.map { p -> JSONArray().put(p.lon).put(p.lat) })))

    /** 0 -> A, 1 -> B, ... 25 -> Z, 26 -> AA ... */
    private fun stopLabel(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do { sb.append(('A' + (n % 26)).toChar()); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }
    private fun stopPt(lat: Double, lon: Double, label: String) = JSONObject().put("type", "Feature")
        .put("properties", JSONObject().put("label", label))
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))

    /** Index of the first route point at or beyond distance s (0 if none); binary search on the cumulative distances. */
    private fun routeIndex(r: Route, s: Double): Int {
        var lo = 1; var hi = r.pts.size - 1; var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (r.cum[mid] >= s) { idx = mid; hi = mid - 1 } else lo = mid + 1
        }
        return idx
    }

    fun push() {
        val s = style ?: return
        try {
            val r = RideState.route.value
            val me = RideState.me.value
            val running = RideState.running.value
            val here = me?.let { P(it.latitude, it.longitude) }
            // One route lookup per update, shared by the trimmed line, the bearing and the zoom logic.
            val snapRes = if (r != null && here != null) r.snap(here) else null
            val snap0 = snapRes?.first ?: 0.0
            // During a ride, trim the route: traveled part in gray, remaining in blue.
            val (traveledLine, remainingLine) = if (r != null && running && snapRes != null && snap0 > 50 && snap0 < r.total - 50) {
                val idx = max(1, routeIndex(r, snap0))
                val prev = r.pts[idx - 1]
                val tLine = line(r.pts.take(idx + 1))
                val rLine = line(listOf(prev) + r.pts.drop(idx))
                Pair(tLine, rLine)
            } else if (r != null) {
                Pair(null, line(r.pts))
            } else Pair(null, null)
            s.getSourceAs<GeoJsonSource>("traveled")?.setGeoJson(fc(listOfNotNull(traveledLine)))
            s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(fc(listOfNotNull(remainingLine)))
            val pois = RideState.pois.value
            if (pois !== lastPois) {
                lastPois = pois
                s.getSourceAs<GeoJsonSource>("pois")?.setGeoJson(fc(pois.map { pt(it.lat, it.lon, it.name, "#e8590c") }))
            }
            // Other riders only; "You" is rendered by the me layer.
            val feats = RideState.riders.value.values.map { pt(it.lat, it.lon, it.name, palette[abs(it.id.hashCode()) % palette.size]) }
            s.getSourceAs<GeoJsonSource>("riders")?.setGeoJson(fc(feats))
            // Stops: lettered markers A, B, C... and a grey line joining them in order.
            val stops = RideState.stops.value
            s.getSourceAs<GeoJsonSource>("stops")?.setGeoJson(fc(stops.mapIndexed { i, st -> stopPt(st.lat, st.lon, stopLabel(i)) }))
            s.getSourceAs<GeoJsonSource>("stopsline")?.setGeoJson(fc(if (stops.size >= 2) listOf(line(stops.map { P(it.lat, it.lon) })) else emptyList()))
            val meFeat = here?.let { pt(it.lat, it.lon, "You", "#2563eb") }
            s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(fc(listOfNotNull(meFeat)))
            val dest = RideState.destination.value
            val destFeat = dest?.let { pt(it.lat, it.lon, "Dest", "#ef4444") }
            s.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(fc(listOfNotNull(destFeat)))
            val preview = RideState.previewRoute.value
            val previewLine = preview?.let { line(it) }
            s.getSourceAs<GeoJsonSource>("preview")?.setGeoJson(fc(listOfNotNull(previewLine)))
            if (me != null && here != null) {
                val ll = LatLng(me.latitude, me.longitude)
                val navZoom = if (dest != null) 17.5 else if (running) 17.0 else 15.5
                // No destination during a ride: keep the map north-up.
                val northUp = dest == null && running
                // Bearing towards the upcoming path, so the road ahead always points up.
                val upcoming: Double? = if (northUp) null else run {
                    var b: Double? = null
                    if (r != null && r.pts.size >= 2 && snapRes != null && snapRes.second < 80) {
                        val idx = routeIndex(r, snapRes.first)
                        val target = if (idx < r.pts.size - 1) r.pts[idx + 1] else if (idx > 0) r.pts[idx - 1] else null
                        b = target?.let { Geo.bearing(here, it) }
                    }
                    b ?: preview?.let { pts ->
                        if (pts.size >= 2) {
                            var best = Double.MAX_VALUE; var bi = 0
                            for (i in 0 until pts.size - 1) {
                                val d = Geo.dist(here, pts[i])
                                if (d < best) { best = d; bi = i }
                            }
                            Geo.bearing(here, pts.getOrElse(bi + 1) { pts.last() })
                        } else null
                    }
                }
                val navBearing = if (northUp) 0.0 else upcoming ?: (if (dest != null) Geo.bearing(here, P(dest.lat, dest.lon))
                    else if (autoRotateState.value && me.hasBearing() && me.speed > 2.5f) me.bearing.toDouble() else null)
                // 3D tilt: enabled by setting, or automatically when navigating to a destination.
                val pitch = if (threeDState.value || dest != null) 55.0 else 0.0
                if (!centered) {
                    centered = true
                    val cb = org.maplibre.android.camera.CameraPosition.Builder().target(ll).zoom(navZoom).tilt(pitch)
                    if (navBearing != null) cb.bearing(navBearing)
                    map?.moveCamera(CameraUpdateFactory.newCameraPosition(cb.build()))
                } else if (follow.value) {
                    // Gimbal-style follow: position, heading and tilt are eased between GPS updates.
                    val cur = map?.cameraPosition
                    val curZoom = cur?.zoom ?: navZoom
                    val zoom = if (curZoom < navZoom - 1) navZoom else curZoom
                    val lat = cur?.target?.let { it.latitude + (me.latitude - it.latitude) * 0.6 } ?: me.latitude
                    val lon = cur?.target?.let { it.longitude + (me.longitude - it.longitude) * 0.6 } ?: me.longitude
                    val bearing = if (navBearing != null) smoothAngle(cur?.bearing ?: navBearing, navBearing, 0.25) else (cur?.bearing ?: 0.0)
                    val tilt = cur?.tilt?.let { it + (pitch - it) * 0.25 } ?: pitch
                    val cb = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).tilt(tilt).bearing(bearing)
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(cb.build()), 900)
                } else {
                    // Keep tilt in sync even when not following.
                    map?.cameraPosition?.let { cp ->
                        if (cp.tilt != pitch) map?.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(cp).tilt(pitch).build()))
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Style was replaced mid-push; the new style's callback pushes again.
        }
    }

    /** Moves angle `from` towards `to` by fraction k, taking the shorter way round. */
    private fun smoothAngle(from: Double, to: Double, k: Double): Double {
        val diff = ((to - from + 540.0) % 360.0) - 180.0
        return (from + diff * k + 360.0) % 360.0
    }

    fun focus(lat: Double, lon: Double, zoom: Double? = null) {
        follow.value = false
        val ll = LatLng(lat, lon)
        map?.animateCamera(if (zoom != null) CameraUpdateFactory.newLatLngZoom(ll, zoom) else CameraUpdateFactory.newLatLng(ll))
    }
    fun fit(pts: List<P>) {
        follow.value = false
        val b = LatLngBounds.Builder(); pts.forEach { b.include(LatLng(it.lat, it.lon)) }
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
    }
}

/** Offline map areas (like Google Maps "download offline map"), using MapLibre's built-in offline manager. */
object Offline {
    fun bounds(): LatLngBounds? = MapHolder.map?.projection?.visibleRegion?.latLngBounds
    private fun lx(lo: Double, z: Int) = floor((lo + 180) / 360 * 2.0.pow(z)).toLong()
    private fun ly(la: Double, z: Int): Long { val r = Math.toRadians(la); return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 2.0.pow(z)).toLong() }
    fun minZ(z: Int) = max(5, z - 5)
    fun tiles(b: LatLngBounds, z0: Int, z1: Int): Long {
        var n = 0L
        for (z in z0..z1) n += (lx(b.longitudeEast, z) - lx(b.longitudeWest, z) + 1) * (ly(b.latitudeSouth, z) - ly(b.latitudeNorth, z) + 1)
        return n
    }
    fun estimate(z: Int): String {
        val b = bounds() ?: return "Map not ready"; val n = tiles(b, minZ(z), z)
        return "About $n tiles · ~${n * 15 / 1024} MB (zoom ${minZ(z)}–$z)" + if (n > 6000) " — too large, zoom in or lower the detail" else ""
    }
    fun download(ctx: Context, styleUrl: String, name: String, z: Int, onProg: (Int) -> Unit, onMsg: (String) -> Unit) {
        val b = bounds() ?: return onMsg("Map not ready")
        if (tiles(b, minZ(z), z) > 6000) return onMsg("Area too large – zoom in or lower the detail")
        val def = OfflineTilePyramidRegionDefinition(styleUrl, b, minZ(z).toDouble(), z.toDouble(), ctx.resources.displayMetrics.density)
        OfflineManager.getInstance(ctx).createOfflineRegion(def, JSONObject().put("name", name).toString().toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(s: OfflineRegionStatus) {
                            onProg(if (s.requiredResourceCount > 0) (100 * s.completedResourceCount / s.requiredResourceCount).toInt() else 0)
                            if (s.isComplete) { onProg(-1); onMsg("Saved offline: $name") }
                        }
                        override fun onError(e: OfflineRegionError) { onMsg("Download error: ${e.message}") }
                        override fun mapboxTileCountLimitExceeded(limit: Long) { onMsg("Tile limit reached ($limit)") }
                    })
                }
                override fun onError(error: String) { onMsg("Could not start: $error") }
            })
    }
    fun list(ctx: Context, cb: (List<Pair<OfflineRegion, String>>) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(r: Array<OfflineRegion>?) {
                cb((r?.toList() ?: emptyList()).map { it to (try { JSONObject(String(it.metadata)).optString("name", "Area") } catch (e: Exception) { "Area" }) })
            }
            override fun onError(error: String) {}
        })
    }
    fun delete(r: OfflineRegion, done: () -> Unit) {
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback { override fun onDelete() = done(); override fun onError(error: String) {} })
    }
    fun view(r: OfflineRegion) {
        val b = (r.definition as? OfflineTilePyramidRegionDefinition)?.bounds ?: return
        MapHolder.follow.value = false
        MapHolder.map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 40))
    }
}
