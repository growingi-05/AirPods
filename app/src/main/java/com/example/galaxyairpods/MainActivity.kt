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

    // 배터리 절약을 위한 스캔 제한 시간 (10초)
    private val SCAN_PERIOD: Long = 10000 

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // UI 요소
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnScan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- UI 구성 ---
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val titleText = TextView(this).apply {
            text = "AirPods BLE Scanner"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 30)
        }

        statusText = TextView(this).apply {
            text = "블루투스 상태 확인 중..."
            textSize = 16f
        }

        btnScan = Button(this).apply {
            text = "스캔 시작"
            setPadding(0, 30, 0, 30)
        }

        // 로그를 보여줄 스크롤 뷰
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
            setPadding(0, 30, 0, 0)
        }

        logText = TextView(this).apply {
            text = "--- 에어팟 신호 로그 ---\n"
            textSize = 13f
            setBackgroundColor(0x11000000) // 연한 회색 배경
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnScan)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)
        // --- UI 구성 끝 ---

        // 1. 블루투스 어댑터 초기화
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "오류: 이 기기는 블루투스를 지원하지 않습니다."
            btnScan.isEnabled = false
            return
        }

        // 2. 스캔 버튼 클릭 리스너
        btnScan.setOnClickListener {
            if (isScanning) {
                stopLeScan()
            } else {
                startLeScanWithPermissionCheck()
            }
        }
    }

    // --- BLE 스캔 로직 ---

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

        addLog("스캔 시작 (근처 에어팟 탐색 중...)")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "주변 에어팟 탐색 중..."

        // 10초 후 스캔 자동 정지
        handler.postDelayed({
            stopLeScan()
        }, SCAN_PERIOD)

        // Apple 제조사 ID (0x004C) 필터링
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
        btnScan.text = "스캔 시작"
        statusText.text = "스캔이 정지되었습니다."
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    // 스캔 결과 콜백 (근처 기기 및 에어팟 패킷 크기 필터링 적용)
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val rssi = result.rssi
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

            // [필터 1] 신호 세기: 바로 근처 기기만 감지 (-70 dBm 이상)
            if (rssi < -70) return

            // [필터 2] 데이터 길이: 에어팟 패킷 규격 (27바이트) 필터링
            if (manuData.size == 27) {
                val deviceAddress = result.device.address
                val dataStr = manuData.joinToString("") { "%02X ".format(it) }

                addLog("🎧 [에어팟 추정 기기 발견!]\n주소: $deviceAddress | RSSI: $rssi dBm\n데이터: $dataStr")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: 오류 코드 $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
        }
    }

    // UI 로그 출력 유틸
    private fun addLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logText.append("$message\n\n")
            (logText.parent as? ScrollView)?.post {
                (logText.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    // 권한 처리
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
                statusText.text = "권한이 거부되었습니다. 스캔 불가."
                Toast.makeText(this, "BLE 스캔을 위해 블루투스 및 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 앱이 화면에서 사라지면 스캔 정지 (배터리 아끼기)
        if (isScanning) {
            stopLeScan()
        }
    }
}
 
