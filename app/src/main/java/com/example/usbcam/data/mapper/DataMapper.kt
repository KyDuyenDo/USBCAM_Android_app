package com.example.usbcam.data.mapper

import com.example.usbcam.api.PoResponse
import com.example.usbcam.data.model.CameraData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extension functions to map between different data models
 */

/**
 * Convert PoResponse (from API) to CameraData (for validation/storage)
 * 
 * @param po PO number from camera scan
 * @param upc UPC barcode from camera scan
 * @return CameraData object ready for validation or storage
 */
fun PoResponse.toCameraData(po: String, upc: String): CameraData {
    return CameraData(
        po = po,
        upc = upc,
        ry = this.ry,
        size = this.size,
        article = this.article,
        qty = 1,  // Each scan counts as 1 item
        shoeImage = this.articleImage,
        dateScan = getCurrentTime(),
        userSerialKey = "DEVICE",
        line = this.lean
    )
}

/**
 * Get current timestamp in database format
 */
private fun getCurrentTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
