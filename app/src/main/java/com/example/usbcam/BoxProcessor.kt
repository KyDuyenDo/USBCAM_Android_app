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
    private val gradX = Mat()
    private val absGradX = Mat()
    private val thresholdMat = Mat()
    private val morphMat = Mat()
    private val hierarchy = Mat()

    // Kernel cập nhật theo Config (21, 3)
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
    // PRESENCE – BARCODE MORPHOLOGY & TEXTURE CHECK
    // =========================================================
    private fun detectPresenceRobust(gray: Mat): Boolean {
        try {
            // 1. Sobel Gradient
            Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0)
            Core.convertScaleAbs(gradX, absGradX)

            // 2. Threshold
            Imgproc.threshold(
                    absGradX,
                    thresholdMat,
                    Config.GRADIENT_THRESHOLD,
                    255.0,
                    Imgproc.THRESH_BINARY
            )

            // 3. Morphology Close
            Imgproc.morphologyEx(thresholdMat, morphMat, Imgproc.MORPH_CLOSE, morphKernel)

            // 4. Find Contours
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                    morphMat,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
            )

            val frameArea = gray.rows() * gray.cols()
            val minArea = frameArea * Config.MIN_BARCODE_AREA_RATIO

            var found = false
            var candidateCount = 0
            var validCount = 0

            for (cnt in contours) {
                val r = Imgproc.boundingRect(cnt)
                val area = r.width * r.height
                val ratio = r.width.toDouble() / r.height

                // Python Logic: Ratio > 2.5 (nghiêm ngặt hơn để loại bỏ nhiễu vuông)
                if (area > minArea && ratio > Config.MIN_ASPECT_RATIO) {
                    candidateCount++

                    // === TEXTURE VALIDATION ===
                    // Cắt ROI từ ảnh Threshold (trước khi morph)
                    val roiCheck = thresholdMat.submat(r)
                    if (validateBarcodeTexture(roiCheck)) {
                        validCount++
                        found = true
                        roiCheck.release()
                        break // Tìm thấy 1 cái hợp lệ là đủ
                    }
                    roiCheck.release()
                }
            }

            // Debug logging
            if (candidateCount == 0) {
                Log.d(
                        TAG,
                        "[Presence] ${contours.size} contours, 0 candidates (all too small/narrow)"
                )
            } else if (validCount == 0) {
                Log.d(TAG, "[Presence] ✗ $candidateCount candidates, none valid (texture failed)")
            } else {
                Log.d(TAG, "[Presence] ✓ BARCODE DETECTED")
            }

            contours.forEach { it.release() }
            return found
        } catch (e: Exception) {
            Log.e(TAG, "Presence error", e)
            return false
        }
    }

    /**
     * Python Port: _validate_barcode_texture Kiểm tra mật độ và sự phân bố của các cột cạnh dọc
     * (Column clustering).
     */
    private fun validateBarcodeTexture(binaryROI: Mat): Boolean {
        val cols = binaryROI.cols()
        val rows = binaryROI.rows()
        val totalPixels = rows * cols

        if (totalPixels == 0) return false
        if (rows < 5 || cols < 10) return false

        // 1. Basic Density Check (Python: 0.05 - 0.20)
        val nonZero = Core.countNonZero(binaryROI)
        val density = nonZero.toDouble() / totalPixels
        if (density < Config.MIN_TEXTURE_DENSITY || density > Config.MAX_TEXTURE_DENSITY) {
            return false
        }

        // 2. Column-based clustering analysis
        // Chuyển dữ liệu sang mảng byte để xử lý nhanh (tránh gọi .get() trong vòng lặp)
        val buffer = ByteArray(totalPixels)
        binaryROI.get(0, 0, buffer)

        // Tính tổng pixel trắng trên mỗi cột
        val colSums = IntArray(cols)
        var maxColSum = 0

        for (x in 0 until cols) {
            var sum = 0
            for (y in 0 until rows) {
                // index = y * width + x
                if (buffer[y * cols + x] != 0.toByte()) {
                    sum++
                }
            }
            colSums[x] = sum
            if (sum > maxColSum) maxColSum = sum
        }

        if (maxColSum == 0) return false

        // Python logic: Active cols > 30% of max
        val activeThreshold = maxColSum * 0.3

        var currentRun = 0
        var maxRun = 0

        // Tìm chuỗi cột liên tiếp dài nhất (Longest run of active columns)
        for (sum in colSums) {
            if (sum > activeThreshold) {
                currentRun++
            } else {
                if (currentRun > maxRun) maxRun = currentRun
                currentRun = 0
            }
        }
        // Check lần cuối
        if (currentRun > maxRun) maxRun = currentRun

        // Python Rule: Barcode thật phải có cụm vạch liền nhau >= 15 cột
        val isValid = maxRun >= Config.MIN_TEXTURE_CLUSTER_WIDTH

        // Debug logging for texture validation failures
        if (!isValid) {
            Log.d(
                    TAG,
                    "[Texture] Rejected: density=${"%.3f".format(density)}, max_run=$maxRun (need >=${Config.MIN_TEXTURE_CLUSTER_WIDTH})"
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
            gradX.release()
            absGradX.release()
            thresholdMat.release()
            morphMat.release()
            hierarchy.release()
            morphKernel.release()

            veppGradX.release()
            veppAbsGradX.release()
            veppProfile.release()
            prevVeppProfile?.release()

            laplacian.release()
            mean.release()
            std.release()
        } catch (_: Exception) {}
    }
}
