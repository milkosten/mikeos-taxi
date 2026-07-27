package com.mikeos.taxi

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.taxi.agent.TaxiMikeAgent
import com.mikeos.taxi.ui.TaxiMap
import com.mikeos.taxi.ui.theme.MikeAccent
import com.mikeos.taxi.ui.theme.MikeBg
import com.mikeos.taxi.ui.theme.MikeGreen
import com.mikeos.taxi.ui.theme.MikeMuted
import com.mikeos.taxi.ui.theme.MikeOnSurface
import com.mikeos.taxi.ui.theme.MikeOsTheme
import com.mikeos.taxi.ui.theme.MikeRed
import com.mikeos.taxi.ui.theme.MikeSurface
import com.mikeos.taxi.ui.theme.MikeSurfaceVariant

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        // Embed the shared MikeAgent runtime (self-register + heartbeat + hive via the core).
        TaxiMikeAgent.install(this)

        setContent {
            MikeOsTheme {
                val vm: TaxiViewModel = viewModel()
                val mode by vm.mode.collectAsStateWithLifecycle()

                Scaffold(containerColor = MikeBg) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        TopBar(
                            mode = mode,
                            onMode = { vm.setMode(it) },
                        )
                        when (mode) {
                            Mode.CLIENT -> ClientScreen(vm)
                            Mode.DRIVER -> DriverScreen(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); HeartbeatService.start(this) }
    override fun onStop() { super.onStop(); HeartbeatService.stop(this) }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}

/* ------------------------------- Top bar + mode toggle ------------------------------- */

@Composable
private fun TopBar(mode: Mode, onMode: (Mode) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MikeSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MikeTaxi", color = MikeAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        // Segmented Client ⇄ Driver control.
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MikeSurfaceVariant),
        ) {
            SegItem("Client", mode == Mode.CLIENT) { onMode(Mode.CLIENT) }
            SegItem("Driver", mode == Mode.DRIVER) { onMode(Mode.DRIVER) }
        }
        Spacer(Modifier.weight(1f))
        // MANDATORY Agent Inspector icon (every MikeOS app).
        com.mikeos.core.ui.AgentIconButton(
            onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
        )
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MikeAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0A0E14) else MikeMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/* ------------------------------- CLIENT mode ------------------------------- */

@Composable
private fun ClientScreen(vm: TaxiViewModel) {
    val s by vm.client.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Map foundation — real MapLibre tiles, centered on the pickup fix.
        TaxiMap(
            centerLat = s.pickup?.lat,
            centerLon = s.pickup?.lon,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val active = s.activeRide
            if (active != null) {
                RideStatusCard(active, onCancel = { vm.cancelActiveRide() })
                return@Column
            }

            // Pickup (from the shared daemon fix).
            SectionLabel("Pickup")
            Card(colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = MikeGreen)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.pickup?.let { it.label ?: "%.5f, %.5f".format(it.lat, it.lon) }
                            ?: "Waiting for location…",
                        color = MikeOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.refreshPickup() }) { Text("Use my location") }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Drop-off")
            var dropText by remember { androidx.compose.runtime.mutableStateOf("") }
            OutlinedTextField(
                value = dropText,
                onValueChange = { dropText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Destination as  lat, lon  (map-pin geocoding is TODO)") },
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    if (!vm.setDropoffFromText(dropText)) { /* notice set by VM path if needed */ }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set drop-off") }

            s.dropoffLat?.let {
                Spacer(Modifier.height(6.dp))
                Text("Drop-off: ${s.dropoffLabel}", color = MikeMuted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.requestEstimate() },
                enabled = s.pickup != null && s.dropoffLat != null && !s.loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MikeAccent),
            ) { Text("Get fare estimate", color = Color(0xFF0A0E14), fontWeight = FontWeight.Bold) }

            if (s.loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = MikeAccent)
            }

            s.estimate?.let { est ->
                Spacer(Modifier.height(16.dp))
                FareCard(est)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.requestRide() },
                    enabled = !s.loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MikeGreen),
                ) { Text("Request ride", color = Color(0xFF0A0E14), fontWeight = FontWeight.Bold) }
            }

            s.notice?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MikeMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FareCard(est: com.mikeos.taxi.net.TaxiCloudClient.Estimate) {
    Card(colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "%.2f %s".format(est.fare, est.currency),
                color = MikeOnSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "%.1f km · ~%.0f min".format(est.distanceKm, est.durationMin),
                color = MikeMuted,
            )
            Spacer(Modifier.height(12.dp))
            // THE selling point — prominent.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x223FB950))
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        "Driver keeps 95% — MikeTaxi only takes 5%",
                        color = MikeGreen, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Driver payout %.2f  ·  platform fee %.2f".format(est.driverPayout, est.platformFee),
                        color = MikeMuted, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RideStatusCard(
    ride: com.mikeos.taxi.net.TaxiCloudClient.Ride,
    onCancel: () -> Unit,
) {
    Column {
        SectionLabel("Your ride")
        Card(colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(ride.status.uppercase(), color = MikeAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                ride.driverName?.let { Text("Driver: $it", color = MikeOnSurface) }
                ride.fare?.let {
                    Text("Fare %.2f %s".format(it, ride.currency ?: "EUR"), color = MikeMuted)
                }
                ride.driverLat?.let { lat ->
                    ride.driverLon?.let { lon ->
                        Text("Driver at %.4f, %.4f".format(lat, lon), color = MikeMuted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel ride", color = MikeRed)
                }
            }
        }
    }
}

/* ------------------------------- DRIVER mode ------------------------------- */

@Composable
private fun DriverScreen(vm: TaxiViewModel) {
    val s by vm.driver.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TaxiMap(centerLat = null, centerLon = null, modifier = Modifier.fillMaxWidth().height(200.dp))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (s.registered == null) {
                DriverRegistration(s, vm)
            } else {
                DriverOnlinePanel(s, vm)
            }
            s.notice?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MikeMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DriverRegistration(s: DriverState, vm: TaxiViewModel) {
    SectionLabel("Become a driver")
    OutlinedTextField(
        value = s.make, onValueChange = { vm.onDriverField(make = it) },
        label = { Text("Vehicle make") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.model, onValueChange = { vm.onDriverField(model = it) },
        label = { Text("Vehicle model") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.plate, onValueChange = { vm.onDriverField(plate = it) },
        label = { Text("Number plate") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    // Dashcam is MANDATORY — a driver cannot register without it.
    Card(colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Dashcam active (required)", color = MikeOnSurface, fontWeight = FontWeight.Bold)
                Text(
                    "A dashcam is mandatory. Its street imagery feeds MikeMaps — that's how we avoid " +
                        "paying Google, which is what lets the fee stay at 5%.",
                    color = MikeMuted, fontSize = 12.sp,
                )
            }
            Switch(checked = s.dashcamActive, onCheckedChange = { vm.setDashcam(it) })
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.registerDriver() },
        enabled = !s.loading,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MikeAccent),
    ) { Text("Register as driver", color = Color(0xFF0A0E14), fontWeight = FontWeight.Bold) }
}

@Composable
private fun DriverOnlinePanel(s: DriverState, vm: TaxiViewModel) {
    val d = s.registered!!
    SectionLabel("Driver")
    Card(colors = CardDefaults.cardColors(containerColor = MikeSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "${d.vehicleMake ?: ""} ${d.vehicleModel ?: ""}".trim().ifBlank { "Your vehicle" },
                color = MikeOnSurface, fontWeight = FontWeight.Bold,
            )
            d.plate?.let { Text(it, color = MikeMuted) }
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.dashcamActive) "Dashcam active ✓" else "Dashcam OFF — required!",
                color = if (d.dashcamActive) MikeGreen else MikeRed, fontSize = 13.sp,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    // GO ONLINE / OFFLINE — pushes the daemon's shared fix; the agent keeps pushing each beat.
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (s.online) "You are ONLINE" else "You are offline",
                color = if (s.online) MikeGreen else MikeMuted, fontWeight = FontWeight.Bold)
            Text("Online pushes your live location so riders can be matched to you.",
                color = MikeMuted, fontSize = 12.sp)
        }
        Switch(checked = s.online, onCheckedChange = { vm.setOnline(it) })
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("Your rides")
    if (s.myRides.isEmpty()) {
        Text("No requested rides yet. Go online to receive them.", color = MikeMuted, fontSize = 13.sp)
    } else {
        s.myRides.take(10).forEach { r -> DriverRideRow(r, vm) }
    }

    Spacer(Modifier.height(16.dp))
    // Dashcam capture hook — the MikeMaps street-imagery feed point (foundation stub).
    OutlinedButton(
        onClick = { /* TODO(MikeMaps): hand off to MikeCamera/MikeMaps dashcam pipeline */ },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Capture street view  (MikeMaps dashcam — TODO)") }
}

@Composable
private fun DriverRideRow(
    r: com.mikeos.taxi.net.TaxiCloudClient.Ride,
    vm: TaxiViewModel,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MikeSurface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(r.status.uppercase(), color = MikeAccent, fontWeight = FontWeight.Bold)
            r.driverPayout?.let {
                Text(
                    "You keep %.2f %s of %.2f".format(it, r.currency ?: "EUR", r.fare ?: it),
                    color = MikeGreen, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Lifecycle buttons — the next legal transition given the status.
            val next = when (r.status) {
                "requested", "matched" -> "accept"
                "accepted" -> "arrive"
                "arrived" -> "start"
                "in_progress" -> "complete"
                else -> null
            }
            Row {
                if (next != null) {
                    Button(
                        onClick = { vm.driverTransition(r.id, next) },
                        colors = ButtonDefaults.buttonColors(containerColor = MikeAccent),
                    ) { Text(next.replaceFirstChar { it.uppercase() }, color = Color(0xFF0A0E14)) }
                    Spacer(Modifier.width(8.dp))
                }
                if (r.status !in setOf("completed", "cancelled")) {
                    OutlinedButton(onClick = { vm.driverTransition(r.id, "cancel") }) {
                        Text("Cancel", color = MikeRed)
                    }
                }
            }
        }
    }
}

/* ------------------------------- shared bits ------------------------------- */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MikeMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
