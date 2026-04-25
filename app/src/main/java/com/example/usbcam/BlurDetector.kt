package com.example.usbcam

import android.graphics.Bitmap
import android.util.Log

/**
 * Blur detector không dùng OpenCV.
 * Thuật toán: Tính variance của pixel intensity trên ROI trung tâm của Bitmap.
 * Variance thấp → ảnh mờ. Variance cao → ảnh sắc nét.
 */
class BlurDetector {

    companion object {
        private const val TAG = "BlurDetector"
    }

    /**
     * Kiểm tra xem bitmap có đủ sắc nét không.
     * @param bitmap Bitmap màu (ARGB_8888) cần kiểm tra.
     * @return true nếu ảnh sắc nét, false nếu bị mờ.
     */
    fun check(bitmap: Bitmap): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height

            // Lấy ROI trung tâm
            val roiW = (w * Config.ROI_WIDTH_RATIO).toInt().coerceAtLeast(10)
            val roiH = (h * Config.ROI_HEIGHT_RATIO).toInt().coerceAtLeast(10)
            val roiX = (w - roiW) / 2
            val roiY = (h - roiH) / 2

            val variance = computeLuminanceVariance(bitmap, roiX, roiY, roiW, roiH)
            val isSharp = variance > Config.BLUR_THRESHOLD

            Log.v(TAG, "Blur check: variance=${"%.2f".format(variance)}, threshold=${Config.BLUR_THRESHOLD}, isSharp=$isSharp")
            isSharp

        } catch (e: Exception) {
            Log.e(TAG, "Blur check error", e)
            false
        }
    }

    /**
     * Kiểm tra xem data NV21 có đủ sắc nét không.
     */
    fun check(data: ByteArray, width: Int, height: Int): Boolean {
        return try {
            // Lấy ROI trung tâm
            val roiW = (width * Config.ROI_WIDTH_RATIO).toInt().coerceAtLeast(10)
            val roiH = (height * Config.ROI_HEIGHT_RATIO).toInt().coerceAtLeast(10)
            val roiX = (width - roiW) / 2
            val roiY = (height - roiH) / 2

            val variance = computeLuminanceVarianceNV21(data, width, height, roiX, roiY, roiW, roiH)
            val isSharp = variance > Config.BLUR_THRESHOLD

            Log.v(TAG, "Blur check (NV21): variance=${"%.2f".format(variance)}, threshold=${Config.BLUR_THRESHOLD}, isSharp=$isSharp")
            isSharp

        } catch (e: Exception) {
            Log.e(TAG, "Blur check error (NV21)", e)
            false
        }
    }

    /**
     * Tính variance của luminance (độ sáng) trong vùng ROI.
     * Dùng công thức: Var = E[X²] - E[X]² để tránh 2 pass.
     */
    private fun computeLuminanceVariance(
        bitmap: Bitmap,
        roiX: Int, roiY: Int,
        roiW: Int, roiH: Int
    ): Double {
        // Sample pixels để tăng tốc (lấy mỗi 2 pixels thay vì toàn bộ)
        val step = 2
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        var y = roiY
        while (y < roiY + roiH) {
            var x = roiX
            while (x < roiX + roiW) {
                val pixel = bitmap.getPixel(x, y)
                // Luminance từ RGB (BT.601)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b

                sum += lum
                sumSq += lum * lum
                count++
                x += step
            }
            y += step
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /**
     * Tính variance của luminance từ data NV21.
     * Vùng Y nằm ở đầu mảng byte, size = width * height.
     */
    private fun computeLuminanceVarianceNV21(
        data: ByteArray,
        width: Int, height: Int,
        roiX: Int, roiY: Int,
        roiW: Int, roiH: Int
    ): Double {
        val step = 2
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        var y = roiY
        while (y < roiY + roiH) {
            var x = roiX
            val rowOffset = y * width
            while (x < roiX + roiW) {
                // NV21 luminance is directly in the first part of the array
                val lum = (data[rowOffset + x].toInt() and 0xFF).toDouble()

                sum += lum
                sumSq += lum * lum
                count++
                x += step
            }
            y += step
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /** Không cần release vì không giữ native resources */
    fun release() {
        // No-op: không dùng OpenCV Mat
    }
}