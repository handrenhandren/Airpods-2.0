package com.example.podsbattery

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Zeigt Akkustand von linkem/rechtem AirPod und Ladecase an.
 * Die AirPods senden (Apple, Hersteller-ID 0x004C) regelmäßig ein BLE-Paket mit
 * den Akkuwerten. Das Paket wird hier nur mitgelesen, es wird nichts verbunden.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var leftView: TextView
    private lateinit var rightView: TextView
    private lateinit var caseView: TextView
    private lateinit var statusView: TextView

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bestRssi = -127
    private var bestTime = 0L
    private var lastUpdate = 0L
    private var seen = 0

    private val tick = object : Runnable {
        override fun run() {
            if (scanning && SystemClock.elapsedRealtime() - lastUpdate > 10_000) {
                statusView.text = "Keine AirPods gefunden – Case-Deckel öffnen\n(Bluetooth-Signale gesehen: $seen)"
            }
            ui.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }

        leftView = line(root, 36f, Color.WHITE, "Links  –")
        rightView = line(root, 36f, Color.WHITE, "Rechts  –")
        caseView = line(root, 36f, Color.WHITE, "Case  –")
        statusView = line(root, 14f, Color.GRAY, "")
        (statusView.layoutParams as LinearLayout.LayoutParams).topMargin = 96

        setContentView(root)
    }

    private fun line(parent: LinearLayout, size: Float, color: Int, text: String): TextView {
        val tv = TextView(this).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            this.text = text
        }
        parent.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        )
        return tv
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        if (hasPermission()) startScan() else requestPermission()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        stopScan()
    }

    private fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasPermission(): Boolean =
        checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        requestPermissions(arrayOf(permissionName()), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission()) {
            startScan()
        } else {
            statusView.text = "Berechtigung fehlt"
        }
    }

    private fun startScan() {
        if (scanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            statusView.text = "Bluetooth einschalten"
            return
        }
        val s = adapter.bluetoothLeScanner ?: return
        // Bewusst ohne Hardware-Filter: manche Geräte filtern Herstellerdaten unzuverlässig.
        // Gefiltert wird stattdessen im Callback.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(null, settings, callback)
            scanner = s
            scanning = true
            lastUpdate = SystemClock.elapsedRealtime()
            statusView.text = "Suche AirPods …"
        } catch (e: SecurityException) {
            statusView.text = "Berechtigung fehlt"
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            // ignorieren
        }
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            seen++
            val d =result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            if (d.size < 8 || d[0] != 0x07.toByte()) return

            // Bei mehreren AirPods in der Nähe: das Gerät mit dem stärksten Signal anzeigen
            val now = SystemClock.elapsedRealtime()
            if (result.rssi < bestRssi && now - bestTime < 4_000) return
            bestRssi = result.rssi
            bestTime = now
            lastUpdate = now

            show(d)
        }

        override fun onScanFailed(errorCode: Int) {
            statusView.text = "Scan-Fehler ($errorCode)"
            scanning = false
        }
    }

    private fun show(d: ByteArray) {
        val status = d[5].toInt() and 0xFF
        val pods = d[6].toInt() and 0xFF
        val misc = d[7].toInt() and 0xFF

        // Bit 5 im Statusbyte: gibt an, welcher Hörer in der oberen/unteren Nibble steht
        val flipped = (status and 0x20) == 0
        val hi = pods shr 4
        val lo = pods and 0x0F
        val left = if (flipped) hi else lo
        val right = if (flipped) lo else hi
        val case = misc and 0x0F

        val charge = misc shr 4
        val chargingLeft = (charge and (if (flipped) 0x02 else 0x01)) != 0
        val chargingRight = (charge and (if (flipped) 0x01 else 0x02)) != 0
        val chargingCase = (charge and 0x04) != 0

        leftView.text = "Links  " + format(left, chargingLeft)
        rightView.text = "Rechts  " + format(right, chargingRight)
        caseView.text = "Case  " + format(case, chargingCase)
        statusView.text = ""
    }

    // Werte 0..10 entsprechen 0..100 %, alles andere (z. B. 15) = unbekannt
    private fun format(level: Int, charging: Boolean): String =
        if (level in 0..10) "${level * 10} %" + (if (charging) " ⚡" else "") else "–"
}
