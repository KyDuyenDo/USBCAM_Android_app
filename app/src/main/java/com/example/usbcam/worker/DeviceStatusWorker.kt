package com.example.usbcam.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.usbcam.api.DeviceStatusRequest
import com.example.usbcam.api.PoApiService
import com.example.usbcam.rfid.RfidConnectionManager
import com.example.usbcam.utils.DeviceStatusTracker
import com.example.usbcam.utils.LinePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DeviceStatusWorker
 *
 * Periodic task that checks the connection status of the USB Camera and RFID Reader
 * and reports it to the server every 15 minutes.
 */
class DeviceStatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        try {
            // 1. Collect status information
            val lineId = LinePreferences.getSelectedLine(applicationContext)
            val cameraConnected = DeviceStatusTracker.isCameraConnected
            val rfidConnected = RfidConnectionManager.getInstance(applicationContext).isConnected
            
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val statusRequest = DeviceStatusRequest(
                lineId = lineId ?: "",
                cameraConnected = cameraConnected,
                rfidConnected = rfidConnected,
                timestamp = now
            )
            
            Log.i("DeviceStatusWorker", "Reporting device health: Camera=$cameraConnected, RFID=$rfidConnected, Line=$lineId")
            
            // 2. Call API
            val apiService = PoApiService.create()
            
            // Heartbeat Ping
            try {
                apiService.pingDevice(lineId ?: "")
                Log.d("DeviceStatusWorker", "Heartbeat ping successful for line: $lineId")
            } catch (e: Exception) {
                Log.e("DeviceStatusWorker", "Heartbeat ping failed", e)
            }
            
            val response = apiService.reportDeviceStatus(statusRequest)
            
            return if (response.isSuccessful) {
                Log.d("DeviceStatusWorker", "Successfully reported device status")
                Result.success()
            } else {
                Log.e("DeviceStatusWorker", "Failed to report device status: ${response.code()} ${response.message()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("DeviceStatusWorker", "Error reporting device status", e)
            return Result.retry()
        }
    }
}
