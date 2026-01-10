package com.example.usbcam

import org.opencv.core.Size

object Config {

    // =========================================================
    // PRESENCE DETECTION (MORPHOLOGY & TEXTURE)
    // =========================================================

    // Ngưỡng cường độ cạnh (giống Python: 50.0)
    const val GRADIENT_THRESHOLD = 50.0

    // Kích thước nhân (Kernel): Rộng 21, Cao 3
    // Chiều cao nhỏ (3) giúp tránh dính chữ vào barcode
    val MORPH_KERNEL_SIZE = Size(21.0, 3.0)

    // Diện tích tối thiểu: 3% khung hình
    const val MIN_BARCODE_AREA_RATIO = 0.03

    // Tỷ lệ khung hình (Width/Height): Tăng lên 2.5
    // Barcode chuẩn thường rất dẹt. Loại bỏ các hình vuông/chữ nhật ngắn.
    const val MIN_ASPECT_RATIO = 2.5

    // --- Texture Validation (Mới) ---
    // Mật độ điểm trắng (cạnh) trong vùng ROI: 5% -> 20%
    const val MIN_TEXTURE_DENSITY = 0.05
    const val MAX_TEXTURE_DENSITY = 0.20

    // Số lượng cột (column) liên tiếp tối thiểu chứa vạch để được coi là barcode
    // Giúp loại bỏ nhiễu dạng hạt hoặc vết xước rời rạc.
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