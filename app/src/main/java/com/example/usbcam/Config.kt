package com.example.usbcam

object Config {
    // --- OPTICAL FLOW ---
    const val MAX_TRACKING_POINTS = 50 // Giảm số điểm để xử lý nhanh hơn
    const val MIN_VALID_POINTS = 3 // Minimum points to consider a valid object (filter noise)
    const val QUALITY_LEVEL = 0.01
    const val MIN_DISTANCE = 10.0
    const val FLOW_WIN_SIZE = 21 // Giảm window size để nhạy hơn với biên cạnh

    // --- UI/API ---
    // Thresholds from Python logic (main.py)
    const val THRESH_ENTRY_X = 0.5
    const val THRESH_STABLE = 1.0
    const val DEBOUNCE_FRAMES = 3
    const val STABILITY_FRAMES = 3
    const val WORK_ZONE_X_MIN = 0.25
    const val WORK_ZONE_X_MAX = 0.75

    // --- LEGACY / UNUSED (Can be cleaned later) ---
    // const val MIN_VELOCITY_X = 3.0  <-- Replaced by THRESH_ENTRY_X
    // const val FRAMES_TO_SETTLE = 6  <-- Replaced by STABILITY_FRAMES
    // const val MAX_ANGLE_DEVIATION = 20.0

    // --- UI/API ---
    const val SCAN_TIMEOUT_MS = 5000L
    const val MAX_PROCESSING_FPS = 30
    const val SCAN_THROTTLE_MS = 1000L
    const val BRIGHTNESS_BOOST = 20.0f
    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 20
    const val BEEP_VOLUME = 50
    
    // --- VOTING MECHANISM ---
    const val VOTING_SAMPLES = 3 // Number of samples to collect
    const val VOTING_CONFIDENCE_THRESHOLD = 0.5 // 50% agreement required
}