package com.example.usbcam

import android.util.Log

class OCRFusion(
    private val maxFrames: Int = 5,
    private val minAgree: Int = 3
) {
    companion object {
        private const val TAG = "OCRFusion"
    }

    private val results = mutableListOf<String>()

    init {
        Log.i(TAG, "Initialized with maxFrames=$maxFrames, minAgree=$minAgree")
    }

    fun reset() {
        Log.d(TAG, "Reset fusion buffer")
        results.clear()
    }

    fun add(raw: String) {
        val clean = normalize(raw)
        Log.v(TAG, "Raw: '$raw' -> Normalized: '$clean'")

        if (isValidFormat(clean)) {
            results.add(clean)
            Log.i(TAG, "✓ Valid PO added: '$clean' (buffer size: ${results.size}/$maxFrames)")
        } else {
            Log.w(TAG, "✗ Invalid format: '$clean'")
        }

        if (results.size > maxFrames) {
            val removed = results.removeAt(0)
            Log.d(TAG, "Buffer full, removed oldest: '$removed'")
        }
    }

    fun isReady(): Boolean = results.size >= minAgree

    /**
     * Majority vote theo từng ký tự
     */
    fun getFused(): String? {
        if (!isReady()) {
            Log.d(TAG, "Not ready for fusion (${results.size}/$minAgree)")
            return null
        }

        Log.i(TAG, "Starting fusion with ${results.size} results: $results")

        val length = results.groupBy { it.length }
            .maxByOrNull { it.value.size }
            ?.key ?: run {
            Log.w(TAG, "Fusion failed: no common length")
            return null
        }

        val sameLen = results.filter { it.length == length }
        if (sameLen.size < minAgree) {
            Log.w(TAG, "Fusion failed: only ${sameLen.size} have length $length (need $minAgree)")
            return null
        }

        Log.d(TAG, "Fusing ${sameLen.size} strings of length $length: $sameLen")

        val sb = StringBuilder()
        for (i in 0 until length) {
            val charVote = sameLen
                .map { it[i] }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: run {
                Log.w(TAG, "Fusion failed at position $i")
                return null
            }
            sb.append(charVote)
        }

        val fused = sb.toString()
        Log.i(TAG, "✓ FUSION SUCCESS: '$fused'")
        return fused
    }

    // ================= HELPERS =================

    private fun normalize(s: String): String {
        var result = s.uppercase()
            .replace(" ", "")
            .replace("\n", "")
            .replace("\t", "")
            .replace("_", "")
            .replace("-", "")
            .replace(".", "")
            .replace(",", "")

        // Fix common OCR errors BEFORE removing prefix
        result = fixCommonOCRErrors(result)

        // Remove "PO#" prefix and variations
        result = removePOPrefix(result)

        Log.v(TAG, "Normalize: '$s' -> '$result'")
        return result
    }

    /**
     * Sửa các lỗi OCR phổ biến với "PO#"
     */
    private fun fixCommonOCRErrors(s: String): String {
        var result = s

        // Pattern 1: "P0#" (O đọc thành số 0)
        result = result.replace(Regex("^P0#"), "PO#")
        result = result.replace(Regex("^P0\\d"), "PO#") // P0123 -> PO#123

        // Pattern 2: "PQ#" (O đọc thành Q)
        result = result.replace(Regex("^PQ#"), "PO#")
        result = result.replace(Regex("^PQ\\d"), "PO#")

        // Pattern 3: "PD#" (O đọc thành D)
        result = result.replace(Regex("^PD#"), "PO#")
        result = result.replace(Regex("^PD\\d"), "PO#")

        // Pattern 4: "O#" hoặc "0#" (thiếu P)
        result = result.replace(Regex("^O#"), "PO#")
        result = result.replace(Regex("^0#"), "PO#")

        // Pattern 5: "P#" (thiếu O)
        result = result.replace(Regex("^P#(?=\\d)"), "PO#")

        // Pattern 6: "POH" (# đọc thành H)
        result = result.replace(Regex("^POH"), "PO#")
        result = result.replace(Regex("^P0H"), "PO#")

        // Pattern 7: "PO4" (# đọc thành số 4)
        result = result.replace(Regex("^PO4(?=\\d)"), "PO#")
        result = result.replace(Regex("^P04(?=\\d)"), "PO#")

        // Pattern 8: "POA" (# đọc thành chữ A)
        result = result.replace(Regex("^POA"), "PO#")

        // Pattern 9: Các biến thể số 0/chữ O
        result = result.replace(Regex("^P[0O][#H4A]"), "PO#")

        Log.v(TAG, "OCR Error Fix: '$s' -> '$result'")
        return result
    }

    /**
     * Loại bỏ prefix "PO#" và các biến thể
     */
    private fun removePOPrefix(s: String): String {
        var result = s

        // Các pattern chuẩn
        result = result.replace(Regex("^PO#"), "")
        result = result.replace(Regex("^PO"), "")
        result = result.replace(Regex("^P#"), "")
        result = result.replace(Regex("^O#"), "")
        result = result.replace(Regex("^0#"), "")

        // Loại bỏ # nếu còn sót ở đầu
        result = result.replace(Regex("^[#H4A]"), "")

        return result
    }

    /**
     * Format: 5-12 chữ số thuần (sau khi đã loại bỏ "PO#" prefix)
     */
    private fun isValidFormat(s: String): Boolean {
        val isValid = s.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH &&
                s.all { it.isDigit() }

        if (isValid) {
            Log.d(TAG, "Format check PASS: '$s' (length=${s.length}, all digits)")
        } else {
            Log.w(TAG, "Format check FAIL: '$s' (length=${s.length}, all digits=${s.all { it.isDigit() }})")
        }

        return isValid
    }
}