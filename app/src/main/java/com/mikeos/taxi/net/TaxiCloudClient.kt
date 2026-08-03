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
 * Talks to **mikeos-taxi-cloud** — the 5%-fee ride-hailing backend (FastAPI + Postgres, self-hosted
 * at `taxi-api.osmike.com`, dual-auth → user_id; drivers and clients are both users).
 *
 * THE PITCH: Uber takes ~20–25% of the fare. MikeTaxi takes **5%**, the driver keeps **95%**,
 * because MikeOS pays ~€0 per ride. Every estimate surfaces the split.
 *
 * Auth: every call carries `X-API-KEY: <hive agent key>`; the cloud resolves it to a `user_id`.
 * TLS: public LE cert → a STANDARD OkHttpClient (with DoH for flaky system DNS).
 *
 * Wire format matches the live cloud: money fields are `*_eur`, ride locations are nested
 * `pickup`/`dropoff` `{lat,lon,label}`, a driver is keyed by `user_id`, and `verification_status`
 * gates go-online. Never trust HTTP 200 alone; numeric fields sent as numbers.
 */
class TaxiCloudClient(
    private val baseUrl: String = BuildConfig.TAXI_CLOUD_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- wire types ------------------------------------------------------------------------

    data class Estimate(
        val distanceKm: Double,
        val durationMin: Double,
        val fare: Double,
        val platformFee: Double,
        val driverPayout: Double,
        val currency: String,
    )

    data class Driver(
        val id: String,                       // == user_id
        val displayName: String?,
        val vehicleMake: String?,
        val vehicleModel: String?,
        val plate: String?,
        val dashcamActive: Boolean,
        val online: Boolean,
        val verificationStatus: String,       // unverified | pending | rejected | approved
    )

    data class Ride(
        val id: String,
        val status: String,
        val fromLat: Double?, val fromLon: Double?, val fromLabel: String?,
        val toLat: Double?, val toLon: Double?, val toLabel: String?,
        val fare: Double?,
        val platformFee: Double?,
        val driverPayout: Double?,
        val currency: String?,
        val driverLat: Double?, val driverLon: Double?, val driverName: String?,
    )

    /** One document in the driver onboarding funnel. */
    data class DocItem(
        val type: String,
        val label: String,
        val required: Boolean,
        val status: String,                   // missing | pending | approved | rejected | expired
        val expiresAt: String?,
        val reviewNote: String?,
    )

    /** A MikeMaps place-search result. */
    data class Place(val name: String, val lat: Double, val lon: Double)

    /** A driver's accrued earnings (the ledger balance; payout disbursal is gated). */
    data class Earnings(val totalEur: Double, val weekEur: Double, val rides: Int)

    /** The driver onboarding checklist + whether the driver may go online. */
    data class Requirements(
        val registered: Boolean,
        val verificationStatus: String,
        val canGoOnline: Boolean,
        val documents: List<DocItem>,
    )

    private fun req(apiKey: String, path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- estimate --------------------------------------------------------------------------

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
                    Log.w(TAG, "estimate HTTP ${resp.code}: $raw"); return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val fare = o.optDouble("fare_eur", Double.NaN)
                if (fare.isNaN()) { Log.w(TAG, "estimate 200 but no fare_eur: $raw"); return@withContext null }
                val fee = o.optDouble("platform_fee_eur", fare * 0.05)
                Estimate(
                    distanceKm = o.optDouble("distance_km", 0.0),
                    durationMin = o.optDouble("duration_min", 0.0),
                    fare = fare,
                    platformFee = fee,
                    driverPayout = o.optDouble("driver_payout_eur", fare - fee),
                    currency = "EUR",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "estimate failed: ${e.message}"); null
        }
    }

    // ---- client: request + track a ride ----------------------------------------------------

    suspend fun requestRide(
        apiKey: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        fromLabel: String? = null, toLabel: String? = null,
        scheduledForIso: String? = null,
    ): Ride? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("pickup", JSONObject().put("lat", fromLat).put("lon", fromLon)
                .put("label", fromLabel ?: "Pickup"))
            .put("dropoff", JSONObject().put("lat", toLat).put("lon", toLon)
                .put("label", toLabel ?: "Drop-off"))
            .apply { if (!scheduledForIso.isNullOrBlank()) put("scheduled_for", scheduledForIso) }
            .toString().toRequestBody(jsonMedia)
        postRide(apiKey, "/api/taxi/rides", payload, "requestRide")
    }

    /** Poll a ride -> `GET /api/taxi/rides/{id}` -> {ride, driver_location}. */
    suspend fun getRide(apiKey: String, id: String): Ride? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/rides/${enc(id)}").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "getRide HTTP ${resp.code}: $raw"); return@withContext null }
                val top = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val rideObj = top.optJSONObject("ride") ?: top
                parseRide(rideObj, top.optJSONObject("driver_location"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRide failed: ${e.message}"); null
        }
    }

    suspend fun myRides(apiKey: String, role: String): List<Ride> = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/rides?role=${enc(role)}").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "myRides HTTP ${resp.code}: $raw"); return@withContext emptyList() }
                val arr = runCatching { JSONObject(raw).optJSONArray("rides") }.getOrNull()
                    ?: runCatching { JSONArray(raw) }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseRide(it, null) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "myRides failed: ${e.message}"); emptyList()
        }
    }

    /** POST accept|arrive|start|complete|cancel on a ride. Returns the updated Ride or null. */
    suspend fun transition(apiKey: String, id: String, action: String): Ride? = withContext(Dispatchers.IO) {
        postRide(apiKey, "/api/taxi/rides/${enc(id)}/$action", "{}".toRequestBody(jsonMedia), "transition:$action")
    }

    // ---- driver: register / status / me ----------------------------------------------------

    suspend fun registerDriver(
        apiKey: String,
        make: String, model: String, plate: String,
        dashcamActive: Boolean,
        displayName: String? = null,
    ): Driver? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("display_name", displayName ?: "$make $model".trim().ifBlank { plate })
            .put("vehicle_make", make)
            .put("vehicle_model", model)
            .put("plate", plate)
            .put("dashcam_active", dashcamActive)
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/register").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "registerDriver HTTP ${resp.code}: $raw"); return@withContext null }
                val o = runCatching { JSONObject(raw).optJSONObject("driver") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                if (o == null || o.str("user_id") == null) { Log.w(TAG, "registerDriver 200 but no user_id: $raw"); return@withContext null }
                parseDriver(o)
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerDriver failed: ${e.message}"); null
        }
    }

    /** The caller's own driver profile -> `GET /api/taxi/drivers/me`. Null if not a driver. */
    suspend fun driverMe(apiKey: String): Driver? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/me").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                val o = runCatching { JSONObject(raw).optJSONObject("driver") }.getOrNull()
                if (o == null || o == JSONObject.NULL) null else parseDriver(o)
            }
        } catch (e: Exception) { null }
    }

    /**
     * Go online/offline + push the current position -> `POST /api/taxi/drivers/status`.
     * The cloud REQUIRES lat/lon, so we never send online without a fix. Returns a result that
     * distinguishes "not verified yet" (403) from other failures so the UI can guide the driver.
     */
    enum class StatusResult { OK, BLOCKED_UNVERIFIED, FAILED }

    suspend fun setDriverStatus(
        apiKey: String, online: Boolean, lat: Double?, lon: Double?,
    ): StatusResult = withContext(Dispatchers.IO) {
        if (online && (lat == null || lon == null)) return@withContext StatusResult.FAILED
        val payload = JSONObject()
            .put("online", online)
            .put("lat", lat ?: 0.0).put("lon", lon ?: 0.0)
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/status").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 403) { Log.w(TAG, "setDriverStatus blocked (unverified): $raw"); return@withContext StatusResult.BLOCKED_UNVERIFIED }
                if (!resp.isSuccessful) { Log.w(TAG, "setDriverStatus HTTP ${resp.code}: $raw"); return@withContext StatusResult.FAILED }
                val o = runCatching { JSONObject(raw) }.getOrNull()
                if (o != null && (o.has("driver") || o.has("status") || o.has("online"))) StatusResult.OK else StatusResult.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "setDriverStatus failed: ${e.message}"); StatusResult.FAILED
        }
    }

    // ---- driver verification (onboarding funnel) -------------------------------------------

    suspend fun requirements(apiKey: String): Requirements? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/requirements").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "requirements HTTP ${resp.code}: $raw"); return@withContext null }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val arr = o.optJSONArray("documents") ?: JSONArray()
                val docs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { d ->
                    DocItem(
                        type = d.optString("type"),
                        label = d.optString("label"),
                        required = d.optBoolean("required", false),
                        status = d.optString("status", "missing"),
                        expiresAt = d.str("expires_at"),
                        reviewNote = d.str("review_note"),
                    )
                }
                Requirements(
                    registered = o.optBoolean("registered", false),
                    verificationStatus = o.optString("verification_status", "unverified"),
                    canGoOnline = o.optBoolean("can_go_online", false),
                    documents = docs,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "requirements failed: ${e.message}"); null
        }
    }

    /** Submit (or resubmit) a document -> `POST /api/taxi/drivers/documents`. */
    suspend fun submitDocument(
        apiKey: String, docType: String, reference: String?, expiresIso: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("doc_type", docType)
            .apply {
                if (!reference.isNullOrBlank()) {
                    put("storage_ref", reference)
                    put("meta", JSONObject().put("reference", reference))
                }
                if (!expiresIso.isNullOrBlank()) put("expires_at", expiresIso)
            }
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/taxi/drivers/documents").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "submitDocument HTTP ${resp.code}: $raw"); return@withContext false }
                runCatching { JSONObject(raw).has("document") }.getOrDefault(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "submitDocument failed: ${e.message}"); false
        }
    }

    // ---- MikeMaps search + earnings --------------------------------------------------------

    /** Place search -> `GET /api/taxi/geocode?q=`. Empty on failure. */
    suspend fun geocode(apiKey: String, q: String): List<Place> = withContext(Dispatchers.IO) {
        if (q.trim().length < 2) return@withContext emptyList()
        try {
            client.newCall(req(apiKey, "/api/taxi/geocode?q=${enc(q)}").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = runCatching { JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val name = o.str("name") ?: return@mapNotNull null
                    val lat = o.optDouble("lat", Double.NaN); val lon = o.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) null else Place(name, lat, lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed: ${e.message}"); emptyList()
        }
    }

    /** Driver earnings -> `GET /api/taxi/earnings`. Null on failure. */
    suspend fun earnings(apiKey: String): Earnings? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/taxi/earnings").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val o = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return@withContext null
                Earnings(
                    totalEur = o.optDouble("total_earned_eur", 0.0),
                    weekEur = o.optDouble("this_week_eur", 0.0),
                    rides = o.optInt("rides", 0),
                )
            }
        } catch (e: Exception) { null }
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$baseUrl/api/health").get().build()).execute()
                .use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    // ---- parsing ---------------------------------------------------------------------------

    private fun postRide(apiKey: String, path: String, body: okhttp3.RequestBody, tag: String): Ride? {
        return try {
            client.newCall(req(apiKey, path).post(body).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "$tag HTTP ${resp.code}: $raw"); return null }
                val o = runCatching { JSONObject(raw).optJSONObject("ride") }.getOrNull()
                    ?: runCatching { JSONObject(raw) }.getOrNull()
                val id = o?.optString("id").takeUnless { it.isNullOrBlank() }
                val status = o?.optString("status").takeUnless { it.isNullOrBlank() }
                if (id == null || status == null) { Log.w(TAG, "$tag 200 but no id/status: $raw"); return null }
                parseRide(o!!, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$tag failed: ${e.message}"); null
        }
    }

    private fun parseDriver(o: JSONObject): Driver = Driver(
        id = o.str("user_id") ?: o.optString("id"),
        displayName = o.str("display_name"),
        vehicleMake = o.str("vehicle_make"),
        vehicleModel = o.str("vehicle_model"),
        plate = o.str("plate"),
        dashcamActive = o.optBoolean("dashcam_active", false),
        online = o.optBoolean("online", false),
        verificationStatus = o.optString("verification_status", "unverified"),
    )

    private fun parseRide(o: JSONObject, driverLoc: JSONObject?): Ride {
        val pickup = o.optJSONObject("pickup")
        val dropoff = o.optJSONObject("dropoff")
        fun dbl(v: Double) = v.takeUnless { it.isNaN() }
        return Ride(
            id = o.optString("id"),
            status = o.optString("status", "unknown"),
            fromLat = dbl(pickup?.optDouble("lat", Double.NaN) ?: Double.NaN),
            fromLon = dbl(pickup?.optDouble("lon", Double.NaN) ?: Double.NaN),
            fromLabel = pickup?.str("label"),
            toLat = dbl(dropoff?.optDouble("lat", Double.NaN) ?: Double.NaN),
            toLon = dbl(dropoff?.optDouble("lon", Double.NaN) ?: Double.NaN),
            toLabel = dropoff?.str("label"),
            fare = dbl(o.optDouble("fare_eur", Double.NaN)),
            platformFee = dbl(o.optDouble("platform_fee_eur", Double.NaN)),
            driverPayout = dbl(o.optDouble("driver_payout_eur", Double.NaN)),
            currency = "EUR",
            driverLat = dbl(driverLoc?.optDouble("lat", Double.NaN) ?: Double.NaN),
            driverLon = dbl(driverLoc?.optDouble("lon", Double.NaN) ?: Double.NaN),
            driverName = o.str("driver_user_id"),
        )
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeUnless { it.isBlank() || it == "null" }

    companion object {
        private const val TAG = "TaxiCloudClient"
    }
}
