package com.example.usbcam

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class POExtractor {

    companion object {
        private const val TAG = "POExtractor"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrFusion = OCRFusion()

    fun extract(bitmap: Bitmap, barcode: String): String? {
        val w = bitmap.width
        val h = bitmap.height
        val roiW = (w * 0.8).toInt()
        val roiH = (h * 0.6).toInt()
        val roiX = (w - roiW) / 2
        val roiY = (h - roiH) / 2

        try {
            val poBitmap = Bitmap.createBitmap(bitmap, roiX, roiY, roiW, roiH)
            val image = InputImage.fromBitmap(poBitmap, 0)

            val task = textRecognizer.process(image)
            val visionText = Tasks.await(task) // Synchronous!

            poBitmap.recycle()

            val lines = visionText.text.split("\n", " ")
            for (line in lines) {
                val clean = line.trim()
                if (clean.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH &&
                                clean.all { it.isDigit() }
                ) {
                    ocrFusion.add(clean)
                    val fused = ocrFusion.getFused()
                    if (fused != null) {
                        Log.i(TAG, "PO FOUND (Fused): $fused")
                        return fused
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PO Extraction Error", e)
        }
        return null
    }

    fun reset() {
        ocrFusion.reset()
    }

    fun release() {
        try {
            textRecognizer.close()
        } catch (_: Exception) {}
    }
}
