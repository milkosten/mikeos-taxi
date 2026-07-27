package com.mikeos.taxi.net

import android.util.Log
import com.mikeos.core.net.loopbackTrustingClientPublic
import com.mikeos.taxi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the **single shared location fix** from the on-device daemon.
 *
 * HOUSE RULE (APP-ANATOMY §3a): an app must NEVER run its own GPS. The daemon is the one
 * location authority; exactly one provider app (com.mikeos.location) pushes GNSS and everyone
 * else — including MikeTaxi — only READS `GET https://127.0.0.1:7743/api/location`.
 *
 * The endpoint is auth-exempt + loopback-only, but we send the bearer anyway (harmless) and
 * trust the daemon's self-signed cert via the core's loopback-scoped client. Never throws;
 * returns null when there is no fix yet so callers degrade gracefully to a map-pin flow.
 */
object DaemonLocation {

    private const val TAG = "DaemonLocation"

    /** A GPS fix as the daemon reports it. */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val label: String?, // reverse-geocoded "city, region" if the daemon has it
    )

    private val client: OkHttpClient by lazy {
        loopbackTrustingClientPublic(BuildConfig.DAEMON_BASE_URL).newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Read the current shared fix, or null if the daemon has none / is unreachable. */
    suspend fun current(): Fix? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.DAEMON_BASE_URL}/api/location")
                .header("Authorization", "Bearer ${BuildConfig.DAEMON_TOKEN}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "location HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                // The daemon may wrap the fix under "location" or return it flat.
                val fix = o.optJSONObject("location") ?: o
                val lat = fix.optDouble("lat", Double.NaN)
                val lon = fix.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) {
                    Log.w(TAG, "no usable lat/lon yet: $raw")
                    return@withContext null
                }
                val label = listOfNotNull(
                    fix.optString("city").takeUnless { it.isBlank() || it == "null" },
                    fix.optString("region").takeUnless { it.isBlank() || it == "null" },
                ).joinToString(", ").ifBlank { null }
                Fix(lat, lon, label)
            }
        } catch (e: Exception) {
            Log.w(TAG, "current() failed: ${e.message}")
            null
        }
    }
}
