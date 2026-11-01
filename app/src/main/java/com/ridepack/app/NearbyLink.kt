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
    private val onError: (String) -> Unit
) {
    private val client = Nearby.getConnectionsClient(ctx)
    private val connected: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var myId = ""

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(ep: String, p: Payload) { p.asBytes()?.let { onPacket(String(it, Charsets.UTF_8)) } }
        override fun onPayloadTransferUpdate(ep: String, u: PayloadTransferUpdate) {}
    }
    private val life = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(ep: String, i: ConnectionInfo) { client.acceptConnection(ep, payloadCb) }
        override fun onConnectionResult(ep: String, r: ConnectionResolution) { if (r.status.isSuccess) connected.add(ep); onLinks(connected.size) }
        override fun onDisconnected(ep: String) { connected.remove(ep); onLinks(connected.size) }
    }
    private val disc = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(ep: String, i: DiscoveredEndpointInfo) {
            // only the smaller rider ID initiates, so two phones never both try at once
            if (myId < i.endpointName && !connected.contains(ep)) client.requestConnection(myId, ep, life)
        }
        override fun onEndpointLost(ep: String) {}
    }

    fun start(code: String, id: String) {
        stop(); myId = id; val sid = "com.ridepack.$code" // same ride code = same service ID
        client.startAdvertising(id, sid, life, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("advertise: ${it.message}") }
        client.startDiscovery(sid, disc, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("discover: ${it.message}") }
    }
    fun send(s: String) {
        val t = synchronized(connected) { connected.toList() }
        if (t.isNotEmpty()) client.sendPayload(t, Payload.fromBytes(s.toByteArray(Charsets.UTF_8)))
    }
    fun stop() { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints(); connected.clear(); onLinks(0) }
}
