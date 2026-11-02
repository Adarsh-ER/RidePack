package com.ridepack.app

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** On-map debug overlay: a REC indicator while logging, plus live matcher values when the overlay is switched on. */
@Composable fun DebugOverlay(modifier: Modifier) {
    val rec by Diag.recording
    val ov by Diag.overlay
    if (!rec && !ov) return
    val l by Diag.live
    val samples by Diag.sampleCount
    Column(modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (rec) Text("● REC · $samples fixes", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (ov) {
            fun n(v: Double?) = v?.let { String.format(Locale.US, "%.0f", it) } ?: "-"
            Text("GPS ±${n(l.accuracyM)} m · ${n(l.speedKmh)} km/h", color = Color.White, fontSize = 11.sp)
            Text("Progress ${n(l.progressM)} m · off route ${n(l.offRouteM)} m", color = Color.White, fontSize = 11.sp)
            Text("Segment ${l.segment ?: "-"} · fallback ${if (l.fallback) "YES" else "no"}", color = Color.White, fontSize = 11.sp)
            Text("Camera follow ${if (l.follow) "on" else "off"}", color = Color.White, fontSize = 11.sp)
        }
    }
}

/** Debug & diagnostics controls in Settings. Capture, export and clear are locked while the rider is moving. */
@Composable fun DebugSettings() {
    val ctx = LocalContext.current
    val rec by Diag.recording
    val ov by Diag.overlay
    val samples by Diag.sampleCount
    val me by RideState.me.collectAsState()
    val moving = (me?.speed ?: 0f) > 2.5f   // about 9 km/h
    val hasSession = Diag.hasSession()
    var confirmClear by remember { mutableStateOf(false) }
    val act = ctx as? Activity

    HorizontalDivider()
    Text("Debug & diagnostics", fontWeight = FontWeight.Bold)
    Text(
        "Off by default. Records GPS and map-matching details on this phone only; nothing is uploaded. " +
            "Exported reports contain your precise location history, so share them only with people you trust.",
        fontSize = 12.sp
    )
    Text(
        if (rec) "Logging ON · $samples GPS samples" else "Logging off",
        fontWeight = FontWeight.Bold, fontSize = 13.sp,
        color = if (rec) Color(0xFFD32F2F) else Color.Unspecified
    )
    if (moving) Text(
        "Capture, export and clear unlock when you stop. Stop logging is always available.",
        fontSize = 12.sp, color = Color(0xFFB45309)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show debug overlay on map", Modifier.weight(1f))
        Switch(checked = ov, onCheckedChange = { Diag.setOverlay(it) })
    }
    if (rec) {
        Button(
            onClick = { Diag.stop(); RideState.msg.value = "Logging stopped" },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7280))
        ) { Text("Stop logging") }
    } else {
        Button(enabled = !moving, onClick = {
            val err = Diag.start()
            RideState.msg.value = err ?: "Logging started"
        }) { Text("Start logging") }
    }
    OutlinedButton(enabled = rec && !moving && act != null, onClick = {
        act?.let { a ->
            Diag.screenshot(a) { r ->
                r.onSuccess { f -> RideState.msg.value = "Screenshot saved: ${f.name}" }
                    .onFailure { e -> RideState.msg.value = "Screenshot failed: ${e.message}" }
            }
        }
    }) { Text("Capture screenshot") }
    OutlinedButton(enabled = hasSession && !moving, onClick = {
        Diag.export { r ->
            r.onSuccess { f -> Diag.share(ctx, f) }
                .onFailure { e -> RideState.msg.value = "Export failed: ${e.message}" }
        }
    }) { Text("Export debug report") }
    OutlinedButton(enabled = hasSession && !moving && !rec, onClick = { confirmClear = true }) { Text("Clear logs") }
    Text(
        "Stored privately inside the app (filesDir/diag). Logs and screenshots are kept until you clear them.",
        fontSize = 11.sp
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all debug logs?", fontWeight = FontWeight.Bold) },
            text = { Text("This removes every saved GPS log and screenshot on this phone. Export first if you need them.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    Diag.clearAll { r ->
                        RideState.msg.value = r.fold({ "Debug logs cleared" }, { "Clear failed: ${it.message}" })
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}
