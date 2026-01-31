package com.example.usbcam.data.model

/**
 * Data transfer objects for RFID validation flow
 */

/**
 * Camera scanned data
 */
data class CameraData(
    val po: String,
    val upc: String,
    val ry: String?,
    val size: String?,
    val article: String?,
    val qty: Int,
    val shoeImage: String?,
    val dateScan: String,
    val userSerialKey: String?,
    val line: String?
)

/**
 * RFID API response data
 */
data class RfidData(
    val rfidCode: String,
    val po: String?,
    val upc: String?,
    val ry: String?,
    val size: String?,
    val article: String?,
    val color: String?,
    val model: String?
)

/**
 * Comparison result between Camera and RFID data
 */
sealed class ComparisonResult {
    object Match : ComparisonResult()
    data class Mismatch(val fields: List<String>) : ComparisonResult()
}

/**
 * Validation result
 */
sealed class ValidationResult {
    data class Success(val isMatch: Boolean, val message: String) : ValidationResult()
    data class Error(val message: String, val exception: Exception? = null) : ValidationResult()
}

/**
 * Processing state for UI
 */
sealed class ProcessingState {
    object Idle : ProcessingState()
    object Processing : ProcessingState()
    
    data class Success(
        val message: String,
        val isRfidMatch: Boolean? = null
    ) : ProcessingState()
    
    data class Warning(
        val message: String,
        val mismatchFields: List<String>
    ) : ProcessingState()
    
    data class OfflineMode(val message: String) : ProcessingState()
    
    data class Error(val message: String) : ProcessingState()
}

/**
 * Sync statistics
 */
data class SyncStats(
    val totalItems: Int,
    val successCount: Int,
    val failureCount: Int,
    val duration: Long
)

/**
 * Result wrapper
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Exception, val message: String? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
}
