package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

data class DataRfid(
    @SerializedName("rfid") val rfid: String,
    @SerializedName("model") val model: String,
    @SerializedName("article") val article: String,
    @SerializedName("barcode") val barcode: String,
    @SerializedName("po") val po: String,
    @SerializedName("color") val color: String,
    @SerializedName("size") val size: String
)
