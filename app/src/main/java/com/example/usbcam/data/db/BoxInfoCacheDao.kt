package com.example.usbcam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.usbcam.data.model.BoxInfoCache

@Dao
interface BoxInfoCacheDao {

    /** Chèn hàng loạt, bỏ qua xung đột (dùng khi load từng page) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BoxInfoCache>)

    /** Xoá toàn bộ cache cũ trước khi load mới */
    @Query("DELETE FROM Box_Info_Cache")
    suspend fun clearAll()

    /** Tra cứu theo UPC + PO — thay thế API select-po */
    @Query("SELECT * FROM Box_Info_Cache WHERE UPC = :upc AND PO = :po LIMIT 1")
    suspend fun findByUpcAndPo(upc: String, po: String): BoxInfoCache?

    /** Tra cứu theo UPC (khi không có PO) */
    @Query("SELECT * FROM Box_Info_Cache WHERE UPC = :upc LIMIT 1")
    suspend fun findByUpc(upc: String): BoxInfoCache?

    /** Tra cứu theo PO (khi không có UPC) */
    @Query("SELECT * FROM Box_Info_Cache WHERE PO = :po LIMIT 1")
    suspend fun findByPo(po: String): BoxInfoCache?

    /** Đếm tổng số bản ghi hiện có trong cache */
    @Query("SELECT COUNT(*) FROM Box_Info_Cache")
    suspend fun count(): Int

    /** Lấy timestamp bản ghi cache mới nhất */
    @Query("SELECT MAX(CachedAt) FROM Box_Info_Cache")
    suspend fun lastCachedAt(): Long?
}
