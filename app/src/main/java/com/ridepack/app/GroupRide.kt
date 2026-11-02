package com.ridepack.app

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Group ride meeting point, arrival and start rules. Pure Kotlin (no Android or Firebase types) so the
 * rules can be unit tested. Transports are reached through [GroupStore].
 */
enum class RideStage { WAITING, STARTED }

/** The leader's confirmed meeting spot. It does not move with the leader's later GPS fixes. */
data class MeetingPoint(val lat: Double, val lon: Double, val confirmedAt: Long, val leaderId: String)

/**
 * Shared ride session. [destination] is only set once the ride is STARTED; before that it stays null.
 * [leaderId] is the rider ID (used in packets) and [leaderUid] is the Firebase UID (used by security rules).
 */
data class GroupSession(
    val code: String,
    val leaderId: String,
    val leaderUid: String,
    val stage: RideStage,
    val meeting: MeetingPoint?,
    val destination: P?
)

/** A GPS fix reduced to plain values, so the rules do not depend on android.location.Location. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

sealed class FixProblem {
    object NoFix : FixProblem()
    object InvalidFix : FixProblem()
    object StaleFix : FixProblem()
    data class Inaccurate(val accuracyM: Float?) : FixProblem()
}

sealed class ArrivalCheck {
    object NoMeeting : ArrivalCheck()
    data class Problem(val problem: FixProblem) : ArrivalCheck()
    data class Outside(val distanceM: Double) : ArrivalCheck()
    data class Ready(val distanceM: Double) : ArrivalCheck()
}

enum class GroupStatus(val label: String) {
    ON_THE_WAY("On the way"),
    ARRIVED_PENDING("Arrived — awaiting confirmation"),
    ARRIVED("Arrived"),
    OFFLINE("Offline or location unavailable")
}

/** What the UI shows for the current group ride. */
data class GroupView(
    val session: GroupSession?,
    val dirty: Boolean,
    val starting: Boolean,
    val confirming: Boolean,
    val arrivals: Map<String, Long>,
    val pendingDestination: P?,
    val message: String?
)

object GroupRules {
    const val ARRIVAL_RADIUS_M = 75.0
    const val MAX_ACCURACY_M = 35f
    const val MAX_FIX_AGE_MS = 15_000L
    const val RIDER_STALE_MS = 30_000L

    fun validPoint(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** Why a fix cannot be used (missing, invalid, old, or too inaccurate), or null when it is usable. */
    fun fixProblem(fix: Fix?, nowMs: Long, maxAccuracyM: Float = MAX_ACCURACY_M): FixProblem? {
        if (fix == null) return FixProblem.NoFix
        if (!validPoint(fix.lat, fix.lon)) return FixProblem.InvalidFix
        if (nowMs - fix.timeMs > MAX_FIX_AGE_MS || fix.timeMs > nowMs + 5_000L) return FixProblem.StaleFix
        val acc = fix.accuracyM
        if (acc == null || !acc.isFinite() || acc > maxAccuracyM) return FixProblem.Inaccurate(acc)
        return null
    }

    fun describeProblem(p: FixProblem): String = when (p) {
        FixProblem.NoFix -> "No GPS fix yet. Wait for GPS and try again."
        FixProblem.InvalidFix -> "The GPS location is invalid. Try again."
        FixProblem.StaleFix -> "The GPS location is old. Wait for a fresh fix and try again."
        is FixProblem.Inaccurate -> "GPS accuracy is too low (±${p.accuracyM?.toInt() ?: "?"} m, need ${MAX_ACCURACY_M.toInt()} m or better). Try again in the open."
    }

    /** Captures the leader's meeting point from a usable fix. Never falls back to (0,0) or a stale location. */
    fun meetingFromFix(fix: Fix?, leaderId: String, nowMs: Long): Result<MeetingPoint> {
        val f = fix ?: return Result.failure(IllegalArgumentException(describeProblem(FixProblem.NoFix)))
        fixProblem(f, nowMs)?.let { return Result.failure(IllegalArgumentException(describeProblem(it))) }
        return Result.success(MeetingPoint(f.lat, f.lon, nowMs, leaderId))
    }

    /** Proximity from a usable fix. Being inside the radius is a precondition for confirming, not confirmation itself. */
    fun arrivalCheck(fix: Fix?, meeting: MeetingPoint?, nowMs: Long, radiusM: Double = ARRIVAL_RADIUS_M, maxAccuracyM: Float = MAX_ACCURACY_M): ArrivalCheck {
        if (meeting == null) return ArrivalCheck.NoMeeting
        val f = fix ?: return ArrivalCheck.Problem(FixProblem.NoFix)
        fixProblem(f, nowMs, maxAccuracyM)?.let { return ArrivalCheck.Problem(it) }
        val d = Geo.dist(P(f.lat, f.lon), P(meeting.lat, meeting.lon))
        return if (d <= radiusM) ArrivalCheck.Ready(d) else ArrivalCheck.Outside(d)
    }

    fun describeCheck(c: ArrivalCheck): String = when (c) {
        ArrivalCheck.NoMeeting -> "The leader has not set the meeting point yet."
        is ArrivalCheck.Problem -> describeProblem(c.problem)
        is ArrivalCheck.Outside -> "You are ${Geo.fmt(c.distanceM)} from the meeting point. Get within ${ARRIVAL_RADIUS_M.toInt()} m to confirm."
        is ArrivalCheck.Ready -> "Within ${ARRIVAL_RADIUS_M.toInt()} m. Tap Confirm arrival."
    }

    /** Status shown for each rider. Arrival is only "Arrived" when a rider has explicitly confirmed it. */
    fun riderStatus(rider: Rider?, meeting: MeetingPoint?, confirmedAt: Long?, nowMs: Long, radiusM: Double = ARRIVAL_RADIUS_M): GroupStatus {
        if (rider == null || nowMs - rider.seen > RIDER_STALE_MS) return GroupStatus.OFFLINE
        if (confirmedAt != null) return GroupStatus.ARRIVED
        if (meeting != null && Geo.dist(P(rider.lat, rider.lon), P(meeting.lat, meeting.lon)) <= radiusM) {
            return GroupStatus.ARRIVED_PENDING
        }
        return GroupStatus.ON_THE_WAY
    }

    /** The destination riders may see. Null until the ride is STARTED in shared state, whatever the transports say. */
    fun visibleDestination(session: GroupSession?): P? = session?.takeIf { it.stage == RideStage.STARTED }?.destination
}

/** Short, non-reversible tag advertised over Nearby instead of the ride code. */
object RideTag {
    fun of(code: String): String =
        MessageDigest.getInstance("SHA-256").digest("ridepack:$code".toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
}

/** JSON for sessions. Firebase stores the session without the destination; Nearby includes it once started. */
object GroupJson {
    fun toJson(s: GroupSession, withDestination: Boolean): JSONObject {
        val o = JSONObject()
            .put("code", s.code).put("leaderId", s.leaderId).put("leaderUid", s.leaderUid).put("stage", s.stage.name)
        s.meeting?.let { m ->
            o.put("meeting", JSONObject().put("lat", m.lat).put("lon", m.lon).put("at", m.confirmedAt).put("leaderId", m.leaderId))
        }
        if (withDestination) s.destination?.let { d -> o.put("destination", JSONObject().put("lat", d.lat).put("lon", d.lon)) }
        return o
    }

    /** Parses a session. Returns null for anything malformed or with invalid coordinates. */
    fun fromJson(o: JSONObject, code: String): GroupSession? {
        return try {
            val stage = RideStage.valueOf(o.getString("stage"))
            var meeting: MeetingPoint? = null
            o.optJSONObject("meeting")?.let { m ->
                val lat = m.getDouble("lat"); val lon = m.getDouble("lon")
                if (!GroupRules.validPoint(lat, lon)) return null
                meeting = MeetingPoint(lat, lon, m.optLong("at", 0L), m.optString("leaderId"))
            }
            var dest: P? = null
            o.optJSONObject("destination")?.let { d ->
                val lat = d.getDouble("lat"); val lon = d.getDouble("lon")
                if (!GroupRules.validPoint(lat, lon)) return null
                dest = P(lat, lon)
            }
            GroupSession(code, o.getString("leaderId"), o.optString("leaderUid"), stage, meeting, dest)
        } catch (e: Exception) { null }
    }
}

/** Persistence used by [GroupController]. Callbacks report whether the write was confirmed. */
interface GroupStore {
    fun writeSession(s: GroupSession, done: (Boolean) -> Unit)
    fun writeArrival(uid: String, riderId: String, atMs: Long, done: (Boolean) -> Unit)
}

/**
 * One ride's group state machine: the leader's meeting point, final destination and start; and each rider's
 * arrival. Local state changes only after the shared store confirms a write. Callbacks are expected on one thread.
 */
class GroupController(
    private val store: GroupStore?,
    private val myId: () -> String,
    private val myUid: () -> String?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onChange: () -> Unit = {},
    /** Called when one of our writes was confirmed, so the leader can push the committed state out. */
    var onCommit: (() -> Unit)? = null
) {
    var code: String? = null; private set
    var session: GroupSession? = null; private set
    /** Leader edits (meeting point) not yet saved to the shared store. */
    var dirty = false; private set
    var starting = false; private set
    var confirming = false; private set
    var pendingDestination: P? = null
    var message: String? = null; private set
    private val arrivalMap = HashMap<String, Long>()

    val arrivals: Map<String, Long> get() = arrivalMap.toMap()

    fun isLeader(): Boolean = session?.leaderId == myId()

    fun snapshot() = GroupView(session, dirty, starting, confirming, arrivals, pendingDestination, message)

    private fun changed() = onChange()

    private fun committed() { val h = onCommit; h?.invoke() }

    /** Leader creates a new session. Only this call writes a session; joining never does. */
    fun createRide(c: String) {
        leave()
        code = c
        session = GroupSession(c, myId(), myUid().orEmpty(), RideStage.WAITING, null, null)
        dirty = true
        flush()
    }

    /** Join an existing session by code. Nothing is written; the shared session arrives through the listeners. */
    fun join(c: String) {
        leave()
        code = c
        changed()
    }

    fun leave() {
        code = null; session = null; dirty = false; starting = false; confirming = false
        pendingDestination = null; arrivalMap.clear(); message = null
        changed()
    }

    /** Leader sets the meeting point from a confirmed fix. Replaces only when the leader explicitly confirms again. */
    fun setMeeting(mp: MeetingPoint): String? {
        val s = session ?: return "Not in a group ride"
        if (!isLeader()) return "Only the leader can set the meeting point"
        if (s.stage == RideStage.STARTED) return "The ride has already started"
        session = s.copy(meeting = mp)
        dirty = true
        flush()
        return null
    }

    /** Pushes unsaved leader edits. Safe to call repeatedly (for example from a timer). */
    fun flush() {
        val s = session
        if (s == null || !dirty || !isLeader() || starting) { changed(); return }
        val st = store
        if (st == null) { message = "Internet link needed to save the ride"; changed(); return }
        st.writeSession(s) { ok ->
            if (ok) { if (session == s) dirty = false; message = null; committed() }
            else message = "Could not save the ride to the internet link. Will retry."
            changed()
        }
    }

    /** Leader stages the final destination. It stays hidden from riders until the ride is started. */
    fun setDestination(d: P): String? {
        val s = session ?: return "Not in a group ride"
        if (!isLeader()) return "Only the leader can set the final destination"
        if (s.stage == RideStage.STARTED) return "The ride has already started"
        pendingDestination = d
        changed()
        return null
    }

    /**
     * Leader starts the group ride. The destination is written first, then the stage. Local state (and so the
     * destination shown to the leader's group) changes only after both writes are confirmed.
     */
    fun startRide(): String? {
        val s = session ?: return "Not in a group ride"
        if (!isLeader()) return "Only the leader can start the group ride"
        if (s.stage == RideStage.STARTED) return "The ride has already started"
        if (starting) return "Starting…"
        if (dirty || s.meeting == null) return "Save the meeting point first"
        val d = pendingDestination ?: return "Set the final destination first"
        val st = store ?: return "Internet link needed to start the group ride"
        starting = true; message = null
        changed()
        fun failStart(msg: String) { starting = false; message = msg; changed() }
        // The final destination and the STARTED stage are written together, so the destination can never be
        // visible to riders before the shared state says the ride started.
        val started = s.copy(stage = RideStage.STARTED, destination = d)
        st.writeSession(started) { ok ->
            if (ok) {
                if (session?.code == s.code) {
                    session = started
                    starting = false
                    message = "Group ride started"
                    committed()
                    changed()
                } else {
                    failStart("Could not start the ride. The destination stays hidden from riders.")
                }
            } else {
                failStart("Could not save the start. The destination stays hidden from riders.")
            }
        }
        return null
    }

    /**
     * Rider confirms arrival. Needs a usable fix within the radius; GPS proximity alone never marks arrival.
     * A repeated confirmation after success is ignored, and a failed write can be retried.
     */
    fun confirmArrival(fix: Fix?, radiusM: Double = GroupRules.ARRIVAL_RADIUS_M, maxAccuracyM: Float = GroupRules.MAX_ACCURACY_M): String? {
        val s = session ?: return "Not in a group ride"
        if (isLeader()) return "The leader does not confirm arrival"
        if (arrivalMap.containsKey(myId())) return "Arrival already confirmed"
        if (confirming) return "Confirming…"
        val check = GroupRules.arrivalCheck(fix, s.meeting, nowMs(), radiusM, maxAccuracyM)
        if (check !is ArrivalCheck.Ready) return GroupRules.describeCheck(check)
        val st = store ?: return "Internet link needed to confirm arrival"
        val uid = myUid() ?: return "Sign-in not ready. Try again."
        confirming = true; message = null
        changed()
        val at = nowMs()
        st.writeArrival(uid, myId(), at) { ok ->
            confirming = false
            if (ok) { arrivalMap[myId()] = minOf(arrivalMap[myId()] ?: at, at); message = null }
            else message = "Could not confirm arrival. Try again."
            changed()
        }
        return null
    }

    /** Applies a shared session (from Firebase or a Nearby packet). Ignores other rides, other leaders, and stage rollbacks. */
    fun applyRemoteSession(incoming: GroupSession): Boolean {
        if (incoming.code != code) return false
        val cur = session
        if (cur == null) { session = incoming; changed(); return true }
        if (incoming.leaderId != cur.leaderId) return false
        if (cur.stage == RideStage.STARTED && incoming.stage != RideStage.STARTED) return false
        // The leader's own unsaved edits win over an older echo of the shared session.
        if (dirty && isLeader()) return true
        session = incoming.copy(destination = incoming.destination ?: cur.destination?.takeIf { incoming.stage == RideStage.STARTED })
        changed()
        return true
    }

    /** Destination read from the shared store. Accepted only once the session is STARTED. */
    fun applyRemoteDestination(d: P): Boolean {
        val cur = session ?: return false
        if (cur.stage != RideStage.STARTED) return false
        session = cur.copy(destination = d)
        changed()
        return true
    }

    /** Arrival confirmed by a rider, as seen in the shared store. Repeats keep the earliest time. */
    fun applyRemoteArrival(riderId: String, atMs: Long) {
        arrivalMap[riderId] = minOf(arrivalMap[riderId] ?: atMs, atMs)
        changed()
    }
}

/** The group ride controller, created on first use. Riders' destination follows the shared started state. */
fun groupController(): GroupController = RideState.groupCtl ?: GroupController(
    store = FirebaseGroupStore,
    myId = { RideState.myId },
    myUid = { RideState.firebaseUid },
    onChange = {
        val ctl = RideState.groupCtl
        val s = ctl?.session
        // Once the shared state says the ride started, the final destination is real for everyone in the ride.
        if (ctl != null && s != null && s.stage == RideStage.STARTED && s.destination != null) {
            RideState.destination.value = s.destination
        }
        RideState.group.value = ctl?.snapshot()
    }
).also { RideState.groupCtl = it }
