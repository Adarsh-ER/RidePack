package com.ridepack.app

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Xml
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

data class Poi(val id: String, val name: String, val cat: String, val lat: Double, val lon: Double)
data class Rider(val id: String, val name: String, val lat: Double, val lon: Double, val speed: Float, val seen: Long)
data class NearbyRider(val id: String, val code: String)
data class SosAlert(val id: String, val name: String, val type: String, val lat: Double, val lon: Double, val time: Long)
data class Stop(val name: String, val lat: Double, val lon: Double)
data class SavedPlace(val name: String, val lat: Double, val lon: Double)

/** Google-Maps-style location filtering: reject inaccurate, stale, or impossible-jump fixes. */
fun shouldAcceptLocation(new: Location, cur: Location?): Boolean = locationRejectReason(new, cur) == null

/** Why a fix is rejected ("accuracy", "stale", "jump"), or null when it is accepted. */
fun locationRejectReason(new: Location, cur: Location?): String? {
    // Reject clearly inaccurate fixes; if the current marker is old, accept a rougher fix so it never sticks.
    val curStale = cur == null || System.currentTimeMillis() - cur.time > 8000L
    if (new.accuracy > (if (curStale) 100f else 40f)) return "accuracy"
    // Reject stale fixes.
    if (System.currentTimeMillis() - new.time > 8000L) return "stale"
    if (cur == null) return null
    // Reject impossible jumps (faster than 70 m/s ~ 252 km/h).
    val elapsed = new.time - cur.time
    if (elapsed > 0 && cur.distanceTo(new) / (elapsed / 1000f) > 70f) return "jump"
    return null
}

object RideState {
    val me = MutableStateFlow<Location?>(null)
    val riders = MutableStateFlow<Map<String, Rider>>(emptyMap())
    val pois = MutableStateFlow<List<Poi>>(emptyList())
    val route = MutableStateFlow<Route?>(null)
    val code = MutableStateFlow<String?>(null)
    val links = MutableStateFlow(0)
    val running = MutableStateFlow(false)
    val msg = MutableStateFlow("")
    val sosAlerts = MutableStateFlow<List<SosAlert>>(emptyList())
    val stops = MutableStateFlow<List<Stop>>(emptyList())
    val savedPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val destination = MutableStateFlow<P?>(null)
    val previewRoute = MutableStateFlow<List<P>?>(null)
    /** Dynamic rerouting status: Navigating, Off route – recalculating, New route found, Unable to recalculate – retrying. */
    val navStatus = MutableStateFlow("Navigating")
    val nearby = MutableStateFlow<List<NearbyRider>>(emptyList())
    /** Group ride controller for the active ride, and its latest snapshot for the UI. */
    var groupCtl: GroupController? = null
    val group = MutableStateFlow<GroupView?>(null)
    var link: NearbyLink? = null
    var online: OnlineLink? = null
    val onlineUp = MutableStateFlow(false)
    var myId = ""
    var myName = "Me"
    var myReg = ""
    /** Firebase anonymous-auth UID, used as the node key so security rules can tie data to the signed-in rider. */
    var firebaseUid: String? = null
    val known = mutableMapOf<String, String>() // rider ID -> tagged name

    /** Route packet; the online link carries fewer points to keep payloads small. */
    fun routePacket(r: Route, max: Int): String {
        val pts = r.pts
        val step = (pts.size / max).coerceAtLeast(1)
        val sampled = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }
        val arr = JSONArray()
        sampled.forEach { p -> arr.put(JSONArray().put(p.lat).put(p.lon)) }
        return JSONObject().put("id", myId).put("route", arr).toString()
    }
}

object Store {
    const val DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private lateinit var sp: SharedPreferences
    private lateinit var dir: File

    var home: P?
        get() = sp.getString("home", null)?.split(",")?.let { if (it.size == 2) P(it[0].toDoubleOrNull() ?: return null, it[1].toDoubleOrNull() ?: return null) else null }
        set(v) { sp.edit().putString("home", v?.let { "${it.lat},${it.lon}" }).apply() }
    var work: P?
        get() = sp.getString("work", null)?.split(",")?.let { if (it.size == 2) P(it[0].toDoubleOrNull() ?: return null, it[1].toDoubleOrNull() ?: return null) else null }
        set(v) { sp.edit().putString("work", v?.let { "${it.lat},${it.lon}" }).apply() }
    var savedPlaces: List<SavedPlace>
        get() = sp.getString("savedPlaces", null)?.split("\n")?.mapNotNull { line ->
            val parts = line.split("|"); if (parts.size == 3) SavedPlace(parts[0], parts[1].toDoubleOrNull() ?: return@mapNotNull null, parts[2].toDoubleOrNull() ?: return@mapNotNull null) else null
        } ?: emptyList()
        set(v) { sp.edit().putString("savedPlaces", v.joinToString("\n") { "${it.name}|${it.lat}|${it.lon}" }).apply() }
    var code: String?
        get() = sp.getString("code", null)
        set(v) { if (v == null) sp.edit().remove("code").apply() else sp.edit().putString("code", v).apply() }
    var vehicleReg: String
        get() = sp.getString("reg", "")!!
        set(v) { sp.edit().putString("reg", v).apply() }
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) { sp.edit().putBoolean("setupDone", v).apply() }

    // ---- Settings ----
    var nightMode: Boolean
        get() = sp.getBoolean("nightMode", false)
        set(v) { sp.edit().putBoolean("nightMode", v).apply() }
    var fontSizeScale: Float
        get() = sp.getFloat("fontSizeScale", 1f)
        set(v) { sp.edit().putFloat("fontSizeScale", v).apply() }
    var fontColor: Int
        get() = sp.getInt("fontColor", 0)
        set(v) { sp.edit().putInt("fontColor", v).apply() }  // 0 = default
    var offlineMode: Boolean
        get() = sp.getBoolean("offlineMode", false)
        set(v) { sp.edit().putBoolean("offlineMode", v).apply() }
    var brightness: Float
        get() = sp.getFloat("brightness", 1f)
        set(v) { sp.edit().putFloat("brightness", v).apply() }
    var autoCenter: Boolean
        get() = sp.getBoolean("autoCenter", true)
        set(v) { sp.edit().putBoolean("autoCenter", v).apply() }
    var autoRotate: Boolean
        get() = sp.getBoolean("autoRotate", false)
        set(v) { sp.edit().putBoolean("autoRotate", v).apply() }
    var threeD: Boolean
        get() = sp.getBoolean("threeD", false)
        set(v) { sp.edit().putBoolean("threeD", v).apply() }
    var onlinePairing: Boolean
        get() = sp.getBoolean("onlinePairing", true)
        set(v) { sp.edit().putBoolean("onlinePairing", v).apply() }
    var arrivalRadiusM: Float
        get() = sp.getFloat("arrivalRadius", 75f)
        set(v) { sp.edit().putFloat("arrivalRadius", v).apply() }
    var arrivalAccuracyM: Float
        get() = sp.getFloat("arrivalAccuracy", 35f)
        set(v) { sp.edit().putFloat("arrivalAccuracy", v).apply() }
    var debugOverlay: Boolean
        get() = sp.getBoolean("debugOverlay", false)
        set(v) { sp.edit().putBoolean("debugOverlay", v).apply() }
    /** Id of the debug logging session that is currently recording, or null when logging is off. */
    var debugSession: String?
        get() = sp.getString("debugSession", null)
        set(v) { if (v == null) sp.edit().remove("debugSession").apply() else sp.edit().putString("debugSession", v).apply() }
    var updateCheck: Boolean
        get() = sp.getBoolean("updateCheck", true)
        set(v) { sp.edit().putBoolean("updateCheck", v).apply() }

    fun init(c: Context) {
        sp = c.getSharedPreferences("rp", 0); dir = c.filesDir
        if (sp.getString("id", null) == null) {
            val al = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
            sp.edit().putString("id", (1..4).map { al.random() }.joinToString("")).apply()
        }
        RideState.myId = sp.getString("id", "")!!
        RideState.code.value = sp.getString("code", null)
        RideState.savedPlaces.value = Store.savedPlaces
        if (sp.getString("name", null) == null) {
            val devName = try {
                android.provider.Settings.Global.getString(c.contentResolver, "device_name")
                    ?: android.provider.Settings.Secure.getString(c.contentResolver, "bluetooth_name")
                    ?: android.os.Build.MODEL
            } catch (e: Exception) { android.os.Build.MODEL }
            sp.edit().putString("name", devName).apply()
        }
        RideState.myName = sp.getString("name", "Me")!!
        RideState.myReg = sp.getString("reg", "")!!
        try { val k = JSONObject(sp.getString("known", "{}")!!); k.keys().forEach { RideState.known[it] = k.getString(it) } } catch (e: Exception) {}
        try {
            val f = File(dir, "pois.json")
            if (f.exists()) { val a = JSONArray(f.readText()); RideState.pois.value = (0 until a.length()).map { val o = a.getJSONObject(it); Poi(o.getString("id"), o.getString("name"), o.getString("cat"), o.getDouble("lat"), o.getDouble("lon")) } }
        } catch (e: Exception) {}
        // Anonymous Firebase sign-in so database rules can require authentication.
        try {
            FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener { t ->
                if (t.isSuccessful) RideState.firebaseUid = t.result?.user?.uid
            }
        } catch (e: Exception) {}
    }
    var style: String
        get() = sp.getString("style", DEFAULT_STYLE)!!
        set(v) { sp.edit().putString("style", v).apply() }
    fun saveName(n: String) { RideState.myName = n; sp.edit().putString("name", n).apply() }
    fun saveKnown() { sp.edit().putString("known", JSONObject(RideState.known as Map<*, *>).toString()).apply() }
    fun savePois() {
        val a = JSONArray(); RideState.pois.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("cat", it.cat).put("lat", it.lat).put("lon", it.lon)) }
        File(dir, "pois.json").writeText(a.toString())
    }
}

object Gpx {
    fun parse(s: InputStream): Pair<List<P>, List<Poi>> {
        val pts = ArrayList<P>(); val wps = ArrayList<Poi>()
        val x = Xml.newPullParser(); x.setInput(s, null)
        var e = x.eventType; var wla = 0.0; var wlo = 0.0; var inW = false; var nm = ""
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (x.name) {
                "trkpt", "rtept" -> pts.add(P(x.getAttributeValue(null, "lat").toDouble(), x.getAttributeValue(null, "lon").toDouble()))
                "wpt" -> { inW = true; nm = ""; wla = x.getAttributeValue(null, "lat").toDouble(); wlo = x.getAttributeValue(null, "lon").toDouble() }
                "name" -> if (inW) nm = x.nextText()
            } else if (e == XmlPullParser.END_TAG && x.name == "wpt") {
                wps.add(Poi("g${wps.size}_$wla", nm.ifEmpty { "Waypoint" }, "view", wla, wlo)); inW = false
            }
            e = x.next()
        }
        return Pair(pts, wps)
    }
}
