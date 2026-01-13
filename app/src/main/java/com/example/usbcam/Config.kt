package com.example.usbcam

import org.opencv.core.Size

object Config {

    // =========================================================
    // PRESENCE DETECTION (MORPHOLOGY & TEXTURE)
    // =========================================================

    // Gradient scaling factor (paper uses 2.0 for both X and Y)
    const val GRADIENT_SCALE = 2.0

    // Blur kernel size (paper uses 9x9 to reduce noise)
    val BLUR_KERNEL_SIZE = Size(9.0, 9.0)

    // Binary threshold (paper uses 80)
    const val BINARY_THRESHOLD = 80.0

    // Morphology kernels - Two-stage approach from paper
    // Stage 1: Close with horizontal kernel to fill gaps between vertical lines
    val MORPH_CLOSE_KERNEL = Size(21.0, 3.0)
    const val MORPH_CLOSE_ITERATIONS = 2

    // Stage 2: Erode with vertical kernel to remove horizontal noise
    val MORPH_ERODE_KERNEL = Size(3.0, 21.0)
    const val MORPH_ERODE_ITERATIONS = 1

    // Diện tích tối thiểu: 3% khung hình
    const val MIN_BARCODE_AREA_RATIO = 0.03

    // Tỷ lệ khung hình (Width/Height): Reduced to 1.0 based on real-world testing
    // Log analysis shows actual barcodes have ratios of 1.1-1.6
    // This allows detection of compact barcodes and angled views
    // while still filtering out perfectly square noise (ratio < 1.0)
    const val MIN_ASPECT_RATIO = 1.0

    // =========================================================
    // BLUR / FOCUS & OTHERS
    // =========================================================

    const val BLUR_THRESHOLD = 100.0

    // Vùng quét (ROI) ở trung tâm (50% width, 50% height)
    const val ROI_WIDTH_RATIO = 0.5
    const val ROI_HEIGHT_RATIO = 0.5

    const val SCAN_TIMEOUT_MS = 3000L

    // Các tham số phụ khác (giữ nguyên)
    const val MIN_PO_LENGTH = 6
    const val MAX_PO_LENGTH = 12
    const val BEEP_VOLUME = 80
    const val MAX_PROCESSING_FPS = 20
    const val SCAN_THROTTLE_MS = 500L
    const val BRIGHTNESS_BOOST = 30f

    // =========================================================
    // TRACKING MANAGER CONFIG
    // =========================================================
    const val TRACKING_TIME_WINDOW_MS: Long = 2000
    const val SHORT_MISS_TOLERANCE_MS: Long = 500
    const val LONG_MISS_THRESHOLD_MS: Long = 1500

    const val MISS_THRESHOLD_SHORT: Int = 5
    const val MISS_THRESHOLD_MEDIUM: Int = 8

    const val STABLE_POSITION_THRESHOLD: Float = 10f
    const val DRIFT_POSITION_THRESHOLD: Float = 30f

    const val NEW_BOX_DISTANCE_THRESHOLD: Float = 100f
    const val NEW_BOX_X_THRESHOLD: Float = 80f
    const val NEW_BOX_Y_THRESHOLD: Float = 60f

    const val MOTION_LOW_THRESHOLD: Float = 5f
    const val MOTION_MEDIUM_THRESHOLD: Float = 20f
}
