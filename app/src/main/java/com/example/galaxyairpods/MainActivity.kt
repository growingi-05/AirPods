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

    private val TAG = "AirPodsScanner"
    private val PERMISSION_REQUEST_CODE = 1001
    private val SCAN_PERIOD: Long = 15000 

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
            text = "AirPods Pro 3 Debugger"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "에어팟 뚜껑을 열고 스캔을 시작하세요."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnScan = Button(this).apply {
            text = "디버그 스캔 시작"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 패킷 분석 대기 중..."
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(30, 30, 30, 30)
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
            text = "--- 디버그 로그 ---\n"
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

        if (bluetoothAdapter == null) {
            statusText.text = "오류: 블루투스 미지원 기기"
            btnScan.isEnabled = false
            return
        }

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
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            startLeScan()
        } else {
            requestPermissions()
        }
    }

    private fun startLeScan() {
        if (isScanning) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("▶ 스캔 시작! 에어팟 뚜껑을 열어주세요.")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "패킷 분석 중..."

        handler.postDelayed({
            stopLeScan()
        }, SCAN_PERIOD)

        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf()) 
            .build()
        
        val filters = listOf(appleFilter)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
            
        bluetoothLeScanner?.startScan(filters, settings.build(), scanCallback)
    }

    private fun stopLeScan() {
        if (!isScanning) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("⏹ 스캔 정지.")
        isScanning = false
        btnScan.text = "디버그 스캔 시작"
        statusText.text = "스캔 완료"
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val rssi = result.rssi
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            val hexStr = manuData.joinToString(" ") { "%02X".format(it) }

            // 수신된 패킷을 화면에 전부 출력하여 확인
            addLog("RX (RSSI:$rssi): $hexStr")

            parseAndShowBattery(manuData, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
        }
    }

    private fun parseAndShowBattery(data: ByteArray, rssi: Int) {
        try {
            var startIdx = -1
            for (i in 0 until data.size - 2) {
                // 0x07 패킷 탐색
                if (data[i] == 0x07.toByte()) {
                    startIdx = i
                    break
                }
            }

            if (startIdx != -1 && data.size >= startIdx + 6) {
                val statusByte = data[startIdx + 3].toInt() and 0xFF
                val earbudByte = data[startIdx + 4].toInt() and 0xFF
                val caseByte = data[startIdx + 5].toInt() and 0xFF

                val rawLeft = (earbudByte and 0xF0) shr 4
                val rawRight = earbudByte and 0x0F
                val rawCase = (caseByte and 0xF0) shr 4

                val isFlipped = (statusByte and 0x20) != 0
                val leftVal = if (isFlipped) rawRight else rawLeft
                val rightVal = if (isFlipped) rawLeft else rawRight

                val leftStr = formatBattery(leftVal)
                val rightStr = formatBattery(rightVal)
                val caseStr = formatBattery(rawCase)

                val resultMsg = """
                    🎉 [배터리 해독 성공!]
                    • L: $leftStr | R: $rightStr | Case: $caseStr
                    • Raw Bytes: L=$rawLeft, R=$rawRight, Case=$rawCase (RSSI: $rssi)
                """.trimIndent()

                runOnUiThread {
                    batteryResultText.text = resultMsg
                }
                addLog(">>> 성공! L:$leftStr R:$rightStr Case:$caseStr")
            }
        } catch (e: Exception) {
            addLog("파싱 오류: ${e.message}")
        }
    }

    private fun formatBattery(valRaw: Int): String {
        return when (valRaw) {
            in 0..10 -> "${valRaw * 10}%"
            15 -> "미연결/케이스 안"
            else -> "$valRaw"
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
        // 생략 가능
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) stopLeScan()
    }
}
