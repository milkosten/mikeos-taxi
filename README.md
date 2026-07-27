# MikeTaxi

**The MikeOS ride-hailing app — the fair, 5%-fee alternative.** ONE native Kotlin/Jetpack
Compose app with **Driver + Client modes**. Uber takes ~20-25% of a fare; MikeTaxi takes only
**5%**, so **the driver keeps 95%**, because MikeOS pays ~€0 per ride (our own OSRM routing,
Nominatim geocoding, `tiles.osmike.com` basemap, and dashcam→MikeMaps street imagery — no
Google data). Part of the [MikeOS](https://github.com/milkosten) app fleet
(see `mikeos-architecture/docs/services/taxi.md` and `docs/APP-ANATOMY.md`).

Package `com.mikeos.taxi` · minSdk 31 · compile/target 35 · label "MikeTaxi".

## The two modes (top-bar segmented toggle, persisted)

**Client**
- A real **MapLibre** map (`tiles.osmike.com/style.json`) centered on the shared location fix.
- **Pickup** from the daemon's single shared fix ("use my location"); **drop-off** entered as
  `lat, lon` (map-pin geocoding via Nominatim is a documented TODO).
- **Fare estimate card** (`GET /api/taxi/estimate`) → distance, ETA, fare, and a prominent
  **"Driver keeps 95% — MikeTaxi only takes 5%"** line with the payout / fee split.
- **Request ride** (`POST /api/taxi/rides`) → a ride-status screen that polls
  `GET /api/taxi/rides/{id}` every 5 s for status + the driver's live position.

**Driver**
- **Registration**: vehicle make / model / plate + a **mandatory "Dashcam active" toggle**
  (`POST /api/taxi/drivers/register`; the app refuses to register without it).
- **GO ONLINE / OFFLINE** (`POST /api/taxi/drivers/status`) pushing the daemon's shared fix;
  while online the agent **keeps pushing the location every heartbeat**.
- **Rides list** with lifecycle buttons (accept → arrive → start → complete / cancel) and an
  earnings line ("you keep €19.00 of €20").
- **Dashcam capture** button — the foundation hook that will feed the **MikeMaps** street-imagery
  pipeline (stubbed / TODO).

## App Anatomy (it's an agent, not a thin UI)

Self-registers on the hive and heartbeats via the **vendored shared runtime** (`com.mikeos.core`,
`TaxiMikeAgent`). The closed loop perceives ride/driver state, and — DETERMINISTICALLY — pushes
the driver's shared location to the cloud every beat while online (never left to the LLM to pick).
Skills: `fare_estimate`, `request_ride`, `driver_status`, `my_rides` (plus the universal
location / hive_send / remember / recall / notify / ask_siblings from the runtime).

**One shared location** (APP-ANATOMY §3a): reads `GET https://127.0.0.1:7743/api/location` — it
NEVER runs its own GPS.

## Build / install

```bash
export ANDROID_HOME=/home/mikeos/android-sdk        # JDK 17
./gradlew :app:assembleDebug --no-daemon --max-workers=2
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Structure

```
app/src/main/java/com/mikeos/taxi/
  MainActivity.kt          top-bar mode toggle + Client/Driver Compose UI, Agent Inspector icon
  TaxiViewModel.kt         mode (persisted) + client/driver state machines, ride polling
  net/TaxiCloudClient.kt   mikeos-taxi-cloud client (estimate/rides/drivers, never-trust-200)
  net/DaemonLocation.kt    reads the ONE shared fix from the daemon (no own GPS)
  net/Doh.kt               DNS-over-HTTPS (flaky GApps-less DNS)
  taxi/TaxiRepository.kt   holds the hive api key; wraps cloud + location
  agent/TaxiMikeAgent.kt   the MikeAgent soul + skills + deterministic online-location push
  ui/TaxiMap.kt            MapLibre GL MapView in a Compose AndroidView (tiles.osmike.com)
  ui/theme/                MikeOS dark theme
  com/mikeos/core/         SHARED RUNTIME (vendored — do not edit here)
```
