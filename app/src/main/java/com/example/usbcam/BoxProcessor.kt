package com.example.usbcam

import android.util.Log
import kotlin.math.max
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class BoxProcessor {

    // ================= PUBLIC STATE =================
    @Volatile var currentState = AppState.IDLE
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    @Volatile var feedbackMessage = "READY"

    // ================= INTERNAL =================
    private var presenceFrames = 0
    private var lostFrames = 0
    private var stateStartTime = 0L

    // =================================================
    // MAIN UPDATE (call every frame with GRAY/Y channel)
    // =================================================
    fun updateLogic(gray: Mat) {
        val presence = detectPresence(gray)
        val now = System.currentTimeMillis()

        when (currentState) {
            AppState.IDLE -> {
                if (presence) {
                    presenceFrames++
                    if (presenceFrames >= Config.PRESENCE_CONFIRM_FRAMES) {
                        Log.i("BoxProcessor", ">>> PRESENCE CONFIRMED")
                        currentState = AppState.SCANNING
                        feedbackMessage = "SCANNING..."
                        stateStartTime = now
                        presenceFrames = 0
                        lostFrames = 0
                    }
                } else {
                    presenceFrames = 0
                }
            }
            AppState.SCANNING -> {
                if (!presence) {
                    lostFrames++
                    if (lostFrames >= Config.PRESENCE_LOST_FRAMES) {
                        markError("OBJECT LOST")
                    }
                } else {
                    lostFrames = 0
                }

                if (now - stateStartTime > Config.SCAN_TIMEOUT_MS) {
                    markError("SCAN TIMEOUT")
                }
            }
            AppState.SUCCESS -> {
                // LOCKED: KHÔNG reset nếu còn presence
                if (!presence) {
                    lostFrames++
                    if (lostFrames >= Config.PRESENCE_LOST_FRAMES) {
                        resetToIdle()
                    }
                } else {
                    lostFrames = 0
                }
            }
            AppState.ERROR -> {
                // Cho phép chỉnh hộp để scan lại
                if (presence) {
                    currentState = AppState.SCANNING
                    feedbackMessage = "SCANNING..."
                    stateStartTime = now
                    lostFrames = 0
                }
            }
            else -> {}
        }
    }

    // =================================================
    // PRESENCE DETECTION (CORE LOGIC)
    // =================================================
    private fun detectPresence(gray: Mat): Boolean {

        // Sobel X → edge dọc (barcode cực mạnh)
        val gradX = Mat()
        Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0)

        Core.absdiff(gradX, Scalar(0.0), gradX)

        val width = gradX.cols()
        val height = gradX.rows()

        // Projection theo trục Y
        val energy = DoubleArray(width)

        for (x in 0 until width) {
            var sum = 0.0
            for (y in 0 until height) {
                sum += gradX.get(y, x)[0]
            }
            energy[x] = sum / height
        }

        gradX.release()

        // Tìm dải X liên tục có năng lượng cao
        val minWidth = (width * Config.PRESENCE_WIDTH_RATIO).toInt()

        var run = 0
        var maxRun = 0

        for (x in energy.indices) {
            if (energy[x] > Config.EDGE_ENERGY_THRESHOLD) {
                run++
                maxRun = max(maxRun, run)
            } else {
                run = 0
            }
        }

        return maxRun >= minWidth
    }

    // =================================================
    // CALLBACKS
    // =================================================
    fun onScanSuccess(barcode: String, po: String?) {
        if (currentState != AppState.SCANNING) return
        currentBarcode = barcode
        currentPO = po
        currentState = AppState.SUCCESS
        feedbackMessage = "SUCCESS"
        lostFrames = 0
    }

    fun markError(msg: String) {
        currentState = AppState.ERROR
        feedbackMessage = msg
        lostFrames = 0
    }

    private fun resetToIdle() {
        currentState = AppState.IDLE
        feedbackMessage = "READY"
        presenceFrames = 0
        lostFrames = 0
        currentBarcode = null
        currentPO = null
    }
}
