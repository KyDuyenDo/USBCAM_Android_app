package com.example.usbcam.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.usbcam.api.DataRfid
import com.example.usbcam.api.PoApiService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * RfidViewModel - MVVM Pattern for RFID functionality
 * 
 * Manages:
 * - RFID connection state
 * - Tag scanning data
 * - API calls to fetch RFID information
 * - UI state for RFID scanner
 */
class RfidViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RfidViewModel"
    }

    private val apiService = PoApiService.create()

    // Connection state
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    // Last scanned RFID tag
    private val _lastEpc = MutableLiveData<String>("")
    val lastEpc: LiveData<String> = _lastEpc

    private val _lastRssi = MutableLiveData<Int>(0)
    val lastRssi: LiveData<Int> = _lastRssi

    // RFID product information from API
    private val _rfidData = MutableLiveData<DataRfid?>()
    val rfidData: LiveData<DataRfid?> = _rfidData

    // Loading state
    private val _isLoadingRfidInfo = MutableLiveData<Boolean>(false)
    val isLoadingRfidInfo: LiveData<Boolean> = _isLoadingRfidInfo

    // Error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Success/Info messages
    private val _infoMessage = MutableLiveData<String?>()
    val infoMessage: LiveData<String?> = _infoMessage

    // Connection status message
    private val _connectionStatus = MutableLiveData<String>("Not Connected")
    val connectionStatus: LiveData<String> = _connectionStatus

    /**
     * Update connection state
     */
    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        _connectionStatus.value = if (connected) "Connected" else "Disconnected"
        
        if (!connected) {
            // Reset scanning state when disconnected
            _isScanning.value = false
        }
    }

    /**
     * Update scanning state
     */
    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        _connectionStatus.value = when {
            !_isConnected.value!! -> "Disconnected"
            scanning -> "Connected - Scanning..."
            else -> "Connected"
        }
    }

    /**
     * Handle new RFID tag read
     * Only keeps first 24 characters of EPC
     */
    fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
        Log.d(TAG, "Tag Read (Full): EPC=$epc, RSSI=$rssi, Ant=$antenna, Ch=$channel")
        
        // Only take first 24 characters
        val trimmedEpc = if (epc.length > 24) {
            epc.substring(0, 24)
        } else {
            epc
        }
        
        Log.d(TAG, "Tag Read (Trimmed): EPC=$trimmedEpc (${trimmedEpc.length} chars)")
        
        _lastEpc.value = trimmedEpc
        _lastRssi.value = rssi
        
        // Automatically fetch RFID information from API with trimmed EPC
        fetchRfidInfo(trimmedEpc)
    }

    /**
     * Fetch RFID information from API
     */
    fun fetchRfidInfo(epc: String) {
        if (epc.isEmpty()) {
            _errorMessage.value = "EPC code is empty"
            return
        }

        _isLoadingRfidInfo.value = true
        _errorMessage.value = null
        
        Log.d(TAG, "Fetching RFID info for EPC: $epc")

        apiService.getRfidInfo(epc).enqueue(object : Callback<DataRfid> {
            override fun onResponse(call: Call<DataRfid>, response: Response<DataRfid>) {
                _isLoadingRfidInfo.value = false

                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    _rfidData.value = data
                    
                    Log.d(TAG, "RFID Info Success: PO=${data.po}, Article=${data.article}, Size=${data.size}")
                    // _infoMessage.value = "Found: ${data.article} - Size ${data.size}"
                } else {
                    _rfidData.value = null
                    _errorMessage.value = "RFID not found in database (${response.code()})"
                    Log.w(TAG, "RFID not found: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<DataRfid>, t: Throwable) {
                _isLoadingRfidInfo.value = false
                _rfidData.value = null
                _errorMessage.value = "Network error: ${t.message}"
                Log.e(TAG, "API call failed", t)
            }
        })
    }

    /**
     * Clear error message after it's been shown
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Clear info message after it's been shown
     */
    fun clearInfo() {
        _infoMessage.value = null
    }

    /**
     * Clear all RFID data
     */
    fun clearRfidData() {
        _lastEpc.value = ""
        _lastRssi.value = 0
        _rfidData.value = null
        _errorMessage.value = null
        _infoMessage.value = null
    }

    /**
     * Manual refresh - refetch current EPC data
     */
    fun refresh() {
        val currentEpc = _lastEpc.value
        if (!currentEpc.isNullOrEmpty()) {
            fetchRfidInfo(currentEpc)
        } else {
            _errorMessage.value = "No RFID tag scanned yet"
        }
    }
}
