package com.example.usbcam

// Không còn import org.opencv.core.Size

object Config {

    // =========================================================
    // PRESENCE DETECTION (thay bằng pure-Bitmap approach)
    // =========================================================

    /** Ngưỡng pixel diff để coi là "có chuyển động" (0-255) */
    const val GRADIENT_THRESHOLD = 40

    /** Tỷ lệ pixel thay đổi tối thiểu để coi là có vật thể */
    const val MIN_CHANGE_RATIO = 0.03   // 3% pixels

    // =========================================================
    // OBJECT DETECTION (SIMPLE MOTION)
    // =========================================================

    const val OBJECT_MOTION_THRESHOLD = 25.0
    const val OBJECT_MIN_CHANGE_RATIO = 0.015 // 1.5% pixels changed

    // =========================================================
    // BLUR / FOCUS & OTHERS
    // =========================================================

    /** Ngưỡng variance luminance. Dưới ngưỡng → bị mờ */
    const val BLUR_THRESHOLD = 100.0

    const val ROI_WIDTH_RATIO  = 0.5
    const val ROI_HEIGHT_RATIO = 0.5

    const val SCAN_TIMEOUT_MS = 2000L

    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 12
    const val BEEP_VOLUME = 0
    const val MAX_PROCESSING_FPS = 15
    const val SCAN_THROTTLE_MS = 500L
    const val BRIGHTNESS_BOOST = 30f

    // =========================================================
    // CAMERA SIGNAL DETECTION
    // =========================================================

    const val CAMERA_SIGNAL_TIMEOUT_MS = 3000L
    const val SIGNAL_CHECK_INTERVAL_MS = 2000L
    const val SIGNAL_DETECTION_INITIAL_DELAY_MS = 1000L
    const val COUNTDOWN_SECONDS = 5
    const val AUTO_DISABLE_ON_SIGNAL_LOSS = true

    // =========================================================
    // USB DEVICE DETECTION
    // =========================================================

    const val USB_CLASS_VIDEO = 14 // 0x0E

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
    const val PO_UPSCALE_WIDTH_FAST = 960
    const val PO_FUSION_MAX_FRAMES_FAST = 2
    const val PO_FUSION_MIN_AGREE_FAST = 2

    // ===== BALANCED Strategy (Recommended) =====
    const val PO_BRIGHTNESS_BOOST_BALANCED = 12f
    const val PO_UPSCALE_WIDTH_BALANCED = 1280
    const val PO_FUSION_MAX_FRAMES_BALANCED = 3
    const val PO_FUSION_MIN_AGREE_BALANCED = 2

    // ===== ACCURATE Strategy =====
    const val PO_BRIGHTNESS_BOOST_ACCURATE = 15f
    const val PO_UPSCALE_WIDTH_ACCURATE = 1600
    const val PO_FUSION_MAX_FRAMES_ACCURATE = 4
    const val PO_FUSION_MIN_AGREE_ACCURATE = 3

    val PO_BRIGHTNESS_BOOST: Float
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST     -> PO_BRIGHTNESS_BOOST_FAST
            POExtractionStrategy.BALANCED -> PO_BRIGHTNESS_BOOST_BALANCED
            POExtractionStrategy.ACCURATE -> PO_BRIGHTNESS_BOOST_ACCURATE
        }

    val PO_UPSCALE_WIDTH: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST     -> PO_UPSCALE_WIDTH_FAST
            POExtractionStrategy.BALANCED -> PO_UPSCALE_WIDTH_BALANCED
            POExtractionStrategy.ACCURATE -> PO_UPSCALE_WIDTH_ACCURATE
        }

    val PO_FUSION_MAX_FRAMES: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST     -> PO_FUSION_MAX_FRAMES_FAST
            POExtractionStrategy.BALANCED -> PO_FUSION_MAX_FRAMES_BALANCED
            POExtractionStrategy.ACCURATE -> PO_FUSION_MAX_FRAMES_ACCURATE
        }

    val PO_FUSION_MIN_AGREE: Int
        get() = when (PO_EXTRACTION_STRATEGY) {
            POExtractionStrategy.FAST     -> PO_FUSION_MIN_AGREE_FAST
            POExtractionStrategy.BALANCED -> PO_FUSION_MIN_AGREE_BALANCED
            POExtractionStrategy.ACCURATE -> PO_FUSION_MIN_AGREE_ACCURATE
        }

    // =========================================================
    // TRACKING MANAGER CONFIG (OPTIMIZED FOR VIBRATION)
    // =========================================================

    enum class ConveyorProfile {
        SLOW,   // Conveyor chậm, ổn định
        MEDIUM, // Conveyor trung bình (Mặc định)
        FAST    // Conveyor nhanh, rung lắc nhiều
    }

    val CONVEYOR_PROFILE = ConveyorProfile.MEDIUM

    // ===== SLOW Profile =====
    const val TRACKING_TIME_WINDOW_SLOW: Long = 1500
    const val SHORT_MISS_TOLERANCE_SLOW: Long = 200
    const val LONG_MISS_THRESHOLD_SLOW: Long = 500
    const val MISS_THRESHOLD_SHORT_SLOW: Int = 5
    const val MISS_THRESHOLD_MEDIUM_SLOW: Int = 8

    // ===== MEDIUM Profile =====
    const val TRACKING_TIME_WINDOW_MEDIUM: Long = 2000
    const val SHORT_MISS_TOLERANCE_MEDIUM: Long = 300
    const val LONG_MISS_THRESHOLD_MEDIUM: Long = 700
    const val MISS_THRESHOLD_SHORT_MEDIUM: Int = 7
    const val MISS_THRESHOLD_MEDIUM_MEDIUM: Int = 10

    // ===== FAST Profile =====
    const val TRACKING_TIME_WINDOW_FAST: Long = 2500
    const val SHORT_MISS_TOLERANCE_FAST: Long = 400
    const val LONG_MISS_THRESHOLD_FAST: Long = 900
    const val MISS_THRESHOLD_SHORT_FAST: Int = 9
    const val MISS_THRESHOLD_MEDIUM_FAST: Int = 13

    val TRACKING_TIME_WINDOW_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW   -> TRACKING_TIME_WINDOW_SLOW
            ConveyorProfile.MEDIUM -> TRACKING_TIME_WINDOW_MEDIUM
            ConveyorProfile.FAST   -> TRACKING_TIME_WINDOW_FAST
        }

    val SHORT_MISS_TOLERANCE_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW   -> SHORT_MISS_TOLERANCE_SLOW
            ConveyorProfile.MEDIUM -> SHORT_MISS_TOLERANCE_MEDIUM
            ConveyorProfile.FAST   -> SHORT_MISS_TOLERANCE_FAST
        }

    val LONG_MISS_THRESHOLD_MS: Long
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW   -> LONG_MISS_THRESHOLD_SLOW
            ConveyorProfile.MEDIUM -> LONG_MISS_THRESHOLD_MEDIUM
            ConveyorProfile.FAST   -> LONG_MISS_THRESHOLD_FAST
        }

    val MISS_THRESHOLD_SHORT: Int
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW   -> MISS_THRESHOLD_SHORT_SLOW
            ConveyorProfile.MEDIUM -> MISS_THRESHOLD_SHORT_MEDIUM
            ConveyorProfile.FAST   -> MISS_THRESHOLD_SHORT_FAST
        }

    val MISS_THRESHOLD_MEDIUM: Int
        get() = when (CONVEYOR_PROFILE) {
            ConveyorProfile.SLOW   -> MISS_THRESHOLD_MEDIUM_SLOW
            ConveyorProfile.MEDIUM -> MISS_THRESHOLD_MEDIUM_MEDIUM
            ConveyorProfile.FAST   -> MISS_THRESHOLD_MEDIUM_FAST
        }

    // Position thresholds
    const val STABLE_POSITION_THRESHOLD: Float = 10f
    const val DRIFT_POSITION_THRESHOLD: Float  = 30f

    const val NEW_BOX_DISTANCE_THRESHOLD: Float = 100f
    const val NEW_BOX_X_THRESHOLD: Float = 80f
    const val NEW_BOX_Y_THRESHOLD: Float = 60f

    const val MOTION_LOW_THRESHOLD: Float    = 5f
    const val MOTION_MEDIUM_THRESHOLD: Float = 20f

    // =========================================================
    // RFID CONFIGURATION
    // =========================================================

    const val RFID_SCAN_WINDOW_MS = 500L
}
