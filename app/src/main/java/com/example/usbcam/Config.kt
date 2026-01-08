package com.example.usbcam

object Config {
    // --- CẤU HÌNH OPTICAL FLOW ---
    const val MAX_TRACKING_POINTS = 100
    const val QUALITY_LEVEL = 0.01
    const val MIN_DISTANCE = 10.0
    const val FLOW_WIN_SIZE = 31

    // --- BỘ LỌC CHỐNG RUNG & TRỤC X ---
    const val MAX_Y_TO_X_RATIO = 0.6f
    const val MAX_VERTICAL_SHAKE_PIXEL = 5.0f

    // --- THUẬT TOÁN CONSISTENCY ---
    const val MIN_DIRECTIONAL_RATIO = 0.7f

    // --- NGƯỠNG VẬN TỐC (VELOCITY THRESHOLD) ---
    // Tốc độ tối thiểu để coi là đang di chuyển (pixel/frame)
    const val VELOCITY_X_THRESHOLD_SLIDING = 3.0f 
    // Tốc độ để xác định vật đã dừng hẳn
    const val VELOCITY_X_THRESHOLD_SETTLED = 0.8f

    // --- NGƯỠNG KHOẢNG CÁCH TÍCH LŨY (QUAN TRỌNG NHẤT) ---
    // Từ IDLE -> SLIDING: Chỉ cần di chuyển 30px là bắt (để nhạy với vật mới)
    const val MIN_DISTANCE_TO_START_SLIDING = 30.0 
    
    // Từ SUCCESS/ERROR -> SLIDING: Cần di chuyển 150px mới Reset (Chống lắc lư)
    // 150px trên độ rộng 640px là khoảng 1/4 màn hình -> Cần đẩy dứt khoát.
    const val MIN_DISTANCE_TO_RESET_RESULT = 150.0

    // --- ĐỘ TRỄ FRAME ---
    const val FRAMES_TO_SETTLE = 6        // Cần 6 frame tĩnh để chốt và quét

    // --- THỜI GIAN CHỜ ---
    const val SCAN_TIMEOUT_MS = 3000L

    // --- LOGIC QUÉT & ẢNH ---
    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 15
    const val MAX_PROCESSING_FPS = 15
    const val SCAN_THROTTLE_MS = 500L
    const val BEEP_VOLUME = 80
    
    // --- TĂNG SÁNG ẢNH ---
    const val BRIGHTNESS_BOOST = 40f 
}