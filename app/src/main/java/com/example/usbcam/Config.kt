package com.example.usbcam

object Config {

    // =========================================================
    // PRESENCE DETECTION (BARCODE / LABEL EDGE)
    // =========================================================

    /**
     * Ngưỡng năng lượng cạnh dọc (Sobel X)
     * - YUYV/YUV sạch: edge rất rõ
     * - Thấp quá → nhiễu
     * - Cao quá → bỏ sót barcode nhỏ
     */
    const val EDGE_ENERGY_THRESHOLD = 15.0

    /**
     * Tỷ lệ chiều rộng tối thiểu của vùng edge liên tục Barcode + label thường chiếm 20–40% frame
     */
    const val PRESENCE_WIDTH_RATIO = 0.18

    /** Số frame liên tiếp cần có presence để xác nhận có hộp */
    const val PRESENCE_CONFIRM_FRAMES = 2

    /**
     * Số frame liên tiếp mất presence để coi là hộp đã rời camera Nên nhỏ để fast recovery (phản
     * hồi nhanh)
     */
    const val PRESENCE_LOST_FRAMES = 2

    // =========================================================
    // STATIONARY DETECTION (ANTI-FLICKER, FRAME DIFFERENCING)
    // =========================================================

    /**
     * Ngưỡng thay đổi pixel để coi là "thay đổi thật"
     * - < 20: bỏ qua nhiễu ánh sáng, flicker
     * - > 20: chuyển động vật lý
     */
    const val STATIONARY_DIFF_PIXEL_THRESHOLD = 20.0

    /** Tỷ lệ pixel thay đổi cho phép trong ROI Ví dụ 0.01 = 1% diện tích ROI */
    const val STATIONARY_CHANGED_RATIO = 0.01

    /** Số frame liên tiếp cần stationary để coi là dừng hẳn (5 frame @ 15 FPS ≈ 300 ms) */
    const val STATIONARY_CONFIRM_FRAMES = 5

    // =========================================================
    // BLUR / FOCUS DETECTION (OCR PROTECTION)
    // =========================================================

    /**
     * Ngưỡng phương sai Laplacian
     * - Thấp → ảnh mờ (out of focus / motion blur)
     * - Cao → ảnh nét
     *
     * Thường:
     * - Camera tốt: 80 – 120
     * - Camera thường: 50 – 100
     *
     * ⚠️ BẮT BUỘC test ngoài hiện trường
     */
    const val BLUR_THRESHOLD = 100.0

    // =========================================================
    // ROI (CENTER AREA ONLY)
    // =========================================================

    /** ROI trung tâm dùng cho stationary + blur Hộp luôn nằm giữa → bỏ nhiễu biên */
    const val ROI_WIDTH_RATIO = 0.5
    const val ROI_HEIGHT_RATIO = 0.5

    // =========================================================
    // SCANNING CONTROL
    // =========================================================

    /** Thời gian tối đa chờ scan (barcode + OCR) */
    const val SCAN_TIMEOUT_MS = 3000L

    /** Thời gian giữa các lần scan để tránh quá tải (ms) */
    const val SCAN_THROTTLE_MS = 500L

    /** Tăng sáng cho ảnh trước khi OCR (0f = không tăng, > 0f = tăng) Ví dụ: 30f là tăng đáng kể */
    const val BRIGHTNESS_BOOST = 30f

    /** Độ dài tối thiểu của mã PO */
    const val MIN_PO_LENGTH = 6

    /** Độ dài tối đa của mã PO */
    const val MAX_PO_LENGTH = 12

    /** Âm lượng beep (0-100) */
    const val BEEP_VOLUME = 80

    // =========================================================
    // ERROR → RETRY / UX
    // =========================================================

    /**
     * Cooldown sau ERROR trước khi retry scan Chỉ áp dụng nếu hộp vẫn nằm đó Nếu hộp bị nhấc ra →
     * reset ngay (fast recovery)
     */
    const val ERROR_RETRY_COOLDOWN_MS = 1200L

    // =========================================================
    // PERFORMANCE
    // =========================================================

    /** Giới hạn FPS xử lý logic (không phải FPS camera) Tránh CPU spike trên Android */
    const val MAX_PROCESSING_FPS = 15
}
