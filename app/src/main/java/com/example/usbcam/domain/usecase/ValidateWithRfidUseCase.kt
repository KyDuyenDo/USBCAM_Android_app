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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    // 🔹 SharedFlow to allow ViewModels to observe validation results globally
    private val _events = MutableSharedFlow<ValidationResult.Success>()
    val events = _events.asSharedFlow()
    companion object {
        private const val TAG = "ValidateWithRfidUseCase"

        @Volatile
        private var instance: ValidateWithRfidUseCase? = null

        fun getInstance(
            rfidRepository: RfidRepository,
            shoeboxRepository: ShoeboxRepository
        ): ValidateWithRfidUseCase {
            return instance ?: synchronized(this) {
                instance ?: ValidateWithRfidUseCase(rfidRepository, shoeboxRepository).also { instance = it }
            }
        }
    }

    suspend operator fun invoke(
        rfidCode: String,
        cameraData: CameraData,
        selectedLine: String? = null,
        erpTarget: Int = 0
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
                            shoeboxRepository.saveToMainTable(cameraData, rfidData, selectedLine, erpTarget)
                            
                            val result = ValidationResult.Success(
                                isMatch = true,
                                message = "✅ RFID Match ($rfidCode) - Data saved successfully",
                                rfidData = rfidData
                            )
                            _events.emit(result)
                            result
                        }
                        is ComparisonResult.Mismatch -> {
                            // MISMATCH → Save to RFID detail table
                            Log.w(TAG, "Data mismatch for $rfidCode! Details: ${comparisonResult.details}")
                            shoeboxRepository.saveToMismatchTable(
                                cameraData = cameraData,
                                rfidData = rfidData,
                                mismatchFields = comparisonResult.details.map { it.field },
                                selectedLine = selectedLine
                            )
                            
                            val mismatchInfo = comparisonResult.details.joinToString("\n") { 
                                "${it.field}: Cam(${it.cameraValue}) != Rfid(${it.rfidValue})" 
                            }
                            
                            val result = ValidationResult.Success(
                                isMatch = false,
                                message = "RFID Mismatch ($rfidCode)\n$mismatchInfo",
                                rfidData = rfidData,
                                mismatchFields = comparisonResult.details.map { it.field }
                            )
                            _events.emit(result)
                            result
                        }
                    }
                }
                is Result.Error -> {
                    Log.e(TAG, "Failed to fetch RFID data ($rfidCode): ${rfidResult.message}. Saving as mismatch but allowing main record.")
                    
                    // 🔹 THEO YÊU CẦU: Lưu vào bảng chính (như không có RFID) và bảng lỗi
                    
                    // 1. Lưu vào bảng chính (Ghi nhận có scan hộp thành công)
                    shoeboxRepository.saveToMainTable(cameraData, null, selectedLine, erpTarget)
                    
                    // 2. Lưu vào bảng lỗi (Mismatch) với mã RFID và thông báo không tìm thấy dữ liệu
                    shoeboxRepository.saveToMismatchTable(
                        cameraData = cameraData,
                        rfidData = RfidData(rfidCode = rfidCode, po = "NO_PO", upc = "NO_UPC", ry = "NO_RY", size = "NO_SIZE", article = "NO_ARTICLE", color = "NO_COLOR", model = "NO_MODEL"),
                        mismatchFields = listOf("RFID_API_NO_DATA"),
                        selectedLine = selectedLine
                    )
                    
                    val errorRfidData = RfidData(rfidCode = rfidCode, po = "NO_PO", upc = "NO_UPC", ry = "NO_RY", size = "NO_SIZE", article = "NO_ARTICLE", color = "NO_COLOR", model = "NO_MODEL")
                    val result = ValidationResult.Success(
                        isMatch = false,
                        message = "No info for RFID $rfidCode. Record saved and logged.",
                        rfidData = errorRfidData, // 🔹 TRẢ VỀ DỮ LIỆU ĐỂ UI HIỂN THỊ
                        mismatchFields = listOf("RFID_API_NO_DATA")
                    )
                    _events.emit(result)
                    result
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
