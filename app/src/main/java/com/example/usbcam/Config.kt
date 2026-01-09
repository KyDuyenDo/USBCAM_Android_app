package com.example.usbcam

object Config {

    // ================= PRESENCE (EDGE-BASED) =================
    // Ngưỡng năng lượng cạnh dọc trung bình (YUV rất sạch)
    const val EDGE_ENERGY_THRESHOLD = 15.0

    // Độ rộng tối thiểu của vùng presence (ratio theo width)
    // Barcode + label ~ 20–40% frame
    const val PRESENCE_WIDTH_RATIO = 0.18

    // ================= TEMPORAL STABILITY =================
    // Số frame liên tiếp để xác nhận presence
    const val PRESENCE_CONFIRM_FRAMES = 2

    // Số frame liên tiếp mất presence để coi là EXIT
    const val PRESENCE_LOST_FRAMES = 6

    // ================= SCAN =================
    const val SCAN_TIMEOUT_MS = 3000L
    const val SCAN_THROTTLE_MS = 500L

    // ================= FPS =================
    const val MAX_PROCESSING_FPS = 15
}
