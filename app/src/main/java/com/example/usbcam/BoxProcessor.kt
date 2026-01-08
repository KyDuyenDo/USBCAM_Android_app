package com.example.usbcam

import android.util.Log
import java.util.Collections
import kotlin.math.abs
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
    
    // Biến theo dõi chuyển động
    private var velocityAbsX = 0.0 
    private var velocitySignedX = 0.0 
    private var directionalConsistency = 0.0 

    // Biến logic mới: Tích lũy quãng đường
    private var accumulatedDistance = 0.0 // Tổng pixel đã di chuyển theo hướng hợp lệ
    private var settledFrameCounter = 0
    private var stateStartTime = 0L
    private val poBuffer = Collections.synchronizedList(ArrayList<String>())

    /** Hàm cập nhật logic mỗi frame */
    fun updateLogic(currentGray: Mat) {
        val now = System.currentTimeMillis()

        // 1. Tính toán vận tốc và độ nhất quán
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
                prevGray, currentGray, prevPoints, nextPoints, status, err,
                Size(Config.FLOW_WIN_SIZE.toDouble(), Config.FLOW_WIN_SIZE.toDouble()), 2
            )
        } catch (e: Exception) {
            resetMotionMetrics()
            return
        }

        val statusArr = status.toArray()
        val p0 = prevPoints!!.toArray()
        val p1 = nextPoints.toArray()
        val goodP1 = ArrayList<Point>()

        var sumAbsDx = 0.0
        var sumSignedDx = 0.0
        var validCount = 0

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                val dx = p1[i].x - p0[i].x
                val dy = p1[i].y - p0[i].y

                // Lọc nhiễu rung dọc và rung chéo
                if (abs(dy) > Config.MAX_VERTICAL_SHAKE_PIXEL) continue
                if (abs(dy) > abs(dx) * Config.MAX_Y_TO_X_RATIO) continue

                sumAbsDx += abs(dx)
                sumSignedDx += dx
                validCount++
                goodP1.add(p1[i])
            }
        }

        if (validCount > 0) {
            velocityAbsX = sumAbsDx / validCount
            velocitySignedX = sumSignedDx / validCount
            directionalConsistency = if (sumAbsDx > 0.001) abs(sumSignedDx) / sumAbsDx else 0.0
        } else {
            resetMotionMetrics()
        }

        prevPoints!!.fromList(goodP1)
        if (validCount < Config.MAX_TRACKING_POINTS / 3) {
            replenishFeatures(currentGray)
        }

        nextPoints.release()
        status.release()
        err.release()
    }

    private fun resetMotionMetrics() {
        velocityAbsX = 0.0
        velocitySignedX = 0.0
        directionalConsistency = 0.0
    }

    private fun handleStateMachine(now: Long) {
        // --- LOGIC MỚI: QUÃNG ĐƯỜNG TÍCH LŨY (CUMULATIVE DISPLACEMENT) ---
        
        // 1. Kiểm tra tín hiệu chuyển động cơ bản
        val isMoving = velocityAbsX > Config.VELOCITY_X_THRESHOLD_SLIDING
        val isConsistent = directionalConsistency > Config.MIN_DIRECTIONAL_RATIO
        
        // Chỉ tích lũy khi chuyển động nhanh và có hướng rõ ràng
        if (isMoving && isConsistent) {
            accumulatedDistance += velocityAbsX
        } else {
            // Nếu dừng lại hoặc rung lắc (consistent thấp), reset tích lũy
            // (Bạn có thể chọn trừ dần thay vì reset về 0 để mượt hơn, nhưng reset 0 là an toàn nhất)
            accumulatedDistance = 0.0
        }

        when (currentState) {
            // --- TRẠNG THÁI CHỜ HOẶC ĐÃ CÓ KẾT QUẢ ---
            AppState.IDLE, AppState.SUCCESS, AppState.ERROR -> {
                // Xác định ngưỡng cần thiết dựa trên trạng thái
                val requiredDistance = if (currentState == AppState.IDLE) 
                    Config.MIN_DISTANCE_TO_START_SLIDING 
                else 
                    Config.MIN_DISTANCE_TO_RESET_RESULT // Ngưỡng cao (150px) để chống rung khi đã có kết quả

                if (accumulatedDistance > requiredDistance) {
                    Log.d("BoxProcessor", "Displacement $accumulatedDistance > $requiredDistance -> START/RESET")
                    resetDataForNewScan()
                    currentState = AppState.SLIDING
                    accumulatedDistance = 0.0 // Reset sau khi chuyển trạng thái
                }
            }

            // --- ĐANG TRƯỢT ---
            AppState.SLIDING -> {
                feedbackMessage = "DETECTING..."
                // Logic dừng: Khi vận tốc tức thời giảm xuống thấp (vật đã vào vị trí)
                if (velocityAbsX < Config.VELOCITY_X_THRESHOLD_SETTLED) {
                    settledFrameCounter++
                    if (settledFrameCounter >= Config.FRAMES_TO_SETTLE) {
                        currentState = AppState.SCANNING
                        stateStartTime = now
                        settledFrameCounter = 0
                        accumulatedDistance = 0.0
                        Log.d("BoxProcessor", "Settled -> SCANNING")
                    }
                } else {
                    settledFrameCounter = 0
                }
            }

            // --- QUÉT ---
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                if (now - stateStartTime > Config.SCAN_TIMEOUT_MS) {
                    markError("SCAN TIMEOUT")
                }
            }

            // --- CHECK API ---
            AppState.VALIDATING -> {
                feedbackMessage = "CHECKING..."
            }
        }
    }

    private fun replenishFeatures(gray: Mat) {
        try {
            val corners = MatOfPoint()
            Imgproc.goodFeaturesToTrack(
                gray, corners, Config.MAX_TRACKING_POINTS,
                Config.QUALITY_LEVEL, Config.MIN_DISTANCE
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
        totalCount++
    }

    fun markError(msg: String) {
        currentState = AppState.ERROR
        feedbackMessage = msg
    }
}