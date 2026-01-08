package com.example.usbcam

enum class AppState {
    IDLE,           // Màn hình chờ, chưa có gì xảy ra
    SLIDING,        // Phát hiện chuyển động có hướng (vật đang vào hoặc đang ra)
    SCANNING,       // Vật đã dừng, đang chụp và quét ML Kit
    VALIDATING,     // Đã có Barcode + PO, đang check API
    SUCCESS,        // Hoàn tất OK -> Giữ màn hình này
    ERROR           // Lỗi -> Giữ màn hình này
    // Bỏ WAITING_EXIT vì ta sẽ dùng SUCCESS/ERROR làm trạng thái chờ "thụ động"
}