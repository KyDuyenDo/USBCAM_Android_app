package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

// /api/get-data-erp-table
data class ErpTableItem(
    @SerializedName("RY")       val ry: String?,
    @SerializedName("GXLB")     val gxlb: String?,
    @SerializedName("Qty")      val qty: Int?,
    @SerializedName("ARTICLE")  val article: String?,
    @SerializedName("Model")    val model: String?,
    @SerializedName("USERDATE") val userDate: String?
)

// /api/get-data-erp-header
data class ErpHeaderItem(
    @SerializedName("SCDate")   val scDate: String?,
    @SerializedName("Fty")      val fty: String?,
    @SerializedName("DepName")  val depName: String?,
    @SerializedName("Hours")    val hours: Double?,
    @SerializedName("Pairs")    val pairs: Int?,
    @SerializedName("USERID")   val userId: String?,
    @SerializedName("USERDATE") val userDate: String?,
    @SerializedName("ProNo")    val proNo: String?
)

// /api/get-detail-data-erp
data class ErpDetailItem(
    @SerializedName("SIZE")     val size: String?,
    @SerializedName("Quantity") val quantity: Int?,
    @SerializedName("Qtyed")    val qtyed: Int?
)

// /api/get-data-scan
data class ScanSummaryItem(
    @SerializedName("DepName")    val depName: String?,
    @SerializedName("SCDate")     val scDate: String?,
    @SerializedName("DepID")      val depId: String?,
    @SerializedName("Scannedqty") val scannedQty: Int?,
    @SerializedName("QtyERP")     val qtyErp: Int?,
    @SerializedName("AdjustQty")  val adjustQty: Int?
)


// /api/get-data-scan-table  &  /api/get-data-scan-detail
data class ScanTableItem(
    @SerializedName("RY")         val ry: String?,
    @SerializedName("Article")    val article: String?,
    @SerializedName("Model")      val model: String?,
    @SerializedName("OrderQty")   val orderQty: Int?,
    @SerializedName("Scannedqty") val scannedQty: Int?,
    @SerializedName("ERPQty")     val erpQty: Int?,
    @SerializedName("AdjustQty")  val adjustQty: Int?,
    @SerializedName("Remaining")  val remaining: Int?,
    @SerializedName("DateID")     val dateId: String?
)


// /api/get-report-by-hours
data class HourlyReportItem(
    @SerializedName("cycle")           val cycle: String?,
    @SerializedName("Timespan")        val timespan: String?,
    @SerializedName("assembly_target") val target: Int?,
    @SerializedName("assembly_output") val output: Int?,
    @SerializedName("Remain")          val remain: Int?
)

// /api/get-report-by-ry
data class RyReportItem(
    @SerializedName("RY")       val ry: String?,
    @SerializedName("PO")       val po: String?,
    @SerializedName("Qtyed")    val scannedQty: Int?,
    @SerializedName("OrderQty") val orderQty: Int?,
    @SerializedName("Remain")   val remaining: Int?,
    @SerializedName("Article")  val article: String?,
    @SerializedName("Model")    val model: String?
)

// /api/get-report-detail-by-ry
data class RyDetailItem(
    @SerializedName("SIZE")     val size: String?,
    @SerializedName("Qtyed")    val qtyed: Int?,
    @SerializedName("OrderQty") val orderQty: Int?,
    @SerializedName("Remain")   val remain: Int?
)