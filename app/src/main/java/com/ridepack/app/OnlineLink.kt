package com.ridepack.app

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

/**
 * Internet rider link over Firebase Realtime Database (free Spark tier).
 *
 * Layout under "rides/<code>/":
 *   riders/<riderId>  -> the rider's latest position packet (overwritten each update)
 *   routes/<riderId>  -> the rider's latest route packet (overwritten when the route changes)
 *   group              -> shared group ride session (leader-only writes; holds stage, meeting and destination)
 *   sos/<pushKey>     -> SOS events (kept as separate entries so they aren't overwritten)
 *   arrivals/<uid>    -> a rider's own arrival confirmation (writable only by that rider)
 */
class OnlineLink(
    private val onPacket: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onGroup: (kind: String, code: String, json: String) -> Unit = { _, _, _ -> }
) {
    private var ridersRef: DatabaseReference? = null
    private var routesRef: DatabaseReference? = null
    private var sosRef: DatabaseReference? = null
    private var myRiderRef: DatabaseReference? = null
    private var myRouteRef: DatabaseReference? = null
    private var groupRef: DatabaseReference? = null
    private val listeners = mutableListOf<ChildEventListener>()
    private val groupListeners = mutableListOf<Pair<DatabaseReference, Any>>()
    /** Bumped on every start/stop, so callbacks from an earlier session are ignored. */
    private var generation = 0
    private var uid: String? = null

    fun start(code: String) {
        stop()
        // Use the Firebase auth UID as the node key so the rules can verify ownership.
        uid = RideState.firebaseUid ?: FirebaseAuth.getInstance().currentUser?.uid ?: RideState.myId
        // The database is in asia-southeast1, so the URL must be explicit: google-services.json has no
        // firebase_url, and the default (us-central1) database does not exist.
        val db = try {
            FirebaseDatabase.getInstance(DB_URL)
        } catch (e: Exception) {
            try { FirebaseDatabase.getInstance() } catch (e2: Exception) {
                RideState.msg.value = "Internet link unavailable: ${e2.message}"
                onState(false); return
            }
        }
        ridersRef = db.getReference("rides/$code/riders")
        routesRef = db.getReference("rides/$code/routes")
        sosRef = db.getReference("rides/$code/sos")
        groupRef = db.getReference("rides/$code/group")
        myRiderRef = ridersRef!!.child(uid!!)
        myRouteRef = routesRef!!.child(uid!!)
        // If we disconnect, Firebase removes our node so other riders see us leave.
        myRiderRef!!.onDisconnect().removeValue()
        myRouteRef!!.onDisconnect().removeValue()
        listen(ridersRef!!)
        listen(routesRef!!)
        listen(sosRef!!)
        listenGroup(db, code, ++generation)
        onState(true)
    }

    private fun listen(ref: DatabaseReference) {
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { deliver(s) }
            override fun onChildChanged(s: DataSnapshot, prev: String?) { deliver(s) }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { onState(false) }
        }
        ref.addChildEventListener(l)
        listeners.add(l)
    }

    private fun deliver(s: DataSnapshot) {
        if (s.key == uid) return
        val v = s.value ?: return
        onPacket(v.toString())
    }

    private fun listenGroup(db: FirebaseDatabase, code: String, gen: Int) {
        val base = db.getReference("rides/$code")
        val group = base.child("group")
        val arrivals = base.child("arrivals")
        val sessionListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { if (gen == generation) emitValue("session", code, s) }
            override fun onCancelled(e: DatabaseError) {}
        }
        val destinationListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { if (gen == generation) emitValue("destination", code, s) }
            override fun onCancelled(e: DatabaseError) {}
        }
        val arrivalListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { if (gen == generation) emitValue("arrival", code, s) }
            override fun onChildChanged(s: DataSnapshot, prev: String?) { if (gen == generation) emitValue("arrival", code, s) }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        group.addValueEventListener(sessionListener); groupListeners.add(group to sessionListener)
        arrivals.addChildEventListener(arrivalListener); groupListeners.add(arrivals to arrivalListener)
    }

    private fun emitValue(kind: String, code: String, s: DataSnapshot) {
        val map = s.value as? Map<*, *> ?: return
        val o = mapToJson(map)
        if (kind == "arrival") o.put("uid", s.key)
        onGroup(kind, code, o.toString())
    }

    /** Firebase returns nested Maps; org.json needs JSONObjects for nested values. */
    private fun mapToJson(m: Map<*, *>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, v) ->
            o.put(k.toString(), when (v) {
                is Map<*, *> -> mapToJson(v)
                null -> JSONObject.NULL
                else -> v
            })
        }
        return o
    }

    /** Route each packet to the right node so positions, routes, group state and SOS never overwrite each other. */
    fun send(s: String) {
        val o = try { JSONObject(s) } catch (e: Exception) { null }
        when {
            o?.has("sos") == true -> sosRef?.push()?.setValue(s)
            o?.has("route") == true -> myRouteRef?.setValue(s)
            o?.has("group") == true -> groupRef?.setValue(s)
            else -> myRiderRef?.setValue(s)
        }
    }

    fun stop() {
        listeners.forEach { l ->
            ridersRef?.removeEventListener(l)
            routesRef?.removeEventListener(l)
            sosRef?.removeEventListener(l)
        }
        listeners.clear()
        groupListeners.forEach { (ref, l) ->
            when (l) {
                is ValueEventListener -> ref.removeEventListener(l)
                is ChildEventListener -> ref.removeEventListener(l)
            }
        }
        groupListeners.clear()
        generation++
        try { myRiderRef?.removeValue() } catch (e: Exception) {}
        try { myRouteRef?.removeValue() } catch (e: Exception) {}
        ridersRef = null; routesRef = null; sosRef = null; groupRef = null
        myRiderRef = null; myRouteRef = null
        onState(false)
    }

    companion object {
        /** Realtime Database URL for the ridepack-3d413 project (asia-southeast1 region). */
        const val DB_URL = "https://ridepack-3d413-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}

/**
 * Writes group ride state to Firebase for the active ride. Every write needs a live internet link;
 * the controller treats a failed write as "not saved", so nothing is shown to riders from a local-only state.
 */
object FirebaseGroupStore : GroupStore {
    private fun ref(path: String): DatabaseReference? {
        val code = RideState.code.value ?: return null
        if (RideState.online == null || !RideState.onlineUp.value) return null
        return FirebaseDatabase.getInstance(OnlineLink.DB_URL).getReference("rides/$code/$path")
    }

    override fun writeSession(s: GroupSession, done: (Boolean) -> Unit) {
        val r = ref("group") ?: return done(false)
        // The whole session (stage, meeting, destination) is written in one atomic write, so the destination
        // can never be visible before the stage says the ride started.
        val dest = s.destination?.let { mapOf("lat" to it.lat, "lon" to it.lon) }
        r.setValue(mapOf(
            "leaderId" to s.leaderId,
            "leaderUid" to s.leaderUid,
            "stage" to s.stage.name,
            "meeting" to s.meeting?.let { m -> mapOf("lat" to m.lat, "lon" to m.lon, "at" to m.confirmedAt, "leaderId" to m.leaderId) },
            "destination" to dest
        )) { err, _ -> done(err == null) }
    }

    override fun writeArrival(uid: String, riderId: String, atMs: Long, done: (Boolean) -> Unit) {
        val r = ref("arrivals/$uid") ?: return done(false)
        r.setValue(mapOf("id" to riderId, "at" to atMs)) { err, _ -> done(err == null) }
    }
}
