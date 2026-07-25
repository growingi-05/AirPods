package com.example.galaxyairpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsProFix"
    private val PERMISSION_REQUEST_CODE = 1001
    private val RSSI_THRESHOLD = -85

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var connectedDeviceName: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnManualScan: Button

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    connectedDeviceName = getDeviceName(device)
                    addLog("⚡ [기기 연결 완료] $connectedDeviceName")
                    startTargetedBleScan()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val name = getDeviceName(device)
                    addLog("🔌 [기기 연결 해제] $name")
                    connectedDeviceName = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "AirPods Pro Battery Monitor"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "에어팟 연결 시 자동으로 배터리를 탐색합니다."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnManualScan = Button(this).apply {
            text = "수동 배터리 탐색"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 에어팟 연결 대기 중..."
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFFE8F0FE.toInt())
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
            setPadding(0, 20, 0, 0)
        }

        logText = TextView(this).apply {
            text = "--- 필터링 해독 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnManualScan)
        rootLayout.addView(batteryResultText)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnManualScan.setOnClickListener {
            if (checkPermissions()) {
                addLog("👆 수동 탐색 요청")
                startTargetedBleScan()
            } else {
                requestPermissions()
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(connectionReceiver, filter)

        if (checkPermissions()) {
            addLog("📡 모니터 활성화 (RSSI: $RSSI_THRESHOLD dBm)")
            startTargetedBleScan()
        } else {
            requestPermissions()
        }
    }

    private fun startTargetedBleScan() {
        if (isScanning) return

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("🔍 [BLE 탐색 시작]")
        isScanning = true
        statusText.text = "배터리 신호 탐색 중..."

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopBleScan() }, 8000) // 8초 스캔

        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(appleFilter), settings, scanCallback)
    }

    private fun stopBleScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        isScanning = false
        statusText.text = "감지 대기 중"
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val rssi = result.rssi
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

            if (rssi < RSSI_THRESHOLD) return

            val batteryInfo = parseAirPodsBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex, modelId) = batteryInfo
                val displayName = connectedDeviceName ?: "더혀니의 AirPods Pro"

                runOnUiThread {
                    batteryResultText.text = """
                        🎉 [$displayName 배터리 정보]
                        
                        • 왼쪽 (L): $leftStr
                        • 오른쪽 (R): $rightStr
                        • 충전 케이스: $caseStr
                        
                        (Model: 0x${"%02X".format(modelId)} | $rssi dBm)
                    """.trimIndent()
                }

                addLog("✅ [포착 성공] Model:0x${"%02X".format(modelId)} | L:$leftStr | R:$rightStr | Case:$caseStr ($rssi dBm)")
                addLog("  └ Hex: $rawHex")
                stopBleScan()
            }
        }
    }

    private fun parseAirPodsBatteryData(data: ByteArray): Quintuple<String, String, String, String, Int>? {
        try {
            var i = 0
            while (i < data.size - 5) {
                if (data[i] == 0x07.toByte()) {
                    val subLen = data[i + 1].toInt() and 0xFF
                    val modelId = data[i + 2].toInt() and 0xFF

                    // Model ID가 0x06 (암호화/연결유지용 비콘)인 경우 배터리 패킷이 아니므로 무시
                    if (modelId == 0x06) {
                        i += if (subLen > 0) subLen + 2 else 1
                        continue
                    }

                    if (i + 5 < data.size) {
                        val statusByte = data[i + 3].toInt() and 0xFF
                        val earbudByte = data[i + 4].toInt() and 0xFF
                        val caseByte = data[i + 5].toInt() and 0xFF

                        val rawLeft = (earbudByte and 0xF0) ushr 4
                        val rawRight = earbudByte and 0x0F
                        val rawCase = (caseByte and 0xF0) ushr 4

                        val chargeStatus = caseByte and 0x0F
                        val isLeftCharging = (chargeStatus and 0x01) != 0
                        val isRightCharging = (chargeStatus and 0x02) != 0
                        val isCaseCharging = (chargeStatus and 0x04) != 0

                        val isFlipped = (statusByte and 0x20) != 0
                        val leftVal = if (isFlipped) rawRight else rawLeft
                        val rightVal = if (isFlipped) rawLeft else rawRight

                        val leftCharging = if (isFlipped) isRightCharging else isLeftCharging
                        val rightCharging = if (isFlipped) isLeftCharging else isRightCharging

                        val leftStr = formatBatteryWithCharge(leftVal, leftCharging)
                        val rightStr = formatBatteryWithCharge(rightVal, rightCharging)
                        val caseStr = formatBatteryWithCharge(rawCase, isCaseCharging)

                        val endIdx = minOf(i + 2 + subLen, data.size)
                        val rawHex = data.copyOfRange(i, endIdx).joinToString(" ") { "%02X".format(it) }

                        // 0~10 범위 수치가 포함된 패킷만 반환
                        if (leftVal in 0..10 || rightVal in 0..10 || rawCase in 0..10) {
                            return Quintuple(leftStr, rightStr, caseStr, rawHex, modelId)
                        }
                    }
                    i += if (subLen > 0) subLen + 2 else 1
                } else {
                    val subLen = data[i + 1].toInt() and 0xFF
                    i += if (subLen > 0) subLen + 2 else 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
        }
        return null
    }

    private fun formatBatteryWithCharge(valRaw: Int, isCharging: Boolean): String {
        val chargeSymbol = if (isCharging) " ⚡(충전 중)" else ""
        return when (valRaw) {
            in 0..10 -> "${valRaw * 10}%$chargeSymbol"
            15 -> "미연결/수면"
            else -> "알 수 없음 ($valRaw)"
        }
    }

    private fun getDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "더혀니의 AirPods Pro"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "더혀니의 AirPods Pro"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            device.alias ?: device.name ?: "더혀니의 AirPods Pro"
        } else {
            device.name ?: "더혀니의 AirPods Pro"
        }
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logText.append("$message\n")
            (logText.parent as? ScrollView)?.post {
                (logText.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Unregister error", e)
        }
    }
}

data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
