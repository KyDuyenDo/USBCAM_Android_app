package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.DataRfid
import com.example.usbcam.data.model.Result
import com.example.usbcam.data.model.RfidData
import com.example.usbcam.api.PoApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for RFID operations
 * Handles fetching RFID data from API
 */
class RfidRepository(
    private val apiService: PoApiService
) {
    companion object {
        private const val TAG = "RfidRepository"
    }

    /**
     * Get RFID information from API
     */
    suspend fun getRfidInfo(rfidCode: String): Result<RfidData> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching RFID info for code: $rfidCode")
            
            val response = apiService.getRfidInfoSuspend(rfidCode)
            
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                Log.i(TAG, "✅ RFID fetch successful: ${data.po}")
                
                Result.Success(data.toRfidData(rfidCode))
            } else {
                Log.w(TAG, "⚠️ RFID not found: ${response.code()}")
                Result.Error(
                    exception = Exception("RFID not found"),
                    message = "RFID code not found in database (HTTP ${response.code()})"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ RFID fetch failed", e)
            Result.Error(
                exception = e,
                message = "Failed to fetch RFID info: ${e.message}"
            )
        }
    }

    /**
     * Convert API response to domain model
     */
    private fun DataRfid.toRfidData(rfidCode: String): RfidData {
        return RfidData(
            rfidCode = rfidCode,
            po = this.po,
            upc = null, // Not available in DataRfid API response
            ry = null, // Not available in DataRfid API response
            size = this.size,
            article = this.article,
            color = this.color,
            model = this.model
        )
    }
}
