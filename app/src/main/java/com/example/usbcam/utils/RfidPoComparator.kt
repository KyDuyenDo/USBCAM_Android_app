package com.example.usbcam.utils

import com.example.usbcam.api.DataRfid
import com.example.usbcam.api.PoResponse

/**
 * Utility class to compare data between RFID and PO APIs
 */
object RfidPoComparator {

    /**
     * Represents a difference between RFID and PO data
     */
    data class Difference(
        val fieldName: String,
        val rfidValue: String,
        val poValue: String
    )

    /**
     * Compares DataRfid and PoResponse based on specific fields.
     * Returns a list of differences.
     */
    fun compare(rfidData: DataRfid, poData: PoResponse): List<Difference> {
        val differences = mutableListOf<Difference>()

        // Compare PO Number
        if (rfidData.po.trim() != (poData.po ?: "").trim()) {
            differences.add(
                Difference(
                    "PO Number",
                    rfidData.po,
                    poData.po ?: "N/A"
                )
            )
        }

        // Compare Article
        if (rfidData.article.trim() != (poData.article ?: "").trim()) {
            differences.add(
                Difference(
                    "Article",
                    rfidData.article,
                    poData.article ?: "N/A"
                )
            )
        }

        // Compare Size
        if (rfidData.size.trim() != (poData.size ?: "").trim()) {
            differences.add(
                Difference(
                    "Size",
                    rfidData.size,
                    poData.size ?: "N/A"
                )
            )
        }

        // Add more fields as needed (Quantity, Status, etc.)
        // Note: PoResponse has quantity/qtyOrder, DataRfid might not have it yet

        return differences
    }

    /**
     * Formats the differences into a human-readable string
     */
    fun formatDifferences(differences: List<Difference>): String {
        if (differences.isEmpty()) return ""

        val sb = StringBuilder("⚠️ Dữ liệu không khớp:\n")
        differences.forEach { diff ->
            sb.append("• ${diff.fieldName}: RFID [${diff.rfidValue}] vs PO [${diff.poValue}]\n")
        }
        return sb.toString()
    }
}