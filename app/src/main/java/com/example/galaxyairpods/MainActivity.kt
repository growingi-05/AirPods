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
    private val SCAN_PERIOD: Long = 10000 

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // 내 폰에 등록된 페어링 기기 MAC 주소 목록
    private val bondedMacAddresses = mutableSetOf<String>()

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnScan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val titleText = TextView(this).apply {
            text = "Galaxy AirPods Controller"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "페어링된 기기 MAC 주소를 조회합니다."
            textSize = 15f
            setPadding(0, 0, 0, 20)
        }

        btnScan = Button(this).apply {
            text = "등록 기기 매칭 스캔 시작"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 스캔 버튼을 누르면 내 페어링 기기를 추적합니다."
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
            setPadding(0, 30, 0, 0)
        }

        logText = TextView(this).apply {
            text = "--- 등록 기기 및 BLE 로그 ---\n"
            textSize = 12f
            setBackgroundColor(0x11000000)
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
            statusText.text = "오류: 이 기기는 블루투스를 지원하지 않습니다."
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
            
            // 1. 등록된 페어링 기기 MAC 주소 전체 추출
            loadBondedDevices()
            
            // 2. BLE 스캔 시작
            startLeScan()
        } else {
            requestPermissions()
        }
    }

    private fun loadBondedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        bondedMacAddresses.clear()
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        
        addLog("📱 [내 갤럭시 등록 기기 목록]")
        if (!pairedDevices.isNullOrEmpty()) {
            for (device in pairedDevices) {
                val deviceName = device.name ?: "이름 없음"
                val deviceAddress = device.address
                bondedMacAddresses.add(deviceAddress)
                addLog(" • $deviceName ($deviceAddress)")
            }
        } else {
            addLog(" 등록된 블루투스 기기가 없습니다.")
        }
        addLog("----------------------------------\n")
    }

    private fun startLeScan() {
        if (isScanning) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("스캔 시작... (내 페어링 MAC 주소 대조 중)")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "등록된 기기 신호 탐색 중..."

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
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("스캔 정지.")
        isScanning = false
        btnScan.text = "등록 기기 매칭 스캔 시작"
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
            val deviceAddress = result.device.address
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            val dataHex = manuData.joinToString("") { "%02X ".format(it) }

            // ★ 핵심: 현재 수신된 BLE 기기의 MAC 주소가 내 페어링 목록에 있는지 대조
            val isMyDevice = bondedMacAddresses.contains(deviceAddress)

            if (isMyDevice) {
                addLog("🎯 [내 페어링 기기 신호 감지!]\nMAC: $deviceAddress | RSSI: $rssi dBm\nData: $dataHex")
                
                runOnUiThread {
                    batteryResultText.text = "🎯 내 등록 기기 수신 성공!\nMAC 주소: $deviceAddress\n신호 세기: $rssi dBm"
                }
            } else {
                // 주변 다른 Apple 기기 신호
                addLog("🍏 [기타 Apple 신호]\nMAC: $deviceAddress | RSSI: $rssi dBm")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: 오류 코드 $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
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
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                statusText.text = "권한 허용됨. 스캔 가능."
            } else {
                statusText.text = "권한 거부됨. 스캔 불가."
                Toast.makeText(this, "스캔을 위해 블루투스 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) {
            stopLeScan()
        }
    }
}
