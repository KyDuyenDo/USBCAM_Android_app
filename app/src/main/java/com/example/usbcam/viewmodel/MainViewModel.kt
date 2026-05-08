package com.example.usbcam.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.usbcam.api.AppVersionResponse
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import com.example.usbcam.data.db.AppDatabase
import com.example.usbcam.data.model.ValidationResult
import com.example.usbcam.domain.usecase.ProcessCameraWithRfidUseCase
import com.example.usbcam.domain.usecase.ValidateWithRfidUseCase
import com.example.usbcam.repository.RfidRepository
import com.example.usbcam.repository.ShoeboxRepository
import com.example.usbcam.worker.BoxInfoCacheWorker
import com.example.usbcam.worker.SyncWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainViewModel(
        private val repository: ShoeboxRepository,
        private val processCameraWithRfidUseCase: ProcessCameraWithRfidUseCase
) : ViewModel() {

    private val _totalScan = MutableLiveData<Int>()
    val totalScan: LiveData<Int> = _totalScan

    private val _timeSlotData = MutableLiveData<MainViewData>()
    val timeSlotData: LiveData<MainViewData> = _timeSlotData

    private val _totalTarget = MutableLiveData<Int>(0)
    val totalTarget: LiveData<Int> = _totalTarget

    private val _targetData = MutableLiveData<TargetData>()
    val targetData: LiveData<TargetData> = _targetData

    // Line dây chuyền được user chọn
    private val _selectedLine = MutableLiveData<String?>()
    val selectedLine: LiveData<String?> = _selectedLine

    // UI State for scan result
    private val _scanResult = MutableLiveData<PoResponse?>()
    val scanResult: LiveData<PoResponse?> = _scanResult

    private val _isCameraEnabled = MutableLiveData<Boolean>(true)
    val isCameraEnabled: LiveData<Boolean> = _isCameraEnabled

    private val _cameraSignalError = MutableLiveData<String?>(null)
    val cameraSignalError: LiveData<String?> = _cameraSignalError

    private val _cameraCountdown = MutableLiveData<Int?>(null)
    val cameraCountdown: LiveData<Int?> = _cameraCountdown

    private val _usbNotification = MutableLiveData<String?>()
    val usbNotification: LiveData<String?> = _usbNotification

    // RFID Validation result
    private val _validationResult = MutableLiveData<ValidationResult?>()
    val validationResult: LiveData<ValidationResult?> = _validationResult

    // Thông báo cập nhật phiên bản mới
    private val _updateAvailable = MutableLiveData<AppVersionResponse?>()
    val updateAvailable: LiveData<AppVersionResponse?> = _updateAvailable

    /**
     * Kiểm tra phiên bản mới từ server.
     * Nếu versionCode trên server > versionCode hiện tại, sẽ emit dữ liệu phiên bản mới qua LiveData.
     * @param currentVersionCode versionCode của APK đang chạy (lấy từ BuildConfig).
     */
    fun checkAppVersion(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val currentVersionCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionCode
                val response = PoApiService.create().getAppVersion()
                if (response.isSuccessful) {
                    val serverVersion = response.body()
                    if (serverVersion != null && serverVersion.versionCode > currentVersionCode) {
                        Log.i("MainViewModel", "Có phiên bản mới: ${serverVersion.versionName} (code ${serverVersion.versionCode})")
                        _updateAvailable.postValue(serverVersion)
                    } else {
                        Log.d("MainViewModel", "Ứng dụng đang ở phiên bản mới nhất.")
                    }
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Không thể kiểm tra phiên bản: ${e.message}")
            }
        }
    }

    fun clearUpdateAvailable() {
        _updateAvailable.postValue(null)
    }

    fun setCameraEnabled(enabled: Boolean) {
        _isCameraEnabled.postValue(enabled)
    }

    /** Cập nhật line được chọn và tự động reload target + time slots */
    fun setSelectedLine(line: String) {
        _selectedLine.value = line
        loadAllTimeSlots()
    }

    /** Khởi tạo line từ SharedPreferences khi mở app */
    fun initSelectedLine(context: android.content.Context) {
        val saved = com.example.usbcam.utils.LinePreferences.getSelectedLine(context)
        _selectedLine.value = saved ?: com.example.usbcam.utils.LinePreferences.DEFAULT_LINE_LABEL
    }

    fun setCameraSignalError(error: String?) {
        _cameraSignalError.postValue(error)
    }

    fun setCameraCountdown(value: Int?) {
        _cameraCountdown.postValue(value)
    }

    fun onUsbDeviceDetached(device: android.hardware.usb.UsbDevice?) {
        device?.let {
            if (com.example.usbcam.utils.UsbHelper.isCameraDevice(it)) {
                _cameraSignalError.postValue("USB Camera Disconnected!")
                _usbNotification.postValue("USB Camera was unplugged!")
                _isCameraEnabled.postValue(false)
            } else {
                _usbNotification.postValue("USB Device (${it.vendorId}) Removed")
            }
        }
    }

    fun clearUsbNotification() {
        _usbNotification.postValue(null)
    }

    fun handleScan(po: String, barcode: String) {
        viewModelScope.launch {
            val result = repository.processScan(po, barcode, _selectedLine.value)
            // Unwrap Result to get PoResponse
            val poResponse =
                    when (result) {
                        is com.example.usbcam.data.model.Result.Success -> result.data
                        is com.example.usbcam.data.model.Result.Error -> {
                            Log.e(
                                    "MainViewModel",
                                    "processScan failed: ${result.message}",
                                    result.exception
                            )
                            null
                        }
                        else -> null
                    }
            _scanResult.postValue(poResponse)
            // loadDataForCurrentTimeSlot()
            loadAllTimeSlots()
        }
    }

    suspend fun getLocalData(po: String, barcode: String): PoResponse? {
        return repository.getLocalPoResponse(po, barcode)
    }

    fun saveScanData(
            po: String,
            barcode: String,
            data: PoResponse,
            scannedRfidCodes: Set<String> = emptySet()
    ) {
        Log.d("saveScanData", "API Success: $data, RFIDs: ${scannedRfidCodes.size}")
        val selectedLine = _selectedLine.value
        viewModelScope.launch {
            try {
                // 🔹 NEW LOGIC: Check for scanned RFID code (from RFID scanner)
                val result =
                        processCameraWithRfidUseCase.invoke(
                                po,
                                barcode,
                                data,
                                scannedRfidCodes,
                                selectedLine
                        )

                // Post result for UI observation
                _validationResult.postValue(result)

                when (result) {
                    is ValidationResult.Success -> {
                        Log.i("MainViewModel", "✅ Scan processed: ${result.message}")
                        // Refresh UI data
                        loadAllTimeSlots()
                        loadTotal()
                    }
                    is ValidationResult.Error -> {
                        Log.e(
                                "MainViewModel",
                                "❌ Validation error: ${result.message}",
                                result.exception
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error in saveScanData", e)
                _validationResult.postValue(
                        ValidationResult.Error(
                                message = "Failed to save: ${e.message}",
                                exception = e
                        )
                )
            }
        }
    }

    /** Clear validation result after it's been displayed */
    fun clearValidationResult() {
        _validationResult.postValue(null)
    }

    suspend fun verifyCode(po: String, barcode: String): PoResponse? {
        val result = repository.processScan(po, barcode, _selectedLine.value)
        // Unwrap Result to get PoResponse
        val poResponse =
                when (result) {
                    is com.example.usbcam.data.model.Result.Success -> result.data
                    is com.example.usbcam.data.model.Result.Error -> {
                        Log.e(
                                "MainViewModel",
                                "verifyCode failed: ${result.message}",
                                result.exception
                        )
                        null
                    }
                    else -> null
                }

        if (poResponse != null) {
            // If we found data (either from API or Local), existing Logic suggests we might want to
            // refresh UI
            // repository.saveLocal is already called inside processScan if it came from API.
            // If it came from Local, we technically don't need to re-save, just refresh UI stats.
            loadAllTimeSlots()
            loadTotal()
        }
        return poResponse
    }

    fun startSyncWorker(context: Context) {
        val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncRequest =
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("SyncWork", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    /**
     * Chạy BoxInfoCacheWorker một lần ngay khi mở app (OneTimeWorkRequest).
     * Worker tự bỏ qua nếu cache còn mới (TTL 4 tiếng).
     */
    fun startBoxInfoCacheWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = androidx.work.OneTimeWorkRequestBuilder<BoxInfoCacheWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                BoxInfoCacheWorker.WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,  // Giữ lại nếu đang chạy
                request
            )
    }

    /**
     * Chạy ConfigCacheWorker để tải danh sách Xưởng, Bộ phận, Vị trí.
     */
    fun startConfigCacheWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = androidx.work.OneTimeWorkRequestBuilder<com.example.usbcam.worker.ConfigCacheWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                com.example.usbcam.worker.ConfigCacheWorker.WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
    }

    /** Ping device immediately (to report 'online' on app open) */
    fun pingNow(context: Context) {
        viewModelScope.launch {
            val lineId = com.example.usbcam.utils.LinePreferences.getSelectedLine(context)
            Log.d("MainViewModel", "Executing initial ping for line: $lineId")
            repository.pingDevice(lineId ?: "")
        }
    }

    private val _timeSlotList = MutableLiveData<List<TimeSlotItem>>()
    val timeSlotList: LiveData<List<TimeSlotItem>> = _timeSlotList

    fun loadAllTimeSlots() {
        viewModelScope.launch {
            val currentLine = _selectedLine.value
            val targetResponse = repository.getTargetByTimeSlot(currentLine)

            val target = targetResponse?.quantityTarget ?: 140
            Log.d("loadAllTimeSlots", "line=$currentLine target=${targetResponse?.quantityTarget}")
            val slots = repository.getAllSlotsToday(target)
            _timeSlotList.postValue(slots)

            // Calculate total target for slots that have data
            val totalT = slots.sumOf { it.target }
            _totalTarget.postValue(totalT)

            targetResponse?.let {
                _targetData.postValue(
                        TargetData(
                                quantityTarget = it.quantityTarget,
                                scgs = it.scgs,
                                qtyByLean = it.qtyByLean
                        )
                )
            }
        }
    }

    fun loadDataForCurrentTimeSlot() {
        viewModelScope.launch {
            val (start, end) = calculateTimeSlot()
            val details = repository.getDetailsByTimeSlot(start, end)

            val targetResponse = repository.getTargetByTimeSlot()
            val target = targetResponse?.quantityTarget ?: 160

            val frameTime = "${start} - ${end}"

            _timeSlotData.postValue(
                    MainViewData(frameTime = frameTime, target = target, quantity = details.size)
            )
        }
    }

    fun loadTarget() {
        viewModelScope.launch {
            val currentLine = _selectedLine.value
            val target = repository.getTargetByTimeSlot(currentLine)

            target?.let {
                _targetData.postValue(
                        TargetData(
                                quantityTarget = it.quantityTarget,
                                scgs = it.scgs,
                                qtyByLean = it.qtyByLean
                        )
                )
            }
        }
    }

    fun loadTotal() {
        viewModelScope.launch {
            val total = repository.getAllToday()
            _totalScan.postValue(total)
        }
    }

    private fun formatTime(time: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(time))
    }

    private fun calculateTimeSlot(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(Calendar.MINUTE)

        // Logic: 7:30 - 8:30 etc.
        var startHour = currentHour

        // If 8:15 -> 7:30 - 8:30 (start 7)
        // If 8:45 -> 8:30 - 9:30 (start 8)
        if (currentMinute < 30) {
            startHour -= 1
        }

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val startStr = String.format("%s %02d:30:00", date, startHour)
        val endStr = String.format("%s %02d:30:00", date, startHour + 1)

        return Pair(startStr, endStr)
    }
}

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val database = AppDatabase.getDatabase(application.applicationContext)
            val apiService = PoApiService.create()

            // Setup repositories (truyền cacheDao vào ShoeboxRepository)
            val shoeboxRepository = ShoeboxRepository(
                dao      = database.shoeboxDao(),
                apiService = apiService,
                cacheDao = database.boxInfoCacheDao()
            )
            val rfidRepository = RfidRepository(apiService)

            // Setup use cases
            val validateWithRfidUseCase = ValidateWithRfidUseCase.getInstance(rfidRepository, shoeboxRepository)
            val processCameraWithRfidUseCase =
                    ProcessCameraWithRfidUseCase(shoeboxRepository, validateWithRfidUseCase)

            @Suppress("UNCHECKED_CAST")
            return MainViewModel(shoeboxRepository, processCameraWithRfidUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
