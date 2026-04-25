package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * PresenceDetector — không còn dùng OpenCV.
 *
 * Thuật toán thay thế (pure Android):
 * 1. Tính gradient ngang đơn giản (|pixel[x] - pixel[x-1]|) bằng luminance array
 * 2. Threshold gradient để tạo "binary edge map"
 * 3. Đếm vùng edge liên tục (approximated bằng column-wise sum)
 * 4. Kiểm tra density và aspect ratio của vùng edge lớn nhất
 *
 * Đây là phiên bản đơn giản hóa nhưng đủ để phát hiện barcode stripe pattern.
 */
class PresenceDetector {

    companion object {
        private const val TAG = "PresenceDetector"

        // Bước sampling để tăng tốc
        private const val SAMPLE_STEP = 3

        // Ngưỡng gradient để coi là edge
        private const val EDGE_THRESHOLD = 30

        // Tỷ lệ diện tích tối thiểu so với frame (3%)
        private const val MIN_AREA_RATIO = 0.03

        // Aspect ratio tối thiểu của vùng barcode (rộng hơn cao)
        private const val MIN_ASPECT_RATIO = 1.0

        // Density của barcode texture (5%–45% white pixels)
        private const val MIN_DENSITY = 0.05
        private const val MAX_DENSITY = 0.45
    }

    fun detect(bitmap: Bitmap): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height

            // 1. Tính horizontal gradient map (sampled)
            val edgeCols = w / SAMPLE_STEP
            val edgeRows = h / SAMPLE_STEP

            // Tổng edge theo mỗi column (để tìm vùng barcode theo chiều ngang)
            val colEdgeSum = IntArray(edgeCols)
            val rowEdgeSum = IntArray(edgeRows)

            var totalEdge = 0
            var ri = 0
            var y = SAMPLE_STEP
            while (y < h) {
                var ci = 0
                var x = SAMPLE_STEP
                while (x < w) {
                    val lum    = luminance(bitmap.getPixel(x, y))
                    val lumL   = luminance(bitmap.getPixel(x - SAMPLE_STEP, y))
                    val lumU   = luminance(bitmap.getPixel(x, y - SAMPLE_STEP))
                    val grad   = maxOf(Math.abs(lum - lumL), Math.abs(lum - lumU))
                    val isEdge = if (grad > EDGE_THRESHOLD) 1 else 0

                    colEdgeSum[ci] += isEdge
                    rowEdgeSum[ri] += isEdge
                    totalEdge += isEdge
                    ci++
                    x += SAMPLE_STEP
                }
                ri++
                y += SAMPLE_STEP
            }

            val totalSampled = edgeCols * edgeRows
            val frameArea    = totalSampled

            // 2. Tìm bounding box của vùng edge đủ lớn
            val edgeDensityGlobal = totalEdge.toDouble() / frameArea
            if (edgeDensityGlobal < MIN_AREA_RATIO) return false

            // 3. Tìm span cột và hàng có edge đủ cao
            val colThreshold = edgeRows * 0.05  // 5% rows cần có edge
            val rowThreshold = edgeCols * 0.05

            var colMin = edgeCols; var colMax = -1
            for (ci in colEdgeSum.indices) {
                if (colEdgeSum[ci] >= colThreshold) {
                    if (ci < colMin) colMin = ci
                    if (ci > colMax) colMax = ci
                }
            }

            var rowMin = edgeRows; var rowMax = -1
            for (ri2 in rowEdgeSum.indices) {
                if (rowEdgeSum[ri2] >= rowThreshold) {
                    if (ri2 < rowMin) rowMin = ri2
                    if (ri2 > rowMax) rowMax = ri2
                }
            }

            if (colMax < colMin || rowMax < rowMin) return false

            val bboxW = (colMax - colMin + 1).toDouble()
            val bboxH = (rowMax - rowMin + 1).toDouble()
            val bboxArea = bboxW * bboxH

            // 4. Kiểm tra area ratio
            if (bboxArea / frameArea < MIN_AREA_RATIO) return false

            // 5. Kiểm tra aspect ratio
            val aspectRatio = bboxW / bboxH.coerceAtLeast(1.0)
            if (aspectRatio < MIN_ASPECT_RATIO) return false

            // 6. Kiểm tra barcode texture density trong vùng bbox
            val edgeInBbox = (colMin..colMax).sumOf { ci ->
                val rowContrib = (rowMin..rowMax).count { ri2 ->
                    // Xấp xỉ: dùng rowEdgeSum thay vì scan lại từng pixel
                    rowEdgeSum[ri2] > 0
                }
                if (colEdgeSum[ci] > 0) rowContrib else 0
            }

            val density = edgeInBbox.toDouble() / bboxArea
            val isValidTexture = density in MIN_DENSITY..MAX_DENSITY

            Log.v(TAG, "Presence: aspectRatio=${"%.2f".format(aspectRatio)}, " +
                    "bboxArea/frame=${"%.3f".format(bboxArea / frameArea)}, " +
                    "density=${"%.3f".format(density)}, valid=$isValidTexture")

            isValidTexture

        } catch (e: Exception) {
            Log.e(TAG, "Presence error", e)
            false
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /** Không có native resources */
    fun release() {
        // No-op
    }
}