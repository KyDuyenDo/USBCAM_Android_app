package com.example.usbcam

class OCRFusion(
    private val maxFrames: Int = 5,
    private val minAgree: Int = 3
) {
    private val results = mutableListOf<String>()

    fun reset() {
        results.clear()
    }

    fun add(raw: String) {
        val clean = normalize(raw)
        if (isValidFormat(clean)) {
            results.add(clean)
        }
        if (results.size > maxFrames) {
            results.removeAt(0)
        }
    }

    fun isReady(): Boolean = results.size >= minAgree

    /**
     * Majority vote theo từng ký tự
     */
    fun getFused(): String? {
        if (!isReady()) return null

        val length = results.groupBy { it.length }
            .maxByOrNull { it.value.size }
            ?.key ?: return null

        val sameLen = results.filter { it.length == length }
        if (sameLen.size < minAgree) return null

        val sb = StringBuilder()
        for (i in 0 until length) {
            val charVote = sameLen
                .map { it[i] }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return null
            sb.append(charVote)
        }
        return sb.toString()
    }

    // ================= HELPERS =================

    private fun normalize(s: String): String =
        s.uppercase().replace(" ", "").replace("\n", "")

    /**
     * Rule reject theo format PO (tùy bạn chỉnh)
     * Ví dụ: P + 5–7 chữ số
     */
    private fun isValidFormat(s: String): Boolean {
        val regex = Regex("^P[0-9]{5,7}$")
        return regex.matches(s)
    }
}
