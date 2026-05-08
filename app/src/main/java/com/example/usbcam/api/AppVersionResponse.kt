package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

/**
 * Response từ API api/app-version
 */
data class AppVersionResponse(
    @SerializedName("id")          val id: Int?,
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String?,
    @SerializedName("apkUrl")      val apkUrl: String?,
    @SerializedName("description") val description: String?
)
