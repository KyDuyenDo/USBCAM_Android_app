package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * ObjectDetector — không còn dùng OpenCV.
 * Thuật toán: Frame differencing trên grayscale của Bitmap.
 * So sánh luminance của từng pixel với frame trước,
 * nếu số pixel thay đổi vượt ngưỡng → có vật thể chuyển động.
 */
class ObjectDetector {

    companion object {
        private const val TAG = "ObjectDetector"
        // Sample step để tăng tốc (mỗi 4 pixel)
        private const val SAMPLE_STEP = 4
    }

    // Lưu luminance array của frame trước
    private var prevLuminance: IntArray? = null
    private var prevWidth = 0
    private var prevHeight = 0

    /**
     * Phát hiện chuyển động bằng frame differencing trên Bitmap.
     * @return true nếu phát hiện đủ chuyển động.
     */
    fun detect(bitmap: Bitmap): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height

            // Tạo array luminance của frame hiện tại (sampled)
            val current = extractSampledLuminance(bitmap, w, h)

            val prev = prevLuminance
            // Lưu frame hiện tại làm frame trước cho lần sau
            prevLuminance = current
            prevWidth = w
            prevHeight = h

            if (prev == null || prev.size != current.size) {
                // Frame đầu tiên hoặc kích thước thay đổi
                return false
            }

            // Đếm số pixel thay đổi vượt ngưỡng
            var changedCount = 0
            val threshold = Config.OBJECT_MOTION_THRESHOLD.toInt()

            for (i in current.indices) {
                if (Math.abs(current[i] - prev[i]) > threshold) {
                    changedCount++
                }
            }

            val totalSampled = current.size
            val ratio = if (totalSampled > 0) changedCount.toDouble() / totalSampled else 0.0
            val isObjectPresent = ratio > Config.OBJECT_MIN_CHANGE_RATIO

            if (isObjectPresent) {
                Log.v(TAG, "Object detected! Change ratio: ${"%.4f".format(ratio)}")
            }

            isObjectPresent

        } catch (e: Exception) {
            Log.e(TAG, "Object detection error", e)
            false
        }
    }

    /** Lấy mảng luminance có sampling để tăng tốc */
    private fun extractSampledLuminance(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val cols = w / SAMPLE_STEP
        val rows = h / SAMPLE_STEP
        val result = IntArray(cols * rows)
        var idx = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                result[idx++] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return result
    }

    /** Không có native resources cần release */
    fun release() {
        prevLuminance = null
    }
}
