package com.example.usbcam

object Config {

    // ================= OPTICAL FLOW =================
    const val MAX_TRACKING_POINTS = 100
    const val QUALITY_LEVEL = 0.01
    const val MIN_DISTANCE = 10.0
    const val FLOW_WIN_SIZE = 31

    // ================= MOTION FILTER =================
    const val MAX_Y_TO_X_RATIO = 0.8f
    const val MAX_VERTICAL_SHAKE_PIXEL = 8.0f

    // ================= ZONE =================
    const val ZONE_SIDE_RATIO = 0.2f

    // ================= INCOMING =================
    const val MIN_INCOMING_FRAMES = 3
    const val MIN_DISTANCE_TO_START_SLIDING = 15.0

    // ================= EXIT (SUCCESS LOCK) =================
    // ratio theo width
    const val EXIT_DISTANCE_RATIO = 0.35

    // ================= SETTLE =================
    const val FRAMES_TO_SETTLE = 6

    // ================= SCAN =================
    const val SCAN_TIMEOUT_MS = 3000L
    const val SCAN_THROTTLE_MS = 500L
    const val MAX_PROCESSING_FPS = 15

    // ================= ERROR SHAKE → RETRY =================
    const val ERROR_SHAKE_MIN = 0.8
    const val ERROR_SHAKE_MAX = 3.0
    const val ERROR_SHAKE_FRAMES = 4
    const val ERROR_SHAKE_ACCUM = 6.0

    // ================= UI =================
    const val BRIGHTNESS_BOOST = 40f
    const val BEEP_VOLUME = 80
}
