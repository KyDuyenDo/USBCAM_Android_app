package com.example.usbcam

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class BlurDetector {

    companion object {
        private const val TAG = "BlurDetector"
    }

    private val laplacian = Mat()
    private val mean = MatOfDouble()
    private val std = MatOfDouble()

    fun check(roi: Mat): Boolean {
        // ✅ CRITICAL: Track ROI for cleanup
        var centerRoi: Mat? = null

        return try {
            val w = roi.cols()
            val h = roi.rows()
            val rw = (w * Config.ROI_WIDTH_RATIO).toInt()
            val rh = (h * Config.ROI_HEIGHT_RATIO).toInt()
            val centerH = (w - rw) / 2
            val centerV = (h - rh) / 2

            centerRoi = roi.submat(Rect(centerH, centerV, rw, rh))

            Imgproc.Laplacian(centerRoi, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, std)
            val variance = std.get(0, 0)[0].let { it * it }

            val isSharp = variance > Config.BLUR_THRESHOLD
            isSharp

        } catch (e: Exception) {
            Log.e(TAG, "Blur check error", e)
            false
        } finally {
            // ✅ CRITICAL: Always cleanup ROI
            centerRoi?.release()
        }
    }

    fun release() {
        try {
            laplacian.release()
            mean.release()
            std.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}