package com.example.usbcam.utils

import android.util.Log
import com.example.usbcam.api.DeviceStatusRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DeviceStatusTracker
 * 
 * A singleton to track the live connection status of hardware devices.
 * This can be accessed from both UI and background workers.
 */
object DeviceStatusTracker {
    private const val TAG = "DeviceStatusTracker"

    @Volatile
    private var _isCameraConnected: Boolean = false

    var isCameraConnected: Boolean
        get() = _isCameraConnected
        set(value) {
            if (_isCameraConnected != value) {
                Log.d(TAG, "Camera connection status changed: $value")
                _isCameraConnected = value
            }
        }

    /**
     * Report current status to server.
     * Logic: Chỉ gọi API khi cả Camera và RFID đều CONNECTED (true).
     * Điều này tránh việc gọi API với trạng thái false khi đang khởi tạo.
     */
    fun reportStatus(context: android.content.Context) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())
        scope.launch {
            try {
                val cam = isCameraConnected
                val rfid = com.example.usbcam.rfid.RfidConnectionManager.getInstance(context).isConnected

                val lineId = LinePreferences.getSelectedLine(context)
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                
                val statusRequest = DeviceStatusRequest(
                    lineId = lineId,
                    cameraConnected = cam,
                    rfidConnected = rfid,
                    timestamp = now
                )
                
                Log.i(TAG, "Sending Health Report (All OK): Cam=$cam, RFID=$rfid")
                val apiService = com.example.usbcam.api.PoApiService.create()
                apiService.reportDeviceStatus(statusRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Failed health report", e)
            }
        }
    }
}
