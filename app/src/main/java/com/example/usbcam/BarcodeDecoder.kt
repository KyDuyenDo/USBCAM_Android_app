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
            val image = InputImage.fromBitmap(bitmap, 0)
            return processImage(image)
        } catch (e: Exception) {
            Log.e(TAG, "Scan #$scanCount - ERROR", e)
            return null
        }
    }

    fun scan(data: ByteArray, width: Int, height: Int): Result? {
        scanCount++
        try {
            // format=NV21, rotation=0
            val image = InputImage.fromByteArray(
                data,
                width,
                height,
                0,
                InputImage.IMAGE_FORMAT_NV21
            )
            return processImage(image)
        } catch (e: Exception) {
            Log.e(TAG, "Scan #$scanCount (NV21) - ERROR", e)
            return null
        }
    }

    private fun processImage(image: InputImage): Result? {
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
    }

    fun close() {
        Log.d(TAG, "Closing scanner (total scans: $scanCount)")
        scanner.close()
    }
}
