package com.example.usbcam.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.usbcam.BoxProcessor
import com.example.usbcam.api.DataRfid
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RfidViewModel - MVVM Pattern for RFID functionality
 *
 * Manages:
 * - RFID connection state
 * - Tag scanning data
 * - API calls to fetch RFID information
 * - UI state for RFID scanner
 */
class RfidViewModel(
        application: Application,
        private val rfidRepository: com.example.usbcam.repository.RfidRepository,
        private val validateWithRfidUseCase:
                com.example.usbcam.domain.usecase.ValidateWithRfidUseCase
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RfidViewModel"
    }

    // Initialized via constructor
    private lateinit var boxProcessor: BoxProcessor

    // Connection state
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    // Last scanned RFID tag
    private val _lastEpc = MutableLiveData<String>("")
    val lastEpc: LiveData<String> = _lastEpc

    // Inventory of all scanned tags in current cycle
    private val _scannedEpcs = mutableSetOf<String>()
    val scannedEpcs: Set<String>
        get() = _scannedEpcs

    data class TagRead(val rssi: Int, val antenna: Int, val channel: Int)
    private val _tagReadings = mutableMapOf<String, MutableList<TagRead>>()

    private val _lastRssi = MutableLiveData<Int>(0)
    val lastRssi: LiveData<Int> = _lastRssi

    // RFID product information from API
    private val _rfidData = MutableLiveData<DataRfid?>()
    val rfidData: LiveData<DataRfid?> = _rfidData

    // Camera data for comparison
    private var _currentCameraData: PoResponse? = null

    // Loading state
    private val _isLoadingRfidInfo = MutableLiveData<Boolean>(false)
    val isLoadingRfidInfo: LiveData<Boolean> = _isLoadingRfidInfo

    // Field match states
    private val _isPoMatch = MutableLiveData<Boolean>(true)
    val isPoMatch: LiveData<Boolean> = _isPoMatch

    private val _isArtMatch = MutableLiveData<Boolean>(true)
    val isArtMatch: LiveData<Boolean> = _isArtMatch

    private val _isSizeMatch = MutableLiveData<Boolean>(true)
    val isSizeMatch: LiveData<Boolean> = _isSizeMatch

    private val _isUpcMatch = MutableLiveData<Boolean>(true)
    val isUpcMatch: LiveData<Boolean> = _isUpcMatch

    // Error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Success/Info messages
    private val _infoMessage = MutableLiveData<String?>()
    val infoMessage: LiveData<String?> = _infoMessage

    // Connection status message
    private val _connectionStatus = MutableLiveData<String>("Not Connected")
    val connectionStatus: LiveData<String> = _connectionStatus

    /** Update connection state */
    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        _connectionStatus.value = if (connected) "Connected" else "Disconnected"

        if (!connected) {
            // Reset scanning state when disconnected
            _isScanning.value = false
        }
    }

    /** Update scanning state */
    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        _connectionStatus.value =
                when {
                    !_isConnected.value!! -> "Disconnected"
                    scanning -> "Connected - Scanning..."
                    else -> "Connected"
                }
    }

    /** Handle new RFID tag read Only keeps first 24 characters of EPC */
    fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
        Log.d(TAG, "Tag Read (Full): EPC=$epc, RSSI=$rssi, Ant=$antenna, Ch=$channel")

        // Only take first 24 characters
        val trimmedEpc =
                if (epc.length > 24) {
                    epc.substring(0, 24)
                } else {
                    epc
                }

        Log.d(TAG, "Tag Read (Trimmed): EPC=$trimmedEpc (${trimmedEpc.length} chars)")

        val list = _tagReadings.getOrPut(trimmedEpc) { mutableListOf() }
        list.add(TagRead(rssi, antenna, channel))

        _scannedEpcs.add(trimmedEpc)
        _lastEpc.value = trimmedEpc
        _lastRssi.value = rssi
    }

    /** Calculate average RSSI and find the best EPC */
    fun processAndGetBestEpc(): String? {
        if (_tagReadings.isEmpty()) return null

        var bestEpc: String? = null
        var maxAverageRssi = Double.NEGATIVE_INFINITY

        for ((epc, reads) in _tagReadings) {
            val byAntenna = reads.groupBy { it.antenna }
            for ((antenna, antReads) in byAntenna) {
                val sortedRssi = antReads.map { it.rssi }.sorted()
                // Drop extreme noise values if we have enough reads
                val filtered =
                        if (sortedRssi.size >= 4) sortedRssi.drop(1).dropLast(1) else sortedRssi
                val avg = filtered.average()

                Log.d(
                        TAG,
                        "EPC: $epc, Ant: $antenna, Avg RSSI: $avg, Count: ${antReads.size} (Filtered: ${filtered.size})"
                )

                if (avg > maxAverageRssi) {
                    maxAverageRssi = avg
                    bestEpc = epc
                }
            }
        }

        Log.d(TAG, "Best EPC determined: $bestEpc with Avg RSSI: $maxAverageRssi")
        if (bestEpc != null) {
            _lastEpc.postValue(bestEpc)
        }
        return bestEpc
    }

    /** Update ViewModel data using result from validation UseCase (called from outside) */
    fun updateFromValidationResult(result: com.example.usbcam.data.model.ValidationResult.Success) {
        val rfidData = result.rfidData ?: return

        // 1. Update product info display
        val apiRfidData =
                com.example.usbcam.api.DataRfid(
                        rfid = rfidData.rfidCode,
                        po = rfidData.po ?: "",
                        barcode = rfidData.upc ?: "",
                        size = rfidData.size ?: "",
                        article = rfidData.article ?: "",
                        color = rfidData.color ?: "",
                        model = rfidData.model ?: ""
                )
        _rfidData.postValue(apiRfidData)

        // 2. Update match booleans based on mismatchFields
        val mismatches = result.mismatchFields
        _isPoMatch.postValue(!mismatches.contains("PO"))
        _isArtMatch.postValue(!mismatches.contains("Article"))
        _isSizeMatch.postValue(!mismatches.contains("Size"))
        _isUpcMatch.postValue(!mismatches.contains("UPC"))

        Log.d(
                TAG,
                "Updated RfidViewModel from validation result: Match=${result.isMatch}, Mismatches=$mismatches"
        )
    }

    /** Fetch RFID information from API - Kept for potential manual refresh only */
    fun fetchRfidInfo(epc: String) {
        // Implementation kept but not automatically called
        if (epc.isEmpty()) {
            _errorMessage.value = "EPC code is empty"
            return
        }

        // Rest of implementation... (Optional: could be completely removed if refresh is not
        // needed)
    }

    /** Validate current RFID scan against camera data using UseCase */
    private suspend fun validateCurrentScan(
            epc: String,
            rfidData: com.example.usbcam.data.model.RfidData
    ) {
        val camResp = _currentCameraData ?: return

        // Convert PoResponse to CameraData
        val cameraData =
                com.example.usbcam.data.model.CameraData(
                        po = camResp.po ?: "",
                        upc = camResp.upc ?: "",
                        ry = camResp.ry,
                        size = camResp.size,
                        article = camResp.article,
                        qty = 1,
                        shoeImage = camResp.articleImage,
                        dateScan =
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                        .format(Date()),
                        userSerialKey = "DEVICE",
                        line = camResp.lean
                )

        // Use the common validation use case
        val validationResult = validateWithRfidUseCase.invoke(epc, cameraData)

        // Update match states based on comparison (re-run local comparison for field-level
        // booleans)
        // Note: Ideally ValidateWithRfidUseCase would return the mismatch details too.
        performDetailedComparison(
                com.example.usbcam.api.DataRfid(
                        rfid = rfidData.rfidCode,
                        po = rfidData.po ?: "",
                        barcode = rfidData.upc ?: "",
                        size = rfidData.size ?: "",
                        article = rfidData.article ?: "",
                        color = rfidData.color ?: "",
                        model = rfidData.model ?: ""
                )
        )
    }

    /** Clear error message after it's been shown */
    fun clearError() {
        _errorMessage.value = null
    }

    /** Clear info message after it's been shown */
    fun clearInfo() {
        _infoMessage.value = null
    }

    /** Clear all RFID data */
    fun clearRfidData() {
        _scannedEpcs.clear()
        _tagReadings.clear()
        _lastEpc.value = ""
        _lastRssi.value = 0
        _rfidData.value = null
        _errorMessage.value = null
        _infoMessage.value = null
        _isPoMatch.value = true
        _isArtMatch.value = true
        _isSizeMatch.value = true
        _isUpcMatch.value = true
    }

    /** Manual refresh - refetch current EPC data */
    fun refresh() {
        val currentEpc = _lastEpc.value
        if (!currentEpc.isNullOrEmpty()) {
            fetchRfidInfo(currentEpc)
        } else {
            // _errorMessage.value = "No RFID tag scanned yet"
        }
    }

    /** Initialize BoxProcessor to allow PO comparison */
    fun initBoxProcessor(processor: BoxProcessor) {
        this.boxProcessor = processor
    }

    /** Set current camera data from API for detailed comparison */
    fun setCurrentCameraResponse(data: PoResponse?) {
        this._currentCameraData = data
    }

    /** Comprehensive comparison of RFID data with Camera/API data */
    private fun performDetailedComparison(rfidData: DataRfid) {
        val epc = _lastEpc.value ?: "Unknown"
        val mismatches = mutableListOf<String>()

        // Reset matches at start
        var poMatch = true
        var artMatch = true
        var sizeMatch = true
        var upcMatch = true

        // 1. Compare with current API response (best source)
        val camData = _currentCameraData
        if (camData != null) {
            // Compare PO
            if (!camData.po.isNullOrEmpty() && rfidData.po.isNotEmpty()) {
                if (camData.po != rfidData.po) {
                    poMatch = false
                    // mismatches.add("PO Mismatch")
                }
            }
            if (!camData.upc.isNullOrEmpty() && rfidData.barcode.isNotEmpty()) {
                if (camData.upc != rfidData.barcode) {
                    upcMatch = false
                    // mismatches.add("PO Mismatch")
                }
            }
            // Compare Article
            if (!camData.article.isNullOrEmpty() && rfidData.article.isNotEmpty()) {
                if (camData.article != rfidData.article) {
                    artMatch = false
                    // mismatches.add("Article Mismatch")
                }
            }
            // Compare Size
            if (!camData.size.isNullOrEmpty() && rfidData.size.isNotEmpty()) {
                if (camData.size != rfidData.size) {
                    sizeMatch = false
                    // mismatches.add("Size Mismatch")
                }
            }
        } else if (::boxProcessor.isInitialized) {
            // 2. Fallback to BoxProcessor PO if API response not yet available
            val cameraPo = boxProcessor.po
            if (cameraPo != null && rfidData.po.isNotEmpty()) {
                if (cameraPo != rfidData.po) {
                    poMatch = false
                    // mismatches.add("PO Mismatch")
                }
            }
        } else {
            Log.d(TAG, "No camera data available for comparison yet")
            return
        }

        // Update match LiveDatas
        _isPoMatch.postValue(poMatch)
        _isArtMatch.postValue(artMatch)
        _isSizeMatch.postValue(sizeMatch)
        _isUpcMatch.postValue(upcMatch)

        if (poMatch && artMatch && sizeMatch) {
            // _infoMessage.postValue("✅ MATCH ($epc): Data is identical")
        } else {
            // Requirement: Don't build detailed mismatch strings.
            // The UI will highlight incorrect fields in red using the booleans above.
            // _errorMessage.postValue("⚠️ MISMATCH DETECTED ($epc)")
        }
    }
}

/** Factory for RfidViewModel */
class RfidViewModelFactory(private val application: Application) :
        androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RfidViewModel::class.java)) {
            val database =
                    com.example.usbcam.data.db.AppDatabase.getDatabase(
                            application.applicationContext
                    )
            val apiService = com.example.usbcam.api.PoApiService.create()

            val shoeboxRepository =
                    com.example.usbcam.repository.ShoeboxRepository(
                            database.shoeboxDao(),
                            apiService
                    )
            val rfidRepository = com.example.usbcam.repository.RfidRepository(apiService)
            val validateWithRfidUseCase =
                    com.example.usbcam.domain.usecase.ValidateWithRfidUseCase(
                            rfidRepository,
                            shoeboxRepository
                    )

            @Suppress("UNCHECKED_CAST")
            return RfidViewModel(application, rfidRepository, validateWithRfidUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
