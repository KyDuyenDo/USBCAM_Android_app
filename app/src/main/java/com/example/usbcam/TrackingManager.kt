package com.example.usbcam

import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tracking manager với hỗ trợ rung lắc conveyor
 *
 * Logic:
 * - SHORT miss (< 150ms): Có thể do rung nhẹ → Đợi thêm
 * - MEDIUM miss (150-400ms): Kiểm tra pattern di chuyển
 * - LONG miss (> 400ms): Hộp đã đi qua → RESET ngay
 */
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
    private var totalMissInWindow = 0

    init {
        Log.i(TAG, "Initialized with profile: ${Config.CONVEYOR_PROFILE}")
        Log.i(TAG, "  Short tolerance: ${Config.SHORT_MISS_TOLERANCE_MS}ms")
        Log.i(TAG, "  Long threshold: ${Config.LONG_MISS_THRESHOLD_MS}ms")
        Log.i(TAG, "  Miss limit: ${Config.MISS_THRESHOLD_SHORT} (short) / ${Config.MISS_THRESHOLD_MEDIUM} (medium)")
    }

    /** Update tracking with new detection */
    fun updateDetection(box: RectF, currentTime: Long) {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f

        history.add(TrackingHistory(currentTime, box, cx, cy))

        // Keep history within time window
        val cutoff = currentTime - Config.TRACKING_TIME_WINDOW_MS
        val removedCount = history.removeAll { it.timestamp < cutoff }

        lastDetectTime = currentTime
        lastBox = box
        missStreak = 0

        Log.d(TAG, "✓ Detection: center=($cx, $cy), history=${history.size} (removed $removedCount old)")
    }

    /**
     * Update when detection is missed
     * Returns: true if should RESET, false if still tracking
     */
    fun updateMiss(currentTime: Long): Boolean {
        missStreak++
        totalMissInWindow++

        val timeSinceLastDetect = currentTime - lastDetectTime
        val framesSinceLast = (timeSinceLastDetect / 50).toInt() // Assuming 20 FPS → 50ms/frame

        Log.d(TAG, "✗ Miss #$missStreak (total in window: $totalMissInWindow), " +
                "time since last: ${timeSinceLastDetect}ms (~$framesSinceLast frames)")

        // ===== CASE 1: LONG MISS → Hộp đã đi qua hoàn toàn =====
        if (timeSinceLastDetect >= Config.LONG_MISS_THRESHOLD_MS) {
            Log.i(TAG, "🔴 LONG MISS (${timeSinceLastDetect}ms >= ${Config.LONG_MISS_THRESHOLD_MS}ms) → BOX GONE → RESET")
            return true
        }

        // ===== CASE 2: SHORT MISS → Có thể do rung lắc =====
        if (timeSinceLastDetect < Config.SHORT_MISS_TOLERANCE_MS) {
            // Trong vùng rung lắc, kiểm tra miss streak
            if (missStreak >= Config.MISS_THRESHOLD_SHORT) {
                Log.w(TAG, "🟡 Short miss streak too high ($missStreak >= ${Config.MISS_THRESHOLD_SHORT}) → Possible box exit → RESET")
                return true
            }

            Log.d(TAG, "🟢 Short miss ($timeSinceLastDetect < ${Config.SHORT_MISS_TOLERANCE_MS}ms) → Likely vibration → Continue tracking")
            return false
        }

        // ===== CASE 3: MEDIUM MISS → Kiểm tra movement pattern =====
        // Giữa SHORT và LONG threshold
        if (history.size >= 3) {
            val movement = analyzeRecentMovement()
            Log.d(TAG, "🟡 Medium miss → Movement pattern: $movement")

            when (movement) {
                MovementPattern.STABLE, MovementPattern.SMALL_DRIFT -> {
                    // Hộp vẫn ở đó nhưng bị che khuất tạm thời
                    if (missStreak >= Config.MISS_THRESHOLD_MEDIUM) {
                        Log.w(TAG, "Medium miss with stable position but streak too high → RESET")
                        return true
                    }
                    Log.d(TAG, "Medium miss but position stable → Continue tracking")
                    return false
                }
                MovementPattern.LARGE_MOVEMENT -> {
                    // Hộp đang di chuyển nhanh → Có thể sắp ra khỏi frame
                    if (missStreak >= (Config.MISS_THRESHOLD_SHORT + 1)) {
                        Log.w(TAG, "Medium miss with large movement → Likely exiting → RESET")
                        return true
                    }
                }
                MovementPattern.UNKNOWN -> {
                    // Không đủ data → Chờ thêm
                }
            }
        }

        // ===== CASE 4: Total misses in window quá cao =====
        if (totalMissInWindow >= Config.MISS_THRESHOLD_MEDIUM) {
            Log.w(TAG, "🔴 Total misses in window ($totalMissInWindow >= ${Config.MISS_THRESHOLD_MEDIUM}) → Unstable tracking → RESET")
            return true
        }

        // Default: Continue tracking
        Log.d(TAG, "Continue tracking (miss #$missStreak, waiting for more frames)")
        return false
    }

    /** Analyze recent movement pattern */
    private fun analyzeRecentMovement(): MovementPattern {
        if (history.size < 2) return MovementPattern.UNKNOWN

        // Calculate variance of X and Y
        val recentHistory = history.takeLast(5)
        val centerXs = recentHistory.map { it.centerX }
        val centerYs = recentHistory.map { it.centerY }

        val xRange = (centerXs.maxOrNull() ?: 0f) - (centerXs.minOrNull() ?: 0f)
        val yRange = (centerYs.maxOrNull() ?: 0f) - (centerYs.minOrNull() ?: 0f)

        Log.v(TAG, "Movement analysis: xRange=$xRange, yRange=$yRange (n=${recentHistory.size})")

        return when {
            xRange < Config.STABLE_POSITION_THRESHOLD &&
                    yRange < Config.STABLE_POSITION_THRESHOLD -> {
                MovementPattern.STABLE
            }
            xRange < Config.DRIFT_POSITION_THRESHOLD &&
                    yRange < Config.DRIFT_POSITION_THRESHOLD -> {
                MovementPattern.SMALL_DRIFT
            }
            else -> {
                MovementPattern.LARGE_MOVEMENT
            }
        }
    }

    /**
     * Check if new box is likely a NEW box (not the old one)
     * Horizontal conveyor -> Large X change = New Box
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

        Log.d(TAG, "New box check: distance=$distance, xDiff=$xDiff, yDiff=$yDiff")

        // New box if:
        // 1. X changed significantly (new box on conveyor)
        // 2. Overall distance too far
        // 3. Y changed too much (abnormal, maybe camera shifted)
        val isNewBox = xDiff > Config.NEW_BOX_X_THRESHOLD ||
                distance > Config.NEW_BOX_DISTANCE_THRESHOLD ||
                yDiff > Config.NEW_BOX_Y_THRESHOLD

        if (isNewBox) {
            Log.i(TAG, "🆕 NEW BOX detected (xDiff=$xDiff, distance=$distance)")
        }

        return isNewBox
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
        Log.i(TAG, "🔄 Tracking RESET (had ${history.size} history entries, $missStreak miss streak)")
        history.clear()
        lastDetectTime = 0L
        lastBox = null
        missStreak = 0
        totalMissInWindow = 0
    }

    enum class MovementPattern {
        STABLE,          // Almost stationary
        SMALL_DRIFT,     // Small jitter/vibration
        LARGE_MOVEMENT,  // Moving significantly
        UNKNOWN
    }
}