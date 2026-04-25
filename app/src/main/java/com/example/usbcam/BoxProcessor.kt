package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * BoxProcessor — không còn phụ thuộc OpenCV.
 *
 * Thay đổi chính:
 * - Presence detection: dùng kết quả ML Kit barcode scan thay vì
 *   OpenCV BarcodeDetector + Mat (nếu ML Kit scan thấy barcode → có vật thể).
 * - BlurDetector: nhận Bitmap thay vì Mat.
 * - updateLogic: nhận Bitmap thay vì (Mat, Bitmap?).
 * - Position tracking: Capture barcode and PO positions for downstream processing
 */
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

    // =========================================================
    // MAIN UPDATE — nhận Bitmap thay vì (gray: Mat, bitmap: Bitmap?)
    // =========================================================
    fun updateLogic(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        frameProcessCount++

        // CRITICAL: Periodic cleanup mỗi ~25 giây (~500 frames @ 20 FPS)
        if (frameProcessCount % CLEANUP_INTERVAL == 0L) {
            performPeriodicCleanup()
            Log.i(TAG, "🧹 Periodic cleanup #${frameProcessCount / CLEANUP_INTERVAL} (frame $frameProcessCount)")
        }

        val presenceScanResult = try {
            barcodeDecoder.scan(bitmap)
        } catch (e: Exception) {
            null
        }
        processInternal(bitmap, presenceScanResult, now)
    }

    /**
     * Tương tự updateLogic(Bitmap) nhưng dùng data NV21 để tránh decode/allocate Bitmap
     */
    fun updateLogic(data: ByteArray, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        frameProcessCount++

        if (frameProcessCount % CLEANUP_INTERVAL == 0L) {
            performPeriodicCleanup()
        }

        // 1. Presence detection bằng NV21
        val presenceScanResult = try {
            barcodeDecoder.scan(data, width, height)
        } catch (e: Exception) {
            null
        }

        // Cho các bước cần Bitmap (như POExtractor), chúng ta sẽ convert sau nếu cần
        // Nhưng phần lớn thời gian (IDLE/SCANNING blur) thì không cần
        processInternal(data, width, height, presenceScanResult, now)
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
                        transitionTo(AppState.DECODED)
                    }
                }
            }
            AppState.DECODED -> {
                feedbackMessage = "DONE"
                if (!presence) transitionTo(AppState.IDLE)
            }
        }
    }

    private fun processInternal(data: ByteArray, width: Int, height: Int, presenceScanResult: BarcodeDecoder.Result?, now: Long) {
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
                if (!blurDetector.check(data, width, height)) return
                if (now - lastScanTime < Config.SCAN_THROTTLE_MS) return
                lastScanTime = now

                // Cần Bitmap để Extract PO
                val bitmap = nv21ToBitmap(data, width, height)
                handleDetection(bitmap, presenceScanResult, now)
                bitmap.recycle()
            }
            AppState.VERIFYING -> {
                feedbackMessage = "VERIFYING..."
                val result = presenceScanResult ?: try { barcodeDecoder.scan(data, width, height) } catch (e: Exception) { null }
                if (result != null) {
                    tracker.updateDetection(result.box, now)
                    if (tracker.isLikelyNewBox(result.box)) {
                        transitionTo(AppState.DECODED)
                    }
                }
            }
            AppState.DECODED -> {
                feedbackMessage = "DONE"
                if (!presence) transitionTo(AppState.IDLE)
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

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = android.graphics.YuvImage(data, android.graphics.ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

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

                val result = try {
                    barcodeDecoder.scan(bitmap)
                } catch (e: Exception) {
                    null
                }

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
                        Log.i(TAG, "TRACKING LOST -> Resetting")
                        transitionTo(AppState.IDLE)
                    }
                }
            }
            AppState.RESETTING -> {
                transitionTo(AppState.IDLE)
            }
        }
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
