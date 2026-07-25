package com.example.galaxyairpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsBLEController"
    private val PERMISSION_REQUEST_CODE = 1001
    private val SCAN_PERIOD: Long = 15000 // 15초 스캔

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnScan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "Galaxy AirPods BLE Scanner"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "에어팟 뚜껑을 닫았다가 스캔 시작 후 열어주세요."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnScan = Button(this).apply {
            text = "BLE 배터리 스캔 시작"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 에어팟 배터리 패킷 대기 중..."
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
            text = "--- BLE 감지 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnScan)
        rootLayout.addView(batteryResultText)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnScan.setOnClickListener {
            if (isScanning) {
                stopLeScan()
            } else {
                startLeScanWithPermissionCheck()
            }
        }
    }

    private fun startLeScanWithPermissionCheck() {
        if (checkPermissions()) {
            if (bluetoothAdapter?.isEnabled == false) {
                Toast.makeText(this, "블루투스를 켜주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            startLeScan()
        } else {
            requestPermissions()
        }
    }

    private fun startLeScan() {
        if (isScanning) return

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            statusText.text = "블루투스 스캐너 준비 실패"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("🔍 [BLE 스캔 시작] 에어팟 뚜껑을 열어주세요!")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "에어팟 배터리 데이터 감지 중..."

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopLeScan() }, SCAN_PERIOD)

        // Apple (0x004C) 제조사 필터
        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(appleFilter), settings, scanCallback)
    }

    private fun stopLeScan() {
        if (!isScanning) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("⏹ 스캔 정지.")
        isScanning = false
        btnScan.text = "BLE 배터리 스캔 시작"
        statusText.text = "스캔 완료"
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val rssi = result.rssi
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

            // 근접 기기 기준 (-75 dBm 이상)
            if (rssi < -75) return

            // TLV 패킷 구조 해석
            val batteryResult = parseAirPodsBatteryData(manuData)
            if (batteryResult != null) {
                val (leftStr, rightStr, caseStr) = batteryResult
                val displayText = """
                    🎉 [에어팟 배터리 포착 성공!]
                    
                    • 왼쪽 유닛 (L): $leftStr
                    • 오른쪽 유닛 (R): $rightStr
                    • 충전 케이스: $caseStr
                    
                    (신호 세기: $rssi dBm)
                """.trimIndent()

                runOnUiThread {
                    batteryResultText.text = displayText
                }
                addLog("✅ [배터리 감지!] L: $leftStr | R: $rightStr | Case: $caseStr ($rssi dBm)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
        }
    }

    // --- Apple 동적 TLV 배터리 해독 알고리즘 ---
    private fun parseAirPodsBatteryData(data: ByteArray): Triple<String, String, String>? {
        try {
            var i = 0
            while (i < data.size - 5) {
                // 서브 패킷 타입 0x07 (Proximity / Battery) 탐색
                if (data[i] == 0x07.toByte()) {
                    val subLen = data[i + 1].toInt() and 0xFF

                    // 배터리가 포함된 위치 계산
                    if (i + 5 < data.size) {
                        val statusByte = data[i + 3].toInt() and 0xFF
                        val earbudByte = data[i + 4].toInt() and 0xFF
                        val caseByte = data[i + 5].toInt() and 0xFF

                        val rawLeft = (earbudByte and 0xF0) ushr 4
                        val rawRight = earbudByte and 0x0F
                        val rawCase = (caseByte and 0xF0) ushr 4

                        // L/R 반전(Flip) 비트 처리
                        val isFlipped = (statusByte and 0x20) != 0
                        val leftVal = if (isFlipped) rawRight else rawLeft
                        val rightVal = if (isFlipped) rawLeft else rawRight

                        val leftStr = formatBattery(leftVal)
                        val rightStr = formatBattery(rightVal)
                        val caseStr = formatBattery(rawCase)

                        // 0~10 (0~100%) 범위 내의 유효 수치가 있다면 성공 반환
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
            15 -> "미연결 / 케이스 내부"
            else -> "알 수 없음 ($valRaw)"
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLeScan()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) stopLeScan()
    }
}
