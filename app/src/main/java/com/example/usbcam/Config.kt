package com.example.usbcam

import org.opencv.core.Size

object Config {

    // =========================================================
    // PRESENCE DETECTION (MORPHOLOGY & TEXTURE)
    // =========================================================

    // Gradient scaling factor (paper uses 2.0 for both X and Y)
    const val GRADIENT_SCALE = 2.0

    // Ngưỡng cường độ cạnh (giống Python: 50.0)
    const val GRADIENT_THRESHOLD = 50.0

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

    // Legacy kernel (kept for VEPP, will be replaced)
    @Deprecated("Use MORPH_CLOSE_KERNEL instead") val MORPH_KERNEL_SIZE = Size(21.0, 3.0)

    // Diện tích tối thiểu: 3% khung hình
    const val MIN_BARCODE_AREA_RATIO = 0.03

    // Tỷ lệ khung hình (Width/Height): Reduced to 1.0 based on real-world testing
    // Log analysis shows actual barcodes have ratios of 1.1-1.6
    // This allows detection of compact barcodes and angled views
    // while still filtering out perfectly square noise (ratio < 1.0)
    const val MIN_ASPECT_RATIO = 1.0

    // --- Texture Validation ---
    // Density range for barcode detection (5% - 45%)
    // Note: Actual range (0.05-0.45) is hardcoded in validateBarcodeTexture
    // for optimal performance after gradient subtraction optimization
    const val MIN_TEXTURE_DENSITY = 0.05
    
    // DEPRECATED: Column clustering removed for performance optimization
    // Gradient subtraction (X-Y) + morphology already filters noise effectively
    @Deprecated("Column clustering removed - redundant after gradient subtraction")
    const val MAX_TEXTURE_DENSITY = 0.20
    
    @Deprecated("Column clustering removed - redundant after gradient subtraction")
    const val MIN_TEXTURE_CLUSTER_WIDTH = 15

    // Số frame xác nhận presence
    const val PRESENCE_CONFIRM_FRAMES = 2
    const val PRESENCE_LOST_FRAMES = 2

    // =========================================================
    // STATIONARY DETECTION
    // =========================================================

    // Giảm xuống 3 frame để phản hồi nhanh hơn (giống Python)
    const val STATIONARY_CONFIRM_FRAMES = 3

    // Ngưỡng so sánh VEPP (L1 Norm).
    // Tăng lên 2.5 để chấp nhận rung tay nhẹ (giá trị cũ 0.15 quá gắt)
    const val BARCODE_STATIONARY_L1_THRESHOLD = 2.5f

    // =========================================================
    // BLUR / FOCUS & OTHERS
    // =========================================================

    const val BLUR_THRESHOLD = 100.0

    // Vùng quét (ROI) ở trung tâm (50% width, 50% height)
    const val ROI_WIDTH_RATIO = 0.5
    const val ROI_HEIGHT_RATIO = 0.5

    const val SCAN_TIMEOUT_MS = 3000L
    const val ERROR_RETRY_COOLDOWN_MS = 1200L

    // Các tham số phụ khác (giữ nguyên)
    const val MIN_PO_LENGTH = 6
    const val MAX_PO_LENGTH = 12
    const val BEEP_VOLUME = 80
    const val MAX_PROCESSING_FPS = 20
    const val SCAN_THROTTLE_MS = 500L
    const val BRIGHTNESS_BOOST = 30f
}
