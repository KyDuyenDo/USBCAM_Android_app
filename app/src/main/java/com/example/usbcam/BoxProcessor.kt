package com.example.usbcam

import android.util.Log
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class BoxProcessor {

    // --- TRẠNG THÁI CÔNG KHAI ---
    @Volatile var currentState = AppState.IDLE
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    @Volatile var feedbackMessage: String = "READY"
    @Volatile var totalCount = 0
    @Volatile var target = 0
    var apiResponse: com.example.usbcam.api.PoResponse? = null

    // --- BIẾN NỘI BỘ ---
    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null

    // Biến theo dõi chuyển động TOÀN CỤC (Dùng để phát hiện dừng)
    private var globalVelocityAbsX = 0.0

    // Biến logic ZONE (Dùng để phát hiện bắt đầu/Reset)
    private var incomingVelocityX = 0.0 // Vận tốc đi vào trung tâm
    private var outgoingVelocityX = 0.0 // [MỚI] Vận tốc đi ra (ngược chiều) - dùng để khử nhiễu
    private var isIncomingDominant = false // True nếu thỏa mãn tỷ lệ điểm biên

    // Debug counts
    private var debugLeftCount = 0
    private var debugRightCount = 0
    private var debugCenterCount = 0

    // Biến logic tích lũy
    private var accumulatedIncomingDistance = 0.0
    private var maxIncomingVelocityX = 0.0 // [MỚI] Theo dõi vận tốc lớn nhất trong đợt tích lũy
    private var settledFrameCounter = 0
    private var stateStartTime = 0L
    private val poBuffer = Collections.synchronizedList(ArrayList<String>())

    /** Hàm cập nhật logic mỗi frame */
    fun updateLogic(currentGray: Mat) {
        val now = System.currentTimeMillis()

        // 1. Tính toán vận tốc & phân vùng
        calculateMotionMetrics(currentGray)

        // 2. Điều hướng máy trạng thái
        handleStateMachine(now)

        // 3. Lưu frame
        prevGray?.release()
        prevGray = currentGray.clone()
    }

    private fun calculateMotionMetrics(currentGray: Mat) {
        if (prevGray == null || prevPoints == null || prevPoints!!.rows() == 0) {
            resetMotionMetrics()
            replenishFeatures(currentGray)
            return
        }

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
            resetMotionMetrics()
            return
        }

        val statusArr = status.toArray()
        val p0 = prevPoints!!.toArray()
        val p1 = nextPoints.toArray()
        val goodP1 = ArrayList<Point>()

        // Zone Parameters
        val width = currentGray.cols()
        val leftZoneLimit = width * Config.ZONE_SIDE_RATIO
        val rightZoneLimit = width * (1.0 - Config.ZONE_SIDE_RATIO)

        var sumAbsDx = 0.0
        var validGlobalCount = 0

        var sumIncomingDx = 0.0
        var validIncomingCount = 0

        // [MỚI] Biến tính toán Outgoing (Đi ngược)
        var sumOutgoingDx = 0.0
        var validOutgoingCount = 0

        // Reset debug counters
        debugLeftCount = 0
        debugRightCount = 0
        debugCenterCount = 0

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                val dx = p1[i].x - p0[i].x
                val dy = p1[i].y - p0[i].y

                // Lọc nhiễu rung dọc
                if (abs(dy) > Config.MAX_VERTICAL_SHAKE_PIXEL) continue
                if (abs(dy) > abs(dx) * Config.MAX_Y_TO_X_RATIO) continue

                // Global logic (bất kể vùng nào, dùng để phát hiện dừng)
                sumAbsDx += abs(dx)
                validGlobalCount++
                goodP1.add(p1[i])

                // Zone Logic (Chỉ xét điểm di chuyển đủ lớn để kích hoạt)
                if (abs(dx) > 1.0) {
                    val startX = p0[i].x
                    var isIncoming = false
                    var isOutgoing = false // [MỚI] Cờ báo hiệu đi ngược

                    if (startX < leftZoneLimit) {
                        // Vùng TRÁI -> Cần dx dương (đi vào phải)
                        if (dx > 0) {
                            isIncoming = true
                            debugLeftCount++
                        } else if (dx < 0) {
                            // Đi ra trái (ngược)
                            isOutgoing = true
                        }
                    } else if (startX > rightZoneLimit) {
                        // Vùng PHẢI -> Cần dx âm (đi vào trái)
                        if (dx < 0) {
                            isIncoming = true
                            debugRightCount++
                        } else if (dx > 0) {
                            // Đi ra phải (ngược)
                            isOutgoing = true
                        }
                    } else {
                        // Vùng GIỮA
                        debugCenterCount++
                    }

                    if (isIncoming) {
                        sumIncomingDx += abs(dx)
                        validIncomingCount++
                    }

                    // [MỚI] Cộng dồn vận tốc ngược
                    if (isOutgoing) {
                        sumOutgoingDx += abs(dx)
                        validOutgoingCount++
                    }
                }
            }
        }

        // 1. Tính vận tốc Global
        globalVelocityAbsX = if (validGlobalCount > 0) sumAbsDx / validGlobalCount else 0.0

        // 2. Tính vận tốc Incoming (từ biên vào)
        if (validIncomingCount > 0) {
            incomingVelocityX = sumIncomingDx / validIncomingCount
            val totalRelevantPoints = validIncomingCount + debugCenterCount
            // Tỷ lệ điểm "tốt" so với toàn bộ điểm chuyển động
            val ratio = validIncomingCount.toFloat() / max(1, totalRelevantPoints)
            isIncomingDominant = ratio >= Config.MIN_SIDE_POINTS_RATIO
        } else {
            incomingVelocityX = 0.0
            isIncomingDominant = false
        }

        // [MỚI] 3. Tính vận tốc Outgoing (từ biên ra ngoài)
        outgoingVelocityX = if (validOutgoingCount > 0) sumOutgoingDx / validOutgoingCount else 0.0

        // Replenish points nếu ít quá
        prevPoints!!.fromList(goodP1)
        if (validGlobalCount < Config.MAX_TRACKING_POINTS / 3) {
            replenishFeatures(currentGray)
        }

        nextPoints.release()
        status.release()
        err.release()
    }

    private fun resetMotionMetrics() {
        globalVelocityAbsX = 0.0
        incomingVelocityX = 0.0
        outgoingVelocityX = 0.0
        isIncomingDominant = false
        accumulatedIncomingDistance = 0.0
        maxIncomingVelocityX = 0.0
    }

    private fun handleStateMachine(now: Long) {

        // --- 1. KILL SWITCH (Ngắt khẩn cấp khi rung lắc ngược) ---
        // "Smart Kill Switch": Chỉ reset nếu lực ngược (Outgoing) LỚN HƠN lực đẩy vào (MaxIncoming)
        // Điều này giúp tránh việc reset khi hộp bị nảy nhẹ hoặc tay rụt lại nhưng vẫn đang đẩy
        // vào.
        if (outgoingVelocityX > Config.OUTGOING_VELOCITY_THRESHOLD) {
            // [MỚI] So sánh tỷ lệ: Nếu Outgoing > MaxIncoming thì mới coi là hành động rút ra/nhiễu
            // mạnh
            if (accumulatedIncomingDistance > 0 && outgoingVelocityX > maxIncomingVelocityX) {
                Log.w(
                        "BoxProcessor",
                        "!!! KILL SWITCH: Outgoing ($outgoingVelocityX) > MaxIn ($maxIncomingVelocityX). Resetting."
                )
                accumulatedIncomingDistance = 0.0
                maxIncomingVelocityX = 0.0
            }
        }

        // --- 2. XÁC ĐỊNH NGƯỠNG KÍCH HOẠT (DUAL THRESHOLD) ---
        // Nếu đang IDLE: Dùng ngưỡng thấp (nhạy).
        // Nếu đang SUCCESS/ERROR: Dùng ngưỡng cao (chống rung).
        val currentVelocityThreshold =
                if (currentState == AppState.IDLE) Config.VELOCITY_X_THRESHOLD_SLIDING
                else Config.VELOCITY_X_THRESHOLD_RESET

        // --- 3. LOGIC TÍCH LŨY ---
        if (isIncomingDominant && incomingVelocityX > currentVelocityThreshold) {
            accumulatedIncomingDistance += incomingVelocityX
            // [MỚI] Cập nhật max velocity
            if (incomingVelocityX > maxIncomingVelocityX) {
                maxIncomingVelocityX = incomingVelocityX
            }

            // Log khi đang tích lũy tốt
            Log.v(
                    "MOTION_DEBUG",
                    "+++ ACCUMULATING ($currentState): Dist=${"%.1f".format(accumulatedIncomingDistance)} | Vel=${"%.1f".format(incomingVelocityX)} | Max=${"%.1f".format(maxIncomingVelocityX)} | Out=$outgoingVelocityX"
            )
        } else {
            // Trừ dần (Decay) khi không đủ điều kiện
            accumulatedIncomingDistance =
                    max(0.0, accumulatedIncomingDistance - Config.DISTANCE_DECAY_VALUE)

            if (accumulatedIncomingDistance == 0.0) {
                maxIncomingVelocityX = 0.0
            }

            if (accumulatedIncomingDistance > 0) {
                Log.v(
                        "MOTION_DEBUG",
                        "--- DECAYING: Dist=${"%.1f".format(accumulatedIncomingDistance)} | Vel=${"%.1f".format(incomingVelocityX)}"
                )
            }
        }

        when (currentState) {
            // --- TRẠNG THÁI CẦN TRIGGER (START/RESET) ---
            AppState.IDLE,
            AppState.SUCCESS,
            AppState.ERROR -> {
                val requiredDistance =
                        if (currentState == AppState.IDLE) Config.MIN_DISTANCE_TO_START_SLIDING
                        else Config.MIN_DISTANCE_TO_RESET_RESULT

                if (accumulatedIncomingDistance > requiredDistance) {
                    Log.i(
                            "BoxProcessor",
                            ">>> TRIGGER ACTIVATED! State: $currentState -> SLIDING. Dist=$accumulatedIncomingDistance"
                    )
                    resetDataForNewScan()
                    currentState = AppState.SLIDING
                    accumulatedIncomingDistance = 0.0
                    maxIncomingVelocityX = 0.0
                }
            }

            // --- ĐANG TRƯỢT (TRACKING) ---
            AppState.SLIDING -> {
                feedbackMessage = "DETECTING..."

                // Khi đã vào SLIDING, dùng Global Velocity để bắt điểm dừng chính xác
                if (globalVelocityAbsX < Config.VELOCITY_X_THRESHOLD_SETTLED) {
                    settledFrameCounter++
                    if (settledFrameCounter >= Config.FRAMES_TO_SETTLE) {
                        Log.i(
                                "BoxProcessor",
                                ">>> STOP DETECTED. Vel=$globalVelocityAbsX -> SCANNING"
                        )
                        currentState = AppState.SCANNING
                        stateStartTime = now
                        settledFrameCounter = 0
                    }
                } else {
                    settledFrameCounter = 0
                }
            }

            // --- TIMEOUT CHECK ---
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                if (now - stateStartTime > Config.SCAN_TIMEOUT_MS) {
                    markError("SCAN TIMEOUT")
                }
            }
            AppState.VALIDATING -> {
                feedbackMessage = "CHECKING..."
            }
        }
    }

    private fun replenishFeatures(gray: Mat) {
        try {
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
        } catch (e: Exception) {
            Log.e("BoxProcessor", "Replenish error", e)
        }
    }

    private fun resetDataForNewScan() {
        currentBarcode = null
        currentPO = null
        poBuffer.clear()
        apiResponse = null
        feedbackMessage = "READY"
    }

    fun onScanSuccess(barcode: String, po: String?) {
        if (currentState != AppState.SCANNING) return
        currentBarcode = barcode
        po?.let { poBuffer.add(it) }

        if (currentBarcode != null) {
            val votedPO = getVotedPO()
            if (votedPO != null) {
                currentPO = votedPO
                currentState = AppState.VALIDATING
            }
        }
    }

    private fun getVotedPO(): String? {
        if (poBuffer.isEmpty()) return null
        val counts = poBuffer.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value }
        return best?.key
    }

    fun markSuccess() {
        currentState = AppState.SUCCESS
        feedbackMessage = "OK"
        accumulatedIncomingDistance = 0.0
        maxIncomingVelocityX = 0.0
    }

    fun markError(msg: String) {
        currentState = AppState.ERROR
        feedbackMessage = msg
        accumulatedIncomingDistance = 0.0
        maxIncomingVelocityX = 0.0
    }
}
