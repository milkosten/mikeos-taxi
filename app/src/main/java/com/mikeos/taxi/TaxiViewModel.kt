package com.mikeos.taxi

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.taxi.agent.TaxiMikeAgent
import com.mikeos.taxi.net.DaemonLocation
import com.mikeos.taxi.net.TaxiCloudClient
import com.mikeos.taxi.taxi.TaxiRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Top-level app mode. Persisted across launches. */
enum class Mode { CLIENT, DRIVER }

/** CLIENT-mode screen state: entering pickup/dropoff → estimate → request → track. */
data class ClientState(
    val pickup: DaemonLocation.Fix? = null,          // from the daemon's shared fix
    val dropoffLat: Double? = null,
    val dropoffLon: Double? = null,
    val dropoffLabel: String = "",
    val estimate: TaxiCloudClient.Estimate? = null,
    val activeRide: TaxiCloudClient.Ride? = null,     // set once requested; polled for status
    val loading: Boolean = false,
    val notice: String? = null,
)

/** DRIVER-mode screen state: profile/registration → online → active ride. */
data class DriverState(
    val make: String = "",
    val model: String = "",
    val plate: String = "",
    val dashcamActive: Boolean = false,
    val registered: TaxiCloudClient.Driver? = null,
    val online: Boolean = false,
    val myRides: List<TaxiCloudClient.Ride> = emptyList(),
    val loading: Boolean = false,
    val notice: String? = null,
)

class TaxiViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaxiRepository.get(app)
    private val prefs = app.getSharedPreferences("miketaxi", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(loadMode())
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _client = MutableStateFlow(ClientState())
    val client: StateFlow<ClientState> = _client.asStateFlow()

    private val _driver = MutableStateFlow(DriverState())
    val driver: StateFlow<DriverState> = _driver.asStateFlow()

    private var ridePoll: Job? = null

    init {
        refreshPickup()
    }

    // ---- mode -------------------------------------------------------------------------------

    fun setMode(m: Mode) {
        if (_mode.value == m) return
        _mode.value = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
        if (m == Mode.DRIVER) refreshDriverRides()
    }

    private fun loadMode(): Mode =
        runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.CLIENT.name)!!) }
            .getOrDefault(Mode.CLIENT)

    // ---- CLIENT -----------------------------------------------------------------------------

    /** Pull the ONE shared location fix from the daemon and use it as the pickup point. */
    fun refreshPickup() {
        viewModelScope.launch {
            val fix = repo.currentLocation()
            _client.value = _client.value.copy(
                pickup = fix,
                notice = if (fix == null) "Waiting for the shared location fix from the daemon…" else null,
            )
        }
    }

    /** Set the drop-off (from a map pin or a typed lat,lon). Clears any stale estimate. */
    fun setDropoff(lat: Double, lon: Double, label: String = "") {
        _client.value = _client.value.copy(
            dropoffLat = lat, dropoffLon = lon,
            dropoffLabel = label.ifBlank { "%.5f, %.5f".format(lat, lon) },
            estimate = null,
        )
    }

    /** Parse a "lat, lon" text field into a drop-off. Returns false if it doesn't parse. */
    fun setDropoffFromText(text: String): Boolean {
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 2) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lon = parts[1].toDoubleOrNull() ?: return false
        setDropoff(lat, lon, text)
        return true
    }

    fun requestEstimate() {
        val s = _client.value
        val from = s.pickup ?: run { _client.value = s.copy(notice = "No pickup fix yet."); return }
        val toLat = s.dropoffLat; val toLon = s.dropoffLon
        if (toLat == null || toLon == null) {
            _client.value = s.copy(notice = "Set a drop-off first."); return
        }
        _client.value = s.copy(loading = true, notice = null)
        viewModelScope.launch {
            val est = repo.estimate(from.lat, from.lon, toLat, toLon)
            _client.value = _client.value.copy(
                loading = false,
                estimate = est,
                notice = if (est == null) "Estimate unavailable (cloud offline or no route)." else null,
            )
        }
    }

    fun requestRide() {
        val s = _client.value
        val from = s.pickup ?: return
        val toLat = s.dropoffLat ?: return
        val toLon = s.dropoffLon ?: return
        _client.value = s.copy(loading = true, notice = null)
        viewModelScope.launch {
            val ride = repo.requestRide(from.lat, from.lon, toLat, toLon)
            if (ride == null) {
                _client.value = _client.value.copy(loading = false, notice = "Ride request failed.")
                return@launch
            }
            _client.value = _client.value.copy(loading = false, activeRide = ride, notice = null)
            startRidePolling(ride.id)
        }
    }

    /** Poll GET /api/taxi/rides/{id} every 5s to refresh status + the driver's live position. */
    private fun startRidePolling(id: String) {
        ridePoll?.cancel()
        ridePoll = viewModelScope.launch {
            while (true) {
                val ride = repo.ride(id) ?: break
                _client.value = _client.value.copy(activeRide = ride)
                if (ride.status in TERMINAL_STATUSES) break
                delay(5_000)
            }
        }
    }

    fun cancelActiveRide() {
        val id = _client.value.activeRide?.id ?: return
        viewModelScope.launch {
            repo.transition(id, "cancel")
            ridePoll?.cancel()
            _client.value = _client.value.copy(activeRide = null, estimate = null)
        }
    }

    fun clearClientNotice() { _client.value = _client.value.copy(notice = null) }

    // ---- DRIVER -----------------------------------------------------------------------------

    fun onDriverField(make: String? = null, model: String? = null, plate: String? = null) {
        _driver.value = _driver.value.copy(
            make = make ?: _driver.value.make,
            model = model ?: _driver.value.model,
            plate = plate ?: _driver.value.plate,
        )
    }

    fun setDashcam(active: Boolean) {
        _driver.value = _driver.value.copy(dashcamActive = active)
    }

    fun registerDriver() {
        val s = _driver.value
        if (!s.dashcamActive) {
            _driver.value = s.copy(notice = "A dashcam is MANDATORY. Enable it to register.")
            return
        }
        if (s.make.isBlank() || s.model.isBlank() || s.plate.isBlank()) {
            _driver.value = s.copy(notice = "Fill in vehicle make, model and plate.")
            return
        }
        _driver.value = s.copy(loading = true, notice = null)
        viewModelScope.launch {
            val d = repo.registerDriver(s.make, s.model, s.plate, s.dashcamActive)
            _driver.value = _driver.value.copy(
                loading = false,
                registered = d,
                notice = if (d == null) "Registration failed (cloud offline?)." else "Registered — you can go online.",
            )
        }
    }

    fun setOnline(online: Boolean) {
        _driver.value = _driver.value.copy(loading = true, notice = null)
        viewModelScope.launch {
            val ok = repo.setDriverStatus(online)
            // Tell the agent so its heartbeat starts/stops the deterministic location push.
            TaxiMikeAgent.driverOnline = online && ok
            _driver.value = _driver.value.copy(
                loading = false,
                online = online && ok,
                notice = if (!ok) "Status change not confirmed by the cloud." else null,
            )
            if (online && ok) refreshDriverRides()
        }
    }

    fun refreshDriverRides() {
        viewModelScope.launch {
            val rides = repo.myRides("driver")
            _driver.value = _driver.value.copy(myRides = rides)
        }
    }

    /** Drive a ride through its lifecycle: accept / arrive / start / complete / cancel. */
    fun driverTransition(id: String, action: String) {
        viewModelScope.launch {
            repo.transition(id, action)
            refreshDriverRides()
        }
    }

    fun clearDriverNotice() { _driver.value = _driver.value.copy(notice = null) }

    override fun onCleared() {
        super.onCleared()
        ridePoll?.cancel()
    }

    companion object {
        private const val KEY_MODE = "mode"
        private val TERMINAL_STATUSES = setOf("completed", "cancelled")
    }
}
