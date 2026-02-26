package com.example.usbcam.domain.usecase

import android.util.Log
import com.example.usbcam.api.PoResponse
import com.example.usbcam.data.mapper.toCameraData
import com.example.usbcam.data.model.ValidationResult
import com.example.usbcam.repository.ShoeboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use Case: Process camera scanned data with RFID validation
 * 
 * Flow:
 * 1. Check if RFID code was scanned from RFID reader
 * 2. If YES → Validate with RFID data (compare and save to appropriate table)
 * 3. If NO → Save normally to main table
 * 
 * This is the main entry point for handling successful API responses
 * from USB Camera scanning.
 */
class ProcessCameraWithRfidUseCase(
    private val shoeboxRepository: ShoeboxRepository,
    private val validateWithRfidUseCase: ValidateWithRfidUseCase
) {
    companion object {
        private const val TAG = "ProcessCameraWithRfid"
    }

    /**
     * Process camera scan data with optional RFID validation
     * 
     * @param po PO number from camera
     * @param barcode UPC barcode from camera
     * @param apiResponse API response containing product info
     * @param scannedRfidCode RFID code scanned from RFID reader (optional)
     * @return ValidationResult indicating success/failure and match status
     */
    suspend operator fun invoke(
        po: String,
        barcode: String,
        apiResponse: PoResponse,
        scannedRfidCode: String? = null,
        selectedLine: String? = null
    ): ValidationResult = withContext(Dispatchers.IO) {
        try {
            // Convert API response to camera data model
            val cameraData = apiResponse.toCameraData(po, barcode)
            
            // 🔹 Bước 1: Kiểm tra RFID đã được scan từ RFID Scanner
            if (scannedRfidCode.isNullOrBlank()) {
                // 🔹 Bước 3: KHÔNG CÓ RFID
                Log.d(TAG, "📦 No RFID scanned → Saving normally to main table")
                
                // Bỏ qua bước so sánh, lưu dữ liệu bình thường
                shoeboxRepository.saveToMainTable(cameraData, rfidData = null, selectedLine, erpTarget = apiResponse.quantity ?: 0)

                return@withContext ValidationResult.Success(
                    isMatch = true,  // Considered "match" since no validation needed
                    message = "Data saved successfully (no RFID scanned)"
                )
            }
            
            // 🔹 Bước 2: CÓ RFID (đã scan từ RFID reader)
            Log.d(TAG, "RFID scanned: $scannedRfidCode → Starting validation process...")
            
            // Thực hiện truy xuất dữ liệu RFID từ API và so sánh tự động
            val validationResult = validateWithRfidUseCase.invoke(
                rfidCode = scannedRfidCode, 
                cameraData = cameraData, 
                selectedLine = selectedLine,
                erpTarget = apiResponse.quantity ?: 0
            )
            
            when (validationResult) {
                is ValidationResult.Success -> {
                    if (validationResult.isMatch) {
                        Log.i(TAG, "✅ RFID MATCH: Data saved to main table")
                    } else {
                        Log.w(TAG, "⚠️ RFID MISMATCH: Data saved to exception table")
                    }
                }
                is ValidationResult.Error -> {
                    Log.e(TAG, "❌ RFID validation failed: ${validationResult.message}")
                }
            }
            
            return@withContext validationResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera data with RFID", e)
            return@withContext ValidationResult.Error(
                message = "Failed to process scan: ${e.message}",
                exception = e
            )
        }
    }
}
