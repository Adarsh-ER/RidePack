package com.ridepack.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun distanceOneDegreeOfLongitudeAtEquator() {
        val d = Geo.dist(P(0.0, 0.0), P(0.0, 1.0))
        assertEquals(111195.0, d, 1.0)
    }

    @Test fun distanceIsZeroForSamePoint() {
        assertEquals(0.0, Geo.dist(P(12.5, 75.3), P(12.5, 75.3)), 1e-6)
    }

    @Test fun bearingNorthAndEast() {
        assertEquals(0.0, Geo.bearing(P(0.0, 0.0), P(1.0, 0.0)), 1e-6)
        assertEquals(90.0, Geo.bearing(P(0.0, 0.0), P(0.0, 1.0)), 1e-6)
    }

    @Test fun formatMetresAndKilometres() {
        assertEquals("500 m", Geo.fmt(500.0))
        assertEquals("1.5 km", Geo.fmt(1500.0))
    }

    @Test fun routeLengthIsSumOfSegments() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.01), P(0.0, 0.02)))
        assertEquals(2 * Geo.dist(P(0.0, 0.0), P(0.0, 0.01)), r.total, 1e-6)
    }

    @Test fun snapFindsPointOnRoute() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.02)))
        val (along, off) = r.snap(P(0.0, 0.01))
        assertEquals(r.total / 2, along, 1.0)
        assertEquals(0.0, off, 1.0)
    }

    @Test fun snapReportsDistanceOffRoute() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.02)))
        val (_, off) = r.snap(P(0.001, 0.01))
        assertEquals(111.0, off, 2.0)
    }

    @Test fun rightAngleIsOneRightTurn() {
        val r = Route(listOf(P(0.0, 0.0), P(0.01, 0.0), P(0.01, 0.01)))
        assertEquals(1, r.turns.size)
        assertFalse(r.turns[0].left)
        assertTrue(r.turns[0].deg > 80)
    }

    @Test fun straightRouteHasNoTurns() {
        val r = Route(listOf(P(0.0, 0.0), P(0.01, 0.0), P(0.02, 0.0)))
        assertTrue(r.turns.isEmpty())
    }

    // Route out east along lat 0, back west along lat 0.0002 (about 22 m north). A fix at lon 0.01 is
    // equally close to both legs, so only heading or continuity can tell them apart.
    private val loop = Route(listOf(P(0.0, 0.0), P(0.0, 0.02), P(0.0002, 0.02), P(0.0002, 0.0)))
    private val tieFix = P(0.0001, 0.01)

    @Test fun snapProgressIsCumulativeAlongRoute() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.01), P(0.0, 0.02)))
        assertEquals(0.75 * r.total, r.snap(P(0.0, 0.015)).first, 1.0)
    }

    @Test fun splitMeetsAtSameCoordinate() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.02)))
        val (a, b) = r.splitAt(r.total / 2)
        assertEquals(P(0.0, 0.0), a.first())
        assertEquals(P(0.0, 0.02), b.last())
        assertEquals(a.last().lat, b.first().lat, 1e-12)
        assertEquals(a.last().lon, b.first().lon, 1e-12)
        assertEquals(0.01, a.last().lon, 1e-9)
    }

    @Test fun splitAtStartAndEnd() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.01), P(0.0, 0.02)))
        val (a0, b0) = r.splitAt(0.0)
        assertEquals(1, a0.size)
        assertEquals(r.pts, b0)
        val (a1, b1) = r.splitAt(r.total)
        assertEquals(r.pts, a1)
        assertEquals(1, b1.size)
    }

    @Test fun splitClampsOutOfRangeProgress() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.02)))
        assertEquals(r.splitAt(0.0), r.splitAt(-500.0))
        assertEquals(r.splitAt(r.total), r.splitAt(r.total + 500.0))
    }

    @Test fun splitHandlesDegenerateRoutes() {
        val empty = Route(emptyList())
        assertEquals(Pair(emptyList<P>(), emptyList<P>()), empty.splitAt(10.0))
        val single = Route(listOf(P(1.0, 1.0)))
        assertEquals(Pair(emptyList<P>(), listOf(P(1.0, 1.0))), single.splitAt(10.0))
    }

    @Test fun splitWithRepeatedPointsStaysFinite() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.0), P(0.0, 0.02)))
        val (a, b) = r.splitAt(r.total / 2)
        assertTrue(a.all { it.lat.isFinite() && it.lon.isFinite() })
        assertTrue(b.all { it.lat.isFinite() && it.lon.isFinite() })
        assertEquals(a.last(), b.first())
    }

    @Test fun zeroLengthRouteProducesFiniteMatch() {
        val r = Route(listOf(P(0.0, 0.0), P(0.0, 0.0), P(0.0, 0.0)))
        val m = r.matchOnRoute(P(0.00001, 0.0), null, 90.0, 10.0, 5.0)
        assertTrue(m.s.isFinite() && m.off.isFinite())
        assertEquals(0.0, m.s, 1e-9)
    }

    @Test fun headingPicksTheSegmentTheRiderIsOn() {
        val total = loop.total
        val eastbound = loop.matchOnRoute(tieFix, null, 90.0, 10.0, 5.0)
        val westbound = loop.matchOnRoute(tieFix, null, 270.0, 10.0, 5.0)
        assertTrue(eastbound.s < total / 2)
        assertTrue(westbound.s > total / 2)
    }

    @Test fun stationaryRiderIgnoresHeading() {
        val withHeading = loop.matchOnRoute(tieFix, null, 270.0, 0.0, 5.0)
        val without = loop.matchOnRoute(tieFix, null, null, 0.0, 5.0)
        assertEquals(without.s, withHeading.s, 1e-9)
    }

    @Test fun continuityBreaksTieTowardPreviousProgress() {
        val total = loop.total
        assertTrue(loop.matchOnRoute(tieFix, 0.25 * total, null, 0.0, 5.0).s < total / 2)
        assertTrue(loop.matchOnRoute(tieFix, 0.75 * total, null, 0.0, 5.0).s > total / 2)
    }

    @Test fun farFromRouteFallsBackToNearestSnap() {
        val far = P(1.0, 1.0)
        val m = loop.matchOnRoute(far, null, 90.0, 10.0, 5.0)
        assertEquals(loop.snap(far).first, m.s, 1e-9)
        assertEquals(loop.snap(far).second, m.off, 1e-9)
    }
}

