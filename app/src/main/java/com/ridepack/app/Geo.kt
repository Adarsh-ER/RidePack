package com.ridepack.app

import kotlin.math.*

data class P(val lat: Double, val lon: Double)

object Geo {
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
        var best = Double.MAX_VALUE; var bs = 0.0
        // Same metres-per-degree as dist() (R = 6371 km), so route position matches route length.
        val kx = 111195.0 * cos(Math.toRadians(p.lat)); val ky = 111195.0
        for (i in 1 until pts.size) {
            val ax = (pts[i - 1].lon - p.lon) * kx; val ay = (pts[i - 1].lat - p.lat) * ky
            val bx = (pts[i].lon - p.lon) * kx; val by = (pts[i].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * t, ay + dy * t)
            if (d < best) { best = d; bs = cum[i - 1] + sqrt(l2) * t }
        }
        return Pair(bs, best)
    }
}
