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

    private val TAG = "AirPodsFilter"
    private val PERMISSION_REQUEST_CODE = 1001

    // 여유 있는 RSSI 기준 (-82 dBm 이상 수신)
    private val RSSI_THRESHOLD = -82

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var connectedDeviceName: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnManualScan: Button

    // 1. 오디오 페어링/연결 이벤트 리시버
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
                    addLog("🎯 페어링 감지 조건 충족 -> BLE 패킷 탐색 시작 ($RSSI_THRESHOLD dBm 이상)")
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
            text = "AirPods Smart Battery Monitor"
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
            text = "🎧 에어팟 연결 대기 중...\n(귀에 꽂거나 뚜껑을 열어주세요)"
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
            text = "--- 스마트 필터링 로그 ---\n"
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

        // 블루투스 연결/해제 이벤트 등록
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(connectionReceiver, filter)

        if (checkPermissions()) {
            addLog("📡 연결 리스너 활성화 완료 (RSSI 기준: $RSSI_THRESHOLD dBm)")
            startTargetedBleScan() // 앱 실행 시 1회 자동 스캔
        } else {
            requestPermissions()
        }
    }

    // 타겟팅 BLE 스캔 실행 (6초간 수행)
    private fun startTargetedBleScan() {
        if (isScanning) return

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("🔍 [BLE 타겟 스캔 시작] 애플 0x004C 패킷 탐색...")
        isScanning = true
        statusText.text = "배터리 신호 수신 중..."

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopBleScan() }, 6000)

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

            // 1차 필터: 느슨해진 RSSI 감도 기준 (-82 dBm 이상만 허용)
            if (rssi < RSSI_THRESHOLD) return

            // 2차 필터: 0x07 TLV 배터리 패킷 해독
            val batteryInfo = parseAppleBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr) = batteryInfo
                val displayName = connectedDeviceName ?: "내 에어팟"

                runOnUiThread {
                    batteryResultText.text = """
                        🎉 [$displayName 배터리 수신!]
                        
                        • 왼쪽 (L): $leftStr
                        • 오른쪽 (R): $rightStr
                        • 충전 케이스: $caseStr
                        
                        (신호 감도: $rssi dBm)
                    """.trimIndent()
                }

                addLog("✅ [배터리 포착 성공] $displayName | L:$leftStr, R:$rightStr, Case:$caseStr ($rssi dBm)")
                stopBleScan()
            }
        }
    }

    // Apple TLV 패킷 (0x07 타입) 해독 알고리즘
    private fun parseAppleBatteryData(data: ByteArray): Triple<String, String, String>? {
        try {
            var i = 0
            while (i < data.size - 5) {
                if (data[i] == 0x07.toByte()) {
                    val subLen = data[i + 1].toInt() and 0xFF
                    if (i + 5 < data.size) {
                        val statusByte = data[i + 3].toInt() and 0xFF
                        val earbudByte = data[i + 4].toInt() and 0xFF
                        val caseByte = data[i + 5].toInt() and 0xFF

                        val rawLeft = (earbudByte and 0xF0) ushr 4
                        val rawRight = earbudByte and 0x0F
                        val rawCase = (caseByte and 0xF0) ushr 4

                        val isFlipped = (statusByte and 0x20) != 0
                        val leftVal = if (isFlipped) rawRight else rawLeft
                        val rightVal = if (isFlipped) rawLeft else rawRight

                        val leftStr = formatBattery(leftVal)
                        val rightStr = formatBattery(rightVal)
                        val caseStr = formatBattery(rawCase)

                        if (leftVal in 0..10 || rightVal in 0..10 || rawCase in 0..10) {
                            return Triple(leftStr, rightStr, caseStr)
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

    private fun formatBattery(valRaw: Int): String {
        return when (valRaw) {
            in 0..10 -> "${valRaw * 10}%"
            15 -> "케이스 안 / 미연결"
            else -> "알 수 없음"
        }
    }

    private fun getDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "내 에어팟"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "내 에어팟"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            device.alias ?: device.name ?: "내 에어팟"
        } else {
            device.name ?: "내 에어팟"
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
