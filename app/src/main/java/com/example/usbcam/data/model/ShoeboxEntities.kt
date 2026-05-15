package com.example.usbcam.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Data_Shoebox_Detail")
data class ShoeboxDetail(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Qty: Int,
    val DateScan: String, // Format: yyyy-MM-dd HH:mm:ss
    val Modify: String?,
    val Article: String?,
    val ShoeImage: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    var Synced: Int = 0 // 0: Not synced, 1: Synced
)

@Entity(tableName = "Data_Shoebox_Total")
data class ShoeboxTotal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Total_Qty_Scan: Int,
    val Total_Qty_ERP: Int,
    val Article: String?,
    val DateScan: String?,
    val Modify: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    var Synced: Int = 0
)
@Entity(tableName = "Data_Shoebox_RFID_Detail")
data class ShoeboxDetailRfid(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Camera Data
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Qty: Int,
    val Article: String?,
    // RFID Data
    val RFID: String?,
    val Size_RFID: String?,
    val PO_RFID: String?,
    val UPC_RFID: String?,
    val Article_RFID: String?,
    val RY_RFID: String?,
    // Mismatch tracking
    val MismatchFields: String?, // JSON array: ["PO", "Size", "Article"]
    // Metadata
    val DateScan: String,
    val Modify: String?,
    val ShoeImage: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    var Synced: Int = 0 // 0: Not synced, 1: Synced
)
@Entity(tableName = "Data_Shoebox_Total_Modify")
data class ShoeboxTotalModify(
    @PrimaryKey(autoGenerate = true) val Shoebox_Total_Serial: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Total_Qty_Scan: Int,
    val Total_Qty_ERP: Int,
    val Article: String?,
    val DateScan: String?,
    val Modify: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    val Total_FQty_Scan: Int,
    var Synced: Int = 0 // 0: Not synced, 1: Synced
)
