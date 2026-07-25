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
            text = "AirPods Pro 3 Controller"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "스캔 시작 버튼을 누른 후 뚜껑을 열어주세요."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnScan = Button(this).apply {
            text = "스캔 시작"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 근처 에어팟 신호를 대기 중입니다..."
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
            text = "--- 감지 로그 ---\n"
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

        addLog("▶ 스캔 시작! (지금 에어팟 뚜껑을 열어주세요)")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "근처 에어팟 신호 수신 중..."

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
        btnScan.text = "스캔 시작"
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

            // 근처 기기 (RSSI -70 dBm 이상) 신호만 대조
            if (rssi > -70) {
                val hexStr = manuData.joinToString(" ") { "%02X".format(it) }
                addLog("⚡ [근접 Apple 신호!] RSSI: $rssi | Hex: $hexStr")

                parseBatteryOrSignal(manuData, rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
        }
    }

    private fun parseBatteryOrSignal(data: ByteArray, rssi: Int) {
        try {
            var startIdx = -1
            for (i in 0 until data.size - 2) {
                if (data[i] == 0x07.toByte()) {
                    startIdx = i
                    break
                }
            }

            if (startIdx != -1 && data.size >= startIdx + 6) {
                // 0x07 배터리 패킷 포착 시
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

                runOnUiThread {
                    batteryResultText.text = "🎉 [에어팟 프로3 배터리 해독!]\n• L: $leftStr | R: $rightStr | Case: $caseStr\n(신호 강도: $rssi dBm)"
                }
            } else {
                // 0x07 패킷은 아니지만 바로 옆에서 강력한 Apple 신호(0x12, 0x10 등)가 들어올 때
                runOnUiThread {
                    batteryResultText.text = "📡 바로 옆 에어팟 신호 수신 중...\n(뚜껑을 닫았다가 다시 열면 배터리가 표시됩니다!)"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error", e)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {}

    override fun onPause() {
        super.onPause()
        if (isScanning) stopLeScan()
    }
}
