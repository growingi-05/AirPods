package com.example.galaxyairpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

class MainActivity : AppCompatActivity() {

    private val TAG = "AirPodsConnected"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null

    private lateinit var statusText: TextView
    private lateinit var batteryResultText: TextView
    private lateinit var logText: TextView
    private lateinit var btnRefresh: Button

    // 블루투스 방송 리시버
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            
            when (action) {
                // 1. 안드로이드 표준 블루투스 기기 배터리 잔량 변경 이벤트
                BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val batteryLevel = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                    
                    if (device != null && batteryLevel != -1) {
                        val deviceName = getDeviceName(device)
                        addLog("🔋 [배터리 변경 감지] $deviceName -> $batteryLevel%")
                        updateBatteryUI(deviceName, batteryLevel)
                    }
                }

                // 2. 애플 전용 AT 커맨드 헤드셋 이벤트 (+IPHONEACCEV)
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                    addLog("📡 [애플 헤드셋 이벤트 수신] Cmd: $command")
                    checkConnectedDeviceBattery()
                }

                // 3. 연결 상태 변경 이벤트
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    addLog("🔄 블루투스 연결 상태 변경됨")
                    checkConnectedDeviceBattery()
                }
            }
        }
    }

    // BluetoothHeadset 프로필 서비스 연결 콜백
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                addLog("✅ Headset 서비스 프로필 연결 완료")
                checkConnectedDeviceBattery()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                addLog("❌ Headset 서비스 프로필 해제됨")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "Connected AirPods Monitor"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }

        statusText = TextView(this).apply {
            text = "연결된 에어팟의 배터리 상태를 모니터링합니다."
            textSize = 14f
            setPadding(0, 0, 0, 15)
        }

        btnRefresh = Button(this).apply {
            text = "연결 기기 배터리 즉시 조회"
            setPadding(0, 30, 0, 30)
        }

        batteryResultText = TextView(this).apply {
            text = "🎧 에어팟 연결 상태를 확인하는 중..."
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
            text = "--- 시스템 이벤트 로그 ---\n"
            textSize = 11f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnRefresh)
        rootLayout.addView(batteryResultText)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnRefresh.setOnClickListener {
            if (checkPermissions()) {
                checkConnectedDeviceBattery()
            } else {
                requestPermissions()
            }
        }

        if (checkPermissions()) {
            initBluetoothProfileAndReceiver()
        } else {
            requestPermissions()
        }
    }

    private fun initBluetoothProfileAndReceiver() {
        // Headset Profile 프록시 요청
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)

        // 시스템 브로드캐스트 필터 등록
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_BASE_OF_CLASS_BLUETOOTH + 76) // Apple Company ID (0x004C = 76)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        
        addLog("📡 시스템 배터리 이벤트 리시버 등록 완료")
        checkConnectedDeviceBattery()
    }

    private fun checkConnectedDeviceBattery() {
        if (!checkPermissions()) return

        val connectedDevices = bluetoothHeadset?.connectedDevices
        
        if (!connectedDevices.isNullOrEmpty()) {
            for (device in connectedDevices) {
                val deviceName = getDeviceName(device)
                val batteryLevel = getBatteryLevelViaReflection(device)
                
                addLog("📱 연결 기기 발견: $deviceName | 배터리 수치: ${if (batteryLevel != -1) "$batteryLevel%" else "읽는 중..."}")
                
                if (batteryLevel != -1) {
                    updateBatteryUI(deviceName, batteryLevel)
                } else {
                    runOnUiThread {
                        batteryResultText.text = "🎧 연결 기기: $deviceName\n안드로이드 배터리 상태 수신 대기 중..."
                    }
                }
            }
        } else {
            addLog("⚠️ 연결된 블루투스 헤드셋 기기가 없습니다.")
            runOnUiThread {
                batteryResultText.text = "🎧 연결된 에어팟이 없습니다.\n스마트폰 설정에서 에어팟을 먼저 블루투스로 연결해 주세요."
            }
        }
    }

    private fun getBatteryLevelViaReflection(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int ?: -1
            level
        } catch (e: Exception) {
            -1
        }
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            "알 수 없는 기기"
        } else {
            device.name ?: device.address ?: "블루투스 기기"
        }
    }

    private fun updateBatteryUI(deviceName: String, batteryLevel: Int) {
        val displayText = """
            🎉 [연결된 에어팟 배터리 수신 성공!]
            
            • 기기명: $deviceName
            • 잔여 배터리: $batteryLevel%
            
            (안드로이드 공식 Bluetooth Profile 연동)
        """.trimIndent()

        runOnUiThread {
            batteryResultText.text = displayText
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
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                statusText.text = "권한 허용됨."
                initBluetoothProfileAndReceiver()
            } else {
                statusText.text = "권한 거부됨."
                Toast.makeText(this, "연결 기기 정보를 읽기 위해 블루투스 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
            if (bluetoothAdapter != null && bluetoothHeadset != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unregister error", e)
        }
    }
}
