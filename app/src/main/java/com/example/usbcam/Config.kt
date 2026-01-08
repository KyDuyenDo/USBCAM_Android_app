package com.example.usbcam

object Config {
    // --- CẤU HÌNH OPTICAL FLOW ---
    const val MAX_TRACKING_POINTS = 100
    const val QUALITY_LEVEL = 0.01
    const val MIN_DISTANCE = 10.0
    const val FLOW_WIN_SIZE = 31

    // --- BỘ LỌC CHỐNG RUNG & TRỤC X ---
    const val MAX_Y_TO_X_RATIO = 0.8f
    const val MAX_VERTICAL_SHAKE_PIXEL = 8.0f

    // --- CẤU HÌNH ZONE (VÙNG QUÉT) ---
    // Tỷ lệ chiều rộng vùng biên (0.2 = 20% trái và 20% phải)
    const val ZONE_SIDE_RATIO = 0.2f

    // Tỷ lệ tối thiểu số điểm nằm trong vùng biên / tổng số điểm để kích hoạt
    const val MIN_SIDE_POINTS_RATIO = 0.3f

    // --- THUẬT TOÁN CONSISTENCY ---
    const val MIN_DIRECTIONAL_RATIO = 0.6f

    // --- NGƯỠNG VẬN TỐC (VELOCITY THRESHOLD) ---

    // 1. Ngưỡng bắt đầu (IDLE -> SLIDING): Giữ thấp để nhạy với hộp mới
    const val VELOCITY_X_THRESHOLD_SLIDING = 1.5f

    // 2. [MỚI] Ngưỡng Reset (SUCCESS/ERROR -> SLIDING): Cần lực đẩy mạnh hơn để tránh rung lắc
    const val VELOCITY_X_THRESHOLD_RESET = 2.5f

    // 3. Tốc độ để xác định vật đã dừng hẳn
    const val VELOCITY_X_THRESHOLD_SETTLED = 0.8f

    // 4. [MỚI] Ngưỡng vận tốc đi ngược (Outgoing) để kích hoạt Kill Switch
    const val OUTGOING_VELOCITY_THRESHOLD = 2.0f

    // --- NGƯỠNG KHOẢNG CÁCH TÍCH LŨY ---
    // Từ IDLE -> SLIDING
    const val MIN_DISTANCE_TO_START_SLIDING = 15.0

    // Từ SUCCESS/ERROR -> SLIDING
    const val MIN_DISTANCE_TO_RESET_RESULT = 80.0

    // [CẬP NHẬT] Tăng giá trị trừ hao để "xả" tích lũy nhanh hơn khi dừng hoặc rung
    // Tăng từ 2.0 -> 5.0
    const val DISTANCE_DECAY_VALUE = 5.0

    // --- ĐỘ TRỄ FRAME ---
    const val FRAMES_TO_SETTLE = 6

    // --- THỜI GIAN CHỜ ---
    const val SCAN_TIMEOUT_MS = 3000L

    // --- LOGIC QUÉT & ẢNH ---
    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 15
    const val MAX_PROCESSING_FPS = 15
    const val SCAN_THROTTLE_MS = 500L
    const val BEEP_VOLUME = 80
    const val BRIGHTNESS_BOOST = 40f
}
