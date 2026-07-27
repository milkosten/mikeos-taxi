package com.mikeos.taxi.net

import android.util.Log
import com.mikeos.taxi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to **mikeos-taxi-cloud** — the 5%-fee ride-hailing backend (FastAPI + Postgres on
 * Railway, dual-auth → user_id; drivers and clients are both users).
 *
 * THE PITCH this app exists to prove: Uber takes ~20–25% of the fare. MikeTaxi takes **5%**,
 * the driver keeps **95%**, because MikeOS pays ~€0 per ride (our own OSRM routing, Nominatim
 * geocoding, dashcam→MikeMaps imagery, tiles.osmike.com). Every estimate surfaces the split.
 *
 * Auth: every call carries `X-API-KEY: <hive agent key>` (from
 * [com.mikeos.core.hive.HiveIdentity] / the installed [com.mikeos.core.agent.MikeAgent]);
 * the cloud resolves it to a `user_id`.
 *
 * TLS: Railway public cert → a STANDARD OkHttpClient (with DoH for flaky system DNS), never
 * the loopback trust-all client.
 *
 * House rules honoured: never trust HTTP 200 alone (verify a real id / a `status` came back);
 * numeric fields sent as numbers (an empty-string in an INTEGER column silently 422s the write).
 *
 * API (per docs/services/taxi.md):
 *   POST /api/taxi/drivers/register   -> {"driver":{...}}
 *   POST /api/taxi/drivers/status     -> {"driver":{...}} | {"status":"online|offline"}
 *   GET  /api/taxi/estimate?from=&to= -> {distance_km,duration_min,fare,platform_fee,driver_payout,currency}
 *   POST /api/taxi/rides              -> {"ride":{...}}  (matches nearest online driver)
 *   GET  /api/taxi/rides/{id}         -> {"ride":{...}}  (status + driver live pos)
 *   POST /api/taxi/rides/{id}/accept|arrive|start|complete|cancel -> {"ride":{...}}
 *   GET  /api/taxi/rides?role=driver|client -> {"rides":[...]}
 *   GET  /api/health
 */
class TaxiCloudClient(
    private val baseUrl: String = BuildConfig.TAXI_CLOUD_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)                          // phone's system DNS is flaky — resolve via Cloudflare
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- wire types ------------------------------------------------------------------------

    /** A fare estimate for a pickup→dropoff, with the all-important 95/5 split. */
    data class Estimate(
        val distanceKm: Double,
        val durationMin: Double,
        val fare: Double,
        val platformFee: Double,
        val driverPayout: Double,
        val currency: String,
    )

    /** A driver profile as stored by the cloud. */
    data class Driver(
        val id: String,
        val vehicleMake: String?,
        val vehicleModel: String?,
        val plate: String?,
        val dashcamActive: Boolean,
        val online: Boolean,
    )

    /** A ride and its live state. `driverLat/Lon` populate once a driver is matched + moving. */
    data class Ride(
        val id: String,
        val status: String,
        val fromLat: Double?,
        val fromLon: Double?,
        val toLat: Double?,
        val toLon: Double?,
        val fare: Double?,
        val platformFee: Double?,
        val driverPayout: Double?,
        val currency: String?,
        val driverLat: Double?,
        val driverLon: Double?,
        val driverName: String?,
    )

    private fun req(apiKey: String, path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- estimate --------------------------------------------------------------------------

    /**
     * Fare estimate -> `GET /api/taxi/estimate?from=lat,lon&to=lat,lon`. Null on failure.
     * Never throws.
     */
    suspend fun estimate(
        apiKey: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): Estimate? = withContext(Dispatchers.IO) {
        val path = "/api/taxi/estimate?from=${enc("$fromLat,$fromLon")}&to=${enc("$toLat,$toLon")}"
        try {
            client.newCall(req(apiKey, path).get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "estimate HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val fare = o.optDouble("fare", Double.NaN)
                if (fare.isNaN()) {
                    Log.w(TAG, "estimate 200 but no fare: $raw")
                    return@withContext null
                }
                val fee = o.optDouble("platform_fee", fare * 0.05)
                Estimate(
                    distanceKm = o.optDouble("distance_km", 0.0),
                    durationMin = o.optDouble("duration_min", 0.0),
                    fare = fare,
                    platformFee = fee,
                    driverPayout = o.optDouble("driver_payout", fare - fee),
                    currency = o.str("currency") ?: "EUR",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "estimate failed: ${e.message}")
            null
        }
    }

    // ---- client: request + track a ride ----------------------------------------------------

    /**
     * Request a ride -> `POST /api/taxi/rides`. `scheduledForIso` (ISO-8601) requests a future
     * pickup (≥1h lead enforced server-side); omit for a "now" ride. Returns the created Ride
     * (verified: a real id + status came back) or null. Never throws.
     */
    suspend fun requestRide(
        apiKey: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        scheduledForIso: String? = null,
    ): Ride? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("from", JSONObject().put("lat", fromLat).put("lon", fromLon))
            .put("to", JSONObject().put("lat", toLat).put("lon", toLon))
            .apply { if (!scheduledForIso.isNullOrBlank()) put("scheduled_for", scheduledForIso) }
            .toString().toRequestBody(jsonMedia)
        postRide(apiKey, "/api/taxi/rides", payload, "requestRide")
    }

    /** Poll a ride -> `GET /api/taxi/rides/{id}`. Null if missing/unreachable. */
    suspend fun getRide(apiKey: String, id: String): Ride? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/rides/${enc(id)}").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRide HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw).optJSONObject("ride") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                o?.let { parseRide(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRide failed: ${e.message}")
            null
        }
    }

    /** My rides -> `GET /api/taxi/rides?role=driver|client`. Empty on failure. */
    suspend fun myRides(apiKey: String, role: String): List<Ride> = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/rides?role=${enc(role)}").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "myRides HTTP ${resp.code}: $raw")
                    return@withContext emptyList()
                }
                val arr = runCatching { JSONObject(raw).optJSONArray("rides") }.getOrNull()
                    ?: runCatching { JSONArray(raw) }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseRide(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "myRides failed: ${e.message}")
            emptyList()
        }
    }

    // ---- lifecycle transitions (driver + client) -------------------------------------------

    /** POST one of accept|arrive|start|complete|cancel on a ride. Returns the updated Ride or null. */
    suspend fun transition(apiKey: String, id: String, action: String): Ride? = withContext(Dispatchers.IO) {
        val body = "{}".toRequestBody(jsonMedia)
        postRide(apiKey, "/api/taxi/rides/${enc(id)}/$action", body, "transition:$action")
    }

    // ---- driver: register + status ---------------------------------------------------------

    /**
     * Become a driver -> `POST /api/taxi/drivers/register`. **A dashcam is mandatory** — the
     * cloud rejects a registration with `dashcam_active=false`, and this app enforces it in the
     * UI too. Returns the created Driver or null. Never throws.
     */
    suspend fun registerDriver(
        apiKey: String,
        make: String, model: String, plate: String,
        dashcamActive: Boolean,
    ): Driver? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("vehicle_make", make)
            .put("vehicle_model", model)
            .put("plate", plate)
            .put("dashcam_active", dashcamActive)
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/register").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "registerDriver HTTP ${resp.code}: $raw")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw).optJSONObject("driver") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                val id = o?.optString("id").takeUnless { it.isNullOrBlank() }
                if (id == null) { Log.w(TAG, "registerDriver 200 but no id: $raw"); return@withContext null }
                parseDriver(o!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerDriver failed: ${e.message}")
            null
        }
    }

    /**
     * Go online/offline + push the current live position -> `POST /api/taxi/drivers/status`.
     * The `{lat,lon}` come from the daemon's shared fix (never our own GPS). Returns true when
     * the cloud confirms the new state. Never throws.
     */
    suspend fun setDriverStatus(
        apiKey: String,
        online: Boolean,
        lat: Double?, lon: Double?,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("online", online)
            .apply {
                if (lat != null && lon != null) {
                    put("lat", lat); put("lon", lon)
                }
            }
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/status").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "setDriverStatus HTTP ${resp.code}: $raw")
                    return@withContext false
                }
                // Never-trust-200: confirm the cloud echoes a state.
                val o = runCatching { JSONObject(raw) }.getOrNull()
                val ok = o != null && (o.has("driver") || o.has("status") || o.has("online"))
                if (!ok) Log.w(TAG, "setDriverStatus 200 but no state echoed: $raw")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "setDriverStatus failed: ${e.message}")
            false
        }
    }

    /** Health -> `GET /api/health`. True if the cloud answers 2xx. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$baseUrl/api/health").get().build()).execute()
                .use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ---- shared POST-a-ride helper + parsing ------------------------------------------------

    private fun postRide(apiKey: String, path: String, body: okhttp3.RequestBody, tag: String): Ride? {
        return try {
            client.newCall(req(apiKey, path).post(body).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$tag HTTP ${resp.code}: $raw")
                    return null
                }
                val o = runCatching { JSONObject(raw).optJSONObject("ride") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                val id = o?.optString("id").takeUnless { it.isNullOrBlank() }
                val status = o?.optString("status").takeUnless { it.isNullOrBlank() }
                if (id == null || status == null) {
                    Log.w(TAG, "$tag 200 but no id/status: $raw")
                    return null
                }
                parseRide(o!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$tag failed: ${e.message}")
            null
        }
    }

    private fun parseDriver(o: JSONObject): Driver = Driver(
        id = o.optString("id"),
        vehicleMake = o.str("vehicle_make"),
        vehicleModel = o.str("vehicle_model"),
        plate = o.str("plate"),
        dashcamActive = o.optBoolean("dashcam_active", false),
        online = o.optBoolean("online", false),
    )

    private fun parseRide(o: JSONObject): Ride {
        // Coords may be nested under from/to objects or flattened as from_lat etc.
        val from = o.optJSONObject("from")
        val to = o.optJSONObject("to")
        val driver = o.optJSONObject("driver")
        fun d(obj: JSONObject?, k: String, flat: String): Double? {
            val v = obj?.optDouble(k, Double.NaN) ?: o.optDouble(flat, Double.NaN)
            return v?.takeUnless { it.isNaN() }
        }
        return Ride(
            id = o.optString("id"),
            status = o.optString("status", "unknown"),
            fromLat = d(from, "lat", "from_lat"),
            fromLon = d(from, "lon", "from_lon"),
            toLat = d(to, "lat", "to_lat"),
            toLon = d(to, "lon", "to_lon"),
            fare = o.optDouble("fare", Double.NaN).takeUnless { it.isNaN() },
            platformFee = o.optDouble("platform_fee", Double.NaN).takeUnless { it.isNaN() },
            driverPayout = o.optDouble("driver_payout", Double.NaN).takeUnless { it.isNaN() },
            currency = o.str("currency"),
            driverLat = d(driver, "lat", "driver_lat"),
            driverLon = d(driver, "lon", "driver_lon"),
            driverName = driver?.optString("name")?.takeUnless { it.isBlank() || it == "null" }
                ?: o.str("driver_name"),
        )
    }

    /** optString that treats JSON null / blanks as null. */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeUnless { it.isBlank() || it == "null" }

    companion object {
        private const val TAG = "TaxiCloudClient"
    }
}
