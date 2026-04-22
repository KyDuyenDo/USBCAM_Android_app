package com.example.usbcam

import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Mat
import org.opencv.objdetect.BarcodeDetector

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

    // ================= INTERNAL =================
    private var stateStartTime = 0L
    private var lastScanTime = 0L

    private var frameProcessCount = 0L
    private var lastCleanupTime = 0L

    // Helpers
    private val barcodeDecoder = BarcodeDecoder()
    private val tracker = TrackingManager()

    // NEW COMPONENTS
    private val opencvBarcodeDetector = BarcodeDetector()
    private val barcodePoints = Mat()
    private val poExtractor = POExtractor()
    private val blurDetector = BlurDetector()

    // =========================================================
    // MAIN UPDATE
    // =========================================================
    fun updateLogic(gray: Mat, bitmap: Bitmap?) {
        val now = System.currentTimeMillis()
        frameProcessCount++

        // CRITICAL: Periodic cleanup mỗi 25 giây (~500 frames @ 20 FPS)
        if (frameProcessCount % CLEANUP_INTERVAL == 0L) {
            performPeriodicCleanup()
            lastCleanupTime = now
            Log.i(
                    TAG,
                    "🧹 Periodic cleanup #${frameProcessCount / CLEANUP_INTERVAL} " +
                            "(frame $frameProcessCount, uptime ${(now - stateStartTime) / 1000}s)"
            )
        }

        // 1. Detect Barcode Presence using OpenCV's BarcodeDetector
        val presence = try {
            opencvBarcodeDetector.detect(gray, barcodePoints)
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Barcode detection error", e)
            false
        }

        when (currentState) {
            AppState.IDLE -> {
                feedbackMessage = "READY"
                motionLevel = "LOW"
                conveyorDirection = ""

                // Transition logic: Presence (Motion) -> SCANNING
                if (presence) {
                    Log.i(TAG, "STATE: IDLE -> SCANNING (Presence Detected)")
                    transitionTo(AppState.SCANNING)
                }
            }
            AppState.SCANNING -> {
                feedbackMessage = "SCANNING..."

                // Timeout Check
                // Timeout Check
                val elapsed = now - stateStartTime
                if (!presence) {
                    Log.i(TAG, "STATE: IDLE -> SCANNING (Presence Detected)")
                    transitionTo(AppState.IDLE)
                }
//                if (elapsed > Config.SCAN_TIMEOUT_MS) {
//                    Log.i(TAG, "SCAN TIMEOUT -> Resetting to IDLE")
//                    transitionTo(AppState.IDLE)
//                    return
//                }

                // Blur Check (Using new component)
                if (!blurDetector.check(gray)) {
                    return
                }

                if (bitmap == null) return

                // Throttle Scan
                if (now - lastScanTime < Config.SCAN_THROTTLE_MS) return
                lastScanTime = now

                // Synchronous Scan
                val result = barcodeDecoder.scan(bitmap)
                if (result != null) {
                    Log.i(TAG, "BARCODE FOUND: ${result.value}")
                    barcode = result.value

                    // Extract PO (Using new component)
                    val poResult = poExtractor.extract(bitmap, result.value)
                    if (poResult != null) {
                        po = poResult
                    }

                    // Initialize Tracking
                    tracker.updateDetection(result.box, now)

                    if (po != null) {
                        Log.i(TAG, "BARCODE + PO FOUND -> Verifying")
                        transitionTo(AppState.VERIFYING)
                    } else {
                        // Keep scanning if PO missing (or maybe we just stay in SCANNING and
                        // retrying PO extraction is implied by the loop)
                        // Actually, if barcode is found but PO is not, we are technically still
                        // SCANNING for PO
                        // while tracking the barcode.
                        // BUT, to keep it simple as per "continue extracting text until a suitable
                        // PO is found",
                        // we can stay in SCANNING, but we need to update tracker so we don't lose
                        // the box.
                        // The current code structure in SCANNING re-detects every time.
                        // Ideally we should switch to a state where we track AND scan for PO.
                        // For now let's stick to the plan: Stay in SCANNING if PO is null.
                    }
                }
            }
            AppState.VERIFYING -> {
                feedbackMessage = "VERIFYING..."

                if (bitmap == null) return

                val result = barcodeDecoder.scan(bitmap)
                if (result != null) {
                    // Update Tracker
                    tracker.updateDetection(result.box, now)

                    // Check for New Box
                    if (tracker.isLikelyNewBox(result.box)) {
                        Log.i(TAG, "NEW BOX DETECTED (during verify) -> Resetting")
                        transitionTo(AppState.IDLE)
                        return
                    }
                } else {
                    // Miss Logic
                    val shouldReset = tracker.updateMiss(now)
                    if (shouldReset) {
                        Log.i(TAG, "TRACKING LOST (during verify) -> Resetting")
                        transitionTo(AppState.IDLE)
                    }
                }
            }
            AppState.DECODED -> {
                feedbackMessage = "TRACKING: $motionLevel"

                if (bitmap == null) return

                val result = barcodeDecoder.scan(bitmap)
                if (result != null) {
                    // Update Tracker
                    tracker.updateDetection(result.box, now)

                    // Check for New Box
                    if (tracker.isLikelyNewBox(result.box)) {
                        Log.i(TAG, "NEW BOX DETECTED -> Resetting")
                        transitionTo(AppState.IDLE)
                        return
                    }

                    motionLevel = tracker.getMotionLevel()
                    // Update direction logic if needed
                } else {
                    // Miss Logic
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

    /** ✅ Periodic cleanup để giải phóng native memory */
    private fun performPeriodicCleanup() {
        try {
            // Force System.gc() để dọn Java heap
            System.gc()

            // Log memory info
            val runtime = Runtime.getRuntime()
            val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMem = runtime.maxMemory() / 1024 / 1024

            Log.d(
                    TAG,
                    "Memory after cleanup: ${usedMem}MB / ${maxMem}MB (${usedMem * 100 / maxMem}%)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during periodic cleanup", e)
        }
    }

    fun release() {
        try {
            barcodeDecoder.close()
            barcodePoints.release()
            // BarcodeDetector doesn't have an explicit release in Java API usually, 
            // GraphicalCodeDetector might not have close() either, but native memory is handled by GC/finalize
            poExtractor.release()
            blurDetector.release()
        } catch (_: Exception) {}
    }
}
