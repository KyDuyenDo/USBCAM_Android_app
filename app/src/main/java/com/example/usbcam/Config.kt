package com.example.usbcam

import org.opencv.core.Size

object Config {

    // =========================================================
    // PRESENCE DETECTION (MORPHOLOGY & TEXTURE)
    // =========================================================

    const val GRADIENT_SCALE = 2.0
    val BLUR_KERNEL_SIZE = Size(9.0, 9.0)
    const val BINARY_THRESHOLD = 80.0

    val MORPH_CLOSE_KERNEL = Size(21.0, 3.0)
    const val MORPH_CLOSE_ITERATIONS = 2

    val MORPH_ERODE_KERNEL = Size(3.0, 21.0)
    const val MORPH_ERODE_ITERATIONS = 1

    const val MIN_BARCODE_AREA_RATIO = 0.03
    const val MIN_ASPECT_RATIO = 1.0

    // =========================================================
    // BLUR / FOCUS & OTHERS
    // =========================================================

    const val BLUR_THRESHOLD = 100.0

    const val ROI_WIDTH_RATIO = 0.5
    const val ROI_HEIGHT_RATIO = 0.5

    const val SCAN_TIMEOUT_MS = 3000L

    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 12
    const val BEEP_VOLUME = 80
    const val MAX_PROCESSING_FPS = 20
    const val SCAN_THROTTLE_MS = 500L
    const val BRIGHTNESS_BOOST = 30f

    // =========================================================
    // PO EXTRACTION OPTIMIZATION
    // =========================================================

    enum class POExtractionStrategy {
        FAST,       // Scan nhanh, chấp nhận sai số cao hơn
        BALANCED,   // Mặc định - cân bằng tốc độ và độ chính xác
        ACCURATE    // Ưu tiên độ chính xác, chấp nhận chậm hơn
    }

    val PO_EXTRACTION_STRATEGY = POExtractionStrategy.BALANCED

    // ===== FAST Strategy =====
    const val PO_BRIGHTNESS_BOOST_FAST = 10f
    const val PO_UPSCALE_WIDTH_FAST = 960          // 960x540 (qHD) - Nhanh nhất
    const val PO_FUSION_MAX_FRAMES_FAST = 2        // Chỉ cần 2 frames
    const val PO_FUSION_MIN_AGREE_FAST = 2         // 2/2 khớp → Accept ngay

    // ===== BALANCED Strategy (Recommended) =====
    const val PO_BRIGHTNESS_BOOST_BALANCED = 12f
    const val PO_UPSCALE_WIDTH_BALANCED = 1280     // 1280x720 (HD Ready) - Đủ rõ
    const val PO_FUSION_MAX_FRAMES_BALANCED = 3    // 3 frames
    const val PO_FUSION_MIN_AGREE_BALANCED = 2     // 2/3 khớp → Accept

    // ===== ACCURATE Strategy =====
    const val PO_BRIGHTNESS_BOOST_ACCURATE = 15f
    const val PO_UPSCALE_WIDTH_ACCURATE = 1600     // 1600x900 (HD+) - Chỉ khi cần
    const val PO_FUSION_MAX_FRAMES_ACCURATE = 4    // 4 frames
    const val PO_FUSION_MIN_AGREE_ACCURATE = 3     // 3/4 khớp → Chính xác cao

    val PO_BRIGHTNESS_BOOST: Float
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST -> PO_BRIGHTNESS_BOOST_FAST
            POExtractionStrategy.BALANCED -> PO_BRIGHTNESS_BOOST_BALANCED
            POExtractionStrategy.ACCURATE -> PO_BRIGHTNESS_BOOST_ACCURATE
        }

    val PO_UPSCALE_WIDTH: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST -> PO_UPSCALE_WIDTH_FAST
            POExtractionStrategy.BALANCED -> PO_UPSCALE_WIDTH_BALANCED
            POExtractionStrategy.ACCURATE -> PO_UPSCALE_WIDTH_ACCURATE
        }

    val PO_FUSION_MAX_FRAMES: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST -> PO_FUSION_MAX_FRAMES_FAST
            POExtractionStrategy.BALANCED -> PO_FUSION_MAX_FRAMES_BALANCED
            POExtractionStrategy.ACCURATE -> PO_FUSION_MAX_FRAMES_ACCURATE
        }

    val PO_FUSION_MIN_AGREE: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST -> PO_FUSION_MIN_AGREE_FAST
            POExtractionStrategy.BALANCED -> PO_FUSION_MIN_AGREE_BALANCED
            POExtractionStrategy.ACCURATE -> PO_FUSION_MIN_AGREE_ACCURATE
        }

    // =========================================================
    // TRACKING MANAGER CONFIG (OPTIMIZED FOR VIBRATION)
    // =========================================================

    /**
     * Conveyor Profile: Tốc độ băng chuyền ảnh hưởng đến tracking
     * - SLOW: Băng chậm, hộp ở lại lâu (3-5s), rung ít
     * - MEDIUM: Băng trung bình (1-2s), rung vừa
     * - FAST: Băng nhanh (0.5-1s), rung mạnh
     */
    enum class ConveyorProfile {
        SLOW,    // Conveyor chậm, ổn định
        MEDIUM,  // Conveyor trung bình (Mặc định)
        FAST     // Conveyor nhanh, rung lắc nhiều
    }

    val CONVEYOR_PROFILE = ConveyorProfile.MEDIUM

    // ===== SLOW Profile (Stable conveyor) =====
    const val TRACKING_TIME_WINDOW_SLOW: Long = 1500          // 1.5s history
    const val SHORT_MISS_TOLERANCE_SLOW: Long = 200           // 200ms = ~4 frames (rung nhẹ)
    const val LONG_MISS_THRESHOLD_SLOW: Long = 500            // 500ms = ~10 frames → RESET
    const val MISS_THRESHOLD_SHORT_SLOW: Int = 5              // Cho phép miss 5 frames liên tục
    const val MISS_THRESHOLD_MEDIUM_SLOW: Int = 8             // Max 8 misses trong window

    // ===== MEDIUM Profile (Balanced) - RECOMMENDED =====
    const val TRACKING_TIME_WINDOW_MEDIUM: Long = 2000        // 2s history
    const val SHORT_MISS_TOLERANCE_MEDIUM: Long = 300         // 300ms = ~6 frames (rung nhẹ-vừa)
    const val LONG_MISS_THRESHOLD_MEDIUM: Long = 700          // 700ms = ~14 frames → RESET
    const val MISS_THRESHOLD_SHORT_MEDIUM: Int = 7            // Cho phép miss 7 frames liên tục trong rung nhẹ
    const val MISS_THRESHOLD_MEDIUM_MEDIUM: Int = 10          // Max 10 misses trong window

    // ===== FAST Profile (High vibration) =====
    const val TRACKING_TIME_WINDOW_FAST: Long = 2500          // 2.5s history (cần buffer lớn hơn)
    const val SHORT_MISS_TOLERANCE_FAST: Long = 400           // 400ms = ~8 frames (rung mạnh)
    const val LONG_MISS_THRESHOLD_FAST: Long = 900            // 900ms = ~18 frames → RESET
    const val MISS_THRESHOLD_SHORT_FAST: Int = 9              // Cho phép miss 9 frames liên tục
    const val MISS_THRESHOLD_MEDIUM_FAST: Int = 13            // Max 13 misses trong window

    // Dynamic config based on profile
    val TRACKING_TIME_WINDOW_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW -> TRACKING_TIME_WINDOW_SLOW
            ConveyorProfile.MEDIUM -> TRACKING_TIME_WINDOW_MEDIUM
            ConveyorProfile.FAST -> TRACKING_TIME_WINDOW_FAST
        }

    val SHORT_MISS_TOLERANCE_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW -> SHORT_MISS_TOLERANCE_SLOW
            ConveyorProfile.MEDIUM -> SHORT_MISS_TOLERANCE_MEDIUM
            ConveyorProfile.FAST -> SHORT_MISS_TOLERANCE_FAST
        }

    val LONG_MISS_THRESHOLD_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW -> LONG_MISS_THRESHOLD_SLOW
            ConveyorProfile.MEDIUM -> LONG_MISS_THRESHOLD_MEDIUM
            ConveyorProfile.FAST -> LONG_MISS_THRESHOLD_FAST
        }

    val MISS_THRESHOLD_SHORT: Int
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW -> MISS_THRESHOLD_SHORT_SLOW
            ConveyorProfile.MEDIUM -> MISS_THRESHOLD_SHORT_MEDIUM
            ConveyorProfile.FAST -> MISS_THRESHOLD_SHORT_FAST
        }

    val MISS_THRESHOLD_MEDIUM: Int
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW -> MISS_THRESHOLD_MEDIUM_SLOW
            ConveyorProfile.MEDIUM -> MISS_THRESHOLD_MEDIUM_MEDIUM
            ConveyorProfile.FAST -> MISS_THRESHOLD_MEDIUM_FAST
        }

    // Position thresholds (giống cũ)
    const val STABLE_POSITION_THRESHOLD: Float = 10f
    const val DRIFT_POSITION_THRESHOLD: Float = 30f

    const val NEW_BOX_DISTANCE_THRESHOLD: Float = 100f
    const val NEW_BOX_X_THRESHOLD: Float = 80f
    const val NEW_BOX_Y_THRESHOLD: Float = 60f

    const val MOTION_LOW_THRESHOLD: Float = 5f
    const val MOTION_MEDIUM_THRESHOLD: Float = 20f
}