package com.mikeos.taxi.taxi

import android.content.Context
import android.util.Log
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.hive.HiveIdentity
import com.mikeos.taxi.BuildConfig
import com.mikeos.taxi.net.DaemonLocation
import com.mikeos.taxi.net.TaxiCloudClient

/**
 * MikeTaxi's REAL ride/driver functions — the store the agent's skills wrap and the source of
 * truth for the UI. Backed by **mikeos-taxi-cloud** (per-user, user-scoped by the hive agent
 * key) and the on-device daemon's **single shared location fix** (never our own GPS).
 *
 * The X-API-KEY is Mike's per-app hive agent key, minted by the daemon on §0 self-registration
 * and persisted by [HiveIdentity]; read from the installed [MikeAgent] once registered, else
 * from the persisted credentials file.
 */
class TaxiRepository private constructor(private val appContext: Context) {

    private val cloud = TaxiCloudClient()
    private val identity = HiveIdentity("MikeTaxi", BuildConfig.DAEMON_BASE_URL)

    /** The user-scoped hive agent key used as X-API-KEY, or null before self-registration. */
    fun apiKey(): String? =
        MikeAgent.get()?.cred?.agentKey ?: identity.load(appContext)?.agentKey

    /** The ONE shared location fix from the daemon (APP-ANATOMY §3a). Null if no fix yet. */
    suspend fun currentLocation(): DaemonLocation.Fix? = DaemonLocation.current()

    // ---- client -----------------------------------------------------------------------------

    suspend fun estimate(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): TaxiCloudClient.Estimate? {
        val key = apiKey() ?: run { Log.w(TAG, "estimate: no api key yet"); return null }
        return cloud.estimate(key, fromLat, fromLon, toLat, toLon)
    }

    suspend fun requestRide(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        scheduledForIso: String? = null,
    ): TaxiCloudClient.Ride? {
        val key = apiKey() ?: run { Log.w(TAG, "requestRide: no api key yet"); return null }
        return cloud.requestRide(key, fromLat, fromLon, toLat, toLon, scheduledForIso)
    }

    suspend fun ride(id: String): TaxiCloudClient.Ride? {
        val key = apiKey() ?: run { Log.w(TAG, "ride: no api key yet"); return null }
        return cloud.getRide(key, id)
    }

    suspend fun myRides(role: String): List<TaxiCloudClient.Ride> {
        val key = apiKey() ?: run { Log.w(TAG, "myRides: no api key yet"); return emptyList() }
        return cloud.myRides(key, role)
    }

    suspend fun transition(id: String, action: String): TaxiCloudClient.Ride? {
        val key = apiKey() ?: run { Log.w(TAG, "transition: no api key yet"); return null }
        return cloud.transition(key, id, action)
    }

    // ---- driver -----------------------------------------------------------------------------

    suspend fun registerDriver(
        make: String, model: String, plate: String, dashcamActive: Boolean,
    ): TaxiCloudClient.Driver? {
        val key = apiKey() ?: run { Log.w(TAG, "registerDriver: no api key yet"); return null }
        return cloud.registerDriver(key, make, model, plate, dashcamActive)
    }

    /** Push online/offline + the daemon's live fix. Returns true when the cloud confirms. */
    suspend fun setDriverStatus(online: Boolean): Boolean {
        val key = apiKey() ?: run { Log.w(TAG, "setDriverStatus: no api key yet"); return false }
        val fix = currentLocation()
        return cloud.setDriverStatus(key, online, fix?.lat, fix?.lon)
    }

    suspend fun cloudHealthy(): Boolean = cloud.health()

    companion object {
        private const val TAG = "TaxiRepository"

        @Volatile private var instance: TaxiRepository? = null
        fun get(context: Context): TaxiRepository =
            instance ?: synchronized(this) {
                instance ?: TaxiRepository(context.applicationContext).also { instance = it }
            }
    }
}
