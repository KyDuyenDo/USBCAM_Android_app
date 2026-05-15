package com.example.usbcam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxTotal

@Dao
interface ShoeboxDao {

    // --- Detail Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetail(detail: ShoeboxDetail): Long

    @Query("SELECT * FROM Data_Shoebox_Detail ORDER BY id DESC LIMIT 1")
    suspend fun getLatestDetail(): ShoeboxDetail?

    @Query("SELECT * FROM Data_Shoebox_Detail WHERE Synced = 0")
    suspend fun getUnsyncedDetails(): List<ShoeboxDetail>

    @Query("UPDATE Data_Shoebox_Detail SET Synced = 1 WHERE id = :id")
    suspend fun updateDetailSynced(id: Long)

    @Query("SELECT * FROM Data_Shoebox_Detail WHERE UPC = :upc")
    suspend fun getDetailsByUpc(upc: String): List<ShoeboxDetail>

    // --- Total Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTotal(total: ShoeboxTotal)

    @Query("SELECT * FROM Data_Shoebox_Total WHERE UPC = :upc AND PO = :po ORDER BY id DESC LIMIT 1")
    suspend fun getTotalByUpcAndPo(upc: String, po: String): ShoeboxTotal?
    
    @Query("SELECT * FROM Data_Shoebox_Total WHERE Synced = 0")
    suspend fun getUnsyncedTotals(): List<ShoeboxTotal>

    @Query("UPDATE Data_Shoebox_Total SET Synced = 1 WHERE id = :id")
    suspend fun updateTotalSynced(id: Long)

    // --- Stats for UI ---
    @Query("SELECT * FROM Data_Shoebox_Detail WHERE DateScan BETWEEN :startTime AND :endTime")
    suspend fun getDetailsInTimeRange(startTime: String, endTime: String): List<ShoeboxDetail>

    // --- RFID Detail Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRfidDetail(rfidDetail: com.example.usbcam.data.model.ShoeboxDetailRfid): Long

    @Query("SELECT * FROM Data_Shoebox_RFID_Detail WHERE Synced = 0")
    suspend fun getUnsyncedRfidDetails(): List<com.example.usbcam.data.model.ShoeboxDetailRfid>

    @Query("UPDATE Data_Shoebox_RFID_Detail SET Synced = 1 WHERE id = :id")
    suspend fun updateRfidDetailSynced(id: Long)

    @Query("DELETE FROM Data_Shoebox_RFID_Detail WHERE id = :id")
    suspend fun deleteRfidDetail(id: Long)

    // --- Total Modify Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTotalModify(totalModify: com.example.usbcam.data.model.ShoeboxTotalModify)

    @Query("SELECT * FROM Data_Shoebox_Total_Modify WHERE Synced = 0")
    suspend fun getUnsyncedTotalModifies(): List<com.example.usbcam.data.model.ShoeboxTotalModify>

    @Query("SELECT * FROM Data_Shoebox_Total_Modify WHERE UPC = :upc AND PO = :po AND DateScan LIKE :datePrefix || '%' LIMIT 1")
    suspend fun getTotalModifyByUpcPoAndDate(upc: String, po: String, datePrefix: String): com.example.usbcam.data.model.ShoeboxTotalModify?

    @Query("UPDATE Data_Shoebox_Total_Modify SET Synced = 1 WHERE Shoebox_Total_Serial = :serial")
    suspend fun updateTotalModifySynced(serial: Long)
}
