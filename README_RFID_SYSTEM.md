# 📱 USB Camera + RFID Validation System

> **Offline-First Android Application** với Camera Scanning, RFID Validation, và Background Sync

---

## 🎯 Overview

Hệ thống Android quét Shoebox sử dụng:
- **USB Camera** để đọc barcode và thông tin sản phẩm
- **RFID Scanner** để xác thực dữ liệu
- **Offline-First Architecture** đảm bảo không mất dữ liệu
- **Clean Architecture + MVVM** để dễ maintain và test

---

## 📚 Documentation

Toàn bộ documentation được chia thành 4 file chính:

### 1. 📋 [ARCHITECTURE_PROPOSAL.md](ARCHITECTURE_PROPOSAL.md)
**Đọc đầu tiên!** - Thiết kế kiến trúc tổng quan

- Mô tả hệ thống hiện tại
- Yêu cầu nâng cấp chi tiết
- Flow xử lý step-by-step
- Code examples cho từng layer
- Testing strategy
- Deployment checklist

### 2. ✅ [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)
**Theo dõi tiến độ** - Tổng kết những gì đã implement

- ✅ Phase 1: Data Layer (Entities, Database, DAO)
- ✅ Phase 2: Domain Layer (Use Cases)
- ✅ Phase 3: Repository Layer (RFID validation logic)
- 📋 Phase 4: ViewModel Integration (Next step)
- 📋 Phase 5: WorkManager Sync (Next step)
- File structure created
- Dependencies needed
- Testing checklist

### 3. 🚀 [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)
**Hướng dẫn thực chiến** - How to integrate vào code hiện tại

- Initialize Use Cases
- Usage examples:
  - Camera scan WITHOUT RFID
  - Camera scan WITH RFID validation
  - Offline mode handling
- RfidViewModel integration
- UI feedback setup
- WorkManager configuration
- Testing scenarios
- Debugging tips
- Performance optimization

### 4. 📊 [ARCHITECTURE_DIAGRAMS.md](ARCHITECTURE_DIAGRAMS.md)
**Visual reference** - Diagrams và flow charts

- System architecture overview
- Sequence diagrams cho tất cả flows:
  - RFID Match
  - RFID Mismatch
  - No RFID
  - Offline mode
  - Background sync
- Database schema
- State machine diagrams

---

## 🏗️ Quick Architecture Overview

```
📱 Presentation Layer
   ↓
🎯 Domain Layer (Use Cases)
   ↓
💾 Data Layer (Repositories)
   ↓
🗄️ Storage & Network
```

### Key Components Created:

**Data Models** (`data/model/`)
- `DataModels.kt` - CameraData, RfidData, ValidationResult, ProcessingState
- `ShoeboxEntities.kt` - Updated ShoeboxDetailRfid entity

**Use Cases** (`domain/usecase/`)
- `ValidateWithRfidUseCase.kt` - So sánh Camera vs RFID
- `ProcessCameraDataUseCase.kt` - Xử lý camera scan + API
- `SyncDataUseCase.kt` - Đồng bộ dữ liệu pending

**Repositories** (`repository/`)
- `RfidRepository.kt` - RFID API calls
- `ShoeboxRepository.kt` - Extended với RFID logic

**Database** (`data/db/`)
- `AppDatabase.kt` - Updated to v2 with migration
- `ShoeboxDao.kt` - Added RFID table methods

---

## 🔄 Main Flows

### Flow 1: RFID Match (Happy Path)
```
Camera scans → API call → RFID scans → Fetch RFID data → Compare
→ Match! → Save to Data_Shoebox_Detail → ✅ Success
```

### Flow 2: RFID Mismatch
```
Camera scans → API call → RFID scans → Fetch RFID data → Compare
→ Mismatch (PO different) → Save to Data_Shoebox_RFID_Detail 
→ ⚠️ Warning (saved for review)
```

### Flow 3: No RFID
```
Camera scans → API call → No RFID scan (timeout)
→ Save to Data_Shoebox_Detail → ✅ Success
```

### Flow 4: Offline Mode
```
Camera scans → API call fails → Use local cache
→ Save with Synced = 0 → 📴 Offline (will sync later)
→ WorkManager syncs when network returns
```

---

## 🚀 Getting Started

### Step 1: Review Documentation
1. Read [ARCHITECTURE_PROPOSAL.md](ARCHITECTURE_PROPOSAL.md) để hiểu tổng quan
2. Check [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) để xem đã làm được gì
3. Follow [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) để tích hợp

### Step 2: Database Migration
Database đã được update từ v1 → v2. Migration tự động chạy khi app start.

```kotlin
// Migration 1→2 tạo bảng Data_Shoebox_RFID_Detail
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Creates RFID detail table
    }
}
```

### Step 3: Initialize Use Cases

```kotlin
// In ViewModel or DI module
val validateWithRfidUseCase = ValidateWithRfidUseCase(
    rfidRepository = RfidRepository(apiService),
    shoeboxRepository = ShoeboxRepository(dao, apiService)
)
```

### Step 4: Integrate into DemoFragment

Xem chi tiết trong [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)

---

## 📊 Database Schema

### Table 1: Data_Shoebox_Detail (Main)
Lưu dữ liệu khi:
- Không có RFID scan
- RFID match với camera data

### Table 2: Data_Shoebox_RFID_Detail (Mismatch)
Lưu dữ liệu khi:
- RFID **KHÔNG** match với camera data
- Chứa cả camera data VÀ RFID data
- Có field `MismatchFields` (JSON) để track fields nào khác nhau

### Table 3: Data_Shoebox_Total (Summary)
Tổng hợp số lượng scan

---

## 🧪 Testing

### Unit Tests (Cần viết)
- [ ] ValidateWithRfidUseCase - Match case
- [ ] ValidateWithRfidUseCase - Mismatch case
- [ ] ProcessCameraDataUseCase - API success
- [ ] ProcessCameraDataUseCase - API failure + cache
- [ ] SyncDataUseCase - Full sync

### Manual Testing Scenarios

**Test 1: RFID Match**
```
1. Camera scan: PO=PO123, Size=42
2. RFID scan: RFID_ABC
3. API returns: PO=PO123, Size=42
4. Expected: ✅ "RFID Match - Data saved"
5. Verify: Row in Data_Shoebox_Detail
```

**Test 2: RFID Mismatch**
```
1. Camera scan: PO=PO123, Size=42
2. RFID scan: RFID_XYZ
3. API returns: PO=PO456, Size=40
4. Expected: ⚠️ "RFID Mismatch - Fields: PO, Size"
5. Verify: Row in Data_Shoebox_RFID_Detail
```

**Test 3: No RFID**
```
1. Camera scan: PO=PO123
2. No RFID scan (5 sec timeout)
3. Expected: ✅ "Saved successfully"
4. Verify: Row in Data_Shoebox_Detail
```

**Test 4: Offline**
```
1. Turn off WiFi
2. Camera scan
3. Expected: 📴 "Offline - Saved locally"
4. Verify: Synced = 0 in database
5. Turn on WiFi, wait 15 min
6. Verify: Synced = 1 after WorkManager sync
```

---

## ⚙️ Configuration

### Gradle Dependencies

```gradle
// Room
implementation "androidx.room:room-runtime:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"

// Coroutines
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"

// WorkManager
implementation "androidx.work:work-runtime-ktx:2.9.0"

// Gson
implementation "com.google.code.gson:gson:2.10.1"

// Retrofit (already present)
implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.retrofit2:converter-gson:2.9.0"
```

### API Endpoints Required

```
GET  /api/select-po?po={po}&barcode={barcode}
GET  /api/info-rfid?rfid={rfid}
POST /api/sync/detail          (body: ShoeboxDetail)
POST /api/sync/rfid-mismatch   (body: ShoeboxDetailRfid)
POST /api/sync/total           (body: ShoeboxTotal)
```

---

## 📈 Performance Considerations

1. **Database Indexing** (Recommended)
   ```sql
   CREATE INDEX idx_po ON Data_Shoebox_Detail(PO)
   CREATE INDEX idx_upc ON Data_Shoebox_Detail(UPC)
   CREATE INDEX idx_synced ON Data_Shoebox_Detail(Synced)
   CREATE INDEX idx_rfid_synced ON Data_Shoebox_RFID_Detail(Synced)
   ```

2. **Batch Sync**
   - Limit 50 items per batch
   - Use semaphore for concurrent API calls

3. **Memory**
   - Use Flow instead of LiveData for large datasets
   - Pagination for history queries

---

## 🔒 Error Handling

### Network Errors
- Timeout → Fallback to local cache
- No connection → Save with Synced = 0
- Server error → Retry với exponential backoff

### RFID Errors
- RFID API fail → Save without RFID validation
- Invalid RFID format → Show warning, continue
- Multiple RFID tags → Use first or strongest signal

### Database Errors
- Migration fail → fallbackToDestructiveMigration (dev only)
- Insert fail → Log error, retry once
- Constraint violation → Update instead of insert

---

## 🎯 Next Steps

### Phase 4: ViewModel Integration (Next sprint)
- [ ] Update MainViewModel với Use Cases
- [ ] Integrate RfidViewModel
- [ ] Add ProcessingState StateFlow
- [ ] Handle RFID detection logic

### Phase 5: Background Sync (Next sprint)
- [ ] Create DataSyncWorker
- [ ] Setup periodic sync (15 min)
- [ ] Add manual sync button
- [ ] Monitor sync status in UI

### Phase 6: Testing & Optimization
- [ ] Write unit tests
- [ ] Integration tests
- [ ] Performance profiling
- [ ] Memory leak detection

---

## 👥 Contributors

- **Architecture Design**: Clean Architecture + MVVM pattern
- **Domain Logic**: Use Cases for business logic
- **Data Layer**: Repository pattern with offline-first
- **Background Sync**: WorkManager with exponential backoff

---

## 📄 License

Internal project - Proprietary

---

## 🆘 Support

Nếu gặp vấn đề:

1. **Check logs**: `Logcat` filter by `RfidViewModel`, `ShoeboxRepo`, `ValidateWithRfid`
2. **Check database**: Query Synced = 0 items
3. **Check network**: Verify API endpoints reachable
4. **Review docs**: 
   - Architecture: [ARCHITECTURE_PROPOSAL.md](ARCHITECTURE_PROPOSAL.md)
   - Integration: [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)
   - Diagrams: [ARCHITECTURE_DIAGRAMS.md](ARCHITECTURE_DIAGRAMS.md)

---

## 📞 Contact

For questions or issues:
- Review documentation files
- Check implementation summary for progress
- Follow integration guide for examples

---

**🎉 Happy Coding! The system is ready for ViewModel integration and testing.**

---

## Quick Links

- 📋 [Architecture Proposal](ARCHITECTURE_PROPOSAL.md) - Design & Requirements
- ✅ [Implementation Summary](IMPLEMENTATION_SUMMARY.md) - What's Done
- 🚀 [Integration Guide](INTEGRATION_GUIDE.md) - How to Use
- 📊 [Architecture Diagrams](ARCHITECTURE_DIAGRAMS.md) - Visual Reference
