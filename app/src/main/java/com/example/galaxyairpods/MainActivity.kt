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

    private val TAG = "AirPodsFixFinal"
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
            text = "스캔 대기 중"
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
            text = "--- 모니터링 로그 ---\n"
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
            addLog("💡 [체크] 핸드폰 상단바 '위치(GPS)'가 켜져 있는지 확인하세요!")
            addLog("📡 BLE 스캐너 가동 중...")
            startRealtimeScan()
        } else {
            requestPermissions()
        }
    }

    private fun startRealtimeScan() {
        if (isScanning) return
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isScanning = true
        statusText.text = "BLE 신호 감지 중..."

        // ★ 핵심 수정: 0x004C 모든 제조사 데이터를 통과시키는 와일드카드 마스크 설정
        val appleFilter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x00), byteArrayOf(0x00))
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

            totalPacketCount++
            val currentTime = System.currentTimeMillis()
            packetQueue.add(PacketRecord(currentTime, manuData, result.rssi))

            // 15초 경과 패킷 삭제
            while (packetQueue.isNotEmpty() && (currentTime - (packetQueue.peek()?.timestamp ?: currentTime)) > 15000) {
                packetQueue.poll()
            }

            // 패킷 수신 시 실시간 카운터 로그 출력 (처음 5개 + 이후 10개 단위)
            if (totalPacketCount <= 5 || totalPacketCount % 10 == 0) {
                addLog("📥 [패킷 수신 #$totalPacketCount] Apple 패킷 큐 저장 완료 (${result.rssi} dBm)")
            }

            // 패킷 해독 시도
            val batteryInfo = parseValidAirPodsBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo
                updateUI(leftStr, rightStr, caseStr, result.rssi)
                addLog("🎉 [실시간 배터리 포착!] L:$leftStr | R:$rightStr | Case:$caseStr")
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
            addLog("⚠️ 링 버퍼 큐가 비어있습니다. (위치/GPS 켜짐 여부 확인 필요)")
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
