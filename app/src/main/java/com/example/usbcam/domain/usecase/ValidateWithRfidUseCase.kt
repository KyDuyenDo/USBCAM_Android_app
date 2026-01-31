package com.example.usbcam.domain.usecase

import android.util.Log
import com.example.usbcam.data.model.CameraData
import com.example.usbcam.data.model.ComparisonResult
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
                            Log.i(TAG, "✅ Data matches! Saving to main table")
                            shoeboxRepository.saveToMainTable(cameraData, rfidData)
                            
                            ValidationResult.Success(
                                isMatch = true,
                                message = "✅ RFID Match - Data saved successfully"
                            )
                        }
                        is ComparisonResult.Mismatch -> {
                            // MISMATCH → Save to RFID detail table
                            Log.w(TAG, "⚠️ Data mismatch! Fields: ${comparisonResult.fields}")
                            shoeboxRepository.saveToMismatchTable(
                                cameraData = cameraData,
                                rfidData = rfidData,
                                mismatchFields = comparisonResult.fields
                            )
                            
                            ValidationResult.Success(
                                isMatch = false,
                                message = "⚠️ RFID Mismatch - Saved for review\nFields: ${comparisonResult.fields.joinToString()}"
                            )
                        }
                    }
                }
                is Result.Error -> {
                    Log.e(TAG, "Failed to fetch RFID data: ${rfidResult.message}", rfidResult.exception)
                    ValidationResult.Error(
                        message = "Failed to fetch RFID data: ${rfidResult.message}",
                        exception = rfidResult.exception
                    )
                }
                is Result.Loading -> {
                    ValidationResult.Error("Unexpected loading state")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation error", e)
            ValidationResult.Error(
                message = "Validation failed: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Compare camera data with RFID data
     * Returns list of mismatched fields
     */
    private fun compareData(camera: CameraData, rfid: RfidData): ComparisonResult {
        val mismatchFields = mutableListOf<String>()
        
        // Compare PO
        if (camera.po != rfid.po && rfid.po != null) {
            mismatchFields.add("PO")
            Log.d(TAG, "PO mismatch: Camera=${camera.po}, RFID=${rfid.po}")
        }
        
        // Compare Size
        if (camera.size != rfid.size && rfid.size != null) {
            mismatchFields.add("Size")
            Log.d(TAG, "Size mismatch: Camera=${camera.size}, RFID=${rfid.size}")
        }
        
        // Compare Article
        if (camera.article != rfid.article && rfid.article != null) {
            mismatchFields.add("Article")
            Log.d(TAG, "Article mismatch: Camera=${camera.article}, RFID=${rfid.article}")
        }
        
        // Compare UPC (Note: Not available in RFID API, will always be null)
        if (camera.upc != rfid.upc && rfid.upc != null) {
            mismatchFields.add("UPC")
            Log.d(TAG, "UPC mismatch: Camera=${camera.upc}, RFID=${rfid.upc}")
        }
        
        // Compare RY (Note: Not available in RFID API, will always be null)
        if (camera.ry != rfid.ry && rfid.ry != null) {
            mismatchFields.add("RY")
            Log.d(TAG, "RY mismatch: Camera=${camera.ry}, RFID=${rfid.ry}")
        }
        
        return if (mismatchFields.isEmpty()) {
            Log.i(TAG, "All fields match!")
            ComparisonResult.Match
        } else {
            Log.w(TAG, "Mismatch detected in fields: $mismatchFields")
            ComparisonResult.Mismatch(mismatchFields)
        }
    }
}
