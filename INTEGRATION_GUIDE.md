# 🚀 INTEGRATION GUIDE - How to Use the New RFID Validation System

## Quick Start

### 1. Initialize Use Cases (in ViewModel or DI Module)

```kotlin
// Create repositories (if not using DI)
val rfidRepository = RfidRepository(apiService)
val shoeboxRepository = ShoeboxRepository(dao, apiService)

// Create use cases
val validateWithRfidUseCase = ValidateWithRfidUseCase(
    rfidRepository = rfidRepository,
    shoeboxRepository = shoeboxRepository
)

val processCameraDataUseCase = ProcessCameraDataUseCase(
    repository = shoeboxRepository
)

val syncDataUseCase = SyncDataUseCase(
    repository = shoeboxRepository
)
```

---

## 2. Usage in DemoFragment

### Scenario A: Camera Scan WITHOUT RFID

```kotlin
// In DemoFragment.kt - when BoxProcessor is in VERIFYING state
private fun processWithoutRfid(po: String, barcode: String) {
    viewModelScope.launch {
        // Create camera data
        val cameraData = CameraData(
            po = po,
            upc = barcode,
            ry = boxProcessor.ry,
            size = boxProcessor.size,
            article = boxProcessor.article,
            qty = 1,
            shoeImage = boxProcessor.shoeImage,
            dateScan = getCurrentTime(),
            userSerialKey = "DEVICE_SERIAL",
            line = "LINE_01"
        )
        
        // Process camera data (API + fallback)
        val result = processCameraDataUseCase(po, barcode, cameraData)
        
        when (result) {
            is Result.Success -> {
                // API success, save normally (no RFID)
                shoeboxRepository.saveToMainTable(cameraData, rfidData = null)
                showSuccess("✅ Saved successfully")
            }
            is Result.Error -> {
                // Both API and cache failed
                showError("❌ Failed: ${result.message}")
            }
        }
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
```

### Scenario B: Camera Scan WITH RFID Validation

```kotlin
// In DemoFragment.kt - when BoxProcessor is in VERIFYING state AND RFID is scanned
private fun processWithRfidValidation(
    po: String,
    barcode: String,
    rfidCode: String
) {
    viewModelScope.launch {
        // Create camera data
        val cameraData = CameraData(
            po = po,
            upc = barcode,
            ry = boxProcessor.ry,
            size = boxProcessor.size,
            article = boxProcessor.article,
            qty = 1,
            shoeImage = boxProcessor.shoeImage,
            dateScan = getCurrentTime(),
            userSerialKey = "DEVICE_SERIAL",
            line = "LINE_01"
        )
        
        // Step 1: Process camera data (API call)
        val apiResult = processCameraDataUseCase(po, barcode, cameraData)
        
        when (apiResult) {
            is Result.Success -> {
                // Step 2: Validate with RFID
                val validationResult = validateWithRfidUseCase(rfidCode, cameraData)
                
                when (validationResult) {
                    is ValidationResult.Success -> {
                        if (validationResult.isMatch) {
                            // ✅ RFID MATCH
                            showSuccess("✅ ${validationResult.message}")
                            playMatchSound()
                        } else {
                            // ⚠️ RFID MISMATCH
                            showWarning("⚠️ ${validationResult.message}")
                            playMismatchSound()
                        }
                    }
                    is ValidationResult.Error -> {
                        // RFID fetch failed, save without RFID
                        shoeboxRepository.saveToMainTable(cameraData)
                        showWarning("⚠️ RFID check failed, saved without validation")
                    }
                }
            }
            is Result.Error -> {
                // API failed, fallback to local cache
                showError("📴 Offline mode: ${apiResult.message}")
            }
        }
    }
}
```

---

## 3. Integration with RfidViewModel

### Update RfidViewModel to expose latest scanned RFID

```kotlin
// In RfidViewModel.kt
class RfidViewModel : ViewModel() {
    
    private val _latestRfidCode = MutableLiveData<String?>()
    val latestRfidCode: LiveData<String?> = _latestRfidCode
    
    fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
        val trimmedEpc = if (epc.length > 24) {
            epc.substring(0, 24)
        } else {
            epc
        }
        
        _latestRfidCode.value = trimmedEpc
        
        // Also fetch RFID info for display (existing logic)
        fetchRfidInfo(trimmedEpc)
    }
    
    fun clearRfidCode() {
        _latestRfidCode.value = null
    }
}
```

### Observe RFID and trigger validation

```kotlin
// In DemoFragment.kt
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    
    // Observe RFID scans
    rfidViewModel.latestRfidCode.observe(viewLifecycleOwner) { rfidCode ->
        // Store for later use
        lastScannedRfid = rfidCode
    }
    
    // Observe BoxProcessor state
    lifecycleScope.launch {
        while (isActive) {
            delay(100)
            
            val state = boxProcessor.currentState
            
            if (state == AppState.VERIFYING && !isApiCalling) {
                val po = boxProcessor.po ?: continue
                val barcode = boxProcessor.barcode ?: continue
                
                // Check if RFID was scanned
                if (lastScannedRfid != null) {
                    // Process WITH RFID validation
                    processWithRfidValidation(po, barcode, lastScannedRfid!!)
                    lastScannedRfid = null // Clear after use
                } else {
                    // Process WITHOUT RFID
                    processWithoutRfid(po, barcode)
                }
                
                isApiCalling = true
            }
        }
    }
}
```

---

## 4. UI Feedback

### Show Processing State

```kotlin
private fun showProcessingState(state: ProcessingState) {
    when (state) {
        is ProcessingState.Idle -> {
            binding.tvStatus.text = "Ready"
            binding.progressBar.visibility = View.GONE
        }
        
        is ProcessingState.Processing -> {
            binding.tvStatus.text = "⏳ Processing..."
            binding.progressBar.visibility = View.VISIBLE
        }
        
        is ProcessingState.Success -> {
            binding.tvStatus.text = state.message
            binding.tvStatus.setTextColor(Color.GREEN)
            
            state.isRfidMatch?.let { isMatch ->
                if (isMatch) {
                    // Green indicator for match
                    binding.ivMatchIndicator.setImageResource(R.drawable.ic_check_circle)
                    binding.ivMatchIndicator.setColorFilter(Color.GREEN)
                } else {
                    // Yellow indicator for mismatch
                    binding.ivMatchIndicator.setImageResource(R.drawable.ic_warning)
                    binding.ivMatchIndicator.setColorFilter(Color.YELLOW)
                }
                binding.ivMatchIndicator.visibility = View.VISIBLE
            }
        }
        
        is ProcessingState.Warning -> {
            binding.tvStatus.text = state.message
            binding.tvStatus.setTextColor(Color.YELLOW)
            binding.tvMismatchFields.text = "Mismatch: ${state.mismatchFields.joinToString()}"
            binding.tvMismatchFields.visibility = View.VISIBLE
        }
        
        is ProcessingState.OfflineMode -> {
            binding.tvStatus.text = state.message
            binding.ivOfflineIndicator.visibility = View.VISIBLE
        }
        
        is ProcessingState.Error -> {
            binding.tvStatus.text = state.message
            binding.tvStatus.setTextColor(Color.RED)
        }
    }
}
```

---

## 5. Background Sync Setup (WorkManager)

### Create Worker

```kotlin
// Create file: worker/DataSyncWorker.kt
class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val repository = ShoeboxRepository(/* get dao and apiService */)
        val syncUseCase = SyncDataUseCase(repository)
        
        val result = syncUseCase()
        
        return when (result) {
            is com.example.usbcam.data.model.Result.Success -> {
                val stats = result.data
                Log.i("DataSyncWorker", "✅ Sync success: ${stats.successCount}/${stats.totalItems}")
                
                if (stats.failureCount > 0) {
                    Result.retry() // Retry for failed items
                } else {
                    Result.success()
                }
            }
            is com.example.usbcam.data.model.Result.Error -> {
                Log.e("DataSyncWorker", "❌ Sync failed: ${result.message}")
                Result.retry()
            }
            else -> Result.failure()
        }
    }
}
```

### Enqueue Periodic Sync

```kotlin
// In Application class or MainActivity
fun setupDataSync(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
    
    val syncRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(
        repeatInterval = 15,
        repeatIntervalTimeUnit = TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )
        .build()
    
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "DataSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
}
```

### Manual Sync Button

```kotlin
// In Fragment/Activity
binding.btnSync.setOnClickListener {
    lifecycleScope.launch {
        binding.tvSyncStatus.text = "🔄 Syncing..."
        
        val result = syncDataUseCase()
        
        when (result) {
            is Result.Success -> {
                val stats = result.data
                binding.tvSyncStatus.text = """
                    ✅ Sync Complete
                    Success: ${stats.successCount}
                    Failed: ${stats.failureCount}
                    Duration: ${stats.duration}ms
                """.trimIndent()
            }
            is Result.Error -> {
                binding.tvSyncStatus.text = "❌ Sync failed: ${result.message}"
            }
        }
    }
}
```

---

## 6. Testing the Flow

### Test Case 1: RFID Match
```
1. Camera scans barcode → PO = "PO123"
2. RFID scanner reads → RFID = "RFID_ABC"
3. RFID API returns → PO = "PO123" (matches!)
4. Expected: Save to Data_Shoebox_Detail
5. UI shows: "✅ RFID Match - Data saved successfully"
```

### Test Case 2: RFID Mismatch
```
1. Camera scans barcode → PO = "PO123", Size = "42"
2. RFID scanner reads → RFID = "RFID_XYZ"
3. RFID API returns → PO = "PO456", Size = "40"
4. Expected: Save to Data_Shoebox_RFID_Detail with mismatch fields
5. UI shows: "⚠️ RFID Mismatch - Saved for review\nFields: PO, Size"
```

### Test Case 3: No RFID
```
1. Camera scans barcode → PO = "PO123"
2. No RFID scan (timeout or not present)
3. Expected: Save to Data_Shoebox_Detail normally
4. UI shows: "✅ Saved successfully"
```

### Test Case 4: Offline Mode
```
1. Network unavailable
2. Camera scans barcode
3. Expected: Use local cache, save with Synced = 0
4. UI shows: "📴 Offline mode - Data saved locally"
5. WorkManager will sync when network returns
```

---

## 7. Debugging Tips

### Enable Detailed Logging

```kotlin
// Add to your Application class
if (BuildConfig.DEBUG) {
    Log.d("RFID_VALIDATION", "Debug mode enabled")
}
```

### Check Database Content

```kotlin
// In ViewModel or Repository
suspend fun debugDatabaseContents() {
    val normalDetails = dao.getUnsyncedDetails()
    val rfidDetails = dao.getUnsyncedRfidDetails()
    
    Log.d("DEBUG_DB", """
        Unsynced Normal: ${normalDetails.size}
        Unsynced RFID: ${rfidDetails.size}
    """.trimIndent())
    
    rfidDetails.forEach { detail ->
        Log.d("DEBUG_RFID", """
            ID: ${detail.id}
            Camera PO: ${detail.PO}
            RFID PO: ${detail.PO_RFID}
            Mismatch: ${detail.MismatchFields}
        """.trimIndent())
    }
}
```

### Monitor Sync Status

```kotlin
// Observe WorkManager status
WorkManager.getInstance(context)
    .getWorkInfosForUniqueWorkLiveData("DataSyncWork")
    .observe(this) { workInfos ->
        workInfos.forEach { workInfo ->
            Log.d("SYNC_STATUS", "State: ${workInfo.state}")
        }
    }
```

---

## 8. Common Issues & Solutions

### Issue 1: "lateinit property boxProcessor has not been initialized"
**Solution**: Ensure `boxProcessor` is initialized before calling validation
```kotlin
if (!::boxProcessor.isInitialized) {
    Log.e(TAG, "BoxProcessor not ready")
    return
}
```

### Issue 2: RFID not detected
**Check**:
- RFID connection status
- RFID scanning state
- Tag read callback is triggered
```kotlin
rfidViewModel.latestRfidCode.observe(...) { code ->
    Log.d(TAG, "RFID detected: $code")
}
```

### Issue 3: Data not syncing
**Check**:
- Network connectivity
- Synced flag (should be 0)
- WorkManager constraints
```kotlin
val unsyncedCount = dao.getUnsyncedDetails().size
Log.d(TAG, "Items pending sync: $unsyncedCount")
```

---

## 9. Performance Optimization

### Batch Database Operations

```kotlin
// Instead of individual inserts
dao.insertDetails(listOfDetails) // Room supports @Insert with List<T>
```

### Use Flow for Real-time Updates

```kotlin
// In DAO
@Query("SELECT * FROM Data_Shoebox_Detail WHERE Synced = 0")
fun getPendingSyncFlow(): Flow<List<ShoeboxDetail>>

// In UI
dao.getPendingSyncFlow().collect { pendingItems ->
    binding.tvPendingCount.text = "Pending: ${pendingItems.size}"
}
```

### Limit Concurrent API Calls

```kotlin
private val semaphore = Semaphore(3) // Max 3 concurrent calls

suspend fun syncWithLimit(item: ShoeboxDetail) {
    semaphore.acquire()
    try {
        apiService.syncDetail(item)
    } finally {
        semaphore.release()
    }
}
```

---

## ✅ Integration Checklist

- [ ] Initialize Use Cases in ViewModel/DI
- [ ] Update `processScan` flow to use `validateWithRfidUseCase`
- [ ] Observe RFID ViewModel for latest scanned code
- [ ] Add UI feedback for match/mismatch states
- [ ] Create `DataSyncWorker`
- [ ] Setup periodic WorkManager sync
- [ ] Add manual sync button
- [ ] Test all 4 scenarios (match, mismatch, no RFID, offline)
- [ ] Add logging for debugging
- [ ] Monitor sync status
- [ ] Optimize database queries

---

**🎉 You're ready to integrate the RFID Validation System!**
