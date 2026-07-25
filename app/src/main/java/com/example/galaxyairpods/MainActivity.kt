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

// 💡 파싱 에러를 방지하기 위해 데이터 클래스들을 MainActivity 바깥(최상단)으로 완전히 분리했습니다.
data class PacketRecord(val timestamp: Long, val data: ByteArray, val rssi: Int)
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsFixMain"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isConnected = false

    private var activeDeviceName: String = "내 AirPods Pro"
    private var totalApplePackets = 0

    private var lastValidLeft: String? = null
    private var lastValidRight: String? = null
    private var lastValidCase: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var batteryUpdateRunnable: Runnable? = null

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
                    val fetchedName = getDeviceName(device)
                    if (fetchedName.isNotBlank()) {
                        activeDeviceName = fetchedName
                    }
                    addLog("⚡ [실시간 기기 연결 완료] $activeDeviceName")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    isConnected = false
                    addLog("🔌 [기기 연결 해제]")
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
                val found = searchBatteryFromQueue()
                if (found) {
                    addLog("👆 수동 해독 성공! (수신된 패킷: $totalApplePackets 개 / 큐: ${packetQueue.size})")
                } else {
                    addLog("👆 수동 해독 실패 - 큐에 가짜(암호화) 패킷만 존재합니다.")
                }
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
            start1SecondBatteryPolling()
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

        if (Build.VERSION.SDK
