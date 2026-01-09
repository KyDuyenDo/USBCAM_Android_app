package com.example.usbcam

import android.util.Log
import kotlin.math.abs
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class BoxProcessor {

    // ================= PUBLIC STATE =================
    @Volatile var currentState = AppState.IDLE
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    @Volatile var feedbackMessage: String = "READY"

    // ================= INTERNAL =================
    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null

    // Incoming trajectory validation
    private var incomingProgress = 0.0
    private var stableIncomingFrames = 0
    private var lastMeanDistToCenter: Double? = null

    // State timing
    private var settledFrames = 0
    private var stateStartTime = 0L

    // SUCCESS / ERROR lock
    private var waitingForExit = false

    // =================================================
    // MAIN ENTRY
    // =================================================
    fun updateLogic(currentGray: Mat) {
        val now = System.currentTimeMillis()

        when (currentState) {
            AppState.IDLE, AppState.SLIDING -> {
                processIncomingTrajectory(currentGray)
            }
            AppState.SCANNING -> {
                // LOCKED: không xử lý motion
            }
            AppState.SUCCESS, AppState.ERROR -> {
                // Chỉ quan sát EXIT
                processExitDetection()
            }
            else -> {}
        }

        handleStateMachine(now)

        prevGray?.release()
        prevGray = currentGray.clone()
    }

    // =================================================
    // TRAJECTORY-BASED INCOMING (CORE FIX)
    // =================================================
    private fun processIncomingTrajectory(currentGray: Mat) {
        if (prevGray == null || prevPoints == null || prevPoints!!.rows() == 0) {
            resetIncoming()
            replenishFeatures(currentGray)
            return
        }

        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        Video.calcOpticalFlowPyrLK(
                prevGray,
                currentGray,
                prevPoints,
                nextPts,
                status,
                err,
                Size(Config.FLOW_WIN_SIZE.toDouble(), Config.FLOW_WIN_SIZE.toDouble()),
                2
        )

        val p0 = prevPoints!!.toArray()
        val p1 = nextPts.toArray()
        val st = status.toArray()

        val width = currentGray.cols()
        val centerX = width / 2.0
        val leftZone = width * Config.ZONE_SIDE_RATIO
        val rightZone = width * (1.0 - Config.ZONE_SIDE_RATIO)

        var validCount = 0
        var progressSum = 0.0
        var meanDist = 0.0

        for (i in st.indices) {
            if (st[i].toInt() != 1) continue

            val dx = p1[i].x - p0[i].x
            val dy = p1[i].y - p0[i].y

            // HARD FILTER: bỏ rung / nhấc
            if (abs(dy) > Config.MAX_VERTICAL_SHAKE_PIXEL) continue
            if (abs(dy) > abs(dx) * Config.MAX_Y_TO_X_RATIO) continue

            val startX = p0[i].x
            val prevDist = abs(centerX - p0[i].x)
            val currDist = abs(centerX - p1[i].x)

            val fromLeft = startX < leftZone && dx > 0
            val fromRight = startX > rightZone && dx < 0

            if ((fromLeft || fromRight) && currDist < prevDist) {
                validCount++
                progressSum += (prevDist - currDist)
                meanDist += currDist
            }
        }

        if (validCount > 0) {
            meanDist /= validCount

            if (lastMeanDistToCenter == null || meanDist < lastMeanDistToCenter!!) {
                stableIncomingFrames++
                incomingProgress += progressSum
            } else {
                resetIncoming()
            }

            lastMeanDistToCenter = meanDist
        } else {
            resetIncoming()
        }

        prevPoints!!.fromList(p1.toList())
        nextPts.release()
        status.release()
        err.release()
    }

    private fun resetIncoming() {
        incomingProgress = 0.0
        stableIncomingFrames = 0
        lastMeanDistToCenter = null
    }

    // =================================================
    // EXIT DETECTION (FOR SUCCESS / ERROR)
    // =================================================
    private fun processExitDetection() {
        val dist = lastMeanDistToCenter ?: return
        if (dist > Config.EXIT_DISTANCE_RATIO) {
            waitingForExit = false
            resetIncoming()
        }
    }

    // =================================================
    // STATE MACHINE (FIXED)
    // =================================================
    private fun handleStateMachine(now: Long) {
        when (currentState) {
            AppState.IDLE -> {
                if (!waitingForExit &&
                                stableIncomingFrames >= Config.MIN_INCOMING_FRAMES &&
                                incomingProgress > Config.MIN_DISTANCE_TO_START_SLIDING
                ) {
                    Log.i("BoxProcessor", ">>> ENTRY CONFIRMED")
                    currentState = AppState.SLIDING
                    resetIncoming()
                }
            }
            AppState.SLIDING -> {
                feedbackMessage = "DETECTING..."

                if (incomingProgress < 1.0) {
                    settledFrames++
                    if (settledFrames >= Config.FRAMES_TO_SETTLE) {
                        currentState = AppState.SCANNING
                        stateStartTime = now
                        settledFrames = 0
                    }
                } else {
                    settledFrames = 0
                }
            }
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                if (now - stateStartTime > Config.SCAN_TIMEOUT_MS) {
                    markError("SCAN TIMEOUT")
                }
            }
            AppState.SUCCESS, AppState.ERROR -> {
                // LOCKED: không reset vì motion
            }
            else -> {}
        }
    }

    // =================================================
    // FEATURE TRACKING
    // =================================================
    private fun replenishFeatures(gray: Mat) {
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
                gray,
                corners,
                Config.MAX_TRACKING_POINTS,
                Config.QUALITY_LEVEL,
                Config.MIN_DISTANCE
        )
        if (corners.rows() > 0) {
            prevPoints?.release()
            prevPoints = MatOfPoint2f(*corners.toArray())
        }
        corners.release()
    }

    // =================================================
    // SCAN CALLBACKS
    // =================================================
    fun onScanSuccess(barcode: String, po: String?) {
        if (currentState != AppState.SCANNING) return
        currentBarcode = barcode
        currentPO = po
        currentState = AppState.VALIDATING
    }

    fun markSuccess() {
        currentState = AppState.SUCCESS
        feedbackMessage = "OK"
        waitingForExit = true
        resetIncoming()
    }

    fun markError(msg: String) {
        currentState = AppState.ERROR
        feedbackMessage = msg
        waitingForExit = true
        resetIncoming()
    }
}
