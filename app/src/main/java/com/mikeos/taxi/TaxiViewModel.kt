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
    val suggestions: List<TaxiCloudClient.Place> = emptyList(),  // MikeMaps search results
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
    val requirements: TaxiCloudClient.Requirements? = null,   // onboarding funnel + go-online gate
    val earnings: TaxiCloudClient.Earnings? = null,           // accrued ledger earnings
    val loading: Boolean = false,
    val notice: String? = null,
) {
    /** A registered driver may only go online once fully verified. */
    val canGoOnline: Boolean get() = requirements?.canGoOnline == true
}

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
        if (m == Mode.DRIVER) refreshDriver()
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

    /** MikeMaps place search — type a destination instead of lat,lon (debounced). */
    private var searchJob: Job? = null
    fun searchDropoff(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _client.value = _client.value.copy(suggestions = emptyList()); return
        }
        searchJob = viewModelScope.launch {
            delay(280)
            val places = repo.geocode(query)
            _client.value = _client.value.copy(suggestions = places)
        }
    }

    /** Choose a searched place as the drop-off. */
    fun pickSuggestion(p: TaxiCloudClient.Place) {
        setDropoff(p.lat, p.lon, p.name)
        _client.value = _client.value.copy(suggestions = emptyList())
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
            val ride = repo.requestRide(from.lat, from.lon, toLat, toLon, toLabel = s.dropoffLabel)
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
            if (d == null) {
                _driver.value = _driver.value.copy(loading = false, notice = "Registration failed (cloud offline?).")
                return@launch
            }
            _driver.value = _driver.value.copy(
                loading = false, registered = d,
                notice = "Registered — now verify your documents to go online.",
            )
            refreshDriver()   // pull the verification checklist
        }
    }

    fun setOnline(online: Boolean) {
        // A registered-but-unverified driver can't go online — guide them to the funnel.
        if (online && !_driver.value.canGoOnline) {
            _driver.value = _driver.value.copy(notice = "Complete driver verification before going online.")
            return
        }
        _driver.value = _driver.value.copy(loading = true, notice = null)
        viewModelScope.launch {
            val res = repo.setDriverStatus(online)
            val nowOnline = online && res == TaxiCloudClient.StatusResult.OK
            TaxiMikeAgent.driverOnline = nowOnline
            _driver.value = _driver.value.copy(
                loading = false,
                online = nowOnline,
                notice = when (res) {
                    TaxiCloudClient.StatusResult.OK -> null
                    TaxiCloudClient.StatusResult.BLOCKED_UNVERIFIED -> "Complete driver verification before going online."
                    TaxiCloudClient.StatusResult.FAILED ->
                        if (online) "Couldn't go online — waiting for a location fix, or the cloud is offline."
                        else "Status change not confirmed by the cloud."
                },
            )
            if (res == TaxiCloudClient.StatusResult.BLOCKED_UNVERIFIED) refreshDriver()
            if (nowOnline) refreshDriverRides()
        }
    }

    /** Load the full driver picture: profile + verification checklist + rides. */
    fun refreshDriver() {
        viewModelScope.launch {
            val d = repo.driverMe()
            val reqs = if (d != null) repo.requirements() else null
            val rides = if (d != null) repo.myRides("driver") else emptyList()
            val earn = if (d != null) repo.earnings() else null
            _driver.value = _driver.value.copy(
                registered = d,
                online = d?.online ?: false,
                requirements = reqs,
                myRides = rides,
                earnings = earn,
            )
        }
    }

    /** Submit (or resubmit) one verification document, then reload the checklist. */
    fun submitDocument(docType: String, reference: String, expiresIso: String?) {
        viewModelScope.launch {
            val ok = repo.submitDocument(docType, reference.ifBlank { null }, expiresIso)
            _driver.value = _driver.value.copy(notice = if (ok) "Submitted — under review." else "Could not submit document.")
            refreshDriver()
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
