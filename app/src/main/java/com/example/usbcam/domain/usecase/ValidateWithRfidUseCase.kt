package com.example.usbcam.domain.usecase

import android.util.Log
import com.example.usbcam.data.model.CameraData
import com.example.usbcam.data.model.ComparisonResult
import com.example.usbcam.data.model.MismatchDetail
import com.example.usbcam.data.model.Result
import com.example.usbcam.data.model.RfidData
import com.example.usbcam.data.model.ValidationResult
import com.example.usbcam.repository.RfidRepository
import com.example.usbcam.repository.ShoeboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use Case: Validate camera data with RFID data
 * 
 * Flow:
 * 1. Fetch RFID data from API
 * 2. Compare camera data with RFID data
 * 3. Save to appropriate table based on comparison result
 */
class ValidateWithRfidUseCase(
    private val rfidRepository: RfidRepository,
    private val shoeboxRepository: ShoeboxRepository
) {
    companion object {
        private const val TAG = "ValidateWithRfidUseCase"
    }

    suspend operator fun invoke(
        rfidCode: String,
        cameraData: CameraData
    ): ValidationResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting validation for RFID: $rfidCode")
            
            // Step 1: Fetch RFID data from API
            val rfidResult = rfidRepository.getRfidInfo(rfidCode)
            
            when (rfidResult) {
                is Result.Success -> {
                    val rfidData = rfidResult.data
                    
                    // Step 2: Compare data
                    val comparisonResult = compareData(cameraData, rfidData)
                    
                    // Step 3: Save based on comparison result
                    when (comparisonResult) {
                        is ComparisonResult.Match -> {
                            // MATCH → Save to main table
                            Log.i(TAG, "Data matches! Saving to main table")
                            shoeboxRepository.saveToMainTable(cameraData, rfidData)
                            
                            ValidationResult.Success(
                                isMatch = true,
                                message = "✅ RFID Match ($rfidCode) - Data saved successfully",
                                rfidData = rfidData
                            )
                        }
                        is ComparisonResult.Mismatch -> {
                            // MISMATCH → Save to RFID detail table
                            Log.w(TAG, "Data mismatch for $rfidCode! Details: ${comparisonResult.details}")
                            shoeboxRepository.saveToMismatchTable(
                                cameraData = cameraData,
                                rfidData = rfidData,
                                mismatchFields = comparisonResult.details.map { it.field }
                            )
                            
                            val mismatchInfo = comparisonResult.details.joinToString("\n") { 
                                "${it.field}: Cam(${it.cameraValue}) != Rfid(${it.rfidValue})" 
                            }
                            
                            ValidationResult.Success(
                                isMatch = false,
                                message = "⚠️ RFID Mismatch ($rfidCode)\n$mismatchInfo",
                                rfidData = rfidData,
                                mismatchFields = comparisonResult.details.map { it.field }
                            )
                        }
                    }
                }
                is Result.Error -> {
                    Log.e(TAG, "Failed to fetch RFID data ($rfidCode): ${rfidResult.message}", rfidResult.exception)
                    ValidationResult.Error(
                        message = "RFID API Error ($rfidCode): ${rfidResult.message}",
                        exception = rfidResult.exception
                    )
                }
                is Result.Loading -> {
                    ValidationResult.Error("Unexpected loading state for $rfidCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation error for $rfidCode", e)
            ValidationResult.Error(
                message = "Validation failed ($rfidCode): ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Compare camera data with RFID data
     * Returns detailed mismatch information
     */
    private fun compareData(camera: CameraData, rfid: RfidData): ComparisonResult {
        val details = mutableListOf<MismatchDetail>()
        
        // Compare PO
        if (camera.po != rfid.po && rfid.po != null) {
            details.add(MismatchDetail("PO", camera.po, rfid.po))
            Log.d(TAG, "PO mismatch: Camera=${camera.po}, RFID=${rfid.po}")
        }
        
        // Compare Size
        if (camera.size != rfid.size && rfid.size != null) {
            details.add(MismatchDetail("Size", camera.size ?: "null", rfid.size))
            Log.d(TAG, "Size mismatch: Camera=${camera.size}, RFID=${rfid.size}")
        }
        
        // Compare Article
        if (camera.article != rfid.article && rfid.article != null) {
            details.add(MismatchDetail("Article", camera.article ?: "null", rfid.article))
            Log.d(TAG, "Article mismatch: Camera=${camera.article}, RFID=${rfid.article}")
        }
        
        // Compare UPC (Note: Not available in RFID API, will always be null)
        if (camera.upc != rfid.upc && rfid.upc != null) {
            details.add(MismatchDetail("UPC", camera.upc, rfid.upc))
            Log.d(TAG, "UPC mismatch: Camera=${camera.upc}, RFID=${rfid.upc}")
        }
        
        // Compare RY (Note: Not available in RFID API, will always be null)
        if (camera.ry != rfid.ry && rfid.ry != null) {
            details.add(MismatchDetail("RY", camera.ry ?: "null", rfid.ry))
            Log.d(TAG, "RY mismatch: Camera=${camera.ry}, RFID=${rfid.ry}")
        }
        
        return if (details.isEmpty()) {
            Log.i(TAG, "All fields match!")
            ComparisonResult.Match
        } else {
            Log.w(TAG, "Mismatch detected: $details")
            ComparisonResult.Mismatch(details)
        }
    }
}
