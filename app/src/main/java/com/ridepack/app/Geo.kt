package com.ridepack.app

import kotlin.math.*

data class P(val lat: Double, val lon: Double)

/** Largest score penalty (metres) that heading can add when matching a fix to a route segment. */
private const val HEADING_PENALTY_M = 25.0

object Geo {
    /** Rider is considered off route beyond this deviation (metres) before a recalculation is tried. */
    const val OFF_ROUTE_THRESHOLD_M = 45.0
    /** Consecutive accepted GPS updates beyond the threshold are needed before a reroute is triggered. */
    const val OFF_ROUTE_CONFIRMATIONS = 3

    fun dist(a: P, b: P): Double {
        val r = 6371000.0
        val dLa = Math.toRadians(b.lat - a.lat); val dLo = Math.toRadians(b.lon - a.lon)
        val h = sin(dLa / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLo / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
    fun bearing(a: P, b: P): Double {
        val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
            sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
        return Math.toDegrees(atan2(y, x))
    }
    fun fmt(m: Double) = if (m >= 1000) "%.1f km".format(m / 1000) else "${(m / 10).roundToInt() * 10} m"
}

class Route(val pts: List<P>) {
    val cum = DoubleArray(pts.size)
    init { for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i]) }
    val total: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    class Turn(val s: Double, val left: Boolean, val deg: Double)

    val turns: List<Turn> by lazy {
        val keep = ArrayList<Int>(); var last = 0; keep.add(0)
        for (i in 1 until pts.size) if (cum[i] - cum[last] >= 40) { keep.add(i); last = i }
        val out = ArrayList<Turn>()
        for (k in 1 until keep.size - 1) {
            val a = pts[keep[k - 1]]; val b = pts[keep[k]]; val c = pts[keep[k + 1]]
            var d = Geo.bearing(b, c) - Geo.bearing(a, b)
            while (d > 180) d -= 360
            while (d < -180) d += 360
            if (abs(d) > 35 && (out.isEmpty() || cum[keep[k]] - out.last().s > 100)) out.add(Turn(cum[keep[k]], d < 0, abs(d)))
        }
        out
    }

    /** (distance along route, distance off route) in metres */
    fun snap(p: P): Pair<Double, Double> {
        if (pts.size < 2) return Pair(0.0, if (pts.isEmpty()) 0.0 else Geo.dist(p, pts[0]))
        var best = Double.MAX_VALUE; var bs = 0.0
        for (i in 1 until pts.size) {
            val (s, d) = project(p, i)
            if (d < best) { best = d; bs = s }
        }
        return Pair(bs, best)
    }

    /**
     * Nearest point on segment (i-1 -> i) to [p]: (distance along route, distance off route).
     * Progress uses the same cumulative model as [total], so it is in the same units as the route length.
     */
    private fun project(p: P, i: Int): Pair<Double, Double> {
        // Same metres-per-degree as dist() (R = 6371 km), so route position matches route length.
        val kx = 111195.0 * cos(Math.toRadians(p.lat)); val ky = 111195.0
        val ax = (pts[i - 1].lon - p.lon) * kx; val ay = (pts[i - 1].lat - p.lat) * ky
        val bx = (pts[i].lon - p.lon) * kx; val by = (pts[i].lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
        val d = hypot(ax + dx * t, ay + dy * t)
        return Pair(cum[i - 1] + (cum[i] - cum[i - 1]) * t, d)
    }

    private val segBearing: DoubleArray by lazy {
        DoubleArray(pts.size) { i -> if (i == 0) 0.0 else Geo.bearing(pts[i - 1], pts[i]) }
    }

    /** Route split at [distance]: the traveled part and the remaining part, meeting at the same interpolated point. */
    fun splitAt(distance: Double): Pair<List<P>, List<P>> {
        if (pts.size < 2) return Pair(emptyList(), pts)

        val s = distance.coerceIn(0.0, total)
        var i = 1
        while (i < pts.size - 1 && cum[i] < s) i++

        val length = cum[i] - cum[i - 1]
        val t = if (length <= 0.0) 0.0
            else ((s - cum[i - 1]) / length).coerceIn(0.0, 1.0)

        val a = pts[i - 1]
        val b = pts[i]
        val split = P(
            a.lat + (b.lat - a.lat) * t,
            a.lon + (b.lon - a.lon) * t
        )

        return when {
            t <= 1e-9 ->
                Pair(pts.take(i), pts.drop(i - 1))
            t >= 1.0 - 1e-9 ->
                Pair(pts.take(i + 1), pts.drop(i))
            else ->
                Pair(pts.take(i) + split, listOf(split) + pts.drop(i))
        }
    }

    /**
     * Result of matching a GPS fix to the route: progress (m), distance off route (m), the chosen segment index,
     * and diagnostics: whether the nearest-segment fallback was used, the heading difference and penalties of the
     * chosen segment, and how many segments were candidates.
     */
    data class Match(
        val s: Double,
        val off: Double,
        val seg: Int,
        val fallback: Boolean = false,
        val headingDiffDeg: Double? = null,
        val headingPenalty: Double = 0.0,
        val continuityPenalty: Double = 0.0,
        val candidates: Int = 0
    )

    /**
     * Map-matches a GPS fix to the route. Candidate segments are scored by:
     *  - perpendicular distance to the fix (the main term),
     *  - heading mismatch, only when moving (>= 2.5 m/s) with a reasonably accurate fix (max [HEADING_PENALTY_M]),
     *  - continuity with the previously accepted progress [prevS] (bounded, so a genuine turn-around still wins).
     * Segments farther than a window around the fix are ignored; if none is close, the plain nearest-segment snap is used.
     */
    fun matchOnRoute(p: P, prevS: Double?, headingDeg: Double?, speedMs: Double, accuracyM: Double): Match {
        val fallback = snap(p)
        if (pts.size < 2 || total <= 0.0) return Match(fallback.first, fallback.second, 0, fallback = true)
        val acc = if (accuracyM.isFinite() && accuracyM > 0.0) accuracyM else 50.0
        val near = max(120.0, acc * 4)
        val speed = if (speedMs.isFinite()) max(0.0, speedMs) else 0.0
        // Heading fades in from 2.5 to 5 m/s and is ignored when the fix is poor.
        val headingW = if (headingDeg != null && headingDeg.isFinite() && acc <= 30.0)
            ((speed - 2.5) / 2.5).coerceIn(0.0, 1.0) * (if (acc <= 15.0) 1.0 else 0.5) else 0.0
        val allow = max(50.0, speed * 8 + acc * 2)
        var bestScore = Double.MAX_VALUE
        var best: Match? = null
        var candidates = 0
        for (i in 1 until pts.size) {
            val (s, d) = project(p, i)
            if (!s.isFinite() || !d.isFinite() || d > near) continue
            candidates++
            var score = d
            var hDiff: Double? = null
            var hPen = 0.0
            if (headingW > 0.0 && headingDeg != null && cum[i] > cum[i - 1]) {
                hDiff = angleDiff(headingDeg, segBearing[i])
                hPen = HEADING_PENALTY_M * headingW * (hDiff / 180.0)
                score += hPen
            }
            var cPen = 0.0
            if (prevS != null) { cPen = min(60.0, 0.5 * max(0.0, abs(s - prevS) - allow)); score += cPen }
            if (score < bestScore) { bestScore = score; best = Match(s, d, i, false, hDiff, hPen, cPen) }
        }
        return best?.copy(candidates = candidates) ?: Match(fallback.first, fallback.second, 0, fallback = true)
    }

    /** Smallest angle between two bearings, in degrees (0..180). */
    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }

}
