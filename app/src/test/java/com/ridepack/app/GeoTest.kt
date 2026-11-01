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
}
