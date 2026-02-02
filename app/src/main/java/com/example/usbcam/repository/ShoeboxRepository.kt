package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import com.example.usbcam.api.TargetResponse
import com.example.usbcam.data.db.ShoeboxDao
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxTotal
import com.example.usbcam.viewmodel.TimeSlotItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ShoeboxRepository(private val dao: ShoeboxDao, private val apiService: PoApiService) {

    // Used when logic is fully delegated to Repository (API + DB)
    suspend fun processScan(po: String, barcode: String): com.example.usbcam.data.model.Result<PoResponse> {
        // 1. Try API first
        try {
            val response = apiService.getPoDetailsSuspend(po, barcode)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Log.d("ShoeboxRepo", "API Success: Body not null, saving local...")
                    try {
                        saveLocal(po, barcode, body)
                        Log.d("ShoeboxRepo", "API Success: Save local done. Returning body.")
                    } catch (e: Exception) {
                        Log.e("ShoeboxRepo", "API Success but SaveLocal Failed: ${e.message}")
                        e.printStackTrace()
                    }
                    return com.example.usbcam.data.model.Result.Success(body)
                } else {
                    Log.w("ShoeboxRepo", "API Success but Body is NULL")
                }
            } else {
                Log.e("ShoeboxRepo", "API Failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e("ShoeboxRepo", "API Error (Offline?): ${e.message}")
            e.printStackTrace()
        }

        // 2. Fallback to Local DB if API failed
        Log.d("ShoeboxRepo", "Attempting local fallback for PO=$po, UPC=$barcode")
        val localData = getLocalPoResponse(po, barcode)
        return if (localData != null) {
            Log.d("ShoeboxRepo", "Local Fallback Success: $localData")
            com.example.usbcam.data.model.Result.Success(localData)
        } else {
            Log.e("ShoeboxRepo", "Local Fallback Failed: No data found")
            com.example.usbcam.data.model.Result.Error(
                Exception("No data available"),
                "API failed and no local cache available"
            )
        }
    }

    /**
     * Get local data only (for Use Case)
     */
    suspend fun getLocalData(po: String, barcode: String): PoResponse? {
        return getLocalPoResponse(po, barcode)
    }

    // Used when API is called externally (e.g., in Fragment), just save DB
    suspend fun saveLocal(po: String, barcode: String, data: PoResponse) {
        // 2. Save Detail
        val detail =
                ShoeboxDetail(
                        RY = data.ry,
                        Size = data.size,
                        PO = po,
                        UPC = barcode,
                        Qty = 1,
                        DateScan = getCurrentTime(),
                        Modify = getCurrentTime(),
                        Article = data.article,
                        ShoeImage = data.articleImage,
                        User_Serial_Key = "DEVICE",
                        Line = data.lean,
                        Synced = 0
                )
        dao.insertDetail(detail)

        // 3. Update Total
        updateTotal(po, barcode, data)
    }

    private suspend fun updateTotal(po: String, upc: String, data: PoResponse) {
        val details = dao.getDetailsByUpc(upc).filter { it.PO == po }
        val totalQty = details.sumOf { it.Qty }

        val currentTotal = dao.getTotalByUpcAndPo(upc, po)
        val newTotal =
                if (currentTotal != null) {
                    currentTotal.copy(
                            Total_Qty_ERP = data.quantity ?: 0,
                            Total_Qty_Scan = totalQty,
                            Modify = getCurrentTime(),
                            Synced = 0
                    )
                } else {
                    ShoeboxTotal(
                            RY = data.ry,
                            Size = data.size,
                            PO = po,
                            UPC = upc,
                            Total_Qty_Scan = totalQty,
                            Total_Qty_ERP = data.quantity ?: 0,
                            Article = data.article,
                            DateScan = getCurrentTime(),
                            Modify = getCurrentTime(),
                            User_Serial_Key = "DEVICE",
                            Line = data.lean,
                            Synced = 0
                    )
                }
        dao.insertTotal(newTotal)
    }

    suspend fun getLatestDetail(): ShoeboxDetail? {
        return dao.getLatestDetail()
    }

    suspend fun getDetailsByTimeSlot(start: String, end: String): List<ShoeboxDetail> {
        return dao.getDetailsInTimeRange(start, end)
    }

    suspend fun syncData() {
        val unsyncedDetails = dao.getUnsyncedDetails()
        unsyncedDetails.forEach { detail ->
            try {
                val response = apiService.syncDetail(detail)
                if (response.isSuccessful) {
                    dao.updateDetailSynced(detail.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val unsyncedTotals = dao.getUnsyncedTotals()
        unsyncedTotals.forEach { total ->
            try {
                val response = apiService.syncTotal(total)
                if (response.isSuccessful) {
                    dao.updateTotalSynced(total.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val unsyncedRfid = dao.getUnsyncedRfidDetails()
        unsyncedRfid.forEach { rfid ->
            try {
                val response = apiService.syncRfidMismatch(rfid)
                if (response.isSuccessful) {
                    dao.updateRfidDetailSynced(rfid.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    suspend fun getAllSlotsToday(target: Int): List<TimeSlotItem> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dbFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val today = dateFormat.format(Date())

        val startDay = "$today 00:00:00"
        val endDay =
                dateFormat.format(
                        Calendar.getInstance()
                                .apply {
                                    time = Date()
                                    add(Calendar.DAY_OF_MONTH, 1)
                                }
                                .time
                ) + " 00:00:00"

        val details = dao.getDetailsInTimeRange(startDay, endDay)

        val slots = mutableListOf<TimeSlotItem>()
        val calendar =
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 7)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                }

        var index = 1

        repeat(10) {
            val start = calendar.time
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            val end = calendar.time

            val frameTime = "${timeFormat.format(start)} - ${timeFormat.format(end)}"

            val count =
                    details.count {
                        val scanTime = dbFormat.parse(it.DateScan) ?: return@count false
                        scanTime >= start && scanTime < end
                    }

            if (count > 0) {
                slots.add(
                        TimeSlotItem(
                                index = index++,
                                frameTime = frameTime,
                                target = target,
                                quantity = count
                        )
                )
            }
        }

        return slots
    }

    suspend fun getAllToday(): Int {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdfDate.format(Date())

        val startDay = "$today 00:00:00"

        val endDay =
                sdfDate.format(
                        Calendar.getInstance()
                                .apply {
                                    time = Date()
                                    add(Calendar.DAY_OF_MONTH, 1)
                                }
                                .time
                ) + " 00:00:00"

        val details = dao.getDetailsInTimeRange(startDay, endDay)
        return details.size
    }

    suspend fun getTargetByTimeSlot(): TargetResponse? {
        return try {
            val response = apiService.getTargetByLean("LHGG4G01")

            if (response.isSuccessful) {
                Log.d("getTargetByTimeSlot", "${response.body()}")
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("getTargetByTimeSlot", "API error", e)
            null
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun Date.toDbString(): String = sdf.format(this)

    suspend fun getLocalPoResponse(po: String, barcode: String): PoResponse? {
        val total = dao.getTotalByUpcAndPo(barcode, po) ?: return null
        val details = dao.getDetailsByUpc(barcode).filter { it.PO == po }
        val image = details.firstOrNull()?.ShoeImage

        return PoResponse(
                upc = total.UPC,
                size = total.Size,
                po = total.PO,
                ry = total.RY,
                article = total.Article,
                articleImage = image,
                quantity = total.Total_Qty_Scan,
                zbln = null, // Not stored locally
                khpo = null, // Not stored locally
                country = null, // Not stored locally
                psdt = null, // Not stored locally
                pedt = null, // Not stored locally
                qtyOrder = total.Total_Qty_ERP,
                remainInternal =
                        if (total.Total_Qty_ERP > 0) total.Total_Qty_ERP - total.Total_Qty_Scan
                        else 0,
                doneInternal = total.Total_Qty_Scan,
                lean = total.Line
        )
    }

    // =========================================================================
    // RFID VALIDATION METHODS
    // =========================================================================

    /**
     * Save camera data with RFID to main table (match case)
     */
    suspend fun saveToMainTable(
        cameraData: com.example.usbcam.data.model.CameraData,
        rfidData: com.example.usbcam.data.model.RfidData? = null
    ) {
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
                Synced = 0
            )
            
            dao.insertDetail(detail)
            Log.i("ShoeboxRepo", "Saved to main table")
        } catch (e: Exception) {
            Log.e("ShoeboxRepo", "Failed to save to main table", e)
            throw e
        }
    }

    /**
     * Save mismatch data to RFID detail table
     */
    suspend fun saveToMismatchTable(
        cameraData: com.example.usbcam.data.model.CameraData,
        rfidData: com.example.usbcam.data.model.RfidData,
        mismatchFields: List<String>
    ) {
        try {
            Log.w("ShoeboxRepo", "Saving mismatch: ${cameraData.po}, Fields=$mismatchFields")
            
            val rfidDetail = com.example.usbcam.data.model.ShoeboxDetailRfid(
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
                MismatchFields = com.google.gson.Gson().toJson(mismatchFields),
                // Metadata
                DateScan = cameraData.dateScan,
                Modify = getCurrentTime(),
                ShoeImage = cameraData.shoeImage,
                User_Serial_Key = cameraData.userSerialKey ?: "DEVICE",
                Line = cameraData.line,
                Synced = 0
            )
            
            dao.insertRfidDetail(rfidDetail)
            Log.i("ShoeboxRepo", "Saved to RFID mismatch table")
        } catch (e: Exception) {
            Log.e("ShoeboxRepo", "Failed to save to RFID table", e)
            throw e
        }
    }

    /**
     * Sync all pending data including RFID mismatches
     */
    suspend fun syncPendingData(): com.example.usbcam.data.model.Result<com.example.usbcam.data.model.SyncStats> {
        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failureCount = 0
        
        try {
            Log.d("ShoeboxRepo", "Starting sync...")
            
            // Sync normal details
            val unsyncedDetails = dao.getUnsyncedDetails()
            Log.d("ShoeboxRepo", "Syncing ${unsyncedDetails.size} normal details...")
            
            unsyncedDetails.forEach { detail ->
                try {
                    val response = apiService.syncDetail(detail)
                    if (response.isSuccessful) {
                        dao.updateDetailSynced(detail.id)
                        successCount++
                    } else {
                        failureCount++
                    }
                } catch (e: Exception) {
                    Log.e("ShoeboxRepo", "Sync detail ${detail.id} failed", e)
                    failureCount++
                }
            }
            
            // Sync RFID mismatch details
            val unsyncedRfidDetails = dao.getUnsyncedRfidDetails()
            Log.d("ShoeboxRepo", "Syncing ${unsyncedRfidDetails.size} RFID mismatch details...")
            
            unsyncedRfidDetails.forEach { rfidDetail ->
                try {
                    val response = apiService.syncRfidMismatch(rfidDetail)
                    if (response.isSuccessful) {
                        // Option 1: Mark as synced
                        dao.updateRfidDetailSynced(rfidDetail.id)
                        // Option 2: Delete after sync (uncomment if preferred)
                        // dao.deleteRfidDetail(rfidDetail.id)
                        successCount++
                    } else {
                        failureCount++
                    }
                } catch (e: Exception) {
                    Log.e("ShoeboxRepo", "Sync RFID detail ${rfidDetail.id} failed", e)
                    failureCount++
                }
            }
            
            // Sync totals
            val unsyncedTotals = dao.getUnsyncedTotals()
            unsyncedTotals.forEach { total ->
                try {
                    val response = apiService.syncTotal(total)
                    if (response.isSuccessful) {
                        dao.updateTotalSynced(total.id)
                        successCount++
                    } else {
                        failureCount++
                    }
                } catch (e: Exception) {
                    failureCount++
                }
            }
            
            val totalItems = unsyncedDetails.size + unsyncedRfidDetails.size + unsyncedTotals.size
            val stats = com.example.usbcam.data.model.SyncStats(
                totalItems = totalItems,
                successCount = successCount,
                failureCount = failureCount,
                duration = System.currentTimeMillis() - startTime
            )
            
            Log.i("ShoeboxRepo", "Sync complete: $stats")
            return com.example.usbcam.data.model.Result.Success(stats)
            
        } catch (e: Exception) {
            Log.e("ShoeboxRepo", "Sync error", e)
            return com.example.usbcam.data.model.Result.Error(e, "Sync failed: ${e.message}")
        }
    }
}
