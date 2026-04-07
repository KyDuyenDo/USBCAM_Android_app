package com.example.usbcam

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ObjectDetector {

    companion object {
        private const val TAG = "ObjectDetector"
    }

    private val prevFrame = Mat()
    private val diffFrame = Mat()
    private val thresholdFrame = Mat()

    /**
     * Detects if an object is present based on frame differencing.
     * Returns true if there is significant change.
     */
    fun detect(gray: Mat): Boolean {
        try {
            if (prevFrame.empty()) {
                gray.copyTo(prevFrame)
                return false
            }

            // Simple frame differencing
            Core.absdiff(gray, prevFrame, diffFrame)
            Imgproc.threshold(
                diffFrame,
                thresholdFrame,
                Config.OBJECT_MOTION_THRESHOLD,
                255.0,
                Imgproc.THRESH_BINARY
            )

            val nonZero = Core.countNonZero(thresholdFrame)
            val totalPixels = gray.rows() * gray.cols()
            val ratio = if (totalPixels > 0) nonZero.toDouble() / totalPixels else 0.0

            // Update prevFrame for next call
            gray.copyTo(prevFrame)

            val isObjectPresent = ratio > Config.OBJECT_MIN_CHANGE_RATIO
            
            if (isObjectPresent) {
                Log.v(TAG, "Object detected! Change ratio: ${String.format("%.4f", ratio)}")
            }

            return isObjectPresent

        } catch (e: Exception) {
            Log.e(TAG, "Object detection error", e)
            return false
        }
    }

    fun release() {
        try {
            prevFrame.release()
            diffFrame.release()
            thresholdFrame.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}
