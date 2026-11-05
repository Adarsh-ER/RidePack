package com.ridepack.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for dynamic rerouting: off-route detection, recalculation lifecycle and route replacement. */
class RerouteTest {

    private val now = 1_700_000_000_000L
    private val threshold = Geo.OFF_ROUTE_THRESHOLD_M
    private val confirmations = Geo.OFF_ROUTE_CONFIRMATIONS

    @Test fun riderOnRouteDoesNotTriggerRerouting() {
        val t = OffRouteTracker(threshold, confirmations, cooldownMs = 0L)
        assertFalse(t.update(0.0, 12f, now))
        assertEquals(0, t.streak)
    }

    @Test fun smallGpsDeviationsDoNotTriggerRerouting() {
        val t = OffRouteTracker(threshold, confirmations, cooldownMs = 0L)
        assertFalse(t.update(10.0, 12f, now))
        assertFalse(t.update(20.0, 12f, now))
        assertEquals(0, t.streak)
    }

    @Test fun sustainedDeviationTriggersRerouting() {
        val t = OffRouteTracker(threshold, confirmations, cooldownMs = 0L)
        assertFalse(t.update(60.0, 12f, now))
        assertFalse(t.update(60.0, 12f, now))
        assertTrue(t.update(60.0, 12f, now))
        assertEquals(0, t.streak)
    }

    @Test fun poorGpsAccuracyDoesNotTriggerFalseRerouting() {
        val t = OffRouteTracker(threshold, confirmations, cooldownMs = 0L)
        assertFalse(t.update(60.0, 150f, now))
        assertEquals(0, t.streak)
        // Once the fix is accurate again, the rider can be judged properly.
        assertFalse(t.update(60.0, 12f, now))
    }

    @Test fun returningToRouteResetsTheOffRouteCounter() {
        val t = OffRouteTracker(threshold, confirmations, cooldownMs = 0L)
        assertFalse(t.update(60.0, 12f, now))
        assertFalse(t.update(60.0, 12f, now))
        assertEquals(2, t.streak)
        // Back on the route: the counter starts over.
        assertFalse(t.update(5.0, 12f, now))
        assertEquals(0, t.streak)
        assertFalse(t.update(60.0, 12f, now))
        assertFalse(t.update(60.0, 12f, now))
        assertTrue(t.update(60.0, 12f, now))
    }

    @Test fun newRouteUsesCurrentRiderPositionAsOriginAndKeepsDestination() {
        val r = Reroute(threshold, confirmations, cooldownMs = 0L)
        val from = P(12.97, 77.59)
        val dest = P(12.98, 77.6)
        assertTrue(r.start(from, dest, nowMs = 1000))
        assertEquals(from, r.lastOrigin)
        assertEquals(dest, r.destination)
        val pts = listOf(P(12.97, 77.59), P(12.97, 77.6), P(12.98, 77.6))
        assertTrue(r.onResponse(pts, from, dest, nowMs = 2000))
        assertEquals(pts, r.currentRoute)
        assertEquals(dest, r.destination)
        assertFalse(r.inProgress)
    }

    @Test fun duplicateReroutingRequestsArePrevented() {
        val r = Reroute(threshold, confirmations, cooldownMs = 0L)
        val from = P(12.97, 77.59)
        val dest = P(12.98, 77.6)
        assertTrue(r.start(from, dest, nowMs = 1000))
        assertTrue(r.inProgress)
        assertFalse(r.start(from, dest, nowMs = 1100)) // duplicate while in flight
        val pts = listOf(P(12.97, 77.59), P(12.97, 77.6), P(12.98, 77.6))
        assertTrue(r.onResponse(pts, from, dest, nowMs = 2000))
        assertFalse(r.inProgress)
        assertTrue(r.start(from, dest, nowMs = 3000)) // allowed again once the previous attempt settled
    }

    @Test fun outdatedRoutingResponsesAreIgnored() {
        val r = Reroute(threshold, confirmations, cooldownMs = 0L)
        val from = P(12.97, 77.59)
        val dest = P(12.98, 77.6)
        assertTrue(r.start(from, dest, nowMs = 1000))
        val farAway = P(13.5, 80.0)
        assertFalse(r.onResponse(listOf<P>(from, farAway), farAway, dest, nowMs = 2000)) // origin moved on
        val pts = listOf<P>(from, P(12.97, 77.6), P(12.98, 77.6))
        assertTrue(r.onResponse(pts, from, dest, nowMs = 3000))
        assertEquals(pts, r.currentRoute)
    }

    @Test fun failedRoutingKeepsThePreviousRoute() {
        val r = Reroute(threshold, confirmations, cooldownMs = 0L)
        val from = P(12.97, 77.59)
        val dest = P(12.98, 77.6)
        val pts = listOf<P>(from, P(12.97, 77.6), P(12.98, 77.6))
        assertTrue(r.start(from, dest, nowMs = 1000))
        assertTrue(r.onResponse(pts, from, dest, nowMs = 2000))
        assertEquals(pts, r.currentRoute)
        assertTrue(r.start(from, dest, nowMs = 3000))
        assertTrue(r.onFailure())
        assertEquals(pts, r.currentRoute) // previous route kept
        assertFalse(r.inProgress)
    }

    @Test fun acceptedNewRouteClearsRerouteState() {
        val r = Reroute(threshold, confirmations, cooldownMs = 0L)
        val from = P(12.97, 77.59)
        val dest = P(12.98, 77.6)
        val oldRoute = listOf<P>(from, P(12.97, 77.6), P(12.98, 77.6))
        val newRoute = listOf<P>(P(12.97, 77.59), P(12.97, 77.65), P(12.98, 77.6))
        assertTrue(r.start(from, dest, nowMs = 1000))
        assertTrue(r.onResponse(oldRoute, from, dest, nowMs = 2000))
        assertEquals(oldRoute, r.currentRoute)
        assertTrue(r.start(from, dest, nowMs = 3000))
        assertTrue(r.onResponse(newRoute, from, dest, nowMs = 4000))
        // The new route replaces the old one and the state is clean for the next navigation.
        assertEquals(newRoute, r.currentRoute)
        assertFalse(r.inProgress)
        assertNotNull(r.currentRoute)
    }

    // ------------------------------------------------------------------ Sample route + simulated GPS fixes

    /** Sample planned route: an east-west road leg (~541 m) between two points. */
    private val sampleRoutePts = listOf(P(12.9716, 77.5946), P(12.9716, 77.5996))

    private val startFix = P(12.9716, 77.5946) // rider at the start of the sample route
    private val midFix = P(12.9716, 77.5976) // rider ~325 m along the sample route
    private val driftedFix = P(12.97214, 77.595) // rider ~60 m north of the sample route (perpendicular offset)

    /** Shortest distance (m) from a simulated GPS fix to the sample route geometry. */
    private fun sampleDeviation(fix: P): Double = Route(sampleRoutePts).snap(fix).second

    @Test fun sampleRouteRiderOnRouteDoesNotReroute() {
        val route = Route(sampleRoutePts)
        assertEquals(0.0, route.snap(startFix).second, 1.0)
        assertEquals(0.0, route.snap(midFix).second, 1.0)
        val tracker = OffRouteTracker(Geo.OFF_ROUTE_THRESHOLD_M, Geo.OFF_ROUTE_CONFIRMATIONS, cooldownMs = 0L)
        assertFalse(tracker.update(route.snap(startFix).second, 12f, now))
        assertFalse(tracker.update(route.snap(midFix).second, 12f, now))
    }

    @Test fun sampleRouteSmallDeviationDoesNotReroute() {
        val tracker = OffRouteTracker(Geo.OFF_ROUTE_THRESHOLD_M, Geo.OFF_ROUTE_CONFIRMATIONS, cooldownMs = 0L)
        val d = sampleDeviation(driftedFix)
        assertTrue(d > Geo.OFF_ROUTE_THRESHOLD_M)
        // Sub-threshold drift must not accumulate into a reroute.
        assertFalse(tracker.update(20.0, 12f, now))
        assertEquals(0, tracker.streak)
    }

    @Test fun sampleRouteSustainedOffRouteTriggersRerouteAfterConfirmations() {
        val route = Route(sampleRoutePts)
        val tracker = OffRouteTracker(Geo.OFF_ROUTE_THRESHOLD_M, Geo.OFF_ROUTE_CONFIRMATIONS, cooldownMs = 0L)
        val d = sampleDeviation(driftedFix)
        assertTrue(d > Geo.OFF_ROUTE_THRESHOLD_M)
        assertFalse(tracker.update(d, 12f, now))
        assertFalse(tracker.update(d, 12f, now))
        assertTrue(tracker.update(d, 12f, now)) // sustained off-route -> reroute due
    }

    @Test fun sampleRoutePoorGpsAccuracyDoesNotFalseReroute() {
        val tracker = OffRouteTracker(Geo.OFF_ROUTE_THRESHOLD_M, Geo.OFF_ROUTE_CONFIRMATIONS, cooldownMs = 0L)
        assertFalse(tracker.update(60.0, 150f, now))
        assertEquals(0, tracker.streak)
    }

    @Test fun sampleRouteGpsFixMimicsRerouteDeviation() {
        val route = Route(sampleRoutePts)
        val onRoute = route.matchOnRoute(midFix, 200.0, 90.0, 5.0, 12.0)
        assertEquals(0.0, onRoute.off, 1.0)
        assertTrue(onRoute.s > 0.0)
        val offRoute = route.matchOnRoute(driftedFix, 0.0, 90.0, 5.0, 12.0)
        assertTrue(offRoute.off > Geo.OFF_ROUTE_THRESHOLD_M)
    }

    @Test fun sampleRouteRerouteRecalculatesFromRiderPositionKeepingFinalDestination() {
        val dest = P(12.9716, 77.5996)
        val r = Reroute(Geo.OFF_ROUTE_THRESHOLD_M, Geo.OFF_ROUTE_CONFIRMATIONS, cooldownMs = 0L)
        val origin = driftedFix // rider is off the sample route here
        assertTrue(r.start(origin, dest, nowMs = 1000))
        assertEquals(origin, r.lastOrigin)
        assertEquals(dest, r.destination)
        val newRoute = listOf<P>(origin, dest) // freshly fetched route via the existing Net provider
        assertTrue(r.onResponse(newRoute, origin, dest, nowMs = 2000))
        assertEquals(newRoute, r.currentRoute)
        assertEquals(dest, r.destination) // final destination never changed
    }
}
