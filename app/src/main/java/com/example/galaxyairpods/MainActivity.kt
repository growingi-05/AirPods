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
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsRingBuffer"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var connectedDeviceName: String = "내 AirPods Pro"
    private val handler = Handler(Looper.getMainLooper())

    // 15초 이내에 수신된 패킷만 보관하는 메모리 링 버퍼 (Rolling Cache)
    private data class PacketRecord(val timestamp: Long, val data: ByteArray, val rssi: Int)
    private val packetQueue = ConcurrentLinkedQueue<PacketRecord>()

    private lateinit var topResultCard: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnScanNow: Button

    // 블루투스 연결/해제 감지 리시버
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
                    addLog("⚡ [기기 연결 감지] $connectedDeviceName")
                    addLog("🎯 링 버퍼에 저장된 직전 수초 간의 패킷 역참조 분석 시작...")
                    
                    // 연결 직후 메모리 큐를 뒤져서 진짜 배터리 패킷 발굴
                    searchBatteryFromQueue()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val name = getDeviceName(device)
                    addLog("🔌 [기기 연결 해제] $name")
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
            text = "AirPods RingBuffer Monitor"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }

        // 최상단 강조 배터리 정보 카드
        topResultCard = TextView(this).apply {
            text = "🎧 에어팟 연결 대기 중...\n(뚜껑을 열거나 연결을 시도하세요)"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(35, 35, 35, 35)
            setBackgroundColor(0xFFE8F0FE.toInt())
        }

        statusText = TextView(this).apply {
            text = "상시 백그라운드 링 버퍼 대기 중"
            textSize, textSize = 13f, 13f
            setPadding(0, 15, 0, 10)
        }

        btnScanNow = Button(this).apply {
            text = "큐 데이터 수동 해독 및 검색"
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
            text = "--- 링 버퍼 및 해독 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(topResultCard)
        rootLayout.addView(statusText)
        rootLayout.addView(btnScanNow)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnScanNow.setOnClickListener {
            if (checkPermissions()) {
                addLog("👆 수동 큐 해독 요청")
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
            addLog("📡 상시 링 버퍼 스캐너 가동 시작")
            startContinuousRingBufferScan()
        } else {
            requestPermissions()
        }
    }

    // 상시 링 버퍼를 채우기 위한 연속 BLE 스캔 구동
    private fun startContinuousRingBufferScan() {
        if (isScanning) return
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isScanning = true
        statusText.text = "상시 패킷 수집 링 버퍼 작동 중..."

        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(appleFilter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            
            // 패킷을 수신한 즉시 메모리 큐(링 버퍼)에 보관
            val currentTime = System.currentTimeMillis()
            packetQueue.add(PacketRecord(currentTime, manuData, result.rssi))

            // 15초 이상 지난 오래된 패킷은 큐에서 자동 제거 (메모리 관리)
            while (packetQueue.isNotEmpty() && currentTime - packetQueue.peek().timestamp > 15000) {
                packetQueue.poll()
            }
        }
    }

    // 메모리 큐에 쌓인 패킷들을 역참조하여 평문 배터리 정보 발굴
    private fun searchBatteryFromQueue() {
        if (packetQueue.isEmpty()) {
            addLog("⚠️ 링 버퍼에 수집된 패킷이 없습니다. 뚜껑을 여닫아 주세요.")
            return
        }

        var foundValid = false
        // 최신 패킷부터 역순으로 탐색
        val queueSnapshot = packetQueue.reversed()

        for (record in queueSnapshot) {
            val batteryInfo = parseValidAirPodsBatteryData(record.data)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo
                foundValid = true

                runOnUiThread {
                    topResultCard.text = """
                        🎉 [$connectedDeviceName 배터리 정보]
                        
                        • 왼쪽 (L): $leftStr
                        • 오른쪽 (R): $rightStr
                        • 충전 케이스: $caseStr
                        
                        (신호 감도: ${record.rssi} dBm)
                    """.trimIndent()
                }

                addLog("✅ [큐 해독 성공] $connectedDeviceName | L:$leftStr | R:$rightStr | Case:$caseStr (${record.rssi} dBm)")
                addLog("  └ Raw Hex: $rawHex")
                break
            }
        }

        if (!foundValid) {
            addLog("🔍 큐 내의 패킷들이 모두 암호화 상태입니다. 뚜껑을 열고 다시 시도하세요.")
        }
    }

    // 평문 배터리 패킷 검증 및 해독 로직
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

                        // 무효/암호화 더미 범위(11~14) 차단
                        if (leftVal in 11..14 || rightVal in 11..14 || rawCase in 11..14) {
                            i += if (subLen > 0) subLen + 2 else 1
                            continue
                        }

                        val isLeftValid = leftVal in 0..10 || leftVal == 15
                        val isRightValid = rightVal in 0..10 || rightVal == 15
                        val isCaseValid = rawCase in 0..10 || rawCase == 15
                        val hasRealBattery = leftVal in 0..10 || rightVal in 0..10 || rawCase in 0..10

                        if (isLeftValid && isRightValid && isCaseValid && hasRealBattery) {
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
