package com.ridepack.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Net {
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "RidePack/0.1"); c.connectTimeout = 15000; c.readTimeout = 30000
        return c
    }
    fun geocode(q: String): P? {
        if (offlineModeState.value) return null
        val t = open("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
            .inputStream.bufferedReader().use { it.readText() }
        val a = JSONArray(t); if (a.length() == 0) return null
        return P(a.getJSONObject(0).getString("lat").toDouble(), a.getJSONObject(0).getString("lon").toDouble())
    }
    private val routers = listOf("https://router.project-osrm.org", "https://routing.openstreetmap.de/routed-car")
    /** Road route from the first router that answers. Null when no router can give a road route. */
    fun route(from: P, to: P): List<P>? = routeVia(listOf(from, to))
    /** Road route through the points in order (start, stops..., destination). */
    fun routeVia(points: List<P>): List<P>? {
        if (offlineModeState.value) return null
        for (base in routers) {
            val road = try { routeFrom(base, points) } catch (e: Exception) { null }
            if (road != null) return road
        }
        return null
    }
    private fun routeFrom(base: String, points: List<P>): List<P>? {
        val waypoints = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "$base/route/v1/driving/$waypoints?overview=full&geometries=geojson"
        val t = open(url).inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(t)
        if (o.optString("code") != "Ok") return null
        val coords = o.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val pts = mutableListOf<P>()
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(P(c.getDouble(1), c.getDouble(0)))
        }
        return if (pts.size < 2) null else pts
    }

    fun guess(s: String): String {
        val t = s.lowercase()
        return when {
            Regex("fuel|petrol").containsMatchIn(t) -> "fuel"
            Regex("restaurant|cafe|food").containsMatchIn(t) -> "food"
            Regex("repair|bicycle|motorcycle").containsMatchIn(t) -> "fix"
            Regex("hotel|guest|hostel").containsMatchIn(t) -> "stay"
            else -> "view"
        }
    }
    fun overpass(s: Double, w: Double, n: Double, e: Double): List<Poi> {
        if (offlineModeState.value) return emptyList()
        val bb = "$s,$w,$n,$e"
        val q = """[out:json][timeout:25];(node["amenity"="fuel"]($bb);node["amenity"~"^(restaurant|cafe|car_repair)${'$'}"]($bb);node["tourism"~"^(viewpoint|hotel|guest_house)${'$'}"]($bb);node["shop"~"^(motorcycle|bicycle)${'$'}"]($bb););out 300;"""
        val c = open("https://overpass-api.de/api/interpreter"); c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elements")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); val tg = o.optJSONObject("tags") ?: JSONObject()
            Poi("o" + o.getLong("id"), tg.optString("name", "Place"), guess(tg.toString()), o.getDouble("lat"), o.getDouble("lon"))
        }
    }
}
