package com.example.usbcam

import android.util.Log
import java.util.ArrayList
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class PresenceDetector {

    companion object {
        private const val TAG = "PresenceDetector"
    }

    // ================= OPENCV BUFFERS =================
    private val gradX = Mat()
    private val gradY = Mat()
    private val absGradX = Mat()
    private val absGradY = Mat()
    private val fullGrad = Mat()

    private val blurredMat = Mat()
    private val thresholdMat = Mat()
    private val morphMat = Mat()
    private val hierarchy = Mat()

    // Morphology kernels
    private val closeKernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Config.MORPH_CLOSE_KERNEL)
    private val erodeKernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Config.MORPH_ERODE_KERNEL)

    fun detect(gray: Mat): Boolean {
        try {
            // 1. Sobel Gradient
            Imgproc.Sobel(gray, gradX, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(gray, gradY, CvType.CV_16S, 0, 1)

            Core.convertScaleAbs(gradX, absGradX, Config.GRADIENT_SCALE, 0.0)
            Core.convertScaleAbs(gradY, absGradY, Config.GRADIENT_SCALE, 0.0)
            Core.subtract(absGradX, absGradY, fullGrad)

            // 2. Blur & Threshold
            Imgproc.blur(fullGrad, blurredMat, Config.BLUR_KERNEL_SIZE)
            Imgproc.threshold(
                    blurredMat,
                    thresholdMat,
                    Config.BINARY_THRESHOLD,
                    255.0,
                    Imgproc.THRESH_BINARY
            )

            // 3. Morphology
            Imgproc.morphologyEx(
                    thresholdMat,
                    morphMat,
                    Imgproc.MORPH_CLOSE,
                    closeKernel,
                    Point(-1.0, -1.0),
                    Config.MORPH_CLOSE_ITERATIONS
            )
            Imgproc.morphologyEx(
                    morphMat,
                    morphMat,
                    Imgproc.MORPH_ERODE,
                    erodeKernel,
                    Point(-1.0, -1.0),
                    Config.MORPH_ERODE_ITERATIONS
            )

            // 4. Contours
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                    morphMat,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
            )

            if (contours.isEmpty()) return false

            var largestArea = 0.0
            var largestContour: MatOfPoint? = null

            for (cnt in contours) {
                val area = Imgproc.contourArea(cnt)
                if (area > largestArea) {
                    largestArea = area
                    largestContour = cnt
                }
            }

            val frameArea = gray.rows() * gray.cols()
            val minArea = frameArea * Config.MIN_BARCODE_AREA_RATIO

            if (largestContour == null || largestArea < minArea) {
                contours.forEach { it.release() }
                return false
            }

            val r = Imgproc.boundingRect(largestContour)
            val ratio = r.width.toDouble() / r.height

            if (ratio < Config.MIN_ASPECT_RATIO) {
                contours.forEach { it.release() }
                return false
            }

            // 5. Texture Validation
            val roiCheck = thresholdMat.submat(r)
            val isValid = validateBarcodeTexture(roiCheck)
            roiCheck.release()

            contours.forEach { it.release() }
            return isValid

        } catch (e: Exception) {
            Log.e(TAG, "Presence error", e)
            return false
        }
    }

    private fun validateBarcodeTexture(binaryROI: Mat): Boolean {
        val cols = binaryROI.cols()
        val rows = binaryROI.rows()
        val totalPixels = rows * cols

        if (totalPixels == 0) return false
        if (rows < 5 || cols < 10) return false

        val nonZero = Core.countNonZero(binaryROI)
        val density = nonZero.toDouble() / totalPixels

        // Config.MIN_TEXTURE_DENSITY was 0.05, matching hardcoded range here
        // Using hardcoded values as per original implementation logic comment
        return density in 0.05..0.45
    }

    fun release() {
        try {
            gradX.release()
            gradY.release()
            absGradX.release()
            absGradY.release()
            fullGrad.release()

            blurredMat.release()
            thresholdMat.release()
            morphMat.release()
            hierarchy.release()

            closeKernel.release()
            erodeKernel.release()
        } catch (_: Exception) {}
    }
}
