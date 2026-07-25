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
            text = "--- 스캔 로그 ---\n"
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
            // 스캐너 가져오기 (필요할 때 초기화)
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            startLeScan()
        } else {
            requestPermissions()
        }
    }

    private fun startLeScan() {
        if (isScanning) return
        
        // Android 12 이상 권한 체크 (컴파일러 경고 방지용)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        addLog("스캔 시작...")
        isScanning = true
        btnScan.text = "스캔 정지"
        statusText.text = "주변 BLE 기기 탐색 중..."

        // 배터리 절약을 위해 정해진 시간 후 스캔 자동 정지
        handler.postDelayed({
            stopLeScan()
        }, SCAN_PERIOD)

        // *** 핵심: Apple 기기 필터링 (에어팟 감지) ***
        // Apple, Inc.의 제조사 고유 ID는 0x004C입니다.
        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf()) 
            .build()
        
        val filters = listOf(appleFilter)

        // 스캔 설정 (낮은 지연 속도 모드 - 배터리 소모 높음, 빠른 탐색)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        // Android 10 이상에서는 화면 켜져 있을 때만 잡히도록 설정하는 것이 좋습니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
            
        // 스캔 시작
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

    // 스캔 결과 콜백
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            // Android 12 이상 권한 체크
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val device = result.device
            val deviceName = device.name ?: "알 수 없는 기기"
            val deviceAddress = device.address
            val rssi = result.rssi // 신호 세기
            
            // 제조사 데이터 가져오기 (Apple 데이터)
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C)
            val dataStr = manuData?.joinToString("") { "%02X ".format(it) } ?: "데이터 없음"

            addLog("찾음: $deviceName ($deviceAddress) RSSI: $rssi\nData: $dataStr")
            
            // TODO: 여기서 dataStr을 파싱하여 배터리 정보를 추출해야 함 (다음 단계)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("스캔 실패: 오류 코드 $errorCode")
            statusText.text = "스캔 실패"
            stopLeScan()
        }
    }

    // --- UI 로그 출력 유틸 ---
    private fun addLog(message: String) {
        Log.d(TAG, message) // Logcat에도 출력
        runOnUiThread {
            logText.append("$message\n\n")
            // 최신 로그로 자동 스크롤
            (logText.parent as ScrollView).post {
                (logText.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    // --- 권한 처리 로직 (기존과 동일) ---

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
 
