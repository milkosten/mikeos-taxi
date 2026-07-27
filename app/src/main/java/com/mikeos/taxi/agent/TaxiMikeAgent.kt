package com.mikeos.taxi.agent

import android.content.Context
import android.util.Log
import com.mikeos.core.MikeAgentConfig
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.agent.Skill
import com.mikeos.core.agent.Soul
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.taxi.BuildConfig
import com.mikeos.taxi.taxi.TaxiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the shared MikeAgent runtime (vendored under `com.mikeos.core`) into MikeTaxi, making
 * it a genuine AI-native agent — not a thin CRUD UI (APP-ANATOMY §0/§1).
 *
 * MikeTaxi is the on-device face of the **5%-fee** ride-hailing platform. The agent:
 *  • self-registers on the hive and heartbeats (via the vendored core — we don't reimplement it);
 *  • when the user is ONLINE as a driver, DETERMINISTICALLY pushes the daemon's shared location
 *    to the cloud on every beat (`POST /api/taxi/drivers/status`), so dispatch always knows where
 *    the driver is — this is a device fact, not something we make the LLM choose to do;
 *  • exposes taxi skills (estimate, request, driver status, my rides) so a sibling or the brain
 *    can act through it.
 *
 * The universal skills (location / hive_send / remember / recall / notify / ask_siblings) are
 * added by the runtime at install time.
 */
object TaxiMikeAgent {

    private const val TAG = "TaxiMikeAgent"

    // Sibling agents a ride-hailing app naturally collaborates with on this phone.
    // MikeMaps owns the dashcam street-imagery pipeline the driver feeds; MikePay watches
    // fares/earnings; MikeGuide/MikeLocation deal in places & the shared fix.
    private val SIBLINGS = listOf("MikeMaps", "MikePay", "MikeGuide", "MikeLocation", "MikeMind")

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installed = false

    /** Set by the UI when the driver flips GO ONLINE — drives the per-beat location push. */
    @Volatile var driverOnline = false

    private val pushingLocation = AtomicBoolean(false)
    @Volatile private var lastPushMs = 0L
    private const val PUSH_MIN_INTERVAL_MS = 30_000L // don't hammer the cloud faster than 30s

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val repo = TaxiRepository.get(app)

        val soul = Soul(
            agentName = "Taxi",
            appName = "MikeTaxi",
            persona = "I'm MikeTaxi's agent — the on-device brain of the fair, 5%-fee ride-hailing " +
                "platform. Uber takes ~20-25% of a fare; MikeTaxi takes only 5%, so the driver keeps " +
                "95%, because MikeOS pays almost nothing per ride (our own routing, geocoding, maps and " +
                "dashcam imagery). In CLIENT mode I get Mike a ride and show the honest fare + the 95/5 " +
                "split; in DRIVER mode I keep him online, surface nearby requests, and make sure his " +
                "dashcam is feeding MikeMaps. I never run my own GPS — I read the one shared fix.",
            goals = listOf(
                "Get the user a ride quickly and show the honest fare with the 'driver keeps 95%' split",
                "Keep a driver reliably ONLINE and push his live location so dispatch can match him",
                "Enforce that a driver's dashcam is active (it feeds MikeMaps street imagery — no Google)",
                "Read the single shared location fix from the daemon — never run my own GPS",
                "Move rides through their lifecycle and keep the rider updated on the driver's approach",
            ),
        )

        // Per-beat perception is CHEAP and also DETERMINISTICALLY pushes the driver's location
        // while online (the spec: "while online, push location each heartbeat"). We never rely on
        // the LLM picking a skill for something this load-bearing.
        HeartbeatService.perceptionProvider = {
            if (driverOnline) maybePushDriverLocation(repo)
            buildPerception(repo)
        }

        bg.launch {
            runCatching {
                MikeAgent.install(
                    app,
                    MikeAgentConfig(
                        daemonToken = BuildConfig.DAEMON_TOKEN,
                        userName = "Mike",
                        siblings = SIBLINGS,
                    ),
                    soul,
                    buildSkills(repo),
                )
                HeartbeatService.start(app)
                MikeAgent.get()?.connectHive()
                Log.i(TAG, "MikeAgent installed; heartbeat + hive up (siblings=$SIBLINGS)")
            }.onFailure { Log.w(TAG, "MikeAgent install failed: ${it.message}") }
        }
    }

    /** Push the driver's shared fix to the cloud, throttled + single-flight, off the beat. */
    private fun maybePushDriverLocation(repo: TaxiRepository) {
        val now = System.currentTimeMillis()
        if (now - lastPushMs < PUSH_MIN_INTERVAL_MS) return
        if (!pushingLocation.compareAndSet(false, true)) return
        bg.launch {
            runCatching {
                lastPushMs = now
                val ok = repo.setDriverStatus(online = true)
                if (!ok) Log.w(TAG, "driver location push not confirmed by cloud")
            }.onFailure { Log.w(TAG, "driver location push failed: ${it.message}") }
            pushingLocation.set(false)
        }
    }

    private suspend fun buildPerception(repo: TaxiRepository): String {
        val fix = runCatching { repo.currentLocation() }.getOrNull()
        val where = fix?.let { "at ${it.label ?: "%.4f,%.4f".format(it.lat, it.lon)}" }
            ?: "location not available yet"
        val mode = if (driverOnline) "DRIVER (ONLINE)" else "CLIENT / idle"
        return "MikeTaxi is in $mode mode; user is $where. The platform fee is 5% — the driver " +
            "always keeps 95%. I can estimate a fare, request a ride, and manage driver status."
    }

    // ---- SKILLS (wrap MikeTaxi's REAL cloud functions) -------------------------------------

    private fun buildSkills(repo: TaxiRepository): List<Skill> = listOf(
        Skill(
            name = "fare_estimate",
            description = "Estimate the fare for a trip from the user's CURRENT location to a " +
                "destination lat,lon. Returns distance, ETA, fare, and the 5% platform fee / 95% " +
                "driver payout split. Use this to answer 'how much would a ride to X cost?'.",
            paramsSchema = """{"to_lat":"destination latitude","to_lon":"destination longitude"}""",
            run = { args ->
                val toLat = args.optDouble("to_lat", Double.NaN)
                val toLon = args.optDouble("to_lon", Double.NaN)
                if (toLat.isNaN() || toLon.isNaN()) return@Skill "fare_estimate needs to_lat and to_lon"
                val fix = repo.currentLocation() ?: return@Skill "no location fix yet"
                val est = repo.estimate(fix.lat, fix.lon, toLat, toLon)
                    ?: return@Skill "estimate unavailable (cloud offline or no route)"
                "${"%.1f".format(est.distanceKm)} km, ~${"%.0f".format(est.durationMin)} min, " +
                    "fare ${"%.2f".format(est.fare)} ${est.currency} — driver keeps " +
                    "${"%.2f".format(est.driverPayout)} (MikeTaxi fee ${"%.2f".format(est.platformFee)}, 5%)."
            },
        ),
        Skill(
            name = "request_ride",
            description = "Request a ride from the user's current location to a destination lat,lon " +
                "(matches the nearest online driver). Use only when Mike has confirmed he wants the ride.",
            paramsSchema = """{"to_lat":"destination latitude","to_lon":"destination longitude"}""",
            run = { args ->
                val toLat = args.optDouble("to_lat", Double.NaN)
                val toLon = args.optDouble("to_lon", Double.NaN)
                if (toLat.isNaN() || toLon.isNaN()) return@Skill "request_ride needs to_lat and to_lon"
                val fix = repo.currentLocation() ?: return@Skill "no location fix yet"
                val ride = repo.requestRide(fix.lat, fix.lon, toLat, toLon)
                    ?: return@Skill "ride request failed (cloud offline / no drivers)"
                "ride ${ride.id} requested — status ${ride.status}"
            },
        ),
        Skill(
            name = "driver_status",
            description = "Set the driver's availability: pass online=true to GO ONLINE (starts " +
                "pushing the live shared location so dispatch can match you) or online=false to go " +
                "offline. Only meaningful once the user has registered as a driver.",
            paramsSchema = """{"online":"true to go online, false to go offline"}""",
            run = { args ->
                val online = args.optBoolean("online", false)
                val ok = repo.setDriverStatus(online)
                driverOnline = online && ok
                if (ok) "driver is now ${if (online) "ONLINE" else "offline"}"
                else "status change not confirmed by the cloud"
            },
        ),
        Skill(
            name = "my_rides",
            description = "List the user's rides for a role — role=client for rides they booked, " +
                "role=driver for rides they drove. Use to answer 'what rides do I have?'.",
            paramsSchema = """{"role":"client or driver"}""",
            run = { args ->
                val role = args.optString("role").ifBlank { "client" }
                val rides = repo.myRides(role)
                if (rides.isEmpty()) "no $role rides"
                else rides.take(15).joinToString("\n") { r ->
                    "- ${r.id}: ${r.status}" + (r.fare?.let { " (${"%.2f".format(it)} ${r.currency ?: "EUR"})" } ?: "")
                }
            },
        ),
    )
}
