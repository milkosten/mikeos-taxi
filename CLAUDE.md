# MikeTaxi — CLAUDE.md

## What this repo is

**MikeTaxi is the MikeOS ride-hailing app-agent — the fair, 5%-fee alternative to Uber.** ONE
native Kotlin/Jetpack-Compose app with a top-level **Driver ⇄ Client** mode toggle. The whole
selling point: Uber takes ~20-25%; MikeTaxi takes **5%**, so the **driver keeps 95%**, because
MikeOS pays ~€0 per ride (our own OSRM routing, Nominatim geocoding, `tiles.osmike.com` basemap,
dashcam→MikeMaps imagery — no Google data). Every fare estimate surfaces the 95/5 split.

**Type:** MikeOS **Android app** (app-agent). applicationId / namespace **`com.mikeos.taxi`**,
versionCode **1**, versionName **0.1.0-foundation**, label **"MikeTaxi"**. minSdk 31, compile/
target 35. Authoritative spec: `mikeos-architecture/docs/services/taxi.md`.

## Build & install

```bash
export ANDROID_HOME=/home/mikeos/android-sdk        # JDK 17
./gradlew :app:assembleDebug --no-daemon --max-workers=2
adb install -r app/build/outputs/apk/debug/app-debug.apk   # OTA is the fleet-preferred path
```

## Architecture — one app, two modes

- **Mode toggle** (segmented control in the top bar, persisted in SharedPreferences) switches
  the whole surface between **Client** and **Driver**. `TaxiViewModel` holds a `ClientState` and
  a `DriverState`, each its own small state machine.
- **Client flow:** map (MapLibre) → pickup from the shared daemon fix → drop-off (lat,lon; Nominatim
  map-pin geocoding is TODO) → `fare_estimate` card with the 95/5 line → `request_ride` →
  ride-status screen polling `GET /api/taxi/rides/{id}` every 5 s (status + driver live position).
- **Driver flow:** register (make/model/plate + **mandatory dashcam toggle**) → GO ONLINE/OFFLINE
  (pushes the shared fix) → rides list with lifecycle buttons (accept→arrive→start→complete/cancel)
  and an earnings line → a **dashcam capture** hook (the MikeMaps feed point — stubbed).

## How it talks to the cloud + daemon

- **Cloud:** `mikeos-taxi-cloud` (FastAPI+Postgres, dual-auth → user_id: X-API-KEY **or** OAuth
  Bearer JWKS). Base URL is the buildConfig field `TAXI_CLOUD_BASE_URL` (default
  `https://taxi-api.osmike.com` — **self-hosted on the media box `91.98.177.242`, fronted by the
  shared Caddy with Let's Encrypt TLS**; Postgres data on the RAID6 `/data/mikeos-taxi-pg`). The
  human web UI is a separate app at `https://taxi.osmike.com` (`mikeos-taxi-web`). Client:
  `net/TaxiCloudClient.kt` — standard OkHttp (public LE cert) + DoH,
  `X-API-KEY: <hive agent key>`. Endpoints: `/api/taxi/estimate`, `/api/taxi/rides[/{id}[/accept|
  arrive|start|complete|cancel]]`, `/api/taxi/drivers/register`, `/api/taxi/drivers/status`,
  `/api/taxi/rides?role=`. **Never-trust-200**: writes verify a real id/status came back.
- **Daemon (loopback):** `net/DaemonLocation.kt` reads the **ONE shared fix** from
  `GET https://127.0.0.1:7743/api/location` via the core's loopback-trusting client. The app
  **NEVER runs its own GPS** (house rule / APP-ANATOMY §3a) — pickup and the driver location push
  both come from this fix.
- **Agent runtime:** vendored `com.mikeos.core` (`TaxiMikeAgent` installs the Soul + skills, then
  the core self-registers on the hive and heartbeats). The driver location push is DETERMINISTIC
  on the heartbeat while online (throttled 30 s) — not left to the LLM to choose.

## Map approach chosen

**Real MapLibre GL Native** (`org.maplibre.gl:android-sdk:11.8.0`) rendering our own vector style
`tiles.osmike.com/style.json` (buildConfig `MAP_STYLE_URL`), wrapped in a Compose `AndroidView`
in `ui/TaxiMap.kt`. NOT a WebView, NOT Google Maps. The APK carries `libmaplibre.so` (~100 MB
debug APK). The map lifecycle is driven off a minimal `DisposableEffect`.

## What's foundation-complete vs stubbed/TODO

**Complete & wired to the cloud endpoints:** mode toggle (persisted) · MapLibre map on real tiles ·
pickup from the shared daemon fix · fare estimate (`/api/taxi/estimate`) with the 95/5 selling-point
card · request ride + status polling · driver registration with the mandatory-dashcam gate ·
GO ONLINE/OFFLINE + per-heartbeat location push · ride lifecycle buttons · MikeAgent soul + skills +
self-registration/heartbeat via the vendored core · Agent Inspector icon.

**Stubbed / TODO (foundation hooks in place):**
- **Dashcam capture pipeline** — a "Capture street view" button is present but stubbed; the real
  frame capture + hand-off to the **MikeMaps** street-imagery pipeline is TODO.
- **Full MapLibre interactivity** — the base map renders; pickup/dropoff/driver **markers**, the
  route **polyline** (OSRM), tap-to-drop a pin, and binding to the Activity lifecycle owner are TODO.
- **Drop-off geocoding** — drop-off is entered as `lat, lon`; the Nominatim address search / map-pin
  drop is TODO.
- **Scheduled booking UX** — the cloud model supports `scheduled_for` (≥1h lead) and the client can
  pass it, but there is no date/time picker UI or reminders yet.
- **Driver incoming-request push** — the driver polls `my_rides`; a real-time request notification
  (hive/events push) is TODO.

## House rules honoured

Never own GPS (read the shared fix) · never-trust-200 (verify id/status) · numeric fields sent as
numbers (empty-string in an INTEGER column silently 422s) · **do NOT edit `com/mikeos/core/`**
(the shared vendored runtime). Bump `versionCode` on every rebuild before OTA publish.
