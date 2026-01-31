package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.DataRfid
import com.example.usbcam.data.model.Result
import com.example.usbcam.data.model.RfidData
import com.example.usbcam.api.PoApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException

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

    suspend fun getRfidInfo(rfidCode: String): Result<RfidData> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching RFID info for code: $rfidCode")

                val response = apiService.getRfidInfoSuspend(rfidCode)

                val body = response.body()

                if (response.isSuccessful && body != null) {
                    Log.i(TAG, "RFID fetch successful: ${body.po}")
                    Result.Success(body.toRfidData(rfidCode))
                } else {
                    val errorText = response.errorBody()?.string()
                    Log.w(TAG, "RFID API empty body, code=${response.code()}, error=$errorText")

                    Result.Error(
                        exception = Exception("Empty response body"),
                        message = "RFID not found or empty response (HTTP ${response.code()})"
                    )
                }

            } catch (e: EOFException) {
                Log.e(TAG, " Empty response from server", e)
                Result.Error(
                    exception = e,
                    message = "Server returned empty response"
                )

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
            upc = this.barcode, // Not available in DataRfid API response
            ry = null, // Not available in DataRfid API response
            size = this.size,
            article = this.article,
            color = this.color,
            model = this.model
        )
    }
}
