package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

data class DeviceStatusRequest(
    @SerializedName("line_id")
    val lineId: String,
    
    @SerializedName("camera_connected")
    val cameraConnected: Boolean,
    
    @SerializedName("rfid_connected")
    val rfidConnected: Boolean,
    
    @SerializedName("timestamp")
    val timestamp: String
)
