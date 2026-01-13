package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

class BarcodeDecoder {

    private val TAG = "BarcodeDecoder"
    private val scanner = BarcodeScanning.getClient()
    private var scanCount = 0

    data class Result(val box: RectF, val value: String)

    fun scan(bitmap: Bitmap): Result? {
        scanCount++

        try {
            // Log.v(TAG, "Scan #$scanCount - Processing ${bitmap.width}x${bitmap.height} bitmap")

            val image = InputImage.fromBitmap(bitmap, 0)
            val task = scanner.process(image)
            Tasks.await(task)

            val barcodes = task.result

            if (barcodes.isNullOrEmpty()) {
                return null
            }

            val barcode = barcodes[0]
            val rawValue = barcode.rawValue ?: return null
            val box = barcode.boundingBox ?: return null

            return Result(box = RectF(box), value = rawValue)
        } catch (e: Exception) {
            Log.e(TAG, "Scan #$scanCount - ERROR", e)
            return null
        }
    }

    fun close() {
        Log.d(TAG, "Closing scanner (total scans: $scanCount)")
        scanner.close()
    }
}
