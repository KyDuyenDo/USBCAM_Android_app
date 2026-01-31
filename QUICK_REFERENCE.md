# ⚡ QUICK REFERENCE - RFID Validation System

## 🎯 1-Minute Overview

**What**: Offline-first Android app with Camera + RFID validation  
**Architecture**: Clean Architecture + MVVM  
**Status**: Phase 1-3 Complete ✅ | Phase 4-5 Pending 📋

---

## 📁 Key Files Created

```
✅ Data Layer
├── data/model/DataModels.kt              (DTOs & sealed classes)
├── data/model/ShoeboxEntities.kt         (Updated with RFID entity)
├── data/db/AppDatabase.kt                (v1→v2 migration)
└── data/db/ShoeboxDao.kt                 (RFID table methods)

✅ Domain Layer
├── domain/usecase/ValidateWithRfidUseCase.kt
├── domain/usecase/ProcessCameraDataUseCase.kt
└── domain/usecase/SyncDataUseCase.kt

✅ Repository Layer
├── repository/RfidRepository.kt          (NEW)
└── repository/ShoeboxRepository.kt       (Extended)

✅ API
└── api/PoApiService.kt                   (Added sync endpoint)

📋 Documentation
├── ARCHITECTURE_PROPOSAL.md              (Design & requirements)
├── IMPLEMENTATION_SUMMARY.md             (Progress tracker)
├── INTEGRATION_GUIDE.md                  (How to use)
├── ARCHITECTURE_DIAGRAMS.md              (Visual reference)
└── README_RFID_SYSTEM.md                 (Master doc)
```

---

## 🔄 Quick Flow Reference

### RFID Match
```kotlin
Camera → API → RFID → Compare → ✅ Match 
→ Save to Data_Shoebox_Detail
```

### RFID Mismatch
```kotlin
Camera → API → RFID → Compare → ⚠️ Mismatch 
→ Save to Data_Shoebox_RFID_Detail (with fields)
```

### No RFID
```kotlin
Camera → API → No RFID → Save to Data_Shoebox_Detail
```

### Offline
```kotlin
Camera → API fails → Use cache → Save (Synced=0)
→ WorkManager syncs later
```

---

## 💻 Code Snippets

### Initialize Use Cases
```kotlin
val rfidRepo = RfidRepository(apiService)
val shoeboxRepo = ShoeboxRepository(dao, apiService)

val validateUseCase = ValidateWithRfidUseCase(rfidRepo, shoeboxRepo)
val processUseCase = ProcessCameraDataUseCase(shoeboxRepo)
val syncUseCase = SyncDataUseCase(shoeboxRepo)
```

### Process WITH RFID
```kotlin
viewModelScope.launch {
    val cameraData = CameraData(po, upc, size, ...)
    
    // Step 1: API call
    val apiResult = processUseCase(po, barcode, cameraData)
    
    // Step 2: Validate with RFID
    if (rfidCode != null) {
        val result = validateUseCase(rfidCode, cameraData)
        when (result) {
            is ValidationResult.Success -> {
                if (result.isMatch) showSuccess()
                else showWarning(result.message)
            }
        }
    }
}
```

### Manual Sync
```kotlin
lifecycleScope.launch {
    val result = syncUseCase()
    when (result) {
        is Result.Success -> {
            val stats = result.data
            log("✅ Synced: ${stats.successCount}/${stats.totalItems}")
        }
    }
}
```

---

## 🗄️ Database Tables

### Data_Shoebox_Detail
**When**: No RFID or RFID matches  
**Key Fields**: PO, UPC, Size, Article, Synced

### Data_Shoebox_RFID_Detail
**When**: RFID mismatches  
**Key Fields**: PO, PO_RFID, Size, Size_RFID, MismatchFields (JSON)

### Data_Shoebox_Total
**When**: Summary aggregation  
**Key Fields**: Total_Qty_Scan, Total_Qty_ERP

---

## 🔍 Debug Commands

### Check Pending Sync
```kotlin
val pending = dao.getUnsyncedDetails()
log("Pending: ${pending.size}")
```

### Check RFID Mismatches
```kotlin
val mismatches = dao.getUnsyncedRfidDetails()
mismatches.forEach { 
    log("${it.PO} vs ${it.PO_RFID}: ${it.MismatchFields}")
}
```

### Force Sync
```kotlin
lifecycleScope.launch {
    val result = repository.syncPendingData()
    // Check result
}
```

---

## 🎨 UI States

```kotlin
when (processingState) {
    Idle -> "Ready"
    Processing -> "⏳ Processing..."
    is Success -> "✅ ${state.message}"
    is Warning -> "⚠️ ${state.message}"
    is OfflineMode -> "📴 ${state.message}"
    is Error -> "❌ ${state.message}"
}
```

---

## 🧪 Test Scenarios

| Scenario | Camera | RFID | Expected | Table |
|----------|--------|------|----------|-------|
| Match | PO123 | RFID_ABC (PO123) | ✅ Success | Detail |
| Mismatch | PO123 | RFID_XYZ (PO456) | ⚠️ Warning | RFID_Detail |
| No RFID | PO123 | - | ✅ Success | Detail |
| Offline | PO123 | - | 📴 Offline | Detail (Synced=0) |

---

## 🚀 Next Steps

### Phase 4: ViewModel Integration
```kotlin
// In MainViewModel
private val _processingState = MutableStateFlow<ProcessingState>(Idle)
fun processScanWithRfid(po, barcode, rfidCode?)
```

### Phase 5: WorkManager
```kotlin
// Create DataSyncWorker
class DataSyncWorker : CoroutineWorker() {
    override suspend fun doWork() = syncUseCase()
}
```

---

## 📚 Documentation Map

```
START HERE
    ↓
README_RFID_SYSTEM.md ──→ Overview & Links
    ↓
ARCHITECTURE_PROPOSAL.md ──→ Design & Requirements
    ↓
IMPLEMENTATION_SUMMARY.md ──→ What's Done
    ↓
INTEGRATION_GUIDE.md ──→ Code Examples
    ↓
ARCHITECTURE_DIAGRAMS.md ──→ Visual Reference
```

---

## ⚡ Common Tasks

### Add New Use Case
1. Create `domain/usecase/MyUseCase.kt`
2. Inject repositories
3. Implement `suspend operator fun invoke()`

### Add New Repository Method
1. Add method to `ShoeboxRepository.kt`
2. Use DAO for database
3. Use apiService for network
4. Return `Result<T>`

### Add New API Endpoint
1. Add method to `PoApiService.kt`
2. Use `@GET` or `@POST`
3. Return `Response<T>`

---

## 🔒 Error Handling Pattern

```kotlin
try {
    val result = repository.operation()
    when (result) {
        is Success -> handleSuccess(result.data)
        is Error -> handleError(result.message)
    }
} catch (e: Exception) {
    Log.e(TAG, "Error", e)
}
```

---

## 📊 Key Metrics

- **Layers**: 3 (Presentation, Domain, Data)
- **Use Cases**: 3 (Process, Validate, Sync)
- **Repositories**: 2 (Shoebox, RFID)
- **Entities**: 3 (Detail, RFID Detail, Total)
- **Database Version**: 2 (migrated from 1)
- **API Endpoints**: 5 (select-po, info-rfid, 3x sync)

---

## ⚙️ Dependencies Checklist

```gradle
✅ Room 2.6.1
✅ Coroutines 1.7.3
✅ Retrofit 2.9.0
✅ Gson 2.10.1
📋 WorkManager 2.9.0 (for Phase 5)
```

---

## 🆘 Troubleshooting

| Issue | Check | Solution |
|-------|-------|----------|
| Uninitialized property | boxProcessor init | Call after onCreate |
| API failure | Network log | Check connectivity |
| No RFID data | RFID scan callback | Verify listener |
| Not syncing | Synced flag | Check Synced = 0 |
| Migration crash | Database version | Clear app data (dev) |

---

## 📞 Quick Help

- **Architecture Questions** → ARCHITECTURE_PROPOSAL.md
- **Integration Help** → INTEGRATION_GUIDE.md
- **Visual Understanding** → ARCHITECTURE_DIAGRAMS.md
- **Progress Tracking** → IMPLEMENTATION_SUMMARY.md

---

**Print this page for quick reference while coding! 🖨️**

---

## Last Updated
2026-01-30 - Phase 1-3 Complete, Ready for Phase 4
