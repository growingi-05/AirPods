package com.example.galaxyairpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsGatt"
    private val PERMISSION_REQUEST_CODE = 1001

    // 표준 배터리 서비스 및 특성 UUID
    private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "AirPods GATT Connect Client"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "에어팟 뚜껑을 연 후 연결 버튼을 눌러주세요."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnConnect = Button(this).apply {
            text = "에어팟 탐색 및 GATT 직접 연결"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 GATT 연결 대기 중..."
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
            text = "--- GATT 통신 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnConnect)
        rootLayout.addView(batteryResultText)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener {
            if (checkPermissions()) {
                startScanForGattConnect()
            } else {
                requestPermissions()
            }
        }
    }

    private fun startScanForGattConnect() {
        if (isScanning) return

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            statusText.text = "블루투스를 켜주세요."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("🔍 [1/3] 에어팟 탐색 시작 (신호 세기 -75 dBm 이상 대상)...")
        isScanning = true
        statusText.text = "연결할 에어팟 신호 탐색 중..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val device = result.device
            val rssi = result.rssi
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C)

            // 근접 Apple 기기 발견 시 스캔 중단 후 GATT 연결 시도
            if (manuData != null && rssi > -75) {
                stopScan()
                connectToDeviceGatt(device)
            }
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        isScanning = false
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connectToDeviceGatt(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val deviceName = device.name ?: device.address
        addLog("🔗 [2/3] $deviceName 기기에 GATT Direct Connect 시도...")
        statusText.text = "GATT 연결 시도 중..."

        // GATT 연결 시작 (autoConnect = false)
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    // GATT 통신 상태 및 이벤트를 수신하는 콜백 객체
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addLog("✅ GATT 서버 연결 성공! 서비스 목록 탐색 시작...")
                runOnUiThread { statusText.text = "GATT 연결됨. 서비스 탐색 중..." }
                
                // 연결 성공 시 기기의 GATT 서비스 목록 탐색 요청
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("❌ GATT 연결 해제됨 (Status: $status)")
                runOnUiThread {
                    statusText.text = "GATT 연결 해제됨"
                    batteryResultText.text = "🎧 GATT 연결이 끊어졌습니다."
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("🎉 [3/3] GATT 서비스 탐색 완료!")
                
                var batteryCharFound = false

                // 기기가 보유한 모든 GATT Service 순회
                for (service in gatt.services) {
                    addLog(" 📦 Service UUID: ${service.uuid}")

                    for (characteristic in service.characteristics) {
                        addLog("   └ Char UUID: ${characteristic.uuid}")

                        // 표준 BLE 배터리 서비스 특성이 존재하는지 체크
                        if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                            batteryCharFound = true
                            addLog("   ★ [배터리 특성 발견!] 값 읽기 요청...")
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                return
                            }
                            gatt.readCharacteristic(characteristic)
                        }
                    }
                }

                if (!batteryCharFound) {
                    addLog("⚠️ 표준 배터리 특성 없음 (Apple 사유 특성 목록 수신 완료)")
                    runOnUiThread {
                        batteryResultText.text = "🎧 GATT 연결 성공!\n(기기가 제공하는 특성 목록을 로그에서 확인하세요)"
                    }
                }
            } else {
                addLog("서비스 탐색 실패: $status")
            }
        }

        // 특성 값 읽기 결과 콜백
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                    val batteryLevel = characteristic.value[0].toInt() and 0xFF
                    addLog("🎉 [GATT 배터리 수치 수신 성공!] -> $batteryLevel%")
                    
                    runOnUiThread {
                        batteryResultText.text = "🎉 [GATT 배터리 수신 성공!]\n\n• 배터리 잔량: $batteryLevel%\n(GATT Client Direct Read)"
                    }
                }
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
