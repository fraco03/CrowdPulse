package com.crowdpulse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlin.math.pow

class BleRangingManager(
    private val context: Context,
    private val roomId: String,
    private val onDistanceUpdated: (Double) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser

    // Unique UUID derived from roomId so only users in the same room scan each other
    private val serviceUuid = ParcelUuid(UUID.nameUUIDFromBytes(roomId.toByteArray()))
    
    private var isRunning = false

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is disabled")
            Log.e("BleRanging", "BLE Disabled")
            return
        }
        isRunning = true
        startAdvertising()
        startScanning()
        onStatus("BLE Active (Scanning & Advertising)")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("BleRanging", "BLE Advertising started successfully")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("BleRanging", "BLE Advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    // Buffer per smoothing RSSI con media mobile
    private val rssiBuffer = mutableListOf<Int>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isRunning) return
            
            val rssi = result.rssi
            rssiBuffer.add(rssi)
            if (rssiBuffer.size > 15) rssiBuffer.removeAt(0)
            
            val avgRssi = rssiBuffer.average()
            // txPower tipico a 1 metro
            val distance = calculateDistance(avgRssi, -59) 
            
            onDistanceUpdated(distance)
        }
    }

    private fun calculateDistance(rssi: Double, txPower: Int): Double {
        // Formula ambientale (Path Loss Model)
        val n = 2.5 // Indoor environment factor
        return 10.0.pow((txPower - rssi) / (10.0 * n))
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isRunning = false
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
        onStatus("Stopped")
    }
}
