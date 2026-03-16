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
    suspend fun processScan(po: String, barcode: String, selectedLine: String? = null): com.example.usbcam.data.model.Result<PoResponse> {
        // 1. Try API first
        try {
            val response = apiService.getPoDetailsSuspend(po, barcode)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Log.d("ShoeboxRepo", "API Success: Body not null, saving local... line=$selectedLine")
                    try {
                        saveLocal(po, barcode, body, selectedLine)
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
    // selectedLine: line do user chọn trên UI (ưu tiên dùng), nếu null fallback về data.lean
    suspend fun saveLocal(po: String, barcode: String, data: PoResponse, selectedLine: String? = null) {
        val lineToSave = selectedLine ?: data.lean

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
                        Line = lineToSave,
                        Synced = 0
                )
        dao.insertDetail(detail)

        // 3. Update Total
        updateTotal(po, barcode, data, lineToSave)
    }

    private suspend fun updateTotal(po: String, upc: String, data: PoResponse, lineOverride: String? = null) {
        updateTotal(
            po = po,
            upc = upc,
            ry = data.ry,
            size = data.size,
            article = data.article,
            erpTarget = data.quantity ?: 0,
            lineOverride = lineOverride ?: data.lean
        )
    }

    private suspend fun updateTotal(
        po: String,
        upc: String,
        ry: String?,
        size: String?,
        article: String?,
        erpTarget: Int,
        lineOverride: String? = null
    ) {
        val details = dao.getDetailsByUpc(upc).filter { it.PO == po }
        val totalQty = details.sumOf { it.Qty }
        val lineToSave = lineOverride

        val currentTotal = dao.getTotalByUpcAndPo(upc, po)
        val newTotal =
                if (currentTotal != null) {
                    currentTotal.copy(
                            Total_Qty_ERP = erpTarget,
                            Total_Qty_Scan = totalQty,
                            Modify = getCurrentTime(),
                            Line = lineToSave,
                            Synced = 0
                    )
                } else {
                    ShoeboxTotal(
                            RY = ry,
                            Size = size,
                            PO = po,
                            UPC = upc,
                            Total_Qty_Scan = totalQty,
                            Total_Qty_ERP = erpTarget,
                            Article = article,
                            DateScan = getCurrentTime(),
                            Modify = getCurrentTime(),
                            User_Serial_Key = "DEVICE",
                            Line = lineToSave,
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

    /**
     * Lấy target theo dây chuyền (lean/line).
     * @param depno Mã dây chuyền (line) do user chọn. Nếu null/rỗng, dùng default.
     */
    suspend fun getTargetByTimeSlot(depno: String? = null): TargetResponse? {
        val lean = if (!depno.isNullOrBlank()) depno else "LHGG4G01"
        return try {
            val response = apiService.getTargetByLean(lean)

            if (response.isSuccessful) {
                Log.d("getTargetByTimeSlot", "line=$lean body=${response.body()}")
                response.body()
            } else {
                Log.w("getTargetByTimeSlot", "API error ${response.code()} for line=$lean")
                null
            }
        } catch (e: Exception) {
            Log.e("getTargetByTimeSlot", "API error for line=$lean", e)
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
        rfidData: com.example.usbcam.data.model.RfidData? = null,
        selectedLine: String? = null,
        erpTarget: Int = 0
    ) {
        try {
            Log.d("ShoeboxRepo", "Saving to main table: ${cameraData.po}, line=$selectedLine")
            
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
                Line = selectedLine,
                Synced = 0
            )
            
            dao.insertDetail(detail)

            // Update Total
            updateTotal(
                po = cameraData.po,
                upc = cameraData.upc,
                ry = cameraData.ry,
                size = cameraData.size,
                article = cameraData.article,
                erpTarget = erpTarget,
                lineOverride = selectedLine
            )
            
            Log.i("ShoeboxRepo", "Saved to main table and updated total")
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
        rfidData: com.example.usbcam.data.model.RfidData? = null,
        mismatchFields: List<String>,
        selectedLine: String? = null
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
                RFID = rfidData?.rfidCode,
                Size_RFID = rfidData?.size,
                PO_RFID = rfidData?.po,
                UPC_RFID = rfidData?.upc,
                Article_RFID = rfidData?.article,
                RY_RFID = rfidData?.ry,
                // Mismatch tracking
                MismatchFields = com.google.gson.Gson().toJson(mismatchFields),
                // Metadata
                DateScan = cameraData.dateScan,
                Modify = getCurrentTime(),
                ShoeImage = cameraData.shoeImage,
                User_Serial_Key = cameraData.userSerialKey ?: "DEVICE",
                Line = selectedLine,
                Synced = 0
            )
            
            dao.insertRfidDetail(rfidDetail)
            Log.i("ShoeboxRepo", "Saved to RFID mismatch table")
        } catch (e: Exception) {
            Log.e("ShoeboxRepo", "Failed to save to RFID table", e)
            throw e
        }
    }

}
