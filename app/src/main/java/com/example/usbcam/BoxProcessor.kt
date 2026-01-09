package com.example.usbcam

import android.util.Log
import kotlin.math.abs
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class BoxProcessor {

    // --- PUBLIC STATE ---
    @Volatile var currentState = AppState.IDLE
    @Volatile var feedbackMessage: String = "READY"

    // Giữ nguyên các biến data
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    @Volatile var totalCount = 0
    @Volatile var target = 0
    var apiResponse: com.example.usbcam.api.PoResponse? = null

    // --- INTERNAL VARS ---
    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null

    // Logic di chuyển
    private var accumulatedDistX = 0.0 // Quãng đường X đã đi được của vật hiện tại
    private var stableFrameCounter = 0 // Đếm số frame vật đứng yên
    private var isTrackingObject = false // Cờ đánh dấu đang theo dõi một vật hợp lệ

    // New Counters for Centroid Logic
    private var debounceCounter = 0
    private var avgVx = 0.0
    private var avgVy = 0.0

    fun updateLogic(currentGray: Mat) {
        // 1. Khởi tạo Tracking nếu chưa có
        if (prevGray == null || prevPoints == null || prevPoints!!.rows() < 10) {
            replenishFeatures(currentGray)
            prevGray = currentGray.clone()
            return
        }

        // 2. Tính Optical Flow
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        try {
            Video.calcOpticalFlowPyrLK(
                    prevGray,
                    currentGray,
                    prevPoints,
                    nextPoints,
                    status,
                    err,
                    Size(Config.FLOW_WIN_SIZE.toDouble(), Config.FLOW_WIN_SIZE.toDouble()),
                    2
            )
        } catch (e: Exception) {
            prevGray = currentGray.clone()
            return
        }

        // 3. Phân tích chuyển động
        processMotion(prevPoints!!, nextPoints, status, currentGray.cols())

        // 4. Lưu frame cũ
        prevGray?.release()
        prevGray = currentGray.clone()
        prevPoints = nextPoints // Cập nhật điểm mới để track tiếp
    }

    private fun processMotion(p0: MatOfPoint2f, p1: MatOfPoint2f, status: MatOfByte, width: Int) {
        val p0Arr = p0.toArray()
        val p1Arr = p1.toArray()
        val statusArr = status.toArray()

        var sumDx = 0.0
        var sumDy = 0.0
        var validPoints = 0
        var centroidX = 0.0

        // 1. Calculate Average Motion (Dense-Flow Simulation)
        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                val dx = p1Arr[i].x - p0Arr[i].x
                val dy = p1Arr[i].y - p0Arr[i].y

                sumDx += dx
                sumDy += dy
                centroidX += p1Arr[i].x
                validPoints++
            }
        }

        if (validPoints < Config.MIN_VALID_POINTS) {
            // Not enough points -> Reset logic if we are just starting
            if (currentState == AppState.IDLE) {
                avgVx = 0.0
                avgVy = 0.0
            }
            return
        }

        val rawVx = sumDx / validPoints
        val rawVy = sumDy / validPoints
        val currentCentroidX = centroidX / validPoints

        // 2. Apply Smoothing (EMA 0.7 / 0.3)
        avgVx = rawVx * 0.7 + avgVx * 0.3
        avgVy = rawVy * 0.7 + avgVy * 0.3

        val mag = Math.sqrt(avgVx * avgVx + avgVy * avgVy)

        // --- STATE MACHINE (MATCHING PYTHON MAIN.PY) ---

        when (currentState) {
            AppState.IDLE -> {
                // Wait for consistent accumulated motion
                if (Math.abs(avgVx) > Config.THRESH_ENTRY_X) {
                    debounceCounter++
                } else {
                    debounceCounter = 0
                }

                if (debounceCounter > Config.DEBOUNCE_FRAMES) {
                    currentState = AppState.SLIDING // Mapped from Python "ENTERING"
                    stableFrameCounter = 0
                    debounceCounter = 0

                    // Reset Data
                    resetDataForNewScan()
                    isTrackingObject = true
                    Log.i("FlowLogic", ">>> DETECTED MOTION (ENTERING)")
                }
            }
            AppState.SLIDING -> {
                // Wait for Stop (Stability)
                if (mag < Config.THRESH_STABLE) {
                    stableFrameCounter++
                } else {
                    stableFrameCounter = 0
                }

                if (stableFrameCounter >= Config.STABILITY_FRAMES) {
                    // CHECK ZONE
                    val minX = width * Config.WORK_ZONE_X_MIN
                    val maxX = width * Config.WORK_ZONE_X_MAX

                    if (currentCentroidX > minX && currentCentroidX < maxX) {
                        currentState = AppState.SCANNING
                        Log.i("FlowLogic", ">>> SCAN TRIGGERED! (Centroid: $currentCentroidX)")
                        feedbackMessage = "SCAN TRIGGERED!"
                    } else {
                        // Stopped BUT outside zone -> Reset
                        if (stableFrameCounter > 20) { // Timeout
                            currentState = AppState.IDLE
                            Log.d("FlowLogic", "Stopped outside zone -> Reset")
                        }
                    }
                } else {
                    feedbackMessage = "TRACKING..."
                }
            }
            AppState.SCANNING -> {
                // LOCK STATE: Only reset if object LEAVES the zone
                val minX = width * Config.WORK_ZONE_X_MIN
                val maxX = width * Config.WORK_ZONE_X_MAX

                if (currentCentroidX < minX || currentCentroidX > maxX) {
                    // Object exited zone
                    currentState = AppState.IDLE
                    isTrackingObject = false
                    Log.i("FlowLogic", ">>> RESET (EXITED ZONE)")
                    feedbackMessage = "RESET"
                } else {
                    // Locked inside zone
                    feedbackMessage = "SCANNING (LOCKED)"
                }
            }
            else -> {
                // VALIDATING, SUCCESS, ERROR -> Managed by API logic
            }
        }

        // Debug Log
        if (validPoints > 0) {
            Log.v(
                    "FlowDebug",
                    "State: $currentState | Vx: ${String.format("%.2f", avgVx)} | Mag: ${String.format("%.2f", mag)} | CX: ${currentCentroidX.toInt()}"
            )
        }
    }

    private fun replenishFeatures(gray: Mat) {
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
                gray,
                corners,
                Config.MAX_TRACKING_POINTS,
                Config.QUALITY_LEVEL,
                Config.MIN_DISTANCE
        )
        prevPoints?.release()
        prevPoints = MatOfPoint2f(*corners.toArray())
        corners.release()
    }

    // --- CÁC HÀM HỖ TRỢ GIỮ NGUYÊN TỪ CODE CŨ ---
    private fun resetDataForNewScan() {
        currentBarcode = null
        currentPO = null
        apiResponse = null
        feedbackMessage = "READY"
        // Quan trọng: Đặt lại trạng thái UI về IDLE hoặc SLIDING
        // để người dùng biết hệ thống đã reset
        if (currentState == AppState.SUCCESS || currentState == AppState.ERROR) {
            currentState = AppState.IDLE
        }
    }

    fun onScanSuccess(barcode: String, po: String?) {
        if (currentState != AppState.SCANNING) return
        currentBarcode = barcode
        currentPO = po // Chấp nhận PO null ở đây, check sau
        if (currentBarcode != null) {
            currentState = AppState.VALIDATING
        }
    }

    fun markSuccess() {
        currentState = AppState.SUCCESS
        feedbackMessage = "OK"
        isTrackingObject = false
        accumulatedDistX = 0.0
    }

    fun markError(msg: String) {
        currentState = AppState.ERROR
        feedbackMessage = msg
        isTrackingObject = false
        accumulatedDistX = 0.0
    }
}
