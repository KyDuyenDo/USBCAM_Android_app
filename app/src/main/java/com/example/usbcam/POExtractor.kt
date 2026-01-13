package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class POExtractor {

    companion object {
        private const val TAG = "POExtractor"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrFusion = OCRFusion()

    fun extract(bitmap: Bitmap, barcode: String): String? {
        val w = bitmap.width
        val h = bitmap.height
        val roiW = (w * 0.8).toInt()
        val roiH = (h * 0.6).toInt()
        val roiX = (w - roiW) / 2
        val roiY = (h - roiH) / 2

        Log.d(TAG, "=== PO EXTRACTION START ===")
        Log.d(TAG, "Frame size: ${w}x${h}, ROI: ${roiW}x${roiH} at ($roiX, $roiY)")

        try {
            // Extract ROI
            val poBitmap = Bitmap.createBitmap(bitmap, roiX, roiY, roiW, roiH)

            // Upscale to HD quality (1920x1080 or proportional)
            val targetWidth = 1920
            val targetHeight = (roiH.toFloat() / roiW.toFloat() * targetWidth).toInt()
            val hdBitmap = Bitmap.createScaledBitmap(poBitmap, targetWidth, targetHeight, true)
            poBitmap.recycle()
            Log.d(TAG, "Upscaled to HD: ${targetWidth}x${targetHeight}")

            // Apply slight brightness boost while keeping original colors
            val enhancedBitmap = applyBrightnessBoost(hdBitmap, Config.PO_BRIGHTNESS_BOOST)
            hdBitmap.recycle()

            val image = InputImage.fromBitmap(enhancedBitmap, 0)

            val task = textRecognizer.process(image)
            val visionText = Tasks.await(task) // Synchronous!

            enhancedBitmap.recycle()

            Log.i(TAG, "OCR Raw Text:\n${visionText.text}")
            Log.i(TAG, "Total text blocks: ${visionText.textBlocks.size}")

            // Parse all lines and words
            val allCandidates = mutableListOf<String>()

            visionText.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val lineText = line.text.trim()
                    Log.d(TAG, "Line: '$lineText'")
                    allCandidates.add(lineText)

                    // Also check individual words
                    line.elements.forEach { element ->
                        val word = element.text.trim()
                        if (word.isNotEmpty()) {
                            Log.v(TAG, "  Word: '$word'")
                            allCandidates.add(word)
                        }
                    }
                }
            }

            // Also split by space and newline from full text
            val splitCandidates = visionText.text.split("\n", " ", "\t")
            splitCandidates.forEach { candidate ->
                val trimmed = candidate.trim()
                if (trimmed.isNotEmpty()) {
                    allCandidates.add(trimmed)
                }
            }

            // Generate smart variations for candidates with potential PO# prefix
            val smartCandidates = generateSmartVariations(allCandidates)
            Log.i(TAG, "Total candidates (with variations): ${smartCandidates.size}")

            // Process each candidate
            var foundCount = 0
            for (candidate in smartCandidates) {
                if (candidate.isEmpty()) continue

                // Normalize: Fix OCR errors, remove "PO#" and clean
                val normalized = normalizeCandidate(candidate)

                if (normalized.isEmpty()) continue

                Log.v(TAG, "Candidate: '$candidate' -> Normalized: '$normalized'")

                // Check if it's valid PO format (5-12 digits only)
                if (normalized.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH &&
                    normalized.all { it.isDigit() }
                ) {
                    foundCount++
                    Log.i(TAG, "✓ VALID PO CANDIDATE #$foundCount: '$normalized' (from: '$candidate')")

                    ocrFusion.add(normalized)
                    val fused = ocrFusion.getFused()
                    if (fused != null) {
                        Log.i(TAG, "🎯 PO FOUND (Fused): $fused")
                        return fused
                    }
                } else {
                    if (normalized.length > 3) { // Only log non-trivial rejects
                        Log.d(TAG, "✗ Rejected: '$normalized' (length=${normalized.length}, digits=${normalized.all { it.isDigit() }})")
                    }
                }
            }

            Log.w(TAG, "No valid PO found (checked ${smartCandidates.size} candidates, $foundCount valid)")

        } catch (e: Exception) {
            Log.e(TAG, "PO Extraction Error", e)
        }

        Log.d(TAG, "=== PO EXTRACTION END ===")
        return null
    }

    /**
     * Generate smart variations để xử lý các trường hợp OCR sai
     */
    private fun generateSmartVariations(candidates: List<String>): List<String> {
        val variations = mutableSetOf<String>()

        for (candidate in candidates) {
            // Add original
            variations.add(candidate)

            val upper = candidate.uppercase().trim()

            // Variation 1: Gộp 2 từ liên tiếp có thể là "PO#" + "123456"
            // Ví dụ: ["P0#", "123456"] -> "P0#123456"
            if (upper.matches(Regex("^[PO0Q][O0Q]?[#H4A]?$"))) {
                // Đây có thể là prefix "PO#", tìm số tiếp theo
                val idx = candidates.indexOf(candidate)
                if (idx < candidates.size - 1) {
                    val next = candidates[idx + 1].trim()
                    if (next.isNotEmpty() && next[0].isDigit()) {
                        val combined = "$upper$next"
                        variations.add(combined)
                        Log.d(TAG, "Smart combine: '$candidate' + '$next' -> '$combined'")
                    }
                }
            }

            // Variation 2: Nếu text chứa cả prefix và số, thử tách
            // Ví dụ: "PO#123456" -> giữ nguyên
            // Ví dụ: "P0123456" -> có thể là "P0#123456"
            if (upper.length > 6 && upper[0] in "PO0Q") {
                // Thử insert "#" ở vị trí 2 hoặc 3
                if (upper.length >= 8) {
                    val v1 = upper.substring(0, 2) + "#" + upper.substring(2)
                    val v2 = upper.substring(0, 3) + "#" + upper.substring(3)
                    variations.add(v1)
                    variations.add(v2)
                }
            }

            // Variation 3: Nếu toàn số và độ dài hợp lý, giữ nguyên
            // (có thể prefix bị mất hoàn toàn)
            if (upper.all { it.isDigit() } && upper.length in 5..12) {
                variations.add(upper)
            }
        }

        return variations.toList()
    }

    /**
     * Normalize candidate: fix OCR errors và clean
     */
    private fun normalizeCandidate(s: String): String {
        var result = s.uppercase()
            .replace(" ", "")
            .replace("\n", "")
            .replace("\t", "")
            .replace("_", "")
            .replace("-", "")
            .replace(".", "")
            .replace(",", "")

        // Fix common OCR errors
        result = fixCommonOCRErrors(result)

        // Remove "PO#" prefix and variations
        result = removePOPrefix(result)

        return result
    }

    /**
     * Sửa các lỗi OCR phổ biến
     */
    private fun fixCommonOCRErrors(s: String): String {
        var result = s

        // P đọc nhầm thành số hoặc chữ khác
        result = result.replace(Regex("^[P8BRD]([O0Q])"), "PO")

        // O đọc nhầm thành 0, Q, D, C
        result = result.replace(Regex("^P[0QDCG]"), "PO")

        // # đọc nhầm thành H, 4, A, X
        result = result.replace(Regex("^PO[#H4AX]"), "PO#")

        // Kết hợp: P0H -> PO#, P04 -> PO#, etc.
        result = result.replace(Regex("^P[O0Q][#H4AX]"), "PO#")

        // Thiếu P: O#123, 0#123 -> PO#123
        result = result.replace(Regex("^[O0][#H4AX]"), "PO#")

        // Thiếu O: P#123 -> PO#123
        result = result.replace(Regex("^P[#H4AX](?=\\d)"), "PO#")

        // Thiếu #: PO123 -> PO#123 (nếu theo sau là số)
        if (result.startsWith("PO") && result.length > 2 && result[2].isDigit()) {
            result = "PO#" + result.substring(2)
        }

        return result
    }

    /**
     * Loại bỏ prefix PO# và các biến thể
     */
    private fun removePOPrefix(s: String): String {
        var result = s

        // Remove standard patterns
        result = result.replace(Regex("^PO[#H4AX]?"), "")
        result = result.replace(Regex("^P[#H4AX]"), "")
        result = result.replace(Regex("^[O0][#H4AX]"), "")

        // Remove remaining prefix characters
        result = result.replace(Regex("^[#H4AX]+"), "")

        return result
    }

    private fun applyBrightnessBoost(source: Bitmap, boost: Float): Bitmap {
        if (boost <= 0) return source

        val result =
            Bitmap.createBitmap(
                source.width,
                source.height,
                (source.config ?: Bitmap.Config.ARGB_8888)
            )
        val canvas = Canvas(result)
        val paint = Paint()

        // ColorMatrix to adjust brightness while preserving colors
        val colorMatrix =
            ColorMatrix(
                floatArrayOf(
                    1f,
                    0f,
                    0f,
                    0f,
                    boost, // Red channel + brightness
                    0f,
                    1f,
                    0f,
                    0f,
                    boost, // Green channel + brightness
                    0f,
                    0f,
                    1f,
                    0f,
                    boost, // Blue channel + brightness
                    0f,
                    0f,
                    0f,
                    1f,
                    0f // Alpha channel (unchanged)
                )
            )

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return result
    }

    fun reset() {
        Log.d(TAG, "POExtractor reset")
        ocrFusion.reset()
    }

    fun release() {
        try {
            textRecognizer.close()
        } catch (_: Exception) {}
    }
}