package com.example.usbcam

import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/** Tracking manager logic ported from barcode_detection_android_app-only-mlkit */
class TrackingManager {

    private val TAG = "TrackingManager"

    data class TrackingHistory(
            val timestamp: Long,
            val box: RectF,
            val centerX: Float,
            val centerY: Float
    )

    private val history = mutableListOf<TrackingHistory>()
    private var lastDetectTime = 0L
    private var lastBox: RectF? = null
    private var missStreak = 0

    /** Update tracking with new detection */
    fun updateDetection(box: RectF, currentTime: Long) {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f

        history.add(TrackingHistory(currentTime, box, cx, cy))

        // Keep history within time window
        val cutoff = currentTime - Config.TRACKING_TIME_WINDOW_MS
        history.removeAll { it.timestamp < cutoff }

        lastDetectTime = currentTime
        lastBox = box
        missStreak = 0

        Log.d(TAG, "Detection updated: center=($cx, $cy), history size=${history.size}")
    }

    /** Update when detection is missed Returns: true if should RESET, false if still tracking */
    fun updateMiss(currentTime: Long): Boolean {
        missStreak++

        val timeSinceLastDetect = currentTime - lastDetectTime

        Log.d(TAG, "Miss #$missStreak, time since last: ${timeSinceLastDetect}ms")

        // Case 1: Short miss (vibration/jitter) - WAIT
        if (timeSinceLastDetect < Config.SHORT_MISS_TOLERANCE_MS) {
            Log.d(TAG, "Short miss - likely vibration, continue tracking")
            return false
        }

        // Case 2: Medium miss - CHECK POSITION HISTORY
        if (timeSinceLastDetect < Config.LONG_MISS_THRESHOLD_MS) {
            // If we have recent history, check for movement pattern
            if (history.size >= 3) {
                val recentMovement = analyzeRecentMovement()

                if (recentMovement == MovementPattern.STABLE ||
                                recentMovement == MovementPattern.SMALL_DRIFT
                ) {
                    Log.d(TAG, "Medium miss but stable position - continue tracking")
                    return false
                }
            }
        }

        // Case 3: Long miss - DEFINITELY GONE
        if (timeSinceLastDetect >= Config.LONG_MISS_THRESHOLD_MS) {
            Log.i(TAG, "Long miss (${timeSinceLastDetect}ms) - barcode disappeared, RESET")
            return true
        }

        // Case 4: Miss streak too high
        val missLimit =
                when {
                    timeSinceLastDetect < Config.SHORT_MISS_TOLERANCE_MS ->
                            Config.MISS_THRESHOLD_SHORT
                    else -> Config.MISS_THRESHOLD_MEDIUM
                }

        if (missStreak >= missLimit) {
            Log.i(TAG, "Miss streak too high ($missStreak >= $missLimit), RESET")
            return true
        }

        return false
    }

    /** Analyze recent movement pattern */
    private fun analyzeRecentMovement(): MovementPattern {
        if (history.size < 2) return MovementPattern.UNKNOWN

        // Calculate variance of X and Y
        val centerXs = history.map { it.centerX }
        val centerYs = history.map { it.centerY }

        val xRange = centerXs.maxOrNull()!! - centerXs.minOrNull()!!
        val yRange = centerYs.maxOrNull()!! - centerYs.minOrNull()!!

        Log.d(TAG, "Movement analysis: xRange=$xRange, yRange=$yRange")

        return when {
            xRange < Config.STABLE_POSITION_THRESHOLD &&
                    yRange < Config.STABLE_POSITION_THRESHOLD -> {
                MovementPattern.STABLE
            }
            xRange < Config.DRIFT_POSITION_THRESHOLD -> {
                MovementPattern.SMALL_DRIFT
            }
            else -> {
                MovementPattern.LARGE_MOVEMENT
            }
        }
    }

    /**
     * Check if new box is likely a NEW box (not the old one). Horizontal conveyor -> Large X change
     * = New Box
     */
    fun isLikelyNewBox(newBox: RectF): Boolean {
        val lastBox = this.lastBox ?: return true

        val oldCx = (lastBox.left + lastBox.right) / 2f
        val oldCy = (lastBox.top + lastBox.bottom) / 2f
        val newCx = (newBox.left + newBox.right) / 2f
        val newCy = (newBox.top + newBox.bottom) / 2f

        // Calculate movement distance
        val distance = hypot(newCx - oldCx, newCy - oldCy)

        // Horizontal conveyor -> X changes
        val xDiff = abs(newCx - oldCx)
        val yDiff = abs(newCy - oldCy)

        Log.d(TAG, "Box comparison: distance=$distance, xDiff=$xDiff, yDiff=$yDiff")

        // New box if:
        // 1. X changed significantly (new box on conveyor)
        // 2. Overall distance too far
        // 3. Y changed too much (abnormal, maybe camera shifted)
        return xDiff > Config.NEW_BOX_X_THRESHOLD ||
                distance > Config.NEW_BOX_DISTANCE_THRESHOLD ||
                yDiff > Config.NEW_BOX_Y_THRESHOLD
    }

    fun getMotionLevel(): String {
        if (history.size < 2) return "LOW"

        val recent = history.takeLast(5)
        val distances = mutableListOf<Float>()

        for (i in 1 until recent.size) {
            val dist =
                    hypot(
                            recent[i].centerX - recent[i - 1].centerX,
                            recent[i].centerY - recent[i - 1].centerY
                    )
            distances.add(dist)
        }

        val avgDist = distances.average().toFloat()

        return when {
            avgDist < Config.MOTION_LOW_THRESHOLD -> "LOW"
            avgDist < Config.MOTION_MEDIUM_THRESHOLD -> "MEDIUM"
            else -> "HIGH"
        }
    }

    fun reset() {
        Log.d(TAG, "Tracking reset")
        history.clear()
        lastDetectTime = 0L
        lastBox = null
        missStreak = 0
    }

    enum class MovementPattern {
        STABLE, // Almost stationary
        SMALL_DRIFT, // Small jitter
        LARGE_MOVEMENT, // Moving
        UNKNOWN
    }
}
