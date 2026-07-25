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

    private val TAG = "AirPodsFixMain"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isConnected = false

    // 실시간 연결된 기기 이름
    private var activeDeviceName: String = "내 AirPods Pro"
    private var totalApplePackets = 0

    private var lastValidLeft: String? = null
    private var lastValidRight: String? = null
    private var lastValidCase: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var batteryUpdateRunnable: Runnable? = null

    private data class PacketRecord(val timestamp: Long, val data: ByteArray, val rssi: Int)
    private val packetQueue = ConcurrentLinkedQueue<PacketRecord>()

    private lateinit var topResultCard: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnManualSearch: Button

    // 오디오 연결 감지 (진짜 연결된 기기 이름 수집)
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
                    val fetchedName = getDeviceName(device)
                    if (fetchedName.isNotBlank()) {
                        activeDeviceName = fetchedName
                    }
                    addLog("⚡ [실시간 기기 연결 완료] $activeDeviceName")
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

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

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
            text = "🎧 [$activeDeviceName]\n배터리 신호를 탐색 중입니다..."
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(35, 35, 35, 35)
            setBackgroundColor(0xFFE8F0FE.toInt())
        }

        statusText = TextView(this).apply {
            text = "스캔 준비 중..."
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
            text = "--- 수신 및 해독 로그 ---\n"
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

        btnManualSearch.setOnClickListener {
            if (checkPermissions()) {
                addLog("👆 수동 해독 요청 (수신된 패킷: $totalApplePackets 개 / 큐: ${packetQueue.size})")
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
            initDeviceNameFromBonded()
            addLog("📡 BLE 스캐너 가동 중...")
            startRealtimeScan()
        } else {
            requestPermissions()
        }
    }

    private fun initDeviceNameFromBonded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices
            bondedDevices?.forEach { device ->
                val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias ?: device.name ?: ""
                } else {
                    device.name ?: ""
                }
                if (name.contains("AirPods", ignoreCase = true) || name.contains("에어팟", ignoreCase = true)) {
                    if (activeDeviceName == "내 AirPods Pro") {
                        activeDeviceName = name
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initDeviceNameFromBonded error", e)
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
        statusText.text = "BLE 패킷 수신 중..."

        // 🚨 수정된 부분: 애플(0x004C) 전용 스캔 필터 추가 (갤럭시 통신 차단 방지)
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(0x004C, byteArrayOf())
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val manuData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

            totalApplePackets++
            val currentTime = System.currentTimeMillis()
            packetQueue.add(PacketRecord(currentTime, manuData, result.rssi))

            while (packetQueue.isNotEmpty() && (currentTime - (packetQueue.peek()?.timestamp ?: currentTime)) > 15000) {
                packetQueue.poll()
            }

            val batteryInfo = parseAirPodsBatteryData(manuData)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, rawHex) = batteryInfo

                lastValidLeft = leftStr
                lastValidRight = rightStr
                lastValidCase = caseStr

                updateUI(leftStr, rightStr, caseStr, result.rssi)
                addLog("🎉 [배터리 해독 성공] L:$leftStr | R:$rightStr | Case:$caseStr (${result.rssi} dBm)")
                addLog("  └ Raw: $rawHex")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            addLog("❌ [스캔 실패] 에러 코드: $errorCode")
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
        if (packetQueue.isEmpty()) return false

        for (record in packetQueue.reversed()) {
            val batteryInfo = parseAirPodsBatteryData(record.data)
            if (batteryInfo != null) {
                val (leftStr, rightStr, caseStr, _) = batteryInfo
                lastValidLeft = leftStr
                lastValidRight = rightStr
                lastValidCase = caseStr

                updateUI(leftStr, rightStr, caseStr, record.rssi)
                return true
            }
        }

        if (lastValidLeft != null) {
            updateUI(lastValidLeft!!, lastValidRight!!, lastValidCase!!, -70)
        }
        return false
    }

    private fun updateUI(leftStr: String, rightStr: String, caseStr: String, rssi: Int) {
        runOnUiThread {
            topResultCard.text = """
                🎉 [$activeDeviceName 배터리 정보]
                
                • 왼쪽 (L): $leftStr
                • 오른쪽 (R): $rightStr
                • 충전 케이스: $caseStr
                
                (신호 감도: $rssi dBm)
            """.trimIndent()
            statusText.text = "실시간 수신 완료"
        }
    }

    private fun parseAirPodsBatteryData(data: ByteArray): Quadruple<String, String, String, String>? {
        try {
            var i = 0
            while (i <= data.size - 6) {
                if (data[i] == 0x07.toByte()) {
                    val subLen = data[i + 1].toInt() and 0xFF

                    // 🚨 수정된 부분: 17바이트(0x11) 암호화 패킷 무시, 평문 패킷(0x18, 0x19 등)만 허용
                    if (subLen == 0x11 || subLen < 0x18) {
                        i += subLen + 2
                        continue
                    }

                    val statusByte = data[i + 3].toInt() and 0xFF
                    val earbudByte = data[i + 4].toInt() and 0xFF
                    val caseByte = data[i + 5].toInt() and 0xFF

                    val rawLeft = (earbudByte and 0xF0) ushr 4
                    val rawRight = earbudByte and 0x0F
                    val rawCase = (caseByte and 0xF0) ushr 4

                    val isFlipped = (statusByte and 0x20) != 0
                    val leftVal = if (isFlipped) rawRight else rawLeft
                    val rightVal = if (isFlipped) rawLeft else rawRight

                    val isLeftValid = leftVal in 0..11 || leftVal == 15
                    val isRightValid = rightVal in 0..11 || rightVal == 15
                    val isCaseValid = rawCase in 0..11 || rawCase == 15
                    val hasRealVal = leftVal in 0..11 || rightVal in 0..11 || rawCase in 0..11

                    if (isLeftValid && isRightValid && isCaseValid && hasRealVal) {
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

                    i += subLen + 2
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
            11 -> "100%$chargeSymbol"
            15 -> "미연결/수면"
            else -> "알 수 없음 ($valRaw)"
        }
    }

    private fun getDeviceName(device: BluetoothDevice?): String {
        if (device == null) return activeDeviceName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return activeDeviceName
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            device.alias ?: device.name ?: activeDeviceName
        } else {
            device.name ?: activeDeviceName
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
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
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
                addLog("✅ 권한 승인 완료!")
                initDeviceNameFromBonded()
                startRealtimeScan()
            } else {
                addLog("❌ 필수 권한이 거부되었습니다.")
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
