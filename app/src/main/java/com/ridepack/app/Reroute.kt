package com.ridepack.app

/**
 * Dynamic rerouting: off-route detection and one-shot recalculation, kept free of Android/Firebase types so
 * the decision logic can be unit tested. The actual network fetch and map update live in [MapHolder].
 */
class OffRouteTracker(
    private val thresholdM: Double = Geo.OFF_ROUTE_THRESHOLD_M,
    private val confirmations: Int = Geo.OFF_ROUTE_CONFIRMATIONS,
    private val cooldownMs: Long = 30_000L
) {
    var streak: Int = 0
        private set

    private var lastTriggerMs = 0L

    /**
     * Records one GPS evaluation: the rider is off route beyond [thresholdM] with an acceptable accuracy, and
     * enough consecutive valid updates have passed. Returns true when a reroute should now be requested.
     * The counter resets as soon as the rider is back within the threshold, so temporary drift never accumulates.
     */
    fun update(deviationM: Double, accuracyM: Float?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (deviationM <= thresholdM || (accuracyM != null && accuracyM > 100f)) {
            streak = 0
            return false
        }
        streak++
        if (streak < confirmations) return false
        if (nowMs - lastTriggerMs < cooldownMs) { streak = 0; return false }
        lastTriggerMs = nowMs
        streak = 0
        return true
    }

    /** Clears the streak (rider returned to the route or a new ride started). */
    fun reset() { streak = 0 }
}

/**
 * Rerouting state: when a recalculation has been requested, with the origin/destination of that request and the
 * route it produced. Pure so the lifecycle rules can be unit tested.
 */
class Reroute(
    private val thresholdM: Double = Geo.OFF_ROUTE_THRESHOLD_M,
    private val confirmations: Int = Geo.OFF_ROUTE_CONFIRMATIONS,
    private val cooldownMs: Long = 30_000L
) {
    private val tracker = OffRouteTracker(thresholdM, confirmations, cooldownMs)
    private var lastTriggerMs = 0L
    var inProgress = false
    var lastOrigin: P? = null
    var destination: P? = null
    var currentRoute: List<P>? = null

    /** One GPS evaluation: true when the rider is confirmed off route and a recalculation is due. */
    fun shouldReroute(deviationM: Double, accuracyM: Float?): Boolean = tracker.update(deviationM, accuracyM)

    /** Requests a new route from [from] to [to]; refuses duplicates while one is in flight or during cooldown. */
    fun start(from: P, to: P, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (inProgress) return false
        if (nowMs - lastTriggerMs < cooldownMs) return false
        lastTriggerMs = nowMs
        inProgress = true
        lastOrigin = from
        destination = to
        return true
    }

    /** Accepts a freshly fetched route for the active request; stale origins or missing geometry are ignored. */
    fun onResponse(pts: List<P>, origin: P, dest: P, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!inProgress) return false
        if (dest != destination) return false
        val reqOrigin = lastOrigin ?: return false
        if (Geo.dist(origin, reqOrigin) > 150.0) return false // rider moved on: response is outdated
        if (pts.size < 2) return false
        currentRoute = pts
        inProgress = false
        return true
    }

    /** Marks the attempt as failed; the previous route stays and a later attempt may happen after cooldown. */
    fun onFailure(): Boolean { inProgress = false; return true }

    /** Clears all rerouting state (ride ended, new destination). */
    fun reset() { tracker.reset(); inProgress = false; currentRoute = null; destination = null; lastOrigin = null }
}
