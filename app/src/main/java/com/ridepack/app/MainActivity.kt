@file:OptIn(ExperimentalMaterial3Api::class)
package com.ridepack.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.OfflineRegion
import kotlin.math.roundToInt

val CAT = linkedMapOf("fuel" to "⛽", "food" to "🍴", "view" to "🏞️", "fix" to "🔧", "stay" to "🛏️")

val darkModeState by lazy { mutableStateOf(Store.nightMode) }
val fontSizeState by lazy { mutableStateOf(Store.fontSizeScale) }
val fontColorState by lazy { mutableStateOf(Store.fontColor) }
val offlineModeState by lazy { mutableStateOf(Store.offlineMode) }
val updateCheckState by lazy { mutableStateOf(Store.updateCheck) }
val brightnessState by lazy { mutableStateOf(Store.brightness) }
val autoCenterState by lazy { mutableStateOf(Store.autoCenter) }
val autoRotateState by lazy { mutableStateOf(Store.autoRotate) }
val threeDState by lazy { mutableStateOf(Store.threeD) }
val panelExpandedState = mutableStateOf(true)
val keepPanelState = mutableStateOf(false)
val editPlaceKey = mutableStateOf<String?>(null)
val droppedPin = mutableStateOf<P?>(null)
val searchOpen = mutableStateOf(false)
val panelHiddenState = mutableStateOf(false)
val hideBtnPing = mutableStateOf(0)
val routeRefreshTick = mutableStateOf(0)
val smoothSpeedState = mutableStateOf(0f)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        MapLibre.getInstance(this); Store.init(this)
        Diag.init(this)
        // Read the cached nearby place index off the main thread; search falls back to the network until it's ready.
        Thread { PlaceIndex.load(this) }.start()
        setContent { MaterialTheme { App() } }
    }
}

fun startRide(c: Context) {
    val ok = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!ok) { RideState.msg.value = "Grant location permission to start a ride"; return }
    try { ContextCompat.startForegroundService(c, Intent(c, RideService::class.java)) }
    catch (e: Exception) { RideState.msg.value = "Could not start ride: ${e.message}" }
}
fun stopRide(c: Context) { c.stopService(Intent(c, RideService::class.java)); RideState.riders.value = emptyMap() }

/** Stops the ride service (and with it location, pairing links and tracking), closes the screen and ends the process. */
fun exitApp(c: Context) {
    stopRide(c)
    (c as? android.app.Activity)?.finishAndRemoveTask()
    Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
}

fun hasLocation(c: Context) =
    ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun locateMe(ctx: Context) {
    if (!hasLocation(ctx)) { RideState.msg.value = "Grant location permission"; return }
    RideState.msg.value = "Locating…"
    val fused = LocationServices.getFusedLocationProviderClient(ctx)
    val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            if (shouldAcceptLocation(l, RideState.me.value)) RideState.me.value = l
        }
    }
    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
        .setMinUpdateDistanceMeters(0f)
        .setWaitForAccurateLocation(true)
        .setDurationMillis(8000L)
        .build()
    try { fused.requestLocationUpdates(req, cb, Looper.getMainLooper()) }
    catch (e: SecurityException) { RideState.msg.value = "Location permission missing"; return }
    Handler(Looper.getMainLooper()).postDelayed({
        try { fused.removeLocationUpdates(cb) } catch (e: Exception) {}
        val l = RideState.me.value
        if (l != null) {
            MapHolder.resetCenter(); MapHolder.push()
            RideState.msg.value = "±${l.accuracy.roundToInt()}m (best in 8s)"
        } else RideState.msg.value = "No location fix"
    }, 8500)
}

/** Recenter the map on the rider and re-enable follow. */
fun recenter(ctx: Context) {
    if (RideState.me.value == null) { locateMe(ctx); return }
    MapHolder.follow.value = true
    MapHolder.resetCenter()
    MapHolder.push()
}

fun sendRoute() {
    val r = RideState.route.value ?: return
    RideState.link?.send(RideState.routePacket(r, 200))
    RideState.online?.send(RideState.routePacket(r, 60))
}

fun sendSos(ctx: Context, type: String) {
    if (RideState.code.value == null) { RideState.msg.value = "Join a ride first to send SOS"; return }
    val m = RideState.me.value
    val packet = org.json.JSONObject().put("id", RideState.myId).put("n", RideState.myName).put("sos", type)
        .put("la", m?.latitude ?: 0.0).put("lo", m?.longitude ?: 0.0).put("seq", System.currentTimeMillis()).toString()
    RideState.link?.send(packet)
    RideState.online?.send(packet)
    RideState.msg.value = "SOS ($type) sent"
}

fun speedKmh(l: Location?): Int = if (l != null && l.hasSpeed()) (l.speed * 3.6f).roundToInt() else 0

fun linkLabel(nearby: Int, online: Boolean): String {
    val parts = buildList { if (nearby > 0) add("Nearby ($nearby)"); if (online) add("Internet") }
    return if (parts.isEmpty()) "No link" else "Linked: " + parts.joinToString(" + ")
}

fun addStop(name: String, lat: Double, lon: Double) {
    RideState.stops.value = RideState.stops.value + Stop(name, lat, lon)
    RideState.msg.value = "Stop added: $name"
}

@Composable fun SearchCard() {
    var q by remember { mutableStateOf("") }
    var sugg by remember { mutableStateOf(listOf<Net.Hit>()) }
    val stops by RideState.stops.collectAsState()
    val idxVer by PlaceIndex.version
    LaunchedEffect(q, idxVer) {
        delay(300)
        val qq = q.trim().lowercase()
        if (qq.length < 2) { sugg = emptyList(); return@LaunchedEffect }
        val near = RideState.me.value?.let { P(it.latitude, it.longitude) }
        val r = mutableListOf<Net.Hit>()
        fun add(name: String, lat: Double, lon: Double) {
            val p = P(lat, lon)
            r.add(Net.Hit(name, p, near?.let { Geo.dist(it, p) } ?: 0.0))
        }
        Store.home?.let { if ("home".contains(qq)) add("Home", it.lat, it.lon) }
        Store.work?.let { if ("work".contains(qq)) add("Work", it.lat, it.lon) }
        RideState.savedPlaces.value.forEach { sp -> if (sp.name.lowercase().contains(qq)) add(sp.name, sp.lat, sp.lon) }
        RideState.pois.value.forEach { p -> if (p.name.lowercase().contains(qq)) add(p.name, p.lat, p.lon) }
        // Instant results from the cached index of place names within ~200 km (works offline too).
        PlaceIndex.search(q.trim(), near, 10).forEach { e -> add(e.name, e.lat, e.lon) }
        if (!offlineModeState.value) {
            try {
                withContext(Dispatchers.IO) { Net.geocodeList(q.trim(), near) }.forEach { r.add(it) }
            } catch (e: Exception) {}
        }
        // Nearest first, so a local match wins over a far-away place with the same name.
        sugg = r.sortedBy { it.distM }.distinctBy { "%.5f,%.5f".format(it.p.lat, it.p.lon) }.take(8)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search a place") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { if (searchOpen.value) IconButton(onClick = { searchOpen.value = false }) { Icon(Icons.Filled.Close, contentDescription = "Close search") } })
            sugg.forEach { hit ->
                val lat = hit.p.lat; val lon = hit.p.lon
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { MapHolder.focus(lat, lon, 15.0) }) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.name, fontSize = 13.sp, maxLines = 1)
                        if (hit.distM > 0) Text(Geo.fmt(hit.distM) + " away", fontSize = 11.sp, maxLines = 1)
                    }
                    TextButton(onClick = { addStop(hit.name, lat, lon); q = "" }) { Text("+ Stop") }
                    TextButton(onClick = { quickDest(hit.name, lat, lon); searchOpen.value = false; q = "" }) { Text("Go") }
                }
            }
            if (stops.isNotEmpty()) {
                Text("Stops: " + stops.joinToString(" > ") { it.name }, fontSize = 12.sp, maxLines = 2)
                val ctx = LocalContext.current
                Button(onClick = { setRide(ctx); searchOpen.value = false }, modifier = Modifier.fillMaxWidth()) { Text("Set ride") }
            }
        }
    }
}

fun quickDest(name: String, lat: Double, lon: Double) {
    RideState.destination.value = P(lat, lon)
    // Setting a destination must not leave the camera parked on it: stay centred on the rider,
    // with the destination marker and route preview drawn ahead.
    MapHolder.follow.value = true
    MapHolder.resetCenter()
    MapHolder.push()
    RideState.msg.value = "Destination set: $name"
}

/** Navigate to a paired rider's current position. Their marker keeps updating, and tapping again re-targets them. */
fun setRideTo(ctx: Context, name: String, lat: Double, lon: Double) {
    RideState.destination.value = P(lat, lon)
    panelExpandedState.value = false
    panelHiddenState.value = true
    MapHolder.follow.value = true; MapHolder.resetCenter()
    if (!RideState.running.value) startRide(ctx)
    RideState.msg.value = "Ride set to $name"
}

/** Start navigating to the first arranged stop; the rest stay queued for sequential navigation. */
fun setRide(ctx: Context) {
    val stops = RideState.stops.value
    if (stops.isNotEmpty()) {
        val first = stops.first()
        RideState.destination.value = P(first.lat, first.lon)
        RideState.stops.value = stops.drop(1)
    } else if (RideState.destination.value == null) {
        RideState.msg.value = "Add a stop or choose a destination first"; return
    }
    panelExpandedState.value = false
    panelHiddenState.value = true
    MapHolder.follow.value = true; MapHolder.resetCenter()
    startRide(ctx)
    RideState.msg.value = "Ride set"
}

fun openInGoogleMaps(c: Context, dest: P, from: P? = null) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${dest.lat},${dest.lon}&mode=d"))
        .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { c.startActivity(nav); return } catch (e: Exception) {}
    val url = buildString {
        append("https://www.google.com/maps/dir/?api=1&destination=${dest.lat},${dest.lon}&travelmode=driving")
        if (from != null) append("&origin=${from.lat},${from.lon}")
    }
    try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { RideState.msg.value = "No app can open Google Maps" }
}

@Composable
fun Modifier.interceptTap(onTap: () -> Unit): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial)
                val change = down.changes.firstOrNull() ?: continue
                if (!change.pressed) continue
                change.consume()
                currentOnTap()
            }
        }
    }
}

fun scaledTypography(scale: Float): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontSize = b.displayLarge.fontSize * scale),
        displayMedium = b.displayMedium.copy(fontSize = b.displayMedium.fontSize * scale),
        displaySmall = b.displaySmall.copy(fontSize = b.displaySmall.fontSize * scale),
        headlineLarge = b.headlineLarge.copy(fontSize = b.headlineLarge.fontSize * scale),
        headlineMedium = b.headlineMedium.copy(fontSize = b.headlineMedium.fontSize * scale),
        headlineSmall = b.headlineSmall.copy(fontSize = b.headlineSmall.fontSize * scale),
        titleLarge = b.titleLarge.copy(fontSize = b.titleLarge.fontSize * scale),
        titleMedium = b.titleMedium.copy(fontSize = b.titleMedium.fontSize * scale),
        titleSmall = b.titleSmall.copy(fontSize = b.titleSmall.fontSize * scale),
        bodyLarge = b.bodyLarge.copy(fontSize = b.bodyLarge.fontSize * scale),
        bodyMedium = b.bodyMedium.copy(fontSize = b.bodyMedium.fontSize * scale),
        bodySmall = b.bodySmall.copy(fontSize = b.bodySmall.fontSize * scale),
        labelLarge = b.labelLarge.copy(fontSize = b.labelLarge.fontSize * scale),
        labelMedium = b.labelMedium.copy(fontSize = b.labelMedium.fontSize * scale),
        labelSmall = b.labelSmall.copy(fontSize = b.labelSmall.fontSize * scale)
    )
}

@Composable fun App() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val expanded = panelExpandedState.value
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showSos by remember { mutableStateOf(false) }
    var hideBtnShown by remember { mutableStateOf(true) }
    var askRejoin by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(!Store.setupDone) }
    val navOn by RideState.destination.collectAsState()
    val darkNow by darkModeState
    LaunchedEffect(landscape, navOn != null) { panelHiddenState.value = landscape || navOn != null }
    LaunchedEffect(navOn != null) {
        val w = (ctx as? android.app.Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        if (navOn != null) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(darkNow) {
        val target = if (darkNow) MapHolder.NAV_STYLE else Store.style
        if (MapHolder.currentStyle != target) MapHolder.applyStyle(target)
    }
    LaunchedEffect(landscape, panelHiddenState.value, hideBtnPing.value) {
        hideBtnShown = true
        if (landscape && !panelHiddenState.value) { delay(20_000); hideBtnShown = false }
    }
    var arrived by remember { mutableStateOf(false) }
    val me by RideState.me.collectAsState()
    val dest by RideState.destination.collectAsState()
    LaunchedEffect(me, dest) {
        val m = me; val d = dest
        if (m != null && d != null && !arrived) {
            if (Geo.dist(P(m.latitude, m.longitude), P(d.lat, d.lon)) < 40) arrived = true
        }
        if (d == null) arrived = false
    }
    val dark by darkModeState
    val fontSizeScale by fontSizeState
    val fontColor by fontColorState
    val brightness by brightnessState
    val running by RideState.running.collectAsState()
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running != wasRunning) {
            if (running && keepPanelState.value) { /* started from the riders tab: keep the panel open */ }
            else panelExpandedState.value = !running
            if (running) MapHolder.follow.value = true
            wasRunning = running
            if (!running) keepPanelState.value = false
        }
    }
    // During a ride, check every 5 s that the camera is still on the rider and bring it back if it drifted
    // (e.g. after panning the map or looking at a saved place).
    LaunchedEffect(running) {
        while (running) {
            delay(5_000)
            val m = RideState.me.value ?: continue
            val target = MapHolder.map?.cameraPosition?.target
            val off = if (target == null) Double.MAX_VALUE
                else Geo.dist(P(m.latitude, m.longitude), P(target.latitude, target.longitude))
            if (!MapHolder.follow.value || off > 50) {
                MapHolder.follow.value = true
                MapHolder.resetCenter()
                MapHolder.push()
            }
        }
    }
    // Keep a cached index of nearby place names (about 200 km) so search suggestions are instant.
    // It is built once and rebuilt only when stale or after moving far away.
    LaunchedEffect(me != null) {
        val m = RideState.me.value ?: return@LaunchedEffect
        if (offlineModeState.value) return@LaunchedEffect
        PlaceIndex.ensure(ctx, P(m.latitude, m.longitude)) { RideState.msg.value = it }
    }
    var locGranted by remember { mutableStateOf(hasLocation(ctx)) }
    val perms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locGranted = hasLocation(ctx) }
    LaunchedEffect(Unit) { launcher.launch(perms) }
    LaunchedEffect(showSetup) {
        if (showSetup) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        if (RideState.code.value != null && !RideState.running.value) askRejoin = true
    }
    val msg by RideState.msg.collectAsState()
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); RideState.msg.value = "" } }
    val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
    val typography = scaledTypography(fontSizeScale)
    val contentColor = if (fontColor == 0) colorScheme.onBackground else Color(fontColor)
    LaunchedEffect(brightness) {
        val w = (ctx as? android.app.Activity)?.window
        w?.let { val a = it.attributes; a.screenBrightness = brightness; it.attributes = a }
    }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
    Column(Modifier.fillMaxSize().systemBarsPadding().background(colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapPane(Modifier.fillMaxSize(), locGranted); Hud()
            DebugOverlay(Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            if (landscape && !panelHiddenState.value && hideBtnShown) Box(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 12.dp)) {
                RoundNavButton(Icons.Filled.Close, "Hide tabs") { panelHiddenState.value = true }
            }
            if (landscape && panelHiddenState.value) Box(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 12.dp)) {
                RoundNavButton(Icons.Filled.Menu, "Tabs") { panelHiddenState.value = false }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (landscape) 16.dp else if (dest != null) 110.dp else 80.dp)) {
                FloatingActionButton(
                    onClick = { recenter(ctx) },
                    containerColor = if (dark) Color(0xFF111418) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (dark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Recenter")
                }
            }
            FilledTonalButton(onClick = { showSos = true }, modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (dest != null) 110.dp else 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)) {
                Text("SOS", fontWeight = FontWeight.Bold, fontSize = if (landscape) 12.sp else 14.sp)
            }
            val dropMode by MapHolder.dropPinMode
            if (dropMode) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "Pin", tint = Color(0xFFD32F2F), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(24.dp))
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val t = MapHolder.map?.cameraPosition?.target
                        if (t != null) {
                            MapHolder.dropPinMode.value = false
                            droppedPin.value = P(t.latitude, t.longitude)
                        }
                    }) { Text("Drop here") }
                    OutlinedButton(onClick = { MapHolder.dropPinMode.value = false }) { Text("Cancel") }
                }
                Text("Pan the map to position the pin, then tap Drop here", fontSize = 12.sp,
                    color = Color(0xFF2563eb), modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
        // Landscape: the panel takes at most 40% of the height so the map stays visible.
        if (!panelHiddenState.value) Column(Modifier.fillMaxWidth().then(
            if (expanded && landscape) Modifier.fillMaxHeight(0.4f)
            else if (expanded) Modifier.weight(1f) else Modifier.wrapContentHeight()
        )) {
            Box(Modifier.fillMaxWidth().height(24.dp).clickable { panelExpandedState.value = !panelExpandedState.value }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            }
            TabRow(selectedTabIndex = tab, modifier = Modifier.height(48.dp)) {
                listOf("Ride", "Riders", "Places", "Offline", "Settings").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i; panelExpandedState.value = true }, text = { Text(t, fontSize = 13.sp) })
                }
            }
            if (expanded) Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp)) { when (tab) { 0 -> RideTab(); 1 -> RidersTab(); 2 -> PlacesTab(); 3 -> OfflineTab(); else -> SettingsTab() } }
        }
        val editKey by editPlaceKey
        if (editKey != null) EditPlaceDialog(editKey!!)
        val dp = droppedPin.value
        if (dp != null) {
            AlertDialog(
                onDismissRequest = { droppedPin.value = null },
                title = { Text("Add this point?", fontWeight = FontWeight.Bold) },
                text = { Text("Add this point to your stops list?") },
                confirmButton = {
                    Button(onClick = {
                        val n = RideState.stops.value.size + 1
                        RideState.stops.value = RideState.stops.value + Stop("Pin $n", dp.lat, dp.lon)
                        droppedPin.value = null
                        RideState.msg.value = "Pin $n added to stops"
                    }) { Text("Add to stops") }
                },
                dismissButton = { TextButton(onClick = { droppedPin.value = null }) { Text("Cancel") } }
            )
        }
        if (showSetup) {
            var nm by remember { mutableStateOf(RideState.myName) }
            var rg by remember { mutableStateOf(Store.vehicleReg) }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Welcome to RidePack", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter your name and vehicle registration so other riders can identify you.", fontSize = 13.sp)
                        OutlinedTextField(nm, { nm = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(rg, { rg = it }, label = { Text("Vehicle registration") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        Store.saveName(nm.ifBlank { "Me" })
                        Store.vehicleReg = rg.trim()
                        Store.setupDone = true
                        RideState.myReg = rg.trim()
                        showSetup = false
                        RideState.msg.value = "Profile saved"
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { Store.setupDone = true; showSetup = false }) { Text("Skip") }
                }
            )
        }
        if (askRejoin) {
            AlertDialog(
                onDismissRequest = { askRejoin = false },
                title = { Text("Continue ride?", fontWeight = FontWeight.Bold) },
                text = { Text("You were in ride RP-${RideState.code.value ?: ""}. Continue it?") },
                confirmButton = {
                    Button(onClick = { askRejoin = false; startRide(ctx) }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        askRejoin = false
                        RideState.code.value = null; Store.code = null; RideState.riders.value = emptyMap()
                        RideState.msg.value = "Ride not continued"
                    }) { Text("Not now") }
                }
            )
        }
        if (showSos) {
            AlertDialog(
                onDismissRequest = { showSos = false },
                title = { Text("Send SOS", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose the reason so nearby riders know why:", fontSize = 13.sp)
                        val opts = listOf("food" to "Food break", "fuel" to "Fuel", "regroup" to "Regroup")
                        opts.forEach { (k, label) ->
                            Button(onClick = { sendSos(ctx, k); showSos = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showSos = false }) { Text("Cancel") } }
            )
        }
        if (arrived) {
            val nextStop = RideState.stops.value.firstOrNull()
            AlertDialog(
                onDismissRequest = { arrived = false },
                title = { Text("You have arrived!", fontWeight = FontWeight.Bold) },
                text = { Text(if (nextStop != null) "Reached this stop." else "You have reached your destination.") },
                confirmButton = {
                    if (nextStop != null) {
                        Button(onClick = {
                            RideState.destination.value = P(nextStop.lat, nextStop.lon)
                            RideState.stops.value = RideState.stops.value.drop(1)
                            MapHolder.follow.value = true; MapHolder.resetCenter()
                            arrived = false
                        }) { Text("Next: ${nextStop.name}") }
                    } else {
                        Button(onClick = {
                            stopRide(ctx)
                            RideState.destination.value = null
                            RideState.previewRoute.value = null
                            arrived = false
                        }) { Text("End ride") }
                    }
                },
                dismissButton = { TextButton(onClick = { arrived = false }) { Text("Continue") } }
            )
        }
        // In-app update: the check is manual now - triggered from the Settings tab ("Check now").
        val updLatest = UpdateChecker.latest.value
        val updOpenNow = UpdateChecker.open.value
        if (updOpenNow && updLatest != null) {
            val notes = updLatest.body.lineSequence().firstOrNull { it.isNotBlank() }?.take(400) ?: ""
            AlertDialog(
                onDismissRequest = { UpdateChecker.open.value = false },
                title = { Text("Update available", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RidePack v${updLatest.versionName} is ready. What's new:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(notes.ifBlank { "Small fixes and improvements." }, fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { UpdateChecker.install(ctx); UpdateChecker.open.value = false }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { UpdateChecker.open.value = false }) { Text("Later") } }
            )
        }
    }
    }
    }
}

@Composable fun EditPlaceDialog(key: String) {
    val saved = RideState.savedPlaces.value
    val idx = if (key.startsWith("saved:")) key.removePrefix("saved:").toIntOrNull() ?: -1 else -1
    val cur: SavedPlace = when (key) {
        "home" -> Store.home?.let { SavedPlace("Home", it.lat, it.lon) }
        "work" -> Store.work?.let { SavedPlace("Work", it.lat, it.lon) }
        else -> saved.getOrNull(idx)
    } ?: return
    var n by remember(key) { mutableStateOf(cur.name) }
    var la by remember(key) { mutableStateOf(cur.lat.toString()) }
    var lo by remember(key) { mutableStateOf(cur.lon.toString()) }
    AlertDialog(
        onDismissRequest = { editPlaceKey.value = null },
        title = { Text("Edit place", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (key.startsWith("saved:")) OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(la, { la = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(lo, { lo = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            Button(onClick = {
                val a = la.trim().toDoubleOrNull(); val b = lo.trim().toDoubleOrNull()
                if (a == null || b == null || a !in -90.0..90.0 || b !in -180.0..180.0) {
                    RideState.msg.value = "Enter a valid latitude (-90 to 90) and longitude (-180 to 180)"; return@Button
                }
                when (key) {
                    "home" -> Store.home = P(a, b)
                    "work" -> Store.work = P(a, b)
                    else -> {
                        RideState.savedPlaces.value = saved.toMutableList().also { it[idx] = SavedPlace(n.trim().ifBlank { cur.name }, a, b) }
                        Store.savedPlaces = RideState.savedPlaces.value
                    }
                }
                editPlaceKey.value = null; RideState.msg.value = "Place updated"
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editPlaceKey.value = null }) { Text("Cancel") } }
    )
}

@Composable fun MapPane(modifier: Modifier, locGranted: Boolean) {
    val ctx = LocalContext.current; val lo = LocalLifecycleOwner.current
    MapHolder.appContext = ctx
    val landscapeMap = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topPad = if (landscapeMap) (LocalConfiguration.current.screenHeightDp * ctx.resources.displayMetrics.density * 0.45f).toInt() else 0
    LaunchedEffect(topPad) { MapHolder.topPadding = topPad; MapHolder.map?.setPadding(0, topPad, 0, 0) }
    val mv = remember {
        MapView(ctx).also { v ->
            v.onCreate(null)
            v.getMapAsync { m ->
                MapHolder.map = m; m.setPadding(0, MapHolder.topPadding, 0, 0); MapHolder.applyStyle(if (darkModeState.value) MapHolder.NAV_STYLE else Store.style)
                m.addOnCameraMoveStartedListener { r -> if (r == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && !RideState.running.value && RideState.destination.value == null) MapHolder.follow.value = false }
                m.addOnMapClickListener { ll ->
                    if (MapHolder.dropPinMode.value) {
                        MapHolder.dropPinMode.value = false
                        droppedPin.value = P(ll.latitude, ll.longitude)
                        true
                    } else { hideBtnPing.value++; false }
                }
                m.addOnMapLongClickListener { ll ->
                    droppedPin.value = P(ll.latitude, ll.longitude)
                    true
                }
            }
        }
    }
    LaunchedEffect(locGranted) {
        if (!locGranted) return@LaunchedEffect
        try {
            val fused = LocationServices.getFusedLocationProviderClient(ctx)
            val l = withContext(Dispatchers.IO) {
                try { Tasks.await(fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null), 10, TimeUnit.SECONDS) }
                catch (e: Exception) { Tasks.await(fused.lastLocation, 3, TimeUnit.SECONDS) }
            }
            val cur = RideState.me.value
            if (l != null && System.currentTimeMillis() - l.time < 5 * 60_000 && (cur == null || l.accuracy < cur.accuracy)) {
                RideState.me.value = l
            }
        } catch (e: Exception) {}
    }
    DisposableEffect(lo, locGranted) {
        if (!locGranted) return@DisposableEffect onDispose {}
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val l = r.lastLocation ?: return
                try {
                    val reason = locationRejectReason(l, RideState.me.value)
                    if (reason == null) RideState.me.value = l else Diag.rejected(l, reason)
                } catch (e: Exception) { Diag.error("gps_callback", e) }
            }
        }
        fun start() {
            try {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateDistanceMeters(1f)
                    .setWaitForAccurateLocation(true)
                    .build()
                fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
            } catch (e: SecurityException) { Log.w("RidePack", "location permission missing") }
        }
        fun stop() { try { fused.removeLocationUpdates(cb) } catch (e: Exception) {} }
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> {}
            }
        }
        lo.lifecycle.addObserver(o)
        if (lo.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lo.lifecycle.removeObserver(o); stop() }
    }
    DisposableEffect(lo) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy(); else -> {}
            }
        }
        lo.lifecycle.addObserver(o); onDispose { lo.lifecycle.removeObserver(o) }
    }
    AndroidView(factory = { mv }, modifier = modifier)
    val me by RideState.me.collectAsState(); val rd by RideState.riders.collectAsState()
    val po by RideState.pois.collectAsState(); val rt by RideState.route.collectAsState()
    val dest by RideState.destination.collectAsState(); val preview by RideState.previewRoute.collectAsState()
    val stopsList by RideState.stops.collectAsState()
    LaunchedEffect(me, rd, po, rt, dest, preview, stopsList) { MapHolder.push() }
    // Re-plan when the destination changes or the first GPS fix arrives. Sequential navigation: only the current destination.
    val refreshTick by routeRefreshTick
    LaunchedEffect(dest, me == null, refreshTick) {
        val d = dest
        if (d == null) { RideState.previewRoute.value = null; return@LaunchedEffect }
        val from = RideState.me.value?.let { P(it.latitude, it.longitude) }
        if (from == null) { RideState.previewRoute.value = null; RideState.msg.value = "Waiting for GPS to plan the road route"; return@LaunchedEffect }
        val pts = withContext(Dispatchers.IO) { Net.route(from, d) }
        if (pts != null) {
            RideState.previewRoute.value = pts
        } else {
            // No straight-line fallback: it looks like a road route but is not.
            RideState.previewRoute.value = null
            RideState.msg.value = if (offlineModeState.value) "Offline mode: road route unavailable" else "No road route found. Check internet and try again"
        }
    }
}

class Nav(val title: String, val sub: String, val arrow: String, val eta: String = "")
fun navInfo(r: Route?, me: android.location.Location?, dest: P? = null, preview: List<P>? = null): Nav {
    if (me == null) return Nav("Waiting for GPS…", "Start a ride", "📡")
    if (r != null && r.pts.size >= 2) {
        val near = r.snap(P(me.latitude, me.longitude))
        val s = MapHolder.matchedProgress(r) ?: near.first
        val off = near.second; val left = r.total - s
        if (left < 40) return Nav("You've arrived", "", "🏁")
        if (off > 200) return Nav("Off route", "${Geo.fmt(off)} from the route", "⚠️")
        val t = r.turns.firstOrNull { it.s > s + 30 }; val d = (t?.s ?: r.total) - s
        val speedMs = etaSpeed(me.speed)
        val etaSec = (left / speedMs).toInt().coerceAtLeast(0)
        val eta = if (etaSec < 60) "${etaSec}s" else if (etaSec < 3600) "${etaSec / 60}m" else "${etaSec / 3600}h ${(etaSec % 3600) / 60}m"
        return if (t != null) Nav("Turn ${if (t.left) "left" else "right"} in ${Geo.fmt(d)}", "${Geo.fmt(left)} to finish · ETA $eta",
            if (t.left) (if (t.deg > 70) "⬅️" else "↖️") else (if (t.deg > 70) "➡️" else "↗️"), eta)
        else Nav("Continue ${Geo.fmt(d)}", "${Geo.fmt(left)} to finish · ETA $eta", "⬆️", eta)
    }
    if (dest != null) {
        val dist = if (preview != null && preview.size >= 2) preview.zipWithNext { a, b -> Geo.dist(a, b) }.sum()
        else Geo.dist(P(me.latitude, me.longitude), dest)
        val speedMs = etaSpeed(me.speed)
        val etaSec = (dist / speedMs).toInt().coerceAtLeast(0)
        val eta = if (etaSec < 60) "${etaSec}s" else if (etaSec < 3600) "${etaSec / 60}m" else "${etaSec / 3600}h ${(etaSec % 3600) / 60}m"
        if (dist < 40) return Nav("You've arrived", "", "🏁", eta)
        return Nav("Navigating to destination", "${Geo.fmt(dist)} to go · ETA $eta", "🧭", eta)
    }
    if (RideState.running.value) return Nav("Riding", "Follow the road ahead", "🚴")
    return Nav("Ready", "Set a destination or import a GPX", "🧭")
}

/** Smoothed, floored speed for a stable, realistic ETA (ignores GPS noise and stationary spikes). */
fun etaSpeed(speed: Float): Float {
    if (speed > 0.5f) smoothSpeedState.value = smoothSpeedState.value * 0.6f + speed * 0.4f
    return smoothSpeedState.value.coerceIn(5.5f, 45f)
}

@Composable fun RoundNavButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick, containerColor = Color(0xFF111418), contentColor = Color.White, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = desc)
    }
}

@Composable fun NavBottomBar(nav: Nav, onEnd: () -> Unit, onMenu: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Distance only; the ETA is already shown large above, so drop the duplicated "· ETA ..." tail.
    val distOnly = nav.sub.substringBefore(" · ETA", nav.sub).ifEmpty { nav.sub }
    val barMod = if (landscape) Modifier.heightIn(min = 72.dp) else Modifier.fillMaxWidth().heightIn(min = 72.dp)
    Surface(color = Color(0xFF111418), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), modifier = barMod) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEnd) { Icon(Icons.Filled.Close, contentDescription = "End navigation", tint = Color.White) }
            Column(Modifier.then(if (landscape) Modifier.width(150.dp) else Modifier.weight(1f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(nav.eta.ifEmpty { "--" }, color = Color(0xFF34D399), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(distOnly, color = Color(0xFFB0B7C3), fontSize = 12.sp)
            }
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White) }
        }
    }
}

@Composable fun BoxScope.Hud() {
    val me by RideState.me.collectAsState(); val route by RideState.route.collectAsState(); val riders by RideState.riders.collectAsState()
    val dest by RideState.destination.collectAsState(); val preview by RideState.previewRoute.collectAsState()
    val nav = remember(me, route, dest, preview) { navInfo(route, me, dest, preview) }
    val far = me?.let { m -> riders.values.filter { Geo.dist(P(m.latitude, m.longitude), P(it.lat, it.lon)) > 1500 }.map { it.name } } ?: emptyList()
    val sosAlerts by RideState.sosAlerts.collectAsState()
    val linkNearby by RideState.links.collectAsState(); val linkOnline by RideState.onlineUp.collectAsState()
    val latestSos = sosAlerts.lastOrNull()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Landscape has little height: keep the overlay in a narrow top-left column so it doesn't cover the map or the bottom buttons.
    val hudAlign = if (landscape) Alignment.TopStart else Alignment.TopCenter
    Column(Modifier.align(hudAlign).padding(if (landscape) 4.dp else 10.dp).widthIn(max = if (landscape) 280.dp else 600.dp)) {
        if (dest == null || searchOpen.value) SearchCard() else Card(Modifier.fillMaxWidth(), colors = if (dest != null) CardDefaults.cardColors(containerColor = Color(0xFF00695C), contentColor = Color.White) else CardDefaults.cardColors()) {
            Row(Modifier.padding(if (landscape) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(nav.arrow, fontSize = if (landscape) 18.sp else 28.sp); Spacer(Modifier.width(if (landscape) 6.dp else 10.dp))
                Column(Modifier.weight(1f)) { Text(nav.title, fontWeight = FontWeight.Bold, fontSize = if (landscape) 13.sp else 17.sp); Text(nav.sub, fontSize = if (landscape) 10.sp else 12.sp)
                    if (landscape) Text("${speedKmh(me)} km/h   GPS +-${me?.accuracy?.roundToInt() ?: "?"} m", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                if (!landscape) Column(horizontalAlignment = Alignment.End) {
                    Text("${((me?.speed ?: 0f) * 3.6f).roundToInt()} km/h", fontWeight = FontWeight.Bold)
                    me?.accuracy?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.GpsFixed, contentDescription = "GPS accuracy", modifier = Modifier.size(12.dp)); Spacer(Modifier.width(2.dp)); Text("+-${it.roundToInt()}m", fontSize = 11.sp, color = if (it > 30f) Color(0xFFB42318) else Color.Unspecified) } }
                }
            }
        }
        if (RideState.running.value) {
            val linked = linkNearby > 0 || linkOnline
            Text(linkLabel(linkNearby, linkOnline), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (linked) Color(0xFF16a34a) else Color(0xFFB42318), modifier = Modifier.padding(top = if (landscape) 1.dp else 4.dp))
        }
        if (far.isNotEmpty()) Surface(color = Color(0xFFB42318), shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
            Text("Out of range: ${far.joinToString()}", color = Color.White, modifier = Modifier.padding(if (landscape) 4.dp else 8.dp), fontSize = if (landscape) 11.sp else 13.sp)
        }
        if (offlineModeState.value) Surface(color = Color(0xFF1F2937), shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
            Text("Offline mode: network (search, places) disabled", color = Color.White, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
        }
        if (latestSos != null && System.currentTimeMillis() - latestSos.time < 10_000) {
            Surface(color = Color(0xFFD32F2F), shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
                Text("SOS from ${latestSos.name}: ${latestSos.type.uppercase()}", color = Color.White, modifier = Modifier.padding(if (landscape) 4.dp else 8.dp), fontSize = if (landscape) 11.sp else 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    val following by MapHolder.follow
    // Show the recenter button whenever the camera has been panned away, including during navigation.
    if (!following) FilledTonalButton(onClick = { MapHolder.follow.value = true; MapHolder.resetCenter(); MapHolder.push() },
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (dest != null) 110.dp else 10.dp)) { Text("Follow me") }
    if (dest != null) Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundNavButton(Icons.Filled.Search, "Search") { searchOpen.value = true }
        RoundNavButton(Icons.Filled.Refresh, "Refresh route") { routeRefreshTick.value++ }
    }
    if (dest != null) Box(Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter).padding(start = if (landscape) 8.dp else 0.dp, bottom = 8.dp)) {
        val ctx = LocalContext.current
        NavBottomBar(nav, onEnd = { stopRide(ctx); RideState.destination.value = null; RideState.previewRoute.value = null; RideState.msg.value = "Ride stopped" },
            onMenu = { panelHiddenState.value = !panelHiddenState.value; if (!panelHiddenState.value) panelExpandedState.value = true })
    }
}

@Composable fun RideTab() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val running by RideState.running.collectAsState(); val route by RideState.route.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val (pts, wps) = Gpx.parse(s)
                if (pts.size > 1) {
                    RideState.route.value = Route(pts)
                    RideState.pois.value = RideState.pois.value.filter { p -> wps.none { it.id == p.id } } + wps
                    Store.savePois(); MapHolder.fit(pts)
                    sendRoute()
                } else RideState.msg.value = "No track found in file"
            }
        } catch (e: Exception) { RideState.msg.value = "Could not read GPX" }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (running) stopRide(ctx) else startRide(ctx) }) { Text(if (running) "Stop ride" else "Start ride") }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Import GPX") }
            OutlinedButton(onClick = { MapHolder.dropPinMode.value = true; RideState.msg.value = "Tap the map to drop a pin" }) { Text("Drop pin") }
        }
        if (MapHolder.dropPinMode.value) Text("Tap anywhere on the map to drop a pin", fontSize = 12.sp, color = Color(0xFF2563eb))
        Text(route?.let { "Route: ${Geo.fmt(it.total)} - ${it.turns.size} turns" } ?: "No route loaded.")
        Text("While a ride is active, GPS and the rider link keep running with the screen off.", fontSize = 12.sp)
        HorizontalDivider()
        Text("Saved places (tap to center the map)", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                if (Store.home == null) RideState.me.value?.let { Store.home = P(it.latitude, it.longitude) }
                Store.home?.let { MapHolder.focus(it.lat, it.lon, 14.0) }
            }) { Text("Home") }
            Button(onClick = {
                if (Store.work == null) RideState.me.value?.let { Store.work = P(it.latitude, it.longitude) }
                Store.work?.let { MapHolder.focus(it.lat, it.lon, 14.0) }
            }) { Text("Work") }
            Button(onClick = { locateMe(ctx) }) { Icon(Icons.Filled.MyLocation, contentDescription = "My location"); Spacer(Modifier.width(4.dp)); Text("Me") }
        }
        HorizontalDivider()
        Text("Stops (${RideState.stops.value.size})", fontWeight = FontWeight.Bold)
        val stops by RideState.stops.collectAsState()
        if (stops.isEmpty()) Text("No stops added. Search a place on the map, then tap Set ride.", fontSize = 12.sp)
        stops.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold)
                Text(st.name, Modifier.weight(1f))
                Text("${Geo.fmt(Geo.dist(P(RideState.me.value?.latitude ?: st.lat, RideState.me.value?.longitude ?: st.lon), P(st.lat, st.lon)))}", fontSize = 11.sp)
                TextButton(enabled = i > 0, onClick = { val l = RideState.stops.value.toMutableList(); java.util.Collections.swap(l, i, i - 1); RideState.stops.value = l }) { Text("Up") }
                TextButton(enabled = i < stops.size - 1, onClick = { val l = RideState.stops.value.toMutableList(); java.util.Collections.swap(l, i, i + 1); RideState.stops.value = l }) { Text("Down") }
                TextButton(onClick = { RideState.stops.value = RideState.stops.value.filterIndexed { idx, _ -> idx != i } }) { Text("X") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { setRide(ctx) }, enabled = stops.isNotEmpty() || RideState.destination.value != null) { Text("Set ride") }
            Button(onClick = { RideState.stops.value = emptyList(); RideState.msg.value = "Stops cleared" }) { Text("Clear") }
        }
        Button(onClick = {
            val r = route
            val ordered = if (r != null && r.pts.size > 1) {
                stops.sortedBy { r.snap(P(it.lat, it.lon)).first }
            } else {
                val remaining = stops.toMutableList()
                val out = mutableListOf<Stop>()
                var cur = RideState.me.value?.let { P(it.latitude, it.longitude) } ?: remaining.firstOrNull()?.let { P(it.lat, it.lon) }
                while (remaining.isNotEmpty()) {
                    val next = remaining.minByOrNull { s -> cur?.let { Geo.dist(it, P(s.lat, s.lon)) } ?: Double.MAX_VALUE } ?: remaining.first()
                    out.add(next); remaining.remove(next); cur = P(next.lat, next.lon)
                }
                out
            }
            RideState.stops.value = ordered
            val total = ordered.zipWithNext { a, b -> Geo.dist(P(a.lat, a.lon), P(b.lat, b.lon)) }.sum()
            val me = RideState.me.value
            val first = ordered.firstOrNull()
            val distToFirst = if (me != null && first != null) Geo.dist(P(me.latitude, me.longitude), P(first.lat, first.lon)) else 0.0
            RideState.msg.value = "Ordered ${ordered.size} stops - ${Geo.fmt(total)} between stops - ${Geo.fmt(distToFirst)} to first"
        }) { Text("Arrange in route order") }
        val stops2 = RideState.stops.value
        if (stops2.size >= 2) {
            val total = stops2.zipWithNext { a, b -> Geo.dist(P(a.lat, a.lon), P(b.lat, b.lon)) }.sum()
            Text("Total between stops: ${Geo.fmt(total)}", fontSize = 12.sp)
        }
    }
}

@Composable fun RidersTab() {
    val ctx = LocalContext.current; val me by RideState.me.collectAsState(); val riders by RideState.riders.collectAsState()
    val code by RideState.code.collectAsState(); val links by RideState.links.collectAsState(); val onlineUp by RideState.onlineUp.collectAsState()
    val nearby by RideState.nearby.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    var id by remember { mutableStateOf("") }; var nm by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }; var n by remember { mutableStateOf(RideState.myName) }
    var subTab by remember { mutableStateOf(0) }
    val mp = me?.let { P(it.latitude, it.longitude) }
    fun join(code: String) {
        val k = code.uppercase().filter { it.isLetterOrDigit() }.removePrefix("RP")
        if (k.length < 4) { RideState.msg.value = "Enter a valid ride code"; return }
        Store.saveName(n.ifBlank { "Me" }); RideState.destination.value = null; RideState.route.value = null; RideState.previewRoute.value = null; RideState.code.value = k; c = "RP-$k"; Store.code = k; keepPanelState.value = true; startRide(ctx)
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Riders", fontSize = 13.sp) })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Pairing", fontSize = 13.sp) })
        }
        if (subTab == 1) {
            Text(if (code != null) "Ride RP-$code - ${linkLabel(links, onlineUp)} - ${riders.size} rider(s) on map" else "Not in a ride", fontWeight = FontWeight.Bold)
            Text("Your rider ID: ${RideState.myId} (others can add you with it)", fontSize = 13.sp)
            Text(if (!Store.onlinePairing) "Internet link: off (Settings)" else if (onlineUp) "Internet link: connected" else "Internet link: connecting…", fontSize = 13.sp)
            OutlinedTextField(n, { n = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c, { c = it }, label = { Text("Ride code, e.g. RP-7K2Q") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            val nearbyOthers = nearby.filter { it.id != RideState.myId && it.code.isNotEmpty() && it.code != code }
            if (nearbyOthers.isNotEmpty()) {
                HorizontalDivider()
                Text("Nearby riders (tap to pair)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                nearbyOthers.forEach { nr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rider ${nr.id} · Ride ${nr.code}", Modifier.weight(1f), fontSize = 13.sp)
                        Button(onClick = { join(nr.code) }) { Text("Pair") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { join(c) }) { Text("Join") }
                Button(onClick = { val al = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; join("RP-" + (1..4).map { al.random() }.joinToString("")) }) { Text("Create ride") }
                OutlinedButton(onClick = { RideState.code.value = null; RideState.riders.value = emptyMap(); Store.code = null; if (RideState.running.value) startRide(ctx) }) { Text("Leave") }
            }
            Text("Riders with the same code find each other over Bluetooth/Wi-Fi, with no mobile data. Keep phones within a few hundred metres.", fontSize = 12.sp)
        } else {
            Text("You: ${RideState.myName} - ID ${RideState.myId}", fontWeight = FontWeight.Bold)
            riders.values.forEach { r ->
                val d = mp?.let { Geo.dist(it, P(r.lat, r.lon)) }; val age = (now - r.seen) / 1000; val ds = d?.let { Geo.fmt(it) } ?: "?"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(if (age > 30) "No signal for ${age}s - last seen $ds away" else "$ds away - ${(r.speed * 3.6f).roundToInt()} km/h",
                            color = if (age > 30 || (d ?: 0.0) > 1500) Color(0xFFB42318) else Color.Unspecified, fontSize = 13.sp)
                    }
                    TextButton(onClick = { MapHolder.focus(r.lat, r.lon) }) { Text("Locate") }
                    TextButton(onClick = { setRideTo(ctx, r.name, r.lat, r.lon) }) { Text("Set ride") }
                }
            }
            if (riders.isEmpty() && code == null) Text("No riders yet. Create or join a ride in the Pairing tab.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text("Rider ID") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(nm, { nm = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                val rid = id.trim().uppercase()
                if (rid.isBlank()) { RideState.msg.value = "Enter a rider ID"; return@Button }
                if (nm.isNotBlank()) { RideState.known[rid] = nm.trim(); Store.saveKnown() }
                val nr = nearby.firstOrNull { it.id.equals(rid, ignoreCase = true) }
                when {
                    nr == null -> RideState.msg.value = "Rider $rid not found nearby (must be in Bluetooth range)"
                    nr.code.isEmpty() -> RideState.msg.value = "Rider $rid is not in a ride yet"
                    else -> { join(nr.code); RideState.msg.value = "Pairing with ${nm.ifBlank { "Rider $rid" }}" }
                }
                id = ""; nm = ""
            }) { Text("Add rider by ID") }
        }
    }
}

@Composable fun PlacesTab() {
    val ctx = LocalContext.current; val me by RideState.me.collectAsState()
    val saved by RideState.savedPlaces.collectAsState()
    var name by remember { mutableStateOf("") }
    val mp = me?.let { P(it.latitude, it.longitude) }
    Column(Modifier.fillMaxSize()) {
        Text("Saved places", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        LazyColumn(Modifier.weight(1f)) {
            item {
                if (Store.home != null) {
                    val h = Store.home!!
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Home", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (mp != null) Text("${Geo.fmt(Geo.dist(mp, P(h.lat, h.lon)))} away", fontSize = 12.sp)
                        TextButton(onClick = { quickDest("Home", h.lat, h.lon) }) { Text("Go") }
                        TextButton(onClick = { editPlaceKey.value = "home" }) { Text("Edit") }
                        TextButton(onClick = { MapHolder.focus(h.lat, h.lon, 14.0) }) { Text("Show") }
                        TextButton(onClick = { openInGoogleMaps(ctx, P(h.lat, h.lon), mp) }) { Text("Maps") }
                        TextButton(onClick = { Store.home = null; RideState.msg.value = "Home removed" }) { Text("X") }
                    }
                }
            }
            item {
                if (Store.work != null) {
                    val w = Store.work!!
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Work", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (mp != null) Text("${Geo.fmt(Geo.dist(mp, P(w.lat, w.lon)))} away", fontSize = 12.sp)
                        TextButton(onClick = { quickDest("Work", w.lat, w.lon) }) { Text("Go") }
                        TextButton(onClick = { editPlaceKey.value = "work" }) { Text("Edit") }
                        TextButton(onClick = { MapHolder.focus(w.lat, w.lon, 14.0) }) { Text("Show") }
                        TextButton(onClick = { openInGoogleMaps(ctx, P(w.lat, w.lon), mp) }) { Text("Maps") }
                        TextButton(onClick = { Store.work = null; RideState.msg.value = "Work removed" }) { Text("X") }
                    }
                }
            }
            items(saved) { sp ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(sp.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (mp != null) Text("${Geo.fmt(Geo.dist(mp, P(sp.lat, sp.lon)))} away", fontSize = 12.sp)
                    TextButton(onClick = { quickDest(sp.name, sp.lat, sp.lon) }) { Text("Go") }
                    TextButton(onClick = { editPlaceKey.value = "saved:${saved.indexOf(sp)}" }) { Text("Edit") }
                    TextButton(onClick = { MapHolder.focus(sp.lat, sp.lon, 14.0) }) { Text("Show") }
                    TextButton(onClick = { openInGoogleMaps(ctx, P(sp.lat, sp.lon), mp) }) { Text("Maps") }
                    TextButton(onClick = {
                        RideState.savedPlaces.value = RideState.savedPlaces.value.filter { it.name != sp.name || it.lat != sp.lat }
                        Store.savedPlaces = RideState.savedPlaces.value
                        RideState.msg.value = "Removed"
                    }) { Text("X") }
                }
            }
            if (Store.home == null && Store.work == null && saved.isEmpty()) {
                item { Text("No saved places yet. Add one below.", fontSize = 12.sp, color = Color.Gray) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("New place (e.g. friend's house)") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                val t = MapHolder.map?.cameraPosition?.target
                if (t != null && name.isNotBlank()) {
                    val np = SavedPlace(name.trim(), t.latitude, t.longitude)
                    RideState.savedPlaces.value = RideState.savedPlaces.value + np
                    Store.savedPlaces = RideState.savedPlaces.value
                    name = ""; RideState.msg.value = "Place saved at map center"
                } else if (name.isBlank()) RideState.msg.value = "Enter a name"
                else RideState.msg.value = "Map not ready"
            }) { Text("Drop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { val l = RideState.me.value; if (l != null) { val np = SavedPlace(name.ifBlank { "Place" }, l.latitude, l.longitude); RideState.savedPlaces.value = RideState.savedPlaces.value + np; Store.savedPlaces = RideState.savedPlaces.value; name = ""; RideState.msg.value = "Saved at your location" } else RideState.msg.value = "No location fix" }) { Text("Save at my location") }
        }
    }
}

@Composable fun OfflineTab() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }; var style by remember { mutableStateOf(Store.style) }
    var zoom by remember { mutableStateOf(14f) }; var poi by remember { mutableStateOf(true) }
    var prog by remember { mutableStateOf(-1) }; var est by remember { mutableStateOf("") }
    var regs by remember { mutableStateOf(listOf<Pair<OfflineRegion, String>>()) }
    fun refresh() = Offline.list(ctx) { regs = it }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(zoom) { while (true) { est = Offline.estimate(zoom.roundToInt()); delay(800) } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, label = { Text("Search a town or area") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { try { Net.geocode(q) } catch (e: Exception) { null } }
                    if (p == null) RideState.msg.value = "Not found (needs internet)" else MapHolder.focus(p.lat, p.lon, 12.0)
                }
            }) { Text("Go") }
        }
        Text("Pan and zoom the map to cover your ride area, choose the detail, then download.", fontSize = 12.sp)
        Text("Detail: zoom ${zoom.roundToInt()}"); Slider(zoom, { zoom = it }, valueRange = 11f..14f, steps = 2)
        Text(est, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(poi, { poi = it }); Text("Also fetch fuel, food, viewpoints, repair, stays") }
        Button(enabled = prog < 0, onClick = {
            val b = Offline.bounds()
            Offline.download(ctx, style, q.ifBlank { "Area ${regs.size + 1}" }, zoom.roundToInt(), { prog = it }, { RideState.msg.value = it; refresh() })
            if (poi && b != null) scope.launch {
                try {
                    val l = withContext(Dispatchers.IO) { Net.overpass(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) }
                    RideState.pois.value = RideState.pois.value.filter { p -> l.none { it.id == p.id } } + l; Store.savePois()
                } catch (e: Exception) { RideState.msg.value = "Places could not be fetched" }
            }
        }) { Text(if (prog >= 0) "Downloading... $prog%" else "Download this area") }
        if (prog >= 0) LinearProgressIndicator(progress = { prog / 100f }, modifier = Modifier.fillMaxWidth())
        regs.forEach { (r, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { Offline.view(r) }) { Text("View") }
                TextButton(onClick = { Offline.delete(r) { refresh() } }) { Text("X") }
            }
        }
        OutlinedTextField(style, { style = it }, label = { Text("Map style URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { Store.style = style; MapHolder.applyStyle(style); RideState.msg.value = "Map style applied" }) { Text("Apply style") }
        Text("Default map: OpenFreeMap (OpenStreetMap data, free, no key). You can paste another MapLibre vector style URL above; only use styles whose terms allow offline download.", fontSize = 12.sp)
    }
}

@Composable fun SettingsTab() {
    val ctx = LocalContext.current
    var night by remember { mutableStateOf(Store.nightMode) }
    var fontSize by remember { mutableStateOf(Store.fontSizeScale) }
    var fontColor by remember { mutableStateOf(Store.fontColor) }
    var offlineMode by remember { mutableStateOf(Store.offlineMode) }
    var brightness by remember { mutableStateOf(Store.brightness) }
    var autoCenter by remember { mutableStateOf(Store.autoCenter) }
    var autoRotate by remember { mutableStateOf(Store.autoRotate) }
    var threeD by remember { mutableStateOf(Store.threeD) }
    var onlinePair by remember { mutableStateOf(Store.onlinePairing) }
    var updateCheck by remember { mutableStateOf(Store.updateCheck) }
    var regs by remember { mutableStateOf(listOf<Pair<OfflineRegion, String>>()) }
    fun refresh() = Offline.list(ctx) { regs = it }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Night mode", Modifier.weight(1f))
            Switch(checked = night, onCheckedChange = { night = it; Store.nightMode = it; darkModeState.value = it })
        }
        Text("Brightness: ${(brightness * 100).roundToInt()}%")
        Slider(brightness, { brightness = it; Store.brightness = it; brightnessState.value = it }, valueRange = 0.1f..1f)
        Text("Font size: ${"%.1f".format(fontSize)}x")
        Slider(fontSize, { fontSize = it; Store.fontSizeScale = it; fontSizeState.value = it }, valueRange = 0.8f..1.4f)
        Text("Text color")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val colors = listOf(0 to "Default", 0xFFFFFFFF.toInt() to "White", 0xFF000000.toInt() to "Black", 0xFF1976D2.toInt() to "Blue", 0xFF388E3C.toInt() to "Green", 0xFFD32F2F.toInt() to "Red")
            colors.forEach { (c, name) ->
                FilterChip(selected = fontColor == c, onClick = { fontColor = c; Store.fontColor = c; fontColorState.value = c }, label = { Text(name, fontSize = 11.sp) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Offline mode (skip network calls)", Modifier.weight(1f))
            Switch(checked = offlineMode, onCheckedChange = { offlineMode = it; Store.offlineMode = it; offlineModeState.value = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-center map on my location", Modifier.weight(1f))
            Switch(checked = autoCenter, onCheckedChange = { autoCenter = it; Store.autoCenter = it; autoCenterState.value = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-rotate map to travel direction", Modifier.weight(1f))
            Switch(checked = autoRotate, onCheckedChange = { autoRotate = it; Store.autoRotate = it; autoRotateState.value = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("3D view (tilted map, no 3D buildings)", Modifier.weight(1f))
            Switch(checked = threeD, onCheckedChange = { threeD = it; Store.threeD = it; threeDState.value = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Internet pairing (Firebase, uses mobile data)", Modifier.weight(1f))
            Switch(checked = onlinePair, onCheckedChange = { onlinePair = it; Store.onlinePairing = it; if (RideState.running.value) startRide(ctx) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Check for updates", Modifier.weight(1f))
            Switch(checked = updateCheck, onCheckedChange = { updateCheck = it; Store.updateCheck = it })
        }
        OutlinedButton(onClick = { UpdateChecker.check(ctx) }) { Text("Check now") }
        HorizontalDivider()
        var confirmExit by remember { mutableStateOf(false) }
        Button(onClick = { confirmExit = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) { Text("Exit app") }
        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("Exit RidePack?", fontWeight = FontWeight.Bold) },
                text = { Text("This stops your ride tracking and rider link, and closes the app.") },
                confirmButton = { Button(onClick = { confirmExit = false; exitApp(ctx) }) { Text("Exit") } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } }
            )
        }
        HorizontalDivider()
        val idxVer by PlaceIndex.version
        val idxBuilding by PlaceIndex.isBuilding
        val idxCount = remember(idxVer) { PlaceIndex.entries.size }
        val idxMaster = remember(idxVer) { PlaceIndex.masterDone }
        Text("Nearby place index", fontWeight = FontWeight.Bold)
        Text(
            when {
                idxBuilding -> if (idxMaster) "Refreshing… place names within about 50 km"
                              else "Building… collecting place names within about 200 km"
                idxCount == 0 -> "Not built yet — search currently uses the network only"
                idxMaster -> "$idxCount place names cached within about 200 km (later refreshes cover 50 km)"
                else -> "$idxCount place names cached so far — master build not finished"
            }, fontSize = 12.sp
        )
        OutlinedButton(enabled = !idxBuilding, onClick = {
            val m = RideState.me.value
            if (m == null) RideState.msg.value = "Waiting for a GPS fix to build the place index"
            else PlaceIndex.build(ctx, P(m.latitude, m.longitude), PlaceIndex.nextRadiusM) { RideState.msg.value = it }
        }) { Text(when { idxCount == 0 -> "Build place index"; idxMaster -> "Refresh place index (50 km)"; else -> "Continue building index" }) }
        HorizontalDivider()
        Text("Downloaded offline maps", fontWeight = FontWeight.Bold)
        if (regs.isEmpty()) Text("No areas downloaded yet.", fontSize = 12.sp)
        regs.forEach { (r, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { Offline.view(r) }) { Text("View") }
                TextButton(onClick = { Offline.delete(r) { refresh() } }) { Text("X") }
            }
        }
        DebugSettings()
    }
}