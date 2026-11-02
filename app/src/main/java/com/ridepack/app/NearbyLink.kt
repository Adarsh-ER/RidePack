package com.ridepack.app

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.util.Collections

/** Phone-to-phone link over Bluetooth/Wi-Fi (Google Nearby Connections). Needs no mobile data. */
class NearbyLink(
    ctx: Context,
    private val onPacket: (String) -> Unit,
    private val onLinks: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onDiscovered: (String, String) -> Unit
) {
    private val client = Nearby.getConnectionsClient(ctx)
    private val connected: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var myId = ""
    private var myCode = ""

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(ep: String, p: Payload) { p.asBytes()?.let { onPacket(String(it, Charsets.UTF_8)) } }
        override fun onPayloadTransferUpdate(ep: String, u: PayloadTransferUpdate) {}
    }
    private val life = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(ep: String, i: ConnectionInfo) {
            // Only accept peers advertising this ride's tag; reject everyone else before any data is exchanged.
            if (myCode.isNotEmpty() && i.endpointName.split(":").getOrNull(1) == RideTag.of(myCode)) client.acceptConnection(ep, payloadCb)
            else client.rejectConnection(ep)
        }
        override fun onConnectionResult(ep: String, r: ConnectionResolution) { if (r.status.isSuccess) connected.add(ep); onLinks(connected.size) }
        override fun onDisconnected(ep: String) { connected.remove(ep); onLinks(connected.size) }
    }
    private val disc = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(ep: String, i: DiscoveredEndpointInfo) {
            val parts = i.endpointName.split(":")
            val rid = parts.getOrNull(0) ?: return
            val rcode = parts.getOrNull(1) ?: ""
            when {
                // Same ride (matched by its public tag, never the code): connect automatically (smaller rider ID initiates).
                rcode.isNotEmpty() && rcode == RideTag.of(myCode) -> {
                    if (myId < rid && !connected.contains(ep)) client.requestConnection(myId, ep, life)
                }
                // Different ride (or no ride yet): surface in the "nearby riders" list for information only.
                else -> onDiscovered(rid, rcode)
            }
        }
        override fun onEndpointLost(ep: String) {}
    }

    fun start(code: String, id: String) {
        stop(); myId = id; myCode = code
        // The endpoint name carries the rider ID and a one-way tag of the ride code. The code itself is never advertised.
        val epName = "$id:${RideTag.of(code)}"
        client.startAdvertising(epName, SID, life, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("advertise: ${it.message}") }
        client.startDiscovery(SID, disc, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("discover: ${it.message}") }
    }
    fun send(s: String) {
        val t = synchronized(connected) { connected.toList() }
        if (t.isNotEmpty()) client.sendPayload(t, Payload.fromBytes(s.toByteArray(Charsets.UTF_8)))
    }
    fun stop() { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints(); connected.clear(); onLinks(0) }

    companion object {
        /** Shared service ID so all nearby RidePack riders discover each other regardless of ride code. */
        const val SID = "com.ridepack.open"
    }
}
