package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class BoxProcessor {

    companion object {
        private const val TAG = "BoxProcessor"
        private const val CLEANUP_INTERVAL = 500 // Cleanup mỗi 500 frames
    }

    // ================= PUBLIC =================
    @Volatile var currentState = AppState.IDLE
    @Volatile var feedbackMessage = "READY"

    @Volatile var barcode: String? = null
    @Volatile var po: String? = null
    @Volatile var motionLevel: String = "LOW"
    @Volatile var conveyorDirection: String = ""

    // ================= POSITION TRACKING =================
    // Bounding boxes for barcode and PO
    @Volatile var barcodeBox: RectF? = null
    @Volatile var poBox: RectF? = null

    // ================= INTERNAL =================
    private var stateStartTime = 0L
    private var lastScanTime = 0L

    private var frameProcessCount = 0L

    // ML Kit barcode decoder (đã có sẵn, dùng cho cả presence + decode)
    private val barcodeDecoder = BarcodeDecoder()

    // Tracker: không đổi (pure Kotlin)
    private val tracker = TrackingManager()

    // BlurDetector: đã viết lại — nhận Bitmap
    private val blurDetector = BlurDetector()

    // PO Extractor: đã dùng ML Kit Text Recognition, không đổi
    private val poExtractor = POExtractor()

    fun updateLogic(data: ByteArray, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        frameProcessCount++

        if (frameProcessCount % CLEANUP_INTERVAL == 0L) {
            performPeriodicCleanup()
            Log.i(TAG, "🧹 Periodic cleanup #${frameProcessCount / CLEANUP_INTERVAL} (frame $frameProcessCount)")
        }

        // 1. Presence detection bằng NV21 trực tiếp (cực nhanh)
        val presenceScanResult = try {
            barcodeDecoder.scan(data, width, height)
        } catch (e: Exception) {
            null
        }

        // 2. Lazy conversion: Chỉ tạo Bitmap khi thực sự cần thiết (khi phát hiện vật thể hoặc đang Verify)
        // Nếu ở trạng thái IDLE và không có presence -> bỏ qua convert
        if (currentState == AppState.IDLE && presenceScanResult == null) {
            feedbackMessage = "READY"
        } else {
            // Chuyển đổi NV21 -> Bitmap sử dụng Bitmap Pool (không alloc thêm object mới)
            val bitmap = nv21ToBitmap(data, width, height)
            processInternal(bitmap, presenceScanResult, now)
        }
    }


    private fun processInternal(bitmap: Bitmap, presenceScanResult: BarcodeDecoder.Result?, now: Long) {
        val presence = presenceScanResult != null

        when (currentState) {
            AppState.IDLE -> {
                feedbackMessage = "READY"
                if (presence) transitionTo(AppState.SCANNING)
            }
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."
                if (!presence) {
                    transitionTo(AppState.IDLE)
                    return
                }
                if (!blurDetector.check(bitmap)) return
                if (now - lastScanTime < Config.SCAN_THROTTLE_MS) return
                lastScanTime = now

                handleDetection(bitmap, presenceScanResult, now)
            }
            AppState.VERIFYING -> {
                feedbackMessage = "VERIFYING..."
                val result = presenceScanResult ?: try { barcodeDecoder.scan(bitmap) } catch (e: Exception) { null }
                if (result != null) {
                    tracker.updateDetection(result.box, now)
                    if (tracker.isLikelyNewBox(result.box)) {
                        Log.i(TAG, "NEW BOX DETECTED (during verify) -> Resetting")
                        transitionTo(AppState.IDLE)
                        return
                    }
                } else {
                    val shouldReset = tracker.updateMiss(now)
                    if (shouldReset) {
                        Log.i(TAG, "TRACKING LOST (during verify) -> Resetting")
                        transitionTo(AppState.IDLE)
                    }
                }
            }
            AppState.DECODED -> {
                feedbackMessage = "TRACKING: $motionLevel"
                val result = presenceScanResult ?: try { barcodeDecoder.scan(bitmap) } catch (e: Exception) { null }

                if (result != null) {
                    tracker.updateDetection(result.box, now)
                    if (tracker.isLikelyNewBox(result.box)) {
                        Log.i(TAG, "NEW BOX DETECTED -> Resetting")
                        transitionTo(AppState.IDLE)
                        return
                    }
                    motionLevel = tracker.getMotionLevel()
                } else {
                    val shouldReset = tracker.updateMiss(now)
                    if (shouldReset) {
                        // Cooldown: Đảm bảo hộp có ít nhất 2 giây để đi ra khỏi khung hình
                        // Tránh việc reset quá sớm do mất barcode vì mờ nhòe (motion blur)
                        if (now - stateStartTime > 2000L) {
                            Log.i(TAG, "TRACKING LOST -> Resetting")
                            transitionTo(AppState.IDLE)
                        } else {
                            Log.d(TAG, "TRACKING LOST but holding DECODED (cooldown: ${now - stateStartTime}ms)")
                        }
                    }
                }
            }
            AppState.RESETTING -> {
                transitionTo(AppState.IDLE)
            }
        }
    }

    private fun handleDetection(bitmap: Bitmap, result: BarcodeDecoder.Result?, now: Long) {
        if (result == null) return
        barcode = result.value
        barcodeBox = result.box
        tracker.updateDetection(result.box, now)

        val poResult = poExtractor.extract(bitmap, result.value)
        if (poResult != null) {
            po = poResult.po
            poBox = poResult.box
            Log.i(TAG, "BARCODE + PO FOUND -> Verifying")
            transitionTo(AppState.VERIFYING)
        }
    }

    // ================= BITMAP POOL =================
    private var reusableBitmap: Bitmap? = null
    private var pixelsArray: IntArray? = null

    private fun getBitmap(width: Int, height: Int): Bitmap {
        var bitmap = reusableBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bitmap
            pixelsArray = IntArray(width * height)
        }
        return bitmap
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = getBitmap(width, height)
        val pixels = pixelsArray ?: return bitmap
        val frameSize = width * height

        var yp = 0
        for (j in 0 until height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0
            for (i in 0 until width) {
                var y = (0xff and data[yp].toInt()) - 16
                if (y < 0) y = 0
                if ((i and 1) == 0) {
                    v = (0xff and data[uvp++].toInt()) - 128
                    u = (0xff and data[uvp++].toInt()) - 128
                }

                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v)
                var g = (y1192 - 833 * v - 400 * u)
                var b = (y1192 + 2066 * u)

                if (r < 0) r = 0 else if (r > 262143) r = 262143
                if (g < 0) g = 0 else if (g > 262143) g = 262143
                if (b < 0) b = 0 else if (b > 262143) b = 262143

                pixels[yp] = -0x1000000 or ((r shl 6) and 0xff0000) or ((g shr 2) and 0xff00) or ((b shr 10) and 0xff)
                yp++
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun transitionTo(newState: AppState) {
        if (currentState == newState) return
        currentState = newState
        stateStartTime = System.currentTimeMillis()

        if (newState == AppState.IDLE) {
            barcode = null
            po = null
            barcodeBox = null
            poBox = null
            poExtractor.reset()
            tracker.reset()
        }
    }

    fun onApiVerification(success: Boolean) {
        if (currentState != AppState.VERIFYING) return

        if (success) {
            Log.i(TAG, "API VERIFIED -> DECODED")
            transitionTo(AppState.DECODED)
        } else {
            Log.i(TAG, "API REJECTED -> Retrying PO")
            po = null
            poExtractor.reset()
            transitionTo(AppState.SCANNING)
        }
    }

    /** Periodic cleanup để giải phóng Java heap */
    private fun performPeriodicCleanup() {
        try {
            System.gc()
            val runtime = Runtime.getRuntime()
            val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMem  = runtime.maxMemory() / 1024 / 1024
            Log.d(TAG, "Memory after cleanup: ${usedMem}MB / ${maxMem}MB (${usedMem * 100 / maxMem}%)")
        } catch (e: Exception) {
            Log.e(TAG, "Error during periodic cleanup", e)
        }
    }

    fun release() {
        try {
            barcodeDecoder.close()
            poExtractor.release()
            blurDetector.release()
        } catch (_: Exception) {}
    }
}
