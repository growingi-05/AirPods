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

    private val TAG = "AirPodsRawSniffer"
    private val PERMISSION_REQUEST_CODE = 1001

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnClearLog: Button

    // 중복 로그 방지를 위한 큐 (화면 도배 방지)
    private val lastSeenHex = ConcurrentLinkedQueue<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "AirPods Raw Sniffer (완전 해제 모드)"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }

        statusText = TextView(this).apply {
            text = "스캔 준비 중... (모든 애플 데이터 수신 중)"
            textSize = 14f
            setPadding(0, 15, 0, 10)
        }

        btnClearLog = Button(this).apply {
            text = "로그 화면 지우기"
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
            text = "--- 원본(Raw Hex) 수신 로그 ---\n"
            textSize = 13f
            setBackgroundColor(0x11000000)
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(btnClearLog)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        btnClearLog.setOnClickListener {
            logText.text = "--- 원본(Raw Hex) 수신 로그 ---\n"
            // 화면 지울 때 큐도 비워줘서 다시 찍힐 수 있게 함
            lastSeenHex.clear()
        }

        if (hasAppPermissions()) {
            addLog("📡 원본 데이터 무제한 스캐너 가동 중...")
            startRealtimeScan()
        } else {
            requestAppPermissions()
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
        statusText.text = "패킷 추적 중... (필터 조건 0개)"

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

            // 🚨 0x07 시작 조건 아예 삭제! 
            // 애플 전파면 무조건 16진수로 변환해서 화면에 다 때려 박습니다!
            if (manuData.isNotEmpty()) {
                val hexString = manuData.joinToString(" ") { "%02X".format(it) }
                
                // 똑같은 패킷이 화면을 꽉 채우는 것만 방지
                if (!lastSeenHex.contains(hexString)) {
                    lastSeenHex.add(hexString)
                    if (lastSeenHex.size > 50) {
                        lastSeenHex.poll()
                    }
                    addLog("📡 [${result.rssi}dBm] $hexString")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            addLog("❌ [스캔 실패] 에러 코드: $errorCode")
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

    private fun hasAppPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
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
                startRealtimeScan()
            } else {
                addLog("❌ 필수 권한이 거부되었습니다.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.stopScan(scanCallback)
            }
        }
    }
}
