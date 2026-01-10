package com.example.usbcam

import android.util.Log
import java.util.ArrayList
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class BoxProcessor {

    companion object {
        private const val TAG = "BoxProcessor"
    }

    // ================= PUBLIC =================
    @Volatile var currentState = AppState.IDLE
    @Volatile var feedbackMessage = "READY"

    @Volatile var barcode: String? = null
    @Volatile var po: String? = null

    // ================= INTERNAL =================
    private var stateStartTime = 0L
    private var errorTime = 0L

    private var presenceFrames = 0
    private var lostFrames = 0
    private var stationaryFrames = 0

    // OCR
    private val ocrFusion = OCRFusion()

    // ================= OPENCV BUFFERS =================
    // Gradient buffers
    private val gradX = Mat()
    private val gradY = Mat()
    private val absGradX = Mat()
    private val absGradY = Mat()
    private val fullGrad = Mat()

    // Processing buffers
    private val blurredMat = Mat()
    private val thresholdMat = Mat()
    private val morphMat = Mat()
    private val hierarchy = Mat()

    // Morphology kernels - Two-stage approach from paper
    private val closeKernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Config.MORPH_CLOSE_KERNEL)
    private val erodeKernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Config.MORPH_ERODE_KERNEL)

    // Legacy kernel for VEPP (kept separate)
    @Suppress("DEPRECATION")
    private val morphKernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Config.MORPH_KERNEL_SIZE)

    // VEPP
    private val veppGradX = Mat()
    private val veppAbsGradX = Mat()
    private val veppProfile = Mat()
    private var prevVeppProfile: Mat? = null

    // Blur
    private val laplacian = Mat()
    private val mean = MatOfDouble()
    private val std = MatOfDouble()

    // ROI
    private var roiRect: Rect? = null

    // =========================================================
    // MAIN UPDATE
    // =========================================================
    fun updateLogic(gray: Mat, ocrResult: String? = null) {
        val now = System.currentTimeMillis()

        // 1. Detect Presence
        val presence = detectPresenceRobust(gray)

        // 2. Detect Stationary (chỉ khi có presence)
        val stationary = if (presence) detectBarcodeStationaryVEPP(gray, true) else false

        when (currentState) {
            AppState.IDLE -> {
                feedbackMessage = "READY"
                if (presence) {
                    presenceFrames++
                    if (presenceFrames >= Config.PRESENCE_CONFIRM_FRAMES) {
                        Log.i(TAG, "STATE: IDLE -> MOVING")
                        currentState = AppState.MOVING
                        resetCounters()
                    }
                } else presenceFrames = 0
            }
            AppState.MOVING -> {
                feedbackMessage = "MOVING..."
                if (!presence) {
                    resetToIdle()
                } else if (stationary) {
                    stationaryFrames++
                    if (stationaryFrames >= Config.STATIONARY_CONFIRM_FRAMES) {
                        Log.i(TAG, "STATE: MOVING -> STABLE")
                        currentState = AppState.STABLE
                        resetCounters()
                    }
                } else stationaryFrames = 0
            }
            AppState.STABLE -> {
                feedbackMessage = "CHECKING FOCUS..."
                if (!presence) {
                    resetToIdle()
                } else {
                    val roi = getCenterROI(gray)
                    val roiMat = gray.submat(roi)
                    if (isImageSharp(roiMat)) {
                        Log.i(TAG, "STATE: STABLE -> SCANNING")
                        currentState = AppState.SCANNING
                        ocrFusion.reset()
                        stateStartTime = now
                    }
                    roiMat.release() // Release submat
                }
            }
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                val elapsed = now - stateStartTime

                if (!presence || elapsed > Config.SCAN_TIMEOUT_MS) {
                    markError()
                } else if (ocrResult != null) {
                    ocrFusion.add(ocrResult)
                    val fused = ocrFusion.getFused()
                    if (fused != null) {
                        po = fused
                        feedbackMessage = "SUCCESS"
                        currentState = AppState.SUCCESS
                        Log.i(TAG, "STATE: SCANNING -> SUCCESS (PO: $fused)")
                    }
                }
            }
            AppState.SUCCESS -> {
                feedbackMessage = "SUCCESS"
                if (!presence) {
                    lostFrames++
                    if (lostFrames >= Config.PRESENCE_LOST_FRAMES) {
                        resetToIdle()
                    }
                } else lostFrames = 0
            }
            AppState.ERROR -> {
                feedbackMessage = "ERROR – ADJUST BOX"
                if (!presence) {
                    resetToIdle()
                } else if (stationary && (now - errorTime) > Config.ERROR_RETRY_COOLDOWN_MS) {
                    stationaryFrames++
                    if (stationaryFrames >= Config.STATIONARY_CONFIRM_FRAMES) {
                        Log.i(TAG, "STATE: ERROR -> STABLE")
                        currentState = AppState.STABLE
                        resetCounters()
                    }
                } else stationaryFrames = 0
            }
        }
    }

    // =========================================================
    // PRESENCE – ENHANCED BARCODE DETECTION (PAPER APPROACH)
    // =========================================================
    private fun detectPresenceRobust(gray: Mat): Boolean {
        try {
            // STEP 1: Compute X and Y Gradients (Paper approach)
            Imgproc.Sobel(gray, gradX, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(gray, gradY, CvType.CV_16S, 0, 1)

            // STEP 2: Scale and convert to absolute values
            // Paper uses scale factor of 2.0 for both gradients
            Core.convertScaleAbs(gradX, absGradX, Config.GRADIENT_SCALE, 0.0)
            Core.convertScaleAbs(gradY, absGradY, Config.GRADIENT_SCALE, 0.0)

            // STEP 3: Subtract Y from X to isolate vertical lines
            // This suppresses horizontal features (text) while enhancing vertical barcode lines
            Core.subtract(absGradX, absGradY, fullGrad)

            // STEP 4: Blur to reduce noise and smooth gradient response
            // Paper uses 9x9 kernel
            Imgproc.blur(fullGrad, blurredMat, Config.BLUR_KERNEL_SIZE)

            // STEP 5: Binary threshold
            // Paper uses threshold value of 80
            Imgproc.threshold(
                    blurredMat,
                    thresholdMat,
                    Config.BINARY_THRESHOLD,
                    255.0,
                    Imgproc.THRESH_BINARY
            )

            // STEP 6: Two-stage morphology (Paper approach)
            // Stage 1: Close with horizontal kernel to fill gaps between vertical lines
            Imgproc.morphologyEx(
                    thresholdMat,
                    morphMat,
                    Imgproc.MORPH_CLOSE,
                    closeKernel,
                    Point(-1.0, -1.0),
                    Config.MORPH_CLOSE_ITERATIONS
            )

            // Stage 2: Erode with vertical kernel to remove horizontal noise
            Imgproc.morphologyEx(
                    morphMat,
                    morphMat,
                    Imgproc.MORPH_ERODE,
                    erodeKernel,
                    Point(-1.0, -1.0),
                    Config.MORPH_ERODE_ITERATIONS
            )

            // STEP 7: Find Contours
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                    morphMat,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
            )

            if (contours.isEmpty()) {
                Log.d(TAG, "[Presence] No contours found")
                return false
            }

            // STEP 8: Select largest contour (Paper approach)
            // Find the contour with the largest area
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

            // Check if largest contour meets minimum area requirement
            if (largestContour == null || largestArea < minArea) {
                Log.d(TAG, "[Presence] Largest contour too small: area=$largestArea, min=$minArea")
                contours.forEach { it.release() }
                return false
            }

            // Check aspect ratio
            val r = Imgproc.boundingRect(largestContour)
            val ratio = r.width.toDouble() / r.height

            if (ratio < Config.MIN_ASPECT_RATIO) {
                Log.d(
                        TAG,
                        "[Presence] Largest contour aspect ratio too low: ratio=${"%.2f".format(ratio)}, min=${Config.MIN_ASPECT_RATIO}"
                )
                contours.forEach { it.release() }
                return false
            }

            // STEP 9: Texture validation on largest contour
            val roiCheck = thresholdMat.submat(r)
            val isValid = validateBarcodeTexture(roiCheck)
            roiCheck.release()

            if (isValid) {
                Log.d(
                        TAG,
                        "[Presence] ✓ BARCODE DETECTED (area=${"%.0f".format(largestArea)}, ratio=${"%.2f".format(ratio)})"
                )
            } else {
                Log.d(TAG, "[Presence] ✗ Largest contour failed texture validation")
            }

            contours.forEach { it.release() }
            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "Presence error", e)
            return false
        }
    }

    /**
     * ✅ OPTIMIZED: Expensive column clustering loop removed.
     * 
     * The gradient subtraction (X-Y), two-stage morphology, and aspect ratio
     * filtering already provide robust noise rejection. Column clustering is
     * redundant and wastes CPU cycles.
     * 
     * Only fast density check remains using native OpenCV Core.countNonZero.
     */
    private fun validateBarcodeTexture(binaryROI: Mat): Boolean {
        val cols = binaryROI.cols()
        val rows = binaryROI.rows()
        val totalPixels = rows * cols

        if (totalPixels == 0) return false
        if (rows < 5 || cols < 10) return false

        // Fast Density Check (Native OpenCV - extremely fast)
        // Barcodes have alternating black/white lines, so white pixel density
        // is typically 5-45% depending on line thickness and barcode type.
        // If density > 0.5, it's likely a white sticker, not a barcode.
        val nonZero = Core.countNonZero(binaryROI)
        val density = nonZero.toDouble() / totalPixels

        val isValid = density in 0.05..0.45

        // Debug logging
        if (!isValid) {
            Log.d(
                    TAG,
                    "[Texture] Rejected: density=${"%.3f".format(density)} (valid range: 0.05-0.45)"
            )
        }

        return isValid
    }

    // =========================================================
    // STATIONARY – VEPP (OBJECT-CENTRIC)
    // =========================================================
    private fun detectBarcodeStationaryVEPP(gray: Mat, presence: Boolean): Boolean {
        if (!presence) {
            prevVeppProfile?.release()
            prevVeppProfile = null
            return false
        }

        return try {
            val roi = getCenterROI(gray)
            val roiMat = gray.submat(roi)

            Imgproc.Sobel(roiMat, veppGradX, CvType.CV_32F, 1, 0)
            Core.convertScaleAbs(veppGradX, veppAbsGradX)

            // Sum columns -> 1D Profile
            Core.reduce(veppAbsGradX, veppProfile, 0, Core.REDUCE_SUM, CvType.CV_32F)

            // Normalize [0, 1]
            val minMax = Core.minMaxLoc(veppProfile)
            if (minMax.maxVal > 0) {
                Core.divide(veppProfile, Scalar(minMax.maxVal), veppProfile)
            }

            val prev = prevVeppProfile
            if (prev == null) {
                prevVeppProfile = veppProfile.clone()
                roiMat.release()
                return false
            }

            // L1 Norm
            val l1 = Core.norm(veppProfile, prev, Core.NORM_L1)

            prevVeppProfile?.release()
            prevVeppProfile = veppProfile.clone()
            roiMat.release()

            // Python Logic: L1 < 2.5 (dung sai cao hơn cho rung tay)
            val stationary = l1 < Config.BARCODE_STATIONARY_L1_THRESHOLD

            // Debug logging
            Log.d(
                    TAG,
                    "[VEPP] L1=${"%.4f".format(l1)} (threshold=${Config.BARCODE_STATIONARY_L1_THRESHOLD}) -> stationary=$stationary"
            )

            stationary
        } catch (e: Exception) {
            Log.e(TAG, "VEPP error", e)
            false
        }
    }

    // =========================================================
    // BLUR CHECK
    // =========================================================
    private fun isImageSharp(roi: Mat): Boolean {
        return try {
            Imgproc.Laplacian(roi, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, std)
            val variance = std.get(0, 0)[0].let { it * it }

            val isSharp = variance > Config.BLUR_THRESHOLD

            // Debug logging
            Log.d(
                    TAG,
                    "[Blur] Variance=${"%.1f".format(variance)} (threshold=${Config.BLUR_THRESHOLD}) -> sharp=$isSharp"
            )

            isSharp
        } catch (e: Exception) {
            false
        }
    }

    private fun getCenterROI(gray: Mat): Rect {
        val w = gray.cols()
        val h = gray.rows()
        val rw = (w * Config.ROI_WIDTH_RATIO).toInt()
        val rh = (h * Config.ROI_HEIGHT_RATIO).toInt()

        if (roiRect == null || roiRect!!.width != rw || roiRect!!.height != rh) {
            roiRect = Rect((w - rw) / 2, (h - rh) / 2, rw, rh)
        }
        return roiRect!!
    }

    private fun markError() {
        currentState = AppState.ERROR
        errorTime = System.currentTimeMillis()
        resetCounters()
    }

    private fun resetToIdle() {
        currentState = AppState.IDLE
        feedbackMessage = "READY"
        barcode = null
        po = null
        prevVeppProfile?.release()
        prevVeppProfile = null
        resetCounters()
    }

    private fun resetCounters() {
        presenceFrames = 0
        lostFrames = 0
        stationaryFrames = 0
    }

    fun release() {
        try {
            // Gradient buffers
            gradX.release()
            gradY.release()
            absGradX.release()
            absGradY.release()
            fullGrad.release()

            // Processing buffers
            blurredMat.release()
            thresholdMat.release()
            morphMat.release()
            hierarchy.release()

            // Morphology kernels
            closeKernel.release()
            erodeKernel.release()
            morphKernel.release()

            // VEPP buffers
            veppGradX.release()
            veppAbsGradX.release()
            veppProfile.release()
            prevVeppProfile?.release()

            // Blur check buffers
            laplacian.release()
            mean.release()
            std.release()
        } catch (_: Exception) {}
    }
}
