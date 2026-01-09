package com.example.usbcam

import kotlin.math.max
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class BoxProcessor {

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

    // ================= OPENCV BUFFERS (REUSE) =================
    private val gradX = Mat()
    private val absGradX = Mat()
    private val colSum = Mat()

    private val diff = Mat()
    private val binaryDiff = Mat()

    private val laplacian = Mat()
    private val mean = MatOfDouble()
    private val std = MatOfDouble()

    private var prevGray: Mat? = null
    private var roiRect: Rect? = null

    // =========================================================
    // MAIN UPDATE (CALL PER FRAME – gray = Y channel)
    // =========================================================
    fun updateLogic(gray: Mat, ocrResult: String? = null) {
        val now = System.currentTimeMillis()

        val presence = detectPresenceFast(gray)
        val stationary = detectStationaryRobust(gray)

        when (currentState) {

            AppState.IDLE -> {
                feedbackMessage = "READY"
                if (presence) {
                    presenceFrames++
                    if (presenceFrames >= Config.PRESENCE_CONFIRM_FRAMES) {
                        currentState = AppState.MOVING
                        resetCounters()
                    }
                } else presenceFrames = 0
            }

            AppState.MOVING -> {
                feedbackMessage = "MOVING..."
                if (!presence) resetToIdle()
                else if (stationary) {
                    stationaryFrames++
                    if (stationaryFrames >= Config.STATIONARY_CONFIRM_FRAMES) {
                        currentState = AppState.STABLE
                        resetCounters()
                    }
                } else stationaryFrames = 0
            }

            AppState.STABLE -> {
                feedbackMessage = "CHECKING FOCUS..."
                if (!presence) resetToIdle()
                else if (isImageSharp(gray.submat(getCenterROI(gray)))) {
                    currentState = AppState.SCANNING
                    ocrFusion.reset()
                    stateStartTime = now
                }
            }

            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                if (!presence) markError()
                else if (now - stateStartTime > Config.SCAN_TIMEOUT_MS) markError()
                else {
                    // ===== OCR MULTI-FRAME =====
                    if (ocrResult != null) {
                        ocrFusion.add(ocrResult)
                        val fused = ocrFusion.getFused()
                        if (fused != null) {
                            po = fused
                            currentState = AppState.SUCCESS
                            feedbackMessage = "SUCCESS"
                        }
                    }
                }
            }

            AppState.SUCCESS -> {
                feedbackMessage = "SUCCESS"
                if (!presence) {
                    lostFrames++
                    if (lostFrames >= Config.PRESENCE_LOST_FRAMES) resetToIdle()
                } else lostFrames = 0
            }

            AppState.ERROR -> {
                feedbackMessage = "ERROR – ADJUST BOX"
                if (!presence) resetToIdle()
                else if (
                    stationary &&
                    now - errorTime > Config.ERROR_RETRY_COOLDOWN_MS
                ) {
                    stationaryFrames++
                    if (stationaryFrames >= Config.STATIONARY_CONFIRM_FRAMES) {
                        currentState = AppState.STABLE
                        resetCounters()
                    }
                } else stationaryFrames = 0
            }
        }

        // update prev frame
        if (prevGray == null) prevGray = gray.clone()
        else gray.copyTo(prevGray!!)
    }

    // =========================================================
    // PRESENCE (FAST)
    // =========================================================
    private fun detectPresenceFast(gray: Mat): Boolean {
        Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0)
        Core.absdiff(gradX, Scalar(0.0), absGradX)
        Core.reduce(absGradX, colSum, 0, Core.REDUCE_AVG, CvType.CV_32F)

        val w = colSum.cols()
        val minW = (w * Config.PRESENCE_WIDTH_RATIO).toInt()

        var run = 0
        var maxRun = 0
        for (x in 0 until w) {
            if (colSum.get(0, x)[0] > Config.EDGE_ENERGY_THRESHOLD) {
                run++
                maxRun = max(maxRun, run)
            } else run = 0
        }
        return maxRun >= minW
    }

    // =========================================================
    // STATIONARY – ANTI FLICKER
    // =========================================================
    private fun detectStationaryRobust(gray: Mat): Boolean {
        val prev = prevGray ?: return false
        val roi = getCenterROI(gray)

        val curr = gray.submat(roi)
        val prevR = prev.submat(roi)

        Core.absdiff(curr, prevR, diff)
        Imgproc.threshold(
            diff,
            binaryDiff,
            Config.STATIONARY_DIFF_PIXEL_THRESHOLD,
            255.0,
            Imgproc.THRESH_BINARY
        )

        val changed = Core.countNonZero(binaryDiff)
        val total = roi.width * roi.height

        curr.release()
        prevR.release()

        return changed < total * Config.STATIONARY_CHANGED_RATIO
    }

    // =========================================================
    // BLUR CHECK (OCR GATE)
    // =========================================================
    private fun isImageSharp(roi: Mat): Boolean {
        Imgproc.Laplacian(roi, laplacian, CvType.CV_64F)
        Core.meanStdDev(laplacian, mean, std)
        val variance = std.get(0, 0)[0].let { it * it }
        roi.release()
        return variance > Config.BLUR_THRESHOLD
    }

    // =========================================================
    // ROI SAFE CACHE
    // =========================================================
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

    // =========================================================
    // HELPERS
    // =========================================================
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
        resetCounters()
    }

    private fun resetCounters() {
        presenceFrames = 0
        lostFrames = 0
        stationaryFrames = 0
    }
}
