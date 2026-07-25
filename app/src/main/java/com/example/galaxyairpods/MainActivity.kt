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

    private val TAG = "AirPodsRealtime"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var connectedDeviceName: String = "더혀니의 AirPods Pro"
    private val handler = Handler(Looper.getMainLooper())

    // 15초 이내 수신 패킷을 보관하는 링 버퍼
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
                    connectedDeviceName = getDeviceName(device)
                    addLog("⚡ [기기 오디오 연결 완료] $connectedDeviceName")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    addLog("🔌 [기기 연결 해제]")
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
            text = "AirPods Realtime Monitor"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }

        // 최상단 배터리 정보 카드
        topResultCard = TextView(this).apply {
            text = "🎧 에어팟 뚜껑을 열어주세요...\n(실시간으로 배터리를 감지합니다)"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(35, 35, 35, 35)
            setBackgroundColor(0xFFE8F0FE.toInt())
        }

        statusText = TextView(this).apply {
            text = "초고속 무필터 실시간 스캔 대기 중"
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
            text = "--- 실시간 스캔 로그 ---\n"
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
            addLog("📡 무필터 고속 실시간 스캐너 가동 시작")
            startRealtimeScan()
        } else {
            requestPermissions()
        }
    }

    // ★ ScanFilter를 완전히 제거하여 갤럭시 칩셋의 패킷 누락 원천 차단
    private fun startRealtimeScan() {
        if (isScanning) return
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isScanning = true
        statusText.text = "실시간 패킷 수신 중 (뚜껑을 열어보세요)"

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 최고 성능 로우 레턴시
            .build()

        // 필터 없이 모든 BLE 광고 패킷 수신
        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            // 수신된 패킷에서 Apple 제조사 데이터(0x004C)만 소프트웨어로 추출
            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            
            val currentTime = System.currentTimeMillis()
            packetQueue.add(PacketRecord(currentTime, manuData, result.rssi))

            // 15초 지난 패킷 정리
            while (packetQueue.isNotEmpty() && (currentTime - (packetQueue.peek()?.timestamp ?: currentTime)) > 15000) {
                packetQueue.poll()
            }

            // ★ 핵심: 패킷이 들어오는 찰나에 유효한 배터리 데이터면 즉시 화면 갱신!
            val batteryInfo = parseValidAirPodsBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo
                
                runOnUiThread {
                    topResultCard.text = """
                        🎉 [$connectedDeviceName 배터리 정보]
                        
                        • 왼쪽 (L): $leftStr
                        • 오른쪽 (R): $rightStr
                        • 충전 케이스: $caseStr
                        
                        (신호 감도: ${result.rssi} dBm)
                    """.trimIndent()
                }
            }
        }
    }

    private fun searchBatteryFromQueue() {
        if (packetQueue.isEmpty()) {
            addLog("⚠️ 링 버퍼에 수집된 패킷이 없습니다.")
            return
        }

        var foundValid = false
        for (record in packetQueue.reversed()) {
            val batteryInfo = parseValidAirPodsBatteryData(record.data)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo
                foundValid = true

                runOnUiThread {
                    topResultCard.text = """
                        🎉 [$connectedDeviceName 배터리 정보 (수동검색)]
                        
                        • 왼쪽 (L): $leftStr
                        • 오른쪽 (R): $rightStr
                        • 충전 케이스: $caseStr
                        
                        (신호 감도: ${record.rssi} dBm)
                    """.trimIndent()
                }

                addLog("✅ [수동 해독 성공] L:$leftStr | R:$rightStr | Case:$caseStr (${record.rssi} dBm)")
                addLog("  └ Raw Hex: $rawHex")
                break
            }
        }

        if (!foundValid) {
            addLog("🔍 현재 큐에 유효한 평문 배터리 패킷이 없습니다.")
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
                            val rightCharging = if (isFlipped) isLeftCharging else rawRight.let { isFlipped } // safe alignment

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
