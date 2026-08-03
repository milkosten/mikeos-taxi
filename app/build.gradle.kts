plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mikeos.taxi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mikeos.taxi"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.0-foundation"

        // MikeDaemon runs ON the phone (loopback). Auth token is pinned for dev.
        // We read the ONE shared location fix from GET /api/location here (never our own GPS).
        buildConfigField("String", "DAEMON_BASE_URL", "\"https://127.0.0.1:7743\"")
        buildConfigField(
            "String",
            "DAEMON_TOKEN",
            "\"7bdc23451b18b5801036f992b66a872670975d19\""
        )

        // mikeos-taxi-cloud: the 5%-fee ride-hailing backend (FastAPI+Postgres,
        // dual-auth → user_id). X-API-KEY = this app's hive agent key. Self-hosted on the
        // media box (91.98.177.242) at the API host taxi-api.osmike.com (Caddy/Let's Encrypt).
        // NOTE: taxi.osmike.com is the human web UI; the API lives at taxi-api.osmike.com.
        buildConfigField(
            "String",
            "TAXI_CLOUD_BASE_URL",
            "\"https://taxi-api.osmike.com\""
        )

        // MikeOS basemap: MapLibre GL style served from our own tile server (no Google).
        buildConfigField(
            "String",
            "MAP_STYLE_URL",
            "\"https://tiles.osmike.com/style.json\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Background heartbeat
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DNS-over-HTTPS: resolve cloud hostnames via Cloudflare even when the phone's
    // system DNS is broken (this GApps-less ROM / flaky cellular fails getaddrinfo).
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading (Coil downsamples to the display size, so it never loads a full-res
    // image into RAM — memory rule). Used for driver-avatar / dashcam-preview surfaces.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // MapLibre GL Native — the basemap for both Client and Driver modes. Pointed at our
    // own vector style (tiles.osmike.com/style.json) via MAP_STYLE_URL — no Google Maps.
    // Wrapped in a Compose AndroidView (see ui/MapView.kt).
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // MikeAgent runtime (vendored source under com.mikeos.core.*) — Room-backed soul memory.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
