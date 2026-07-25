package com.example.galaxyairpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
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
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsDiagnostic"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isConnected = false
    private var connectedDeviceName: String = "더혀니의 AirPods Pro"
    private var totalPacketCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private var batteryUpdateRunnable: Runnable? = null

    private data class PacketRecord(val timestamp: Long, val data: ByteArray, val rssi: Int)
    private val packetQueue = ConcurrentLinkedQueue<PacketRecord>()

    private lateinit var topResultCard: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnManualSearch: Button

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
                    isConnected = true
                    connectedDeviceName = getDeviceName(device)
                    addLog("⚡ [기기 연결 감지] $connectedDeviceName")
                    start1SecondBatteryPolling()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    isConnected = false
                    addLog("🔌 [기기 연결 해제]")
                    stop1SecondBatteryPolling()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "AirPods Pro Monitor"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }

        topResultCard = TextView(this).apply {
            text = "🎧 에어팟 뚜껑을 열거나 연결해 주세요..."
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(35, 35, 35, 35)
            setBackgroundColor(0xFFE8F0FE.toInt())
        }

        statusText = TextView(this).apply {
            text = "스캔 준비 중"
            textSize = 13f
            setPadding(0, 15, 0, 10)
        }

        btnManualSearch = Button(this).apply {
            text = "큐 데이터 수동 해독"
            setPadding(0, 20, 0, 20)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
            setPadding(0, 15, 0, 0)
        }

        logText = TextView(this).apply {
            text = "--- 진단 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(topResultCard)
        rootLayout.addView(statusText)
        rootLayout.addView(btnManualSearch)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnManualSearch.setOnClickListener {
            if (checkPermissions()) {
                addLog("👆 수동 큐 해독 요청 (현재 큐 크기: ${packetQueue.size})")
                searchBatteryFromQueue()
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
            addLog("📡 BLE 진단 스캐너 시동...")
            startRealtimeScan()
        } else {
            addLog("🔑 권한 요청 필요")
            requestPermissions()
        }
    }

    private fun startRealtimeScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            addLog("❌ 블루투스가 꺼져 있습니다. 블루투스를 켜주세요.")
            return
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            addLog("❌ BluetoothLeScanner 객체를 가져오지 못했습니다.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            addLog("❌ BLUETOOTH_SCAN 권한이 거부되었습니다.")
            return
        }

        // 기존 스캔 중단 후 재시작 (중복 스캔 오류 방지)
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "stopScan error", e)
            }
            isScanning = false
        }

        isScanning = true
        statusText.text = "BLE 신호 감지 중..."
        addLog("🔍 [startScan 실행] 무필터 고속 스캔 시작")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            // 수신된 패킷 중 Apple 제조사 ID(0x004C)만 추출
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

            totalPacketCount++
            val currentTime = System.currentTimeMillis()
            packetQueue.add(PacketRecord(currentTime, manuData, result.rssi))

            // 15초 경과 패킷 정리
            while (packetQueue.isNotEmpty() && (currentTime - (packetQueue.peek()?.timestamp ?: currentTime)) > 15000) {
                packetQueue.poll()
            }

            // 첫 3개 수신 로그 및 10개 단위 로그 출력
            if (totalPacketCount <= 3 || totalPacketCount % 10 == 0) {
                addLog("📥 [패킷 수신 #$totalPacketCount] 애플 패킷 수집 성공! (큐 크기: ${packetQueue.size})")
            }

            // 실시간 해독 시도
            val batteryInfo = parseValidAirPodsBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, _) = batteryInfo
                updateUI(leftStr, rightStr, caseStr, result.rssi)
                addLog("🎉 [배터리 포착 성공!] L:$leftStr | R:$rightStr | Case:$caseStr")
            }
        }

        // 스캔 실패 시 원인 코드 출력
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            addLog("❌ [ScanFailed] 스캔 실패 에러 코드: $errorCode")
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> addLog("  └ 원인: 이미 스캔이 실행 중입니다.")
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> addLog("  └ 원인: 앱 등록 실패 (블루투스를 껐다 켜보세요).")
                SCAN_FAILED_INTERNAL_ERROR -> addLog("  └ 원인: 안드로이드 내부 시스템 에러.")
                SCAN_FAILED_FEATURE_UNSUPPORTED -> addLog("  └ 원인: BLE 스캔 미지원 기기.")
            }
        }
    }

    private fun start1SecondBatteryPolling() {
        stop1SecondBatteryPolling()

        batteryUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                searchBatteryFromQueue()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(batteryUpdateRunnable!!)
    }

    private fun stop1SecondBatteryPolling() {
        batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        batteryUpdateRunnable = null
    }

    private fun searchBatteryFromQueue(): Boolean {
        if (packetQueue.isEmpty()) {
            addLog("⚠️ 링 버퍼 큐가 비어있습니다. (핸드폰 '위치/GPS'가 켜졌는지 확인하세요!)")
            return false
        }

        for (record in packetQueue.reversed()) {
            val batteryInfo = parseValidAirPodsBatteryData(record.data)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo
                updateUI(leftStr, rightStr, caseStr, record.rssi)
                addLog("✅ [큐 해독 성공] L:$leftStr | R:$rightStr | Case:$caseStr")
                return true
            }
        }
        return false
    }

    private fun updateUI(leftStr: String, rightStr: String, caseStr: String, rssi: Int) {
        runOnUiThread {
            topResultCard.text = """
                🎉 [$connectedDeviceName 배터리 정보]
                
                • 왼쪽 (L): $leftStr
                • 오른쪽 (R): $rightStr
                • 충전 케이스: $caseStr
                
                (신호 감도: $rssi dBm)
            """.trimIndent()
            statusText.text = "연결됨: 실시간 수신 완료"
        }
    }

    private fun parseValidAirPodsBatteryData(data: ByteArray): Quadruple<String, String, String, String>? {
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

                        val chargeStatus = caseByte and 0x0F
                        val isLeftCharging = (chargeStatus and 0x01) != 0
                        val isRightCharging = (chargeStatus and 0x02) != 0
                        val isCaseCharging = (chargeStatus and 0x04) != 0

                        val leftCharging = if (isFlipped) isRightCharging else isLeftCharging
                        val rightCharging = if (isFlipped) isLeftCharging else isRightCharging

                        val leftStr = formatBatteryWithCharge(leftVal, leftCharging)
                        val rightStr = formatBatteryWithCharge(rightVal, rightCharging)
                        val caseStr = formatBatteryWithCharge(rawCase, isCaseCharging)

                        val endIdx = minOf(i + 2 + subLen, data.size)
                        val rawHex = data.copyOfRange(i, endIdx).joinToString(" ") { "%02X".format(it) }

                        return Quadruple(leftStr, rightStr, caseStr, rawHex)
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
            in 11..14 -> "100%$chargeSymbol"
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
        // 안드로이드 12 이상
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // ★ 위치 권한 필수 동시 체크
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
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                addLog("✅ 모든 권한 승인 완료! 스캔을 재시작합니다.")
                startRealtimeScan()
            } else {
                addLog("❌ 권한이 거부되어 BLE 스캔을 수행할 수 없습니다.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stop1SecondBatteryPolling()
            unregisterReceiver(connectionReceiver)
            if (isScanning) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner?.stopScan(scanCallback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
