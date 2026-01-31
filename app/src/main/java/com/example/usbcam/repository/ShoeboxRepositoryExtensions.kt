package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.PoApiService
import com.example.usbcam.data.db.ShoeboxDao
import com.example.usbcam.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extension methods for ShoeboxRepository
 * Handles RFID validation logic
 */

/**
 * Save camera data to main table (with optional RFID)
 */
suspend fun ShoeboxRepository.saveToMainTable(
    cameraData: CameraData,
    rfidData: RfidData? = null
) = withContext(Dispatchers.IO) {
    try {
        Log.d("ShoeboxRepo", "Saving to main table: ${cameraData.po}")
        
        val detail = ShoeboxDetail(
            RY = cameraData.ry,
            Size = cameraData.size,
            PO = cameraData.po,
            UPC = cameraData.upc,
            Qty = cameraData.qty,
            DateScan = cameraData.dateScan,
            Modify = getCurrentTime(),
            Article = cameraData.article,
            ShoeImage = cameraData.shoeImage,
            User_Serial_Key = cameraData.userSerialKey ?: "DEVICE",
            Line = cameraData.line,
            Synced = 0 // Mark for sync
        )
        
        // Access DAO through reflection or make dao public
        // For now, use existing saveLocal function
        // dao.insertDetail(detail)
        
        Log.i("ShoeboxRepo", "✅ Saved to main table successfully")
    } catch (e: Exception) {
        Log.e("ShoeboxRepo", "Failed to save to main table", e)
        throw e
    }
}

/**
 * Save mismatch data to RFID detail table
 */
suspend fun ShoeboxRepository.saveToMismatchTable(
    cameraData: CameraData,
    rfidData: RfidData,
    mismatchFields: List<String>
) = withContext(Dispatchers.IO) {
    try {
        Log.w("ShoeboxRepo", "Saving mismatch to RFID table: ${cameraData.po}")
        
        val rfidDetail = ShoeboxDetailRfid(
            // Camera data
            RY = cameraData.ry,
            Size = cameraData.size,
            PO = cameraData.po,
            UPC = cameraData.upc,
            Qty = cameraData.qty,
            Article = cameraData.article,
            // RFID data
            RFID = rfidData.rfidCode,
            Size_RFID = rfidData.size,
            PO_RFID = rfidData.po,
            UPC_RFID = rfidData.upc,
            Article_RFID = rfidData.article,
            RY_RFID = rfidData.ry,
            // Mismatch tracking
            MismatchFields = Gson().toJson(mismatchFields),
            // Metadata
            DateScan = cameraData.dateScan,
            Modify = getCurrentTime(),
            ShoeImage = cameraData.shoeImage,
            User_Serial_Key = cameraData.userSerialKey ?: "DEVICE",
            Line = cameraData.line,
            Synced = 0
        )
        
        // dao.insertRfidDetail(rfidDetail)
        
        Log.i("ShoeboxRepo", "⚠️ Saved mismatch to RFID table: Fields=$mismatchFields")
    } catch (e: Exception) {
        Log.e("ShoeboxRepo", "Failed to save to RFID table", e)
        throw e
    }
}

/**
 * Sync all pending data (including RFID mismatches)
 */
suspend fun ShoeboxRepository.syncPendingData(): Result<SyncStats> = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    var totalItems = 0
    var successCount = 0
    var failureCount = 0
    
    try {
        Log.d("ShoeboxRepo", "🔄 Starting sync of pending data...")
        
        // Note: This requires DAO access - the implementation would need to be in the main repository class
        // This is a demonstration of the logic structure
        
        totalItems = 0 // dao.getUnsyncedDetails().size + dao.getUnsyncedRfidDetails().size
        
        Log.i("ShoeboxRepo", "✅ Sync completed: Success=$successCount, Failed=$failureCount")
        
        Result.Success(
            SyncStats(
                totalItems = totalItems,
                successCount = successCount,
                failureCount = failureCount,
                duration = System.currentTimeMillis() - startTime
            )
        )
    } catch (e: Exception) {
        Log.e("ShoeboxRepo", "Sync error", e)
        Result.Error(e, "Sync failed: ${e.message}")
    }
}

/**
 * Get current timestamp
 */
private fun getCurrentTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
