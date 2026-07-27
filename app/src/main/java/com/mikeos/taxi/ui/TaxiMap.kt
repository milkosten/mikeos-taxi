package com.mikeos.taxi.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mikeos.taxi.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The REAL basemap for both modes: a MapLibre GL Native [MapView] rendering our own vector
 * style (`tiles.osmike.com/style.json` via [BuildConfig.MAP_STYLE_URL]) — NOT a WebView, NOT
 * Google Maps (ecosystem rule). Wrapped in a Compose [AndroidView].
 *
 * MapLibre needs a one-time [MapLibre.getInstance] before any MapView is created (no API key —
 * our tiles are open). The [MapView] lifecycle (onStart/onResume/…) is driven off Compose's
 * DisposableEffect; that's enough for a foundation surface. Markers/route polylines and full
 * lifecycle-owner binding are a documented TODO on top of this.
 *
 * @param centerLat/centerLon where to center the camera (e.g. the daemon's shared fix).
 * @param zoom initial zoom (13–15 is a good city-street level).
 */
@Composable
fun TaxiMap(
    centerLat: Double?,
    centerLon: Double?,
    modifier: Modifier = Modifier,
    zoom: Double = 13.5,
) {
    val context = LocalContext.current

    // One-time SDK init. Idempotent — getInstance may be called repeatedly.
    remember {
        runCatching { MapLibre.getInstance(context.applicationContext) }
            .onFailure { Log.w("TaxiMap", "MapLibre init failed: ${it.message}") }
        true
    }

    val mapView = remember {
        MapView(context).apply {
            runCatching {
                getMapAsync { map -> applyStyleAndCamera(map, centerLat, centerLon, zoom) }
            }.onFailure { Log.w("TaxiMap", "getMapAsync failed: ${it.message}") }
        }
    }

    // Drive the MapView through a minimal lifecycle so it renders and releases cleanly.
    DisposableEffect(Unit) {
        runCatching { mapView.onStart(); mapView.onResume() }
        onDispose {
            runCatching {
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun applyStyleAndCamera(
    map: MapLibreMap,
    lat: Double?,
    lon: Double?,
    zoom: Double,
) {
    runCatching {
        map.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL)) {
            if (lat != null && lon != null) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .build()
            }
        }
    }.onFailure { Log.w("TaxiMap", "setStyle failed: ${it.message}") }
}
