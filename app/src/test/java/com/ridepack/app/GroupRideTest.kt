package com.ridepack.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the group ride state machine: fixed meeting point, arrival confirmation and ride start. */
class GroupRideTest {

    private val now = 1_700_000_000_000L

    private fun fix(lat: Double, lon: Double, accuracyM: Float?, timeMs: Long = now) = Fix(lat, lon, accuracyM, timeMs)

    /** Stand-in for Firebase: confirms writes unless [failNext] is set, and records them. */
    private class FakeStore : GroupStore {
        val sessions = mutableListOf<GroupSession>()
        val arrivals = mutableListOf<Pair<String, Long>>()
        var failNext = false

        override fun writeSession(s: GroupSession, done: (Boolean) -> Unit) {
            if (failNext) { failNext = false; done(false); return }
            sessions.add(s); done(true)
        }

        override fun writeArrival(uid: String, riderId: String, atMs: Long, done: (Boolean) -> Unit) {
            arrivals.add(Pair(riderId, atMs)); done(true)
        }
    }

    private fun leaderCtl(store: GroupStore, leaderId: String = "leader1"): GroupController =
        GroupController(store, { leaderId }, { "uid-$leaderId" }, { now })

    private fun riderCtl(store: GroupStore, riderId: String = "rider1"): GroupController =
        GroupController(store, { riderId }, { "uid-$riderId" }, { now })

    /** A rider who joined ride ABC and received the leader's session at the given stage. */
    private fun riderInRide(stage: RideStage, meeting: MeetingPoint?, destination: P?): GroupController {
        val rider = riderCtl(FakeStore())
        rider.join("ABC")
        rider.applyRemoteSession(GroupSession("ABC", "leader1", "uid-leader1", stage, meeting, destination))
        return rider
    }

    private fun startedLeader(): GroupController {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        val meeting = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!
        leader.setMeeting(meeting)
        leader.setDestination(P(12.98, 77.6))
        assertNull(leader.startRide())
        return leader
    }

    @Test fun leaderMeetingPointComesFromConfirmedFixOnly() {
        val ok = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now)
        assertTrue(ok.isSuccess)
        val mp = ok.getOrNull()!!
        assertEquals(12.97, mp.lat, 1e-9)
        assertEquals(77.59, mp.lon, 1e-9)
        assertEquals("leader1", mp.leaderId)
        assertEquals(now, mp.confirmedAt)
        // Missing, invalid and too inaccurate fixes never become a meeting point.
        assertTrue(GroupRules.meetingFromFix(null, "leader1", now).isFailure)
        assertTrue(GroupRules.meetingFromFix(fix(0.0, 0.0, 12f), "leader1", now).isFailure)
        assertTrue(GroupRules.meetingFromFix(fix(12.97, 77.59, 60f), "leader1", now).isFailure)
        assertTrue(GroupRules.meetingFromFix(fix(12.97, 77.59, null), "leader1", now).isFailure)
    }

    @Test fun meetingPointIsFixedUntilTheLeaderExplicitlyReconfirms() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        assertNull(leader.snapshot().session?.meeting)
        leader.setMeeting(GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!)
        val mp = leader.snapshot().session?.meeting
        assertNotNull(mp)
        assertEquals(12.97, mp!!.lat, 1e-9)
        assertEquals(77.59, mp.lon, 1e-9)
    }

    @Test fun finalDestinationStaysHiddenUntilTheRideStarts() {
        val leaderStore = FakeStore()
        val leader = leaderCtl(leaderStore)
        leader.createRide("ABC")
        leader.setMeeting(GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!)
        leader.setDestination(P(12.98, 77.6))
        assertNull(leader.startRide())
        val started = leader.snapshot().session
        assertEquals(RideStage.STARTED, started?.stage)
        assertEquals(P(12.98, 77.6), started?.destination)
        assertEquals(RideStage.STARTED, leaderStore.sessions.last().stage)
        assertNotNull(leaderStore.sessions.last().destination)
    }

    @Test fun riderSeesDestinationOnlyAfterTheRideStarts() {
        val leader = startedLeader()
        val meeting = leader.snapshot().session?.meeting
        val rider = riderInRide(RideStage.STARTED, meeting, P(12.98, 77.6))
        assertEquals(RideStage.STARTED, rider.snapshot().session?.stage)
        assertEquals(P(12.98, 77.6), rider.snapshot().session?.destination)
        val waitingRider = riderInRide(RideStage.WAITING, meeting, null)
        assertEquals(RideStage.WAITING, waitingRider.snapshot().session?.stage)
        assertNull(waitingRider.snapshot().session?.destination)
    }

    @Test fun gpsProximityAloneDoesNotConfirmArrival() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        val meeting = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!
        leader.setMeeting(meeting)
        val rider = riderInRide(RideStage.WAITING, meeting, null)
        val check = GroupRules.arrivalCheck(fix(12.97, 77.59, 12f), meeting, now)
        assertTrue(check is ArrivalCheck.Ready)
        assertTrue(rider.arrivals.isEmpty())
        assertNull(rider.confirmArrival(fix(12.97, 77.59, 12f)))
        assertEquals(1, rider.arrivals.size)
        assertEquals(GroupStatus.ARRIVED, GroupRules.riderStatus(Rider("rider1", "R", 12.97, 77.59, 0f, now), meeting, rider.arrivals["rider1"], now))
    }

    @Test fun poorAccuracyPreventsArrivalConfirmation() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        val meeting = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!
        leader.setMeeting(meeting)
        val rider = riderInRide(RideStage.WAITING, meeting, null)
        assertNotNull(rider.confirmArrival(fix(12.97, 77.59, 60f)))
        assertTrue(rider.arrivals.isEmpty())
    }

    @Test fun riderOutsideRadiusCannotConfirmArrival() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        val meeting = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!
        leader.setMeeting(meeting)
        val rider = riderInRide(RideStage.WAITING, meeting, null)
        val far = fix(12.98, 77.6, 12f) // roughly 150 m from the meeting point
        val check = GroupRules.arrivalCheck(far, meeting, now)
        assertTrue(check is ArrivalCheck.Outside)
        assertNotNull(rider.confirmArrival(far))
        assertTrue(rider.arrivals.isEmpty())
    }

    @Test fun repeatedConfirmationDoesNotDuplicateArrival() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        val meeting = GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!
        leader.setMeeting(meeting)
        val rider = riderInRide(RideStage.WAITING, meeting, null)
        assertNull(rider.confirmArrival(fix(12.97, 77.59, 12f)))
        assertEquals("Arrival already confirmed", rider.confirmArrival(fix(12.97, 77.59, 12f)))
    }

    @Test fun onlyLeaderCanStartTheGroupRide() {
        val leader = startedLeader()
        val rider = riderInRide(RideStage.STARTED, leader.snapshot().session?.meeting, P(12.98, 77.6))
        assertEquals("Only the leader can start the group ride", rider.startRide())
    }

    @Test fun failedStartWriteKeepsDestinationHidden() {
        val store = FakeStore()
        val leader = leaderCtl(store)
        leader.createRide("ABC")
        leader.setMeeting(GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!)
        leader.setDestination(P(12.98, 77.6))
        store.failNext = true
        assertNull(leader.startRide())
        assertEquals("Could not save the start. The destination stays hidden from riders.", leader.snapshot().message)
        val session = leader.snapshot().session
        assertEquals(RideStage.WAITING, session?.stage)
        assertNull(session?.destination)
    }

    @Test fun leaderCanStartWhileRidersAreStillOnTheWay() {
        val leader = leaderCtl(FakeStore())
        leader.createRide("ABC")
        leader.setMeeting(GroupRules.meetingFromFix(fix(12.97, 77.59, 12f), "leader1", now).getOrNull()!!)
        leader.setDestination(P(12.98, 77.6))
        // Nobody has confirmed arrival yet, but the leader may still start the ride.
        assertNull(leader.startRide())
        assertEquals(RideStage.STARTED, leader.snapshot().session?.stage)
    }

    @Test fun leavingResetsGroupStateAndStaleSessionsAreIgnored() {
        val rider = riderCtl(FakeStore())
        rider.join("ABC")
        assertFalse(rider.applyRemoteSession(GroupSession("XYZ", "leader1", "uid-leader1", RideStage.WAITING, null, null)))
        rider.leave()
        assertNull(rider.snapshot().session)
        assertNull(rider.code)
    }

    @Test fun sessionJsonRoundTripKeepsStageMeetingAndDestination() {
        val started = GroupSession("ABC", "leader1", "uid-leader1", RideStage.STARTED, MeetingPoint(12.97, 77.59, now, "leader1"), P(12.98, 77.6))
        val back = GroupJson.fromJson(GroupJson.toJson(started, withDestination = true), "ABC")
        assertNotNull(back)
        assertEquals(RideStage.STARTED, back!!.stage)
        assertEquals(P(12.98, 77.6), back.destination)
        val waiting = GroupSession("ABC", "leader1", "uid-leader1", RideStage.WAITING, MeetingPoint(12.97, 77.59, now, "leader1"), null)
        val wBack = GroupJson.fromJson(GroupJson.toJson(waiting, withDestination = false), "ABC")
        assertEquals(RideStage.WAITING, wBack?.stage)
        assertNull(wBack?.destination)
        assertNull(GroupJson.fromJson(JSONObject().put("stage", "NOT_A_STAGE"), "ABC"))
    }
}
