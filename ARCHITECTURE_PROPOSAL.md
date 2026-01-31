# 🏗️ KIẾN TRÚC HỆ THỐNG - USB CAMERA + RFID + OFFLINE-FIRST

## 📌 Tổng quan

Giải pháp này áp dụng **Clean Architecture** + **MVVM** + **Offline-First** cho ứng dụng Android quét Shoebox với USB Camera và RFID validation.

---

## 🎯 Các Bước Xử lý Chính

### FLOW TỔNG QUAN

```
┌─────────────────────────────────────────────────────────────────┐
│  1. USB CAMERA SCAN                                             │
│     • Đọc Barcode (UPC)                                         │
│     • Trích xuất PO từ hình ảnh (OCR)                          │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. GỌI API BACKEND                                             │
│     Endpoint: GET /api/po-details/{po}/{barcode}               │
│     Response: PoResponse (RY, Size, Article, Qty, etc.)        │
└─────────────────────────────────────────────────────────────────┘
                            ↓
              ┌─────────────┴─────────────┐
              │                           │
         ✅ SUCCESS                   ❌ FAILURE
              │                           │
              ↓                           ↓
┌──────────────────────────┐   ┌──────────────────────────┐
│  3a. KIỂM TRA RFID       │   │  3b. FALLBACK TO LOCAL   │
│                          │   │  • Đọc cache từ Room DB  │
│  Có RFID trong data?     │   │  • Lưu vào Pending Queue │
│  ├─ CÓ: Gọi RFID API    │   │  • Đánh dấu Synced = 0   │
│  └─ KHÔNG: Lưu bình thường│  └──────────────────────────┘
└──────────────────────────┘                │
              │                              │
              ↓                              ↓
┌──────────────────────────┐   ┌──────────────────────────┐
│  4a. SO SÁNH DỮ LIỆU     │   │  5. BACKGROUND SYNC      │
│                          │   │  • WorkManager           │
│  Camera Data vs RFID Data│   │  • Retry với backoff     │
│  ├─ MATCH:               │   │  • Network aware         │
│  │   → ShoeboxDetail    │   └──────────────────────────┘
│  └─ MISMATCH:            │
│      → ShoeboxDetailRfid │
└──────────────────────────┘
```

---

## 🏛️ KIẾN TRÚC CHI TIẾT

### Layer 1: PRESENTATION (UI + ViewModel)

```kotlin
// ViewModel xử lý logic nghiệp vụ
class MainViewModel {
    fun processScannedData(po: String, barcode: String, rfidCode: String?)
}

class RfidViewModel {
    fun validateRfidData(rfidCode: String)
}
```

### Layer 2: DOMAIN (Use Cases)

```kotlin
// Use case: Xử lý dữ liệu từ camera
class ProcessCameraDataUseCase(
    private val repository: ShoeboxRepository,
    private val rfidRepository: RfidRepository
) {
    suspend operator fun invoke(
        po: String, 
        barcode: String,
        cameraData: CameraData
    ): Result<ProcessResult>
}

// Use case: Validate với RFID
class ValidateWithRfidUseCase(
    private val rfidRepository: RfidRepository,
    private val shoeboxRepository: ShoeboxRepository
) {
    suspend operator fun invoke(
        rfidCode: String,
        cameraData: CameraData
    ): ValidationResult
}

// Use case: Đồng bộ dữ liệu
class SyncDataUseCase(
    private val repository: ShoeboxRepository
) {
    suspend operator fun invoke(): SyncResult
}
```

### Layer 3: DATA (Repository + Data Sources)

```kotlin
// Repository tổng hợp
class ShoeboxRepository(
    private val localDataSource: ShoeboxLocalDataSource,
    private val remoteDataSource: ShoeboxRemoteDataSource,
    private val networkMonitor: NetworkConnectionMonitor
) {
    suspend fun processScan(
        po: String, 
        barcode: String
    ): Result<PoResponse>
    
    suspend fun saveWithRfidValidation(
        cameraData: CameraData,
        rfidData: RfidData?
    ): Result<Unit>
    
    suspend fun syncPendingData(): Result<SyncStats>
}
```

---

## 📋 FLOW CHI TIẾT - STEP BY STEP

### **Step 1: Camera Scan (USB Camera → BoxProcessor)**

```kotlin
// DemoFragment.kt - processFrame()
fun processFrame(data: ByteArray) {
    // 1. Decode MJPEG/YUV → Bitmap
    val bitmap = createScanBitmap()
    
    // 2. Xử lý với BoxProcessor
    boxProcessor.updateLogic(mGray, bitmap)
    
    // 3. Khi state = VERIFYING → Gọi API
    if (state == AppState.VERIFYING && !isApiCalling) {
        callApi()
    }
}
```

### **Step 2: API Call + RFID Check**

```kotlin
// MainViewModel.kt
fun processScanWithRfid(
    po: String, 
    barcode: String,
    rfidCode: String? // Từ RfidViewModel
) {
    viewModelScope.launch {
        // 2.1. Gọi API Backend
        val apiResult = repository.getPoDetails(po, barcode)
        
        when (apiResult) {
            is Success -> {
                val poData = apiResult.data
                
                // 2.2. Kiểm tra RFID
                if (rfidCode != null) {
                    // CÓ RFID → Validate
                    handleWithRfidValidation(poData, rfidCode)
                } else {
                    // KHÔNG RFID → Lưu bình thường
                    repository.saveNormal(poData)
                }
            }
            is Error -> {
                // 2.3. Fallback to Local
                handleApiFallback(po, barcode)
            }
        }
    }
}
```

### **Step 3: RFID Validation Logic**

```kotlin
// UseCase: ValidateWithRfidUseCase.kt
suspend fun invoke(
    cameraData: CameraData,
    rfidCode: String
): ValidationResult {
    
    // 3.1. Gọi API lấy dữ liệu RFID
    val rfidData = rfidRepository.getRfidInfo(rfidCode)
    
    // 3.2. So sánh
    val comparison = compareData(cameraData, rfidData)
    
    // 3.3. Lưu theo kết quả
    return when (comparison) {
        is Match -> {
            // KHỚP → Lưu vào bảng chính
            shoeboxRepository.saveToMainTable(
                cameraData = cameraData,
                rfidData = rfidData
            )
            ValidationResult.Success(isMatch = true)
        }
        is Mismatch -> {
            // KHÔNG KHỚP → Lưu vào bảng riêng
            shoeboxRepository.saveToMismatchTable(
                cameraData = cameraData,
                rfidData = rfidData,
                mismatchFields = comparison.fields
            )
            ValidationResult.Success(isMatch = false)
        }
    }
}

// Logic so sánh
private fun compareData(
    camera: CameraData, 
    rfid: RfidData
): ComparisonResult {
    val mismatchFields = mutableListOf<String>()
    
    // So sánh từng field
    if (camera.po != rfid.po) mismatchFields.add("PO")
    if (camera.size != rfid.size) mismatchFields.add("Size")
    if (camera.article != rfid.article) mismatchFields.add("Article")
    
    return if (mismatchFields.isEmpty()) {
        ComparisonResult.Match
    } else {
        ComparisonResult.Mismatch(mismatchFields)
    }
}
```

### **Step 4: Offline Handling + Local Cache**

```kotlin
// Repository Implementation
class ShoeboxRepositoryImpl : ShoeboxRepository {
    
    override suspend fun processScan(
        po: String, 
        barcode: String
    ): Result<PoResponse> {
        // 4.1. Check network
        if (!networkMonitor.isConnected()) {
            // Offline → Đọc local
            return getFromLocalCache(po, barcode)
        }
        
        // 4.2. Try API call
        return try {
            val response = remoteDataSource.getPoDetails(po, barcode)
            
            // 4.3. Cache vào local
            localDataSource.cachePoResponse(response)
            
            Result.Success(response)
        } catch (e: Exception) {
            // 4.4. Fallback to cache
            Log.e(TAG, "API failed, using cache", e)
            getFromLocalCache(po, barcode)
        }
    }
    
    override suspend fun saveWithRfidValidation(
        cameraData: CameraData,
        rfidData: RfidData?
    ): Result<Unit> {
        return try {
            if (rfidData == null) {
                // Không RFID → Bảng chính
                localDataSource.insertShoeboxDetail(
                    cameraData.toShoeboxDetail(synced = 0)
                )
            } else {
                // So sánh và lưu
                val isMatch = compareDataFields(cameraData, rfidData)
                
                if (isMatch) {
                    localDataSource.insertShoeboxDetail(
                        cameraData.toShoeboxDetail(synced = 0)
                    )
                } else {
                    localDataSource.insertShoeboxRfidDetail(
                        cameraData.toShoeboxRfidDetail(
                            rfidData = rfidData,
                            synced = 0
                        )
                    )
                }
            }
            
            // Trigger sync worker
            enqueueSyncWorker()
            
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

### **Step 5: Background Sync với WorkManager**

```kotlin
// SyncWorker.kt
class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        
        // 5.1. Lấy dữ liệu chưa sync
        val pendingDetails = dao.getPendingSync() // Synced = 0
        val pendingRfidDetails = dao.getPendingRfidSync()
        
        var successCount = 0
        var failCount = 0
        
        // 5.2. Sync từng item
        pendingDetails.forEach { detail ->
            try {
                // Gọi API upload
                apiService.uploadShoeboxDetail(detail)
                
                // Đánh dấu đã sync
                dao.updateSyncStatus(detail.id, synced = 1)
                successCount++
                
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for ${detail.id}", e)
                failCount++
            }
        }
        
        // 5.3. Sync RFID mismatch data
        pendingRfidDetails.forEach { rfidDetail ->
            try {
                apiService.uploadRfidMismatch(rfidDetail)
                dao.deleteRfidDetail(rfidDetail.id) // Xóa sau khi sync
                successCount++
            } catch (e: Exception) {
                failCount++
            }
        }
        
        // 5.4. Return result
        return if (failCount == 0) {
            Result.success()
        } else if (successCount > 0) {
            Result.retry() // Một số thành công → Retry phần còn lại
        } else {
            Result.failure() // Tất cả fail
        }
    }
}

// Enqueue worker với constraints
fun enqueueSyncWorker() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
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
            "DataSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
}
```

---

## 🗄️ DATABASE SCHEMA

### Room Database Structure

```kotlin
@Database(
    entities = [
        ShoeboxDetail::class,
        ShoeboxDetailRfid::class,
        ShoeboxTotal::class
    ],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shoeboxDao(): ShoeboxDao
}

@Dao
interface ShoeboxDao {
    // Chèn dữ liệu bình thường
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetail(detail: ShoeboxDetail): Long
    
    // Chèn dữ liệu RFID mismatch
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRfidDetail(detail: ShoeboxDetailRfid): Long
    
    // Lấy dữ liệu chưa sync
    @Query("SELECT * FROM Data_Shoebox_Detail WHERE Synced = 0")
    suspend fun getPendingSync(): List<ShoeboxDetail>
    
    @Query("SELECT * FROM Data_Shoebox_RFID_Detail WHERE Synced = 0")
    suspend fun getPendingRfidSync(): List<ShoeboxDetailRfid>
    
    // Cập nhật trạng thái sync
    @Query("UPDATE Data_Shoebox_Detail SET Synced = :synced WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, synced: Int)
    
    // Cache đọc local
    @Query("SELECT * FROM Data_Shoebox_Detail WHERE PO = :po AND UPC = :upc LIMIT 1")
    suspend fun getCachedData(po: String, upc: String): ShoeboxDetail?
}
```

### Entity với Synced Flag

```kotlin
@Entity(tableName = "Data_Shoebox_Detail")
data class ShoeboxDetail(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Qty: Int,
    val DateScan: String,
    val Article: String?,
    val RFID: String? = null, // RFID code nếu có
    val Synced: Int = 0 // 0: Chưa sync, 1: Đã sync
)

@Entity(tableName = "Data_Shoebox_RFID_Detail")
data class ShoeboxDetailRfid(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Dữ liệu từ Camera
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Article: String?,
    // Dữ liệu từ RFID
    val RFID: String?,
    val Size_RFID: String?,
    val PO_RFID: String?,
    val Article_RFID: String?,
    // Metadata
    val MismatchFields: String?, // JSON: ["PO", "Size"]
    val DateScan: String,
    val Synced: Int = 0
)
```

---

## 🔄 RETRY MECHANISM

### Exponential Backoff Strategy

```kotlin
class RetryPolicy {
    companion object {
        const val MAX_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 10000L
    }
    
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = MAX_RETRIES,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = INITIAL_BACKOFF_MS
        
        repeat(maxRetries) { attempt ->
            try {
                return Result.Success(block())
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed", e)
                
                if (attempt == maxRetries - 1) {
                    return Result.Error(e)
                }
                
                delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        
        return Result.Error(Exception("Max retries exceeded"))
    }
}
```

---

## 📱 INTEGRATION EXAMPLE

### ViewModel Integration

```kotlin
class MainViewModel(
    private val processCameraDataUseCase: ProcessCameraDataUseCase,
    private val validateWithRfidUseCase: ValidateWithRfidUseCase,
    private val repository: ShoeboxRepository
) : ViewModel() {
    
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState
    
    fun processScanWithRfid(
        po: String,
        barcode: String,
        cameraData: CameraData,
        rfidCode: String? = null
    ) {
        viewModelScope.launch {
            _processingState.emit(ProcessingState.Processing)
            
            try {
                // Step 1: Gọi API backend
                val result = processCameraDataUseCase(po, barcode, cameraData)
                
                when (result) {
                    is Result.Success -> {
                        val apiData = result.data
                        
                        // Step 2: Kiểm tra RFID
                        if (rfidCode != null) {
                            // Có RFID → Validate
                            val validationResult = validateWithRfidUseCase(
                                rfidCode = rfidCode,
                                cameraData = cameraData
                            )
                            
                            when (validationResult) {
                                is ValidationResult.Match -> {
                                    _processingState.emit(
                                        ProcessingState.Success(
                                            message = "✅ RFID Match - Đã lưu",
                                            isRfidMatch = true
                                        )
                                    )
                                }
                                is ValidationResult.Mismatch -> {
                                    _processingState.emit(
                                        ProcessingState.Warning(
                                            message = "⚠️ RFID Mismatch - Lưu vào bảng riêng",
                                            mismatchFields = validationResult.fields
                                        )
                                    )
                                }
                            }
                        } else {
                            // Không RFID → Lưu bình thường
                            repository.saveNormal(apiData)
                            _processingState.emit(
                                ProcessingState.Success(
                                    message = "✅ Đã lưu (No RFID)",
                                    isRfidMatch = null
                                )
                            )
                        }
                    }
                    is Result.Error -> {
                        // Step 3: Fallback
                        _processingState.emit(
                            ProcessingState.OfflineMode("📴 Offline - Đã lưu local")
                        )
                    }
                }
            } catch (e: Exception) {
                _processingState.emit(
                    ProcessingState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    object Processing : ProcessingState()
    data class Success(
        val message: String,
        val isRfidMatch: Boolean?
    ) : ProcessingState()
    data class Warning(
        val message: String,
        val mismatchFields: List<String>
    ) : ProcessingState()
    data class OfflineMode(val message: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
```

---

## 🎨 UI/UX Considerations

### Hiển thị Status cho User

```kotlin
// DemoFragment.kt
lifecycleScope.launch {
    viewModel.processingState.collect { state ->
        when (state) {
            is ProcessingState.Processing -> {
                binding.tvStatus.text = "⏳ Đang xử lý..."
                binding.pbProgress.visibility = View.VISIBLE
            }
            is ProcessingState.Success -> {
                binding.tvStatus.text = state.message
                binding.tvStatus.setTextColor(Color.GREEN)
                
                // Nếu có RFID match
                state.isRfidMatch?.let { isMatch ->
                    if (isMatch) {
                        playSuccessSound()
                        showMatchIndicator()
                    }
                }
            }
            is ProcessingState.Warning -> {
                binding.tvStatus.text = state.message
                binding.tvStatus.setTextColor(Color.YELLOW)
                
                // Hiển thị các field không khớp
                binding.tvMismatch.text = 
                    "Fields: ${state.mismatchFields.joinToString()}"
                playWarningSound()
            }
            is ProcessingState.OfflineMode -> {
                binding.tvStatus.text = state.message
                binding.ivOfflineIndicator.visibility = View.VISIBLE
            }
            is ProcessingState.Error -> {
                binding.tvStatus.text = "❌ ${state.message}"
                binding.tvStatus.setTextColor(Color.RED)
                playErrorSound()
            }
        }
    }
}
```

---

## 📊 TESTING STRATEGY

### Unit Tests

```kotlin
class ValidateWithRfidUseCaseTest {
    
    @Test
    fun `when RFID matches camera data, should save to main table`() = runTest {
        // Arrange
        val cameraData = CameraData(po = "PO123", size = "42")
        val rfidData = RfidData(po = "PO123", size = "42")
        
        // Act
        val result = validateWithRfidUseCase(rfidCode, cameraData)
        
        // Assert
        assertTrue(result is ValidationResult.Match)
        verify(shoeboxRepository).saveToMainTable(any(), any())
    }
    
    @Test
    fun `when RFID mismatches, should save to mismatch table`() = runTest {
        // Arrange
        val cameraData = CameraData(po = "PO123", size = "42")
        val rfidData = RfidData(po = "PO456", size = "42")
        
        // Act
        val result = validateWithRfidUseCase(rfidCode, cameraData)
        
        // Assert
        assertTrue(result is ValidationResult.Mismatch)
        verify(shoeboxRepository).saveToMismatchTable(any(), any(), any())
    }
}
```

---

## 🚀 DEPLOYMENT CHECKLIST

### Migration Steps

1. **Database Migration**
   ```kotlin
   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(database: SupportSQLiteDatabase) {
           // Thêm cột RFID vào bảng chính
           database.execSQL(
               "ALTER TABLE Data_Shoebox_Detail ADD COLUMN RFID TEXT"
           )
           
           // Tạo bảng mismatch
           database.execSQL("""
               CREATE TABLE IF NOT EXISTS Data_Shoebox_RFID_Detail (
                   id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                   PO TEXT,
                   Size TEXT,
                   RFID TEXT,
                   PO_RFID TEXT,
                   Size_RFID TEXT,
                   MismatchFields TEXT,
                   DateScan TEXT,
                   Synced INTEGER NOT NULL DEFAULT 0
               )
           """)
       }
   }
   ```

2. **WorkManager Setup**
   - Enqueue sync worker trong Application onCreate
   - Set appropriate constraints

3. **API Endpoints**
   - `POST /api/shoebox/upload` - Upload normal data
   - `POST /api/shoebox/rfid-mismatch` - Upload mismatch data
   - `GET /api/rfid/{code}` - Get RFID details

4. **Performance**
   - Batch insert cho sync
   - Pagination cho large datasets
   - Image compression trước khi upload

---

## 📈 MONITORING & ANALYTICS

```kotlin
// Track sync performance
class SyncAnalytics {
    fun logSyncSuccess(count: Int, duration: Long) {
        Firebase.analytics.logEvent("sync_success") {
            param("items_count", count.toLong())
            param("duration_ms", duration)
        }
    }
    
    fun logRfidMismatch(fields: List<String>) {
        Firebase.analytics.logEvent("rfid_mismatch") {
            param("fields", fields.joinToString())
        }
    }
}
```

---

## ✅ SUMMARY

### Ưu điểm của giải pháp này:

1. **Offline-First**: Dữ liệu luôn được lưu local trước
2. **Reliable**: WorkManager đảm bảo sync ngay cả khi app đóng
3. **Flexible**: Dễ dàng thêm logic validation mới
4. **Testable**: Clean Architecture cho phép test từng layer
5. **Scalable**: Có thể mở rộng thêm nhiều loại validation

### Các điểm cần lưu ý:

- ⚠️ Xử lý conflict khi sync (server có data mới hơn local)
- ⚠️ Giới hạn kích thước database local
- ⚠️ Background sync battery optimization
- ⚠️ Image caching strategy
- ⚠️ Error handling cho edge cases

---

**Tài liệu này cung cấp một roadmap hoàn chỉnh để triển khai hệ thống. Bạn có thể bắt đầu implement từng layer một cách độc lập và test riêng biệt.**
