package com.ridepack.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject

/** Foreground service: keeps GPS + radio link alive with the screen off. */
class RideService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private var link: NearbyLink? = null
    private val knownRiders = mutableSetOf<String>()
    private val seenSos = mutableSetOf<String>()
    private val h = Handler(Looper.getMainLooper())
    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            if (shouldAcceptLocation(l, RideState.me.value)) RideState.me.value = l
        }
    }
    private var idleTicks = 0
    private val tick = object : Runnable {
        override fun run() {
            send()
            // No rider linked yet: restart discovery every ~30 s so late or dropped riders reconnect.
            if (RideState.code.value != null && RideState.links.value == 0) {
                if (++idleTicks >= 15) { idleTicks = 0; initLink() }
            } else idleTicks = 0
            h.postDelayed(this, 2000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("ride", "Ride tracking", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ride").setContentTitle("RidePack").setContentText("Tracking your ride")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        } catch (e: Exception) { RideState.msg.value = "Cannot start tracking: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        if (!RideState.running.value) {
            RideState.running.value = true
            fused = LocationServices.getFusedLocationProviderClient(this)
            try { fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateDistanceMeters(1f).setWaitForAccurateLocation(true).build(), locCb, Looper.getMainLooper()) }
            catch (e: SecurityException) { RideState.msg.value = "Location permission missing" }
            h.postDelayed(tick, 2000)
        }
        initLink()
        return START_STICKY
    }

    private fun initLink() {
        link?.stop(); link = null
        val c = RideState.code.value ?: return
        RideState.nearby.value = emptyList()
        link = NearbyLink(this, ::onPacket, { newCount ->
            val prev = RideState.links.value
            RideState.links.value = newCount
            if (newCount > prev && RideState.running.value) { send(); sendRoute() }
        }, { RideState.msg.value = "Radio: $it" }, { rid, rcode ->
            RideState.nearby.value = RideState.nearby.value.filter { it.id != rid } + NearbyRider(rid, rcode)
        }).also { it.start(c, RideState.myId) }
        RideState.link = link
        RideState.online?.stop()
        RideState.online = null
        if (Store.onlinePairing && !Store.offlineMode) {
            RideState.online = OnlineLink(::onPacket, { RideState.onlineUp.value = it }).also { it.start(c) }
        } else RideState.onlineUp.value = false
    }

    private fun postSosNotification(name: String, type: String) {
        val label = when (type) { "food" -> "Food"; "fuel" -> "Fuel"; "repair" -> "Repair"; "medical" -> "Medical"; "regroup" -> "Regroup"; else -> "Help" }
        // High importance so the alert makes a sound and vibrates; the "ride" channel is quiet.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sos", "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val n = Notification.Builder(this, "sos")
            .setContentTitle("SOS: $name needs $label")
            .setContentText("$name requested $label")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(10000)
            .build()
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    private var sendTick = 0

    private fun send() {
        val m = RideState.me.value ?: return
        val pkt = JSONObject().put("id", RideState.myId).put("n", RideState.myName)
            .put("la", m.latitude).put("lo", m.longitude).put("v", m.speed.toDouble())
        // Share destination so joined riders see the same target.
        RideState.destination.value?.let { d -> pkt.put("dest", "${d.lat},${d.lon}").put("destName", "Destination") }
        val s = pkt.toString()
        link?.send(s)
        // Firebase is reliable and the packets are tiny, so send every tick for smooth tracking.
        if (sendTick++ % 2 == 0) RideState.online?.send(s)
    }

    fun sendRoute() {
        val r = RideState.route.value ?: return
        // Nearby payloads are limited to 32 KB; keep the online route smaller too.
        link?.send(RideState.routePacket(r, 200))
        RideState.online?.send(RideState.routePacket(r, 60))
    }

    private fun onPacket(s: String) {
        try {
            val o = JSONObject(s); val id = o.getString("id"); if (id == RideState.myId) return
            val name = RideState.known[id] ?: o.optString("n", id)
            // Sync destination from the ride leader.
            if (o.has("dest")) {
                val parts = o.getString("dest").split(",")
                if (parts.size == 2) {
                    val la = parts[0].toDoubleOrNull(); val lo = parts[1].toDoubleOrNull()
                    if (la != null && lo != null && RideState.destination.value == null) {
                        RideState.destination.value = P(la, lo)
                    }
                }
            }
            // Sync route from the ride leader.
            if (o.has("route")) {
                val arr = o.getJSONArray("route")
                val pts = mutableListOf<P>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONArray(i)
                    pts.add(P(c.getDouble(0), c.getDouble(1)))
                }
                val cur = RideState.route.value
                val same = cur != null && cur.pts.size == pts.size && cur.pts.first() == pts.first() && cur.pts.last() == pts.last()
                if (pts.size >= 2 && !same) {
                    RideState.route.value = Route(pts)
                }
            }
            if (o.has("sos")) {
                // The same SOS can arrive over both links; show it once.
                if (!seenSos.add("$id|${o.optLong("seq")}")) return
                if (seenSos.size > 200) seenSos.clear()
                val type = o.getString("sos")
                val alert = SosAlert(id, name, type, o.optDouble("la", 0.0), o.optDouble("lo", 0.0), System.currentTimeMillis())
                RideState.sosAlerts.value = (RideState.sosAlerts.value + alert).takeLast(20)
                postSosNotification(name, type)
                return
            }
            val isNew = !knownRiders.contains(id)
            if (isNew) knownRiders.add(id)
            RideState.riders.value = RideState.riders.value + (id to Rider(id, name, o.getDouble("la"), o.getDouble("lo"), o.optDouble("v", 0.0).toFloat(), System.currentTimeMillis()))
            if (isNew) {
                RideState.msg.value = "$name joined the ride"
                postJoinNotification(name)
            }
        } catch (e: Exception) {}
    }

    private fun postJoinNotification(name: String) {
        val n = Notification.Builder(this, "ride")
            .setContentTitle("Rider joined")
            .setContentText("$name is now tracking with you")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        if (::fused.isInitialized) fused.removeLocationUpdates(locCb)
        link?.stop(); RideState.link = null
        RideState.online?.stop(); RideState.online = null; RideState.onlineUp.value = false
        knownRiders.clear(); RideState.running.value = false; RideState.links.value = 0; RideState.nearby.value = emptyList()
        super.onDestroy()
    }
}
