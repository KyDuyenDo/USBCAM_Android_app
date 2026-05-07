package com.example.usbcam.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.usbcam.api.PoResponse

/**
 * Cache entity lưu toàn bộ dữ liệu từ API all-info-box vào SQLite.
 * Index trên UPC và PO để tra cứu nhanh thay thế gọi API select-po.
 */
@Entity(
    tableName = "Box_Info_Cache",
    indices = [
        Index(value = ["UPC"]),
        Index(value = ["PO"]),
        Index(value = ["UPC", "PO"])
    ]
)
data class BoxInfoCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val UPC: String?,
    val SIZE: String?,
    val PO: String?,
    val RY: String?,
    val Article: String?,
    val Article_Image: String?,
    val Quantity: Int?,
    val CachedAt: Long = System.currentTimeMillis() // Timestamp lưu cache
)

/** Chuyển entity cache thành PoResponse để dùng chung với logic hiện có */
fun BoxInfoCache.toPoResponse() = PoResponse(
    upc          = UPC,
    size         = SIZE,
    po           = PO,
    ry           = RY,
    article      = Article,
    articleImage = Article_Image,
    quantity     = Quantity,
    zbln         = null,
    khpo         = null,
    country      = null,
    psdt         = null,
    pedt         = null,
    qtyOrder     = Quantity,
    remainInternal = null,
    doneInternal   = null,
    lean           = null
)

