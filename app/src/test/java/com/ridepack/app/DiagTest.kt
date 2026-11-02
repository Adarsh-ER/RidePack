package com.ridepack.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DiagTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun records(f: File): List<JSONObject> =
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    private fun gpsRecord(delta: Double?, off: Double?, acc: Double?, fallback: Boolean): JSONObject =
        DiagJson.build("gps", "s", mapOf(
            "progress_delta_m" to delta, "distance_to_route_m" to off,
            "acc_m" to acc, "fallback" to fallback
        ), 0L)

    @Test fun recordSerializesWithIsoTimeAndNullForBadNumbers() {
        val o = DiagJson.build("gps", "s1", mapOf("lat" to 12.5, "acc" to Double.NaN, "missing" to null), 1_700_000_000_000L)
        assertEquals("gps", o.getString("type"))
        assertEquals("2023-11-14T22:13:20Z", o.getString("t"))
        assertEquals(12.5, o.getDouble("lat"), 1e-9)
        assertTrue(o.isNull("acc"))
        assertTrue(o.isNull("missing"))
        assertEquals("s1", JSONObject(o.toString()).getString("session"))
    }

    @Test fun loggingIsOffByDefaultAndIgnoresEvents() {
        val rec = DiagRecorder(tmp.root)
        assertFalse(rec.recording)
        rec.event("gps", mapOf("lat" to 1.0))
        assertEquals(0, rec.samples)
        assertNull(rec.store.sessionDir)
    }

    @Test fun sessionStartRecordsAndStopIgnoresLaterEvents() {
        val rec = DiagRecorder(tmp.root)
        assertTrue(rec.start("s-1", device = mapOf("device" to "test")))
        assertTrue(rec.recording)
        rec.event("gps", mapOf("lat" to 1.0))
        assertEquals(1, rec.samples)
        assertTrue(rec.stop())
        assertFalse(rec.recording)
        rec.event("gps", mapOf("lat" to 2.0))
        assertEquals(1, rec.samples)
        rec.flushSync()
        val types = records(File(tmp.root, "s-1/gps_log.jsonl")).map { it.getString("type") }
        assertEquals(listOf("session_start", "gps", "session_stop"), types)
    }

    @Test fun restartResumesSameSessionFolder() {
        val first = DiagRecorder(tmp.root)
        first.start("s-2")
        first.event("gps", mapOf("lat" to 1.0))
        first.stop()
        first.flushSync()
        val second = DiagRecorder(tmp.root)
        assertTrue(second.start("s-2", resumed = true))
        second.stop()
        second.flushSync()
        val types = records(File(tmp.root, "s-2/gps_log.jsonl")).map { it.getString("type") }
        assertTrue(types.contains("app_restart"))
        assertEquals("s-2", second.store.sessionId)
    }

    @Test fun analyzerFlagsJumpsBackwardsOffRouteAccuracyAndFallback() {
        val a = DiagAnalyzer(DiagThresholds())
        a.onRecord(gpsRecord(300.0, null, null, false))
        a.onRecord(gpsRecord(-40.0, 90.0, 30.0, true))
        a.onRecord(gpsRecord(-40.0, 90.0, 30.0, true))
        val kinds = a.warnings.map { it.kind }
        assertTrue(kinds.contains("progress_jump"))
        assertTrue(kinds.contains("progress_backward"))
        assertTrue(kinds.contains("far_from_route"))
        assertTrue(kinds.contains("matcher_fallback"))
        assertEquals(1, kinds.count { it == "poor_accuracy" })
        assertEquals(2, a.counts["matcher_fallback"])
    }

    @Test fun analyzerIgnoresSmallProgressChanges() {
        val a = DiagAnalyzer(DiagThresholds())
        a.onRecord(gpsRecord(10.0, 3.0, 5.0, false))
        assertTrue(a.warnings.isEmpty())
    }

    @Test fun analyzerAcceptsMissingOptionalFields() {
        val a = DiagAnalyzer(DiagThresholds())
        a.onRecord(JSONObject().put("type", "gps").put("t", "x"))
        a.onRecord(gpsRecord(null, null, null, false))
        assertTrue(a.warnings.isEmpty())
        assertEquals(2, a.counts["gps"])
    }

    @Test fun routeEventsUpdateReportedRouteSize() {
        val rec = DiagRecorder(tmp.root)
        rec.start("s-3")
        rec.event("route", mapOf("action" to "loaded", "points" to 10, "total_m" to 1234.0))
        assertEquals(10, rec.analyzer.routePoints)
        assertEquals(1234.0, rec.analyzer.routeTotalM!!, 1e-9)
        rec.event("route", mapOf("action" to "cleared", "points" to null, "total_m" to null))
        assertNull(rec.analyzer.routePoints)
    }

    @Test fun rotationKeepsOneOlderFileAndCapsSize() {
        val store = DiagStore(tmp.root, maxFileBytes = 300)
        assertTrue(store.open("r-1"))
        for (batch in 1..3) {
            repeat(5) { store.append("""{"batch":$batch,"pad":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}""") }
            store.flush()
        }
        val dir = File(tmp.root, "r-1")
        val rotated = records(File(dir, DiagStore.ROTATED))
        val current = records(File(dir, DiagStore.LOG))
        assertEquals(5, rotated.size)
        assertEquals(5, current.size)
        assertEquals(2, rotated.first().getInt("batch"))
        assertEquals(3, current.first().getInt("batch"))
    }

    @Test fun reportZipContainsLogsScreenshotsAndSummary() {
        val session = File(tmp.root, "z-1")
        File(session, "screenshots").mkdirs()
        File(session, DiagStore.LOG).writeText("{\"type\":\"gps\"}\n")
        File(session, "screenshots/shot-1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val out = File(tmp.root, "out/report.zip")
        DiagReport.writeZip(session, "summary body", out)
        ZipFile(out).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("summary.txt", DiagStore.LOG, "screenshots/shot-1.jpg")))
            assertEquals("summary body", z.getInputStream(z.getEntry("summary.txt")).bufferedReader().readText())
        }
    }

    @Test fun summaryHasPrivacyNoteWarningsAndThresholds() {
        val rec = DiagRecorder(tmp.root)
        rec.start("s-4")
        rec.event("gps", mapOf("progress_delta_m" to 500.0))
        val text = rec.summary(mapOf("device" to "test"))
        assertTrue(text.contains("PRIVACY"))
        assertTrue(text.contains("Diagnostic warnings"))
        assertTrue(text.contains("progress_jump"))
        assertTrue(text.contains("Thresholds"))
        assertNotNull(rec.analyzer.warnings.firstOrNull())
    }
}
