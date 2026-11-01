package com.ridepack.app

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

/**
 * Internet rider link over Firebase Realtime Database (free Spark tier).
 *
 * Layout under "rides/<code>/":
 *   riders/<riderId>  -> the rider's latest position packet (overwritten each update)
 *   routes/<riderId>  -> the rider's latest route packet (overwritten when the route changes)
 *   sos/<pushKey>     -> SOS events (kept as separate entries so they aren't overwritten)
 */
class OnlineLink(
    private val onPacket: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private var ridersRef: DatabaseReference? = null
    private var routesRef: DatabaseReference? = null
    private var sosRef: DatabaseReference? = null
    private var myRiderRef: DatabaseReference? = null
    private var myRouteRef: DatabaseReference? = null
    private val listeners = mutableListOf<ChildEventListener>()
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
        myRiderRef = ridersRef!!.child(uid!!)
        myRouteRef = routesRef!!.child(uid!!)
        // If we disconnect, Firebase removes our node so other riders see us leave.
        myRiderRef!!.onDisconnect().removeValue()
        myRouteRef!!.onDisconnect().removeValue()
        listen(ridersRef!!)
        listen(routesRef!!)
        listen(sosRef!!)
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

    /** Route each packet to the right node so positions, routes and SOS never overwrite each other. */
    fun send(s: String) {
        val o = try { JSONObject(s) } catch (e: Exception) { null }
        when {
            o?.has("sos") == true -> sosRef?.push()?.setValue(s)
            o?.has("route") == true -> myRouteRef?.setValue(s)
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
        try { myRiderRef?.removeValue() } catch (e: Exception) {}
        try { myRouteRef?.removeValue() } catch (e: Exception) {}
        ridersRef = null; routesRef = null; sosRef = null
        myRiderRef = null; myRouteRef = null
        onState(false)
    }

    companion object {
        /** Realtime Database URL for the ridepack-3d413 project (asia-southeast1 region). */
        const val DB_URL = "https://ridepack-3d413-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
