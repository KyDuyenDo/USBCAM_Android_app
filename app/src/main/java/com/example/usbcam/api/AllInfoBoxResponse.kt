package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

/**
 * Response từ API all-info-box?page=X&pagesize=Y
 */
data class AllInfoBoxResponse(
    @SerializedName("data")    val data: List<BoxInfoItem>,
    @SerializedName("total")   val total: Int,
    @SerializedName("totalPages") val totalPages: Int
)

data class BoxInfoItem(
    @SerializedName("UPC")           val upc: String?,
    @SerializedName("SIZE")          val size: String?,
    @SerializedName("PO")            val po: String?,
    @SerializedName("RY")            val ry: String?,
    @SerializedName("Article")       val article: String?,
    @SerializedName("Article_Image") val articleImage: String?,
    @SerializedName("Quantity")      val quantity: Int?,
    @SerializedName("TotalCount")    val totalCount: Int?
)
