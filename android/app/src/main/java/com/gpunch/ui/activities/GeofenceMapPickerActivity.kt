package com.gpunch.ui.activities

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GeofenceMapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_RADIUS = "extra_radius"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 11.0168)
        val lng = intent.getDoubleExtra(EXTRA_LONGITUDE, 76.9558)
        val radius = intent.getIntExtra(EXTRA_RADIUS, 100)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "Tap the map to move the pin, then confirm."
            setPadding(24, 20, 24, 20)
            textSize = 14f
        }
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(MapBridge(), "GPunchPicker")
        }
        root.addView(title)
        root.addView(webView)
        setContentView(root)

        webView.loadDataWithBaseURL(
            "https://leafletjs.com/",
            pickerHtml(lat, lng, radius),
            "text/html",
            "UTF-8",
            null
        )
    }

    inner class MapBridge {
        @JavascriptInterface
        fun select(latitude: Double, longitude: Double) {
            runOnUiThread {
                setResult(
                    Activity.RESULT_OK,
                    intent.putExtra(EXTRA_LATITUDE, latitude)
                        .putExtra(EXTRA_LONGITUDE, longitude)
                )
                finish()
            }
        }
    }

    private fun pickerHtml(lat: Double, lng: Double, radius: Int) = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body, #map { height: 100%; margin: 0; }
            .confirm {
              position: fixed; left: 16px; right: 16px; bottom: 18px; z-index: 1000;
              border: 0; border-radius: 8px; padding: 14px; background: #1A73E8;
              color: white; font: 600 15px sans-serif;
            }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <button class="confirm" onclick="GPunchPicker.select(pin.getLatLng().lat, pin.getLatLng().lng)">Use selected pin</button>
          <script>
            const start = [$lat, $lng];
            const map = L.map('map').setView(start, 17);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
              maxZoom: 19,
              attribution: '&copy; OpenStreetMap'
            }).addTo(map);
            const pin = L.marker(start, { draggable: true }).addTo(map);
            let circle = L.circle(start, { radius: $radius, color: '#1A73E8', fillColor: '#1A73E8', fillOpacity: 0.12 }).addTo(map);
            function move(latlng) {
              pin.setLatLng(latlng);
              circle.setLatLng(latlng);
            }
            map.on('click', e => move(e.latlng));
            pin.on('drag', e => circle.setLatLng(e.target.getLatLng()));
          </script>
        </body>
        </html>
    """.trimIndent()
}
