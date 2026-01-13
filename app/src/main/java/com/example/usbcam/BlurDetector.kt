package com.example.usbcam

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class BlurDetector {

    private val laplacian = Mat()
    private val mean = MatOfDouble()
    private val std = MatOfDouble()

    fun check(roi: Mat): Boolean {
        return try {
            val w = roi.cols()
            val h = roi.rows()
            val rw = (w * Config.ROI_WIDTH_RATIO).toInt()
            val rh = (h * Config.ROI_HEIGHT_RATIO).toInt()
            val centerH = (w - rw) / 2
            val centerV = (h - rh) / 2
            val centerRoi = roi.submat(Rect(centerH, centerV, rw, rh))

            Imgproc.Laplacian(centerRoi, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, std)
            val variance = std.get(0, 0)[0].let { it * it }

            centerRoi.release()

            val isSharp = variance > Config.BLUR_THRESHOLD
            isSharp
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        try {
            laplacian.release()
            mean.release()
            std.release()
        } catch (_: Exception) {}
    }
}
