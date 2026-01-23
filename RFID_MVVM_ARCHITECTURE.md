# 🏗️ RFID MVVM Architecture Documentation

## 📋 Tổng quan

Ứng dụng RFID đã được refactor theo **MVVM Pattern** để tách biệt logic nghiệp vụ khỏi UI, dễ test và bảo trì hơn.

---

## 🎯 MVVM Architecture

### Các thành phần chính:

```
┌─────────────────────────────────────────────────┐
│                     VIEW                         │
│  (RfidScannerFragment + XML Layout)             │
│  - Hiển thị UI                                  │
│  - Xử lý user interaction                       │
│  - Observe LiveData từ ViewModel                │
└────────────────┬────────────────────────────────┘
                 │
                 ↓ Events (clicks, lifecycle)
                 ↑ LiveData updates
┌────────────────┴────────────────────────────────┐
│                  VIEW MODEL                      │
│         (RfidViewModel)                          │
│  - Quản lý UI state                             │
│  - Xử lý business logic                         │
│  - Gọi API                                      │
│  - Expose LiveData                              │
└────────────────┬────────────────────────────────┘
                 │
                 ↓ API calls
                 ↑ Response data
┌────────────────┴────────────────────────────────┐
│                    MODEL                         │
│  (DataRfid, PoApiService, RfidManager)          │
│  - Data classes                                 │
│  - API service                                  │
│  - SDK wrapper                                  │
└─────────────────────────────────────────────────┘
```

---

## 📦 Component Details

### 1. **View Layer**

#### RfidScannerFragment
**Responsibility:** UI presentation và user interaction

**File:** `rfid/RfidScannerFragment.kt`

**Features:**
- ⚙️ **Settings button trong title bar** (góc phải)
- 📊 Hiển thị connection status
- 🏷️ Hiển thị EPC code và RSSI
- 📦 Hiển thị thông tin sản phẩm từ API
- 🔄 Refresh và Clear buttons
- ❌ Close button

**Key Methods:**
```kotlin
setupConnectionManager()  // Kết nối với RfidConnectionManager
setupObservers()         // Observe LiveData từ ViewModel
setupClickListeners()    // Handle button clicks
openSettings()          // Mở RfidSettingsFragment
```

#### Layout XML
**File:** `layout/fragment_rfid_scanner.xml`

**Structure:**
```xml
<CardView>
  <LinearLayout>
    <!-- Title Bar với Settings Button -->
    <LinearLayout background="blue">
      <ImageView (RFID icon) />
      <TextView "RFID Scanner" />
      <ImageView btn_settings />  ← ⚙️ Settings
      <ImageView btn_close />
    </LinearLayout>
    
    <!-- Content Area -->
    <ScrollView>
      <!-- Connection Status -->
      <TextView tv_connection_status />
      
      <!-- Scanned Tag Info -->
      <LinearLayout background="light_blue">
        <TextView tv_epc_value />
        <TextView tv_rssi_value />
      </LinearLayout>
      
      <!-- Loading -->
      <ProgressBar progress_bar />
      
      <!-- Error Message -->
      <TextView tv_error />
      
      <!-- Product Information (from API) -->
      <LinearLayout ll_product_info background="light_green">
        <TextView tv_product_model />
        <TextView tv_product_article />
        <TextView tv_product_po />
        <TextView tv_product_color />
        <TextView tv_product_size />
      </LinearLayout>
      
      <!-- Action Buttons -->
      <Button btn_refresh />
      <Button btn_clear />
    </ScrollView>
  </LinearLayout>
</CardView>
```

---

### 2. **ViewModel Layer**

#### RfidViewModel
**Responsibility:** Business logic và state management

**File:** `viewmodel/RfidViewModel.kt`

**LiveData Properties:**
```kotlin
// Connection State
val isConnected: LiveData<Boolean>
val isScanning: LiveData<Boolean>
val connectionStatus: LiveData<String>

// Tag Data
val lastEpc: LiveData<String>
val lastRssi: LiveData<Int>

// API Data
val rfidData: LiveData<DataRfid?>
val isLoadingRfidInfo: LiveData<Boolean>

// Messages
val errorMessage: LiveData<String?>
val infoMessage: LiveData<String?>
```

**Key Methods:**
```kotlin
setConnected(Boolean)               // Cập nhật connection state
setScanning(Boolean)                // Cập nhật scanning state
onTagRead(epc, rssi, antenna, ch)  // Xử lý tag mới đọc được
fetchRfidInfo(epc)                 // Gọi API lấy thông tin
refresh()                          // Refresh data hiện tại
clearRfidData()                    // Clear tất cả dữ liệu
clearError()                       // Clear error message
clearInfo()                        // Clear info message
```

**Flow khi đọc tag:**
```kotlin
onTagRead(epc, rssi, antenna, channel)
    ↓
_lastEpc.value = epc
_lastRssi.value = rssi
    ↓
fetchRfidInfo(epc)
    ↓
_isLoadingRfidInfo.value = true
    ↓
apiService.getRfidInfo(epc).enqueue(...)
    ↓
onResponse:
  _rfidData.value = response.body()
  _infoMessage.value = "✅ Found: ..."
    ↓
_isLoadingRfidInfo.value = false
```

---

### 3. **Model Layer**

#### DataRfid (Data Class)
**File:** `api/DataRfid.kt`

```kotlin
data class DataRfid(
    val rfid: String,     // EPC code
    val model: String,    // Tên model
    val article: String,  // Article code
    val po: String,       // PO number
    val color: String,    // Màu sắc
    val size: String      // Size
)
```

#### PoApiService
**File:** `api/PoApiService.kt`

**New Endpoints:**
```kotlin
@GET("api/info-rfid")
fun getRfidInfo(@Query("rfid") rfid: String): Call<DataRfid>

@GET("api/info-rfid")
suspend fun getRfidInfoSuspend(@Query("rfid") rfid: String): Response<DataRfid>
```

---

## 🔄 Data Flow Diagram

### Complete Flow từ Tag Read → UI Update

```
┌──────────────┐
│ RFID Reader  │ (Hardware)
└──────┬───────┘
       │ SDK callback
       ↓
┌──────────────────┐
│   RfidManager    │ (SDK Wrapper)
│   - Parse data   │
└──────┬───────────┘
       │ onTagRead(epc, rssi, ...)
       ↓
┌───────────────────────┐
│ RfidConnectionManager │ (Singleton)
│   - Event routing     │
└──────┬────────────────┘
       │ RfidEventCallback
       ↓
┌───────────────────────┐
│ RfidScannerFragment   │ (View)
│   activity.runOnUi {  │
│     viewModel.onTag() │
│   }                   │
└──────┬────────────────┘
       │
       ↓
┌───────────────────────┐
│    RfidViewModel      │ (ViewModel)
│  onTagRead(epc, ...)  │
│      ↓                │
│  _lastEpc.value = epc │
│      ↓                │
│  fetchRfidInfo(epc)   │
│      ↓                │
│  apiService.call()    │
└──────┬────────────────┘
       │ HTTP GET
       ↓
┌───────────────────────┐
│   API Server          │
│ /api/info-rfid?rfid=  │
└──────┬────────────────┘
       │ JSON Response
       ↓
┌───────────────────────┐
│   RfidViewModel       │
│  onResponse() {       │
│    _rfidData.value =  │
│        response.body()│
│  }                    │
└──────┬────────────────┘
       │ LiveData update
       ↓
┌───────────────────────┐
│ RfidScannerFragment   │
│  Observer triggered   │
│      ↓                │
│  binding.tvProduct... │
│         .text = data  │
└───────────────────────┘
       ↓
    📱 UI Updated!
```

---

## 🎨 UI States

### 1. **Initial State** (Chưa kết nối)
```
┌─────────────────────────────────┐
│ RFID Scanner          ⚙️  ×     │
├─────────────────────────────────┤
│ CONNECTION STATUS               │
│ ❌ Disconnected                 │
│                                 │
│ LAST SCANNED TAG                │
│ EPC: ---                        │
│ RSSI: 0 dBm                     │
│                                 │
│ [Refresh] [Clear]               │
└─────────────────────────────────┘
```

### 2. **Connected & Scanning** (Đã kết nối)
```
┌─────────────────────────────────┐
│ RFID Scanner          ⚙️  ×     │
├─────────────────────────────────┤
│ CONNECTION STATUS               │
│ ✅ Connected - Scanning...      │
│                                 │
│ LAST SCANNED TAG                │
│ EPC: 30340BF28C4EE0CAB41ECC86  │
│ RSSI: -45 dBm                   │
│                                 │
│ ⏳ Loading...                   │
└─────────────────────────────────┘
```

### 3. **Data Loaded** (API thành công)
```
┌─────────────────────────────────┐
│ RFID Scanner          ⚙️  ×     │
├─────────────────────────────────┤
│ CONNECTION STATUS               │
│ ✅ Connected - Scanning...      │
│                                 │
│ LAST SCANNED TAG                │
│ EPC: 30340BF28C4EE0CAB41ECC86  │
│ RSSI: -45 dBm                   │
│                                 │
│ PRODUCT INFORMATION             │
│ Model:    Air Max 270           │
│ Article:  JH9767                │
│ PO:       0900005032            │
│ Color:    Black/White           │
│ Size:     08                    │
│                                 │
│ [Refresh] [Clear]               │
└─────────────────────────────────┘
```

### 4. **Error State** (API thất bại)
```
┌─────────────────────────────────┐
│ RFID Scanner          ⚙️  ×     │
├─────────────────────────────────┤
│ CONNECTION STATUS               │
│ ✅ Connected - Scanning...      │
│                                 │
│ LAST SCANNED TAG                │
│ EPC: INVALID123456789           │
│ RSSI: -50 dBm                   │
│                                 │
│ ⚠️ RFID not found in database  │
│                                 │
│ [Refresh] [Clear]               │
└─────────────────────────────────┘
```

---

## 🔧 How to Use

### 1. Từ Dashboard, click RFID button:
```kotlin
// DemoFragment.kt
mViewBinding?.btnUsbRfid?.setOnClickListener {
    val scannerDialog = RfidScannerFragment.newInstance()
    scannerDialog.show(parentFragmentManager, "RfidScannerDialog")
}
```

### 2. Scanner dialog mở lên
- Auto-connect nếu đã kết nối RFID trước đó
- Tự động bắt đầu scanning

### 3. Quét thẻ RFID
- EPC hiển thị ngay lập tức
- API được gọi tự động
- Thông tin sản phẩm hiển thị sau vài giây

### 4. Click Settings (⚙️) để cấu hình
- Mở RfidSettingsFragment
- Chọn thiết bị USB khác
- Kiểm tra connection status

---

## 🧪 Testing

### Unit Tests (ViewModel)
```kotlin
@Test
fun `when tag is read, should update lastEpc and call API`() {
    val viewModel = RfidViewModel(application)
    
    viewModel.onTagRead("ABC123", -50, 1, 1)
    
    assertEquals("ABC123", viewModel.lastEpc.value)
    assertEquals(-50, viewModel.lastRssi.value)
    // Verify API call được thực hiện
}
```

### UI Tests (Fragment)
```kotlin
@Test
fun `when settings button clicked, should open settings dialog`() {
    launchFragment<RfidScannerFragment>()
    
    onView(withId(R.id.btn_settings)).perform(click())
    
    // Verify RfidSettingsFragment hiển thị
}
```

---

## 🎯 Benefits of MVVM

### ✅ Separation of Concerns
- **View** chỉ lo hiển thị
- **ViewModel** xử lý logic
- **Model** quản lý data

### ✅ Testability
- ViewModel có thể test độc lập
- Không cần mock Fragment/Activity
- Easy to verify business logic

### ✅ Lifecycle Aware
- LiveData tự động cleanup
- Không lo memory leak
- Configuration changes được handle tự động

### ✅ Maintainability
- Code rõ ràng, dễ đọc
- Dễ thêm feature mới
- Dễ refactor

---

## 🚀 Future Enhancements

- [ ] Add Repository layer cho offline caching
- [ ] Use Kotlin Flow thay vì LiveData
- [ ] Add UseCase layer cho complex business logic
- [ ] Implement Dependency Injection (Hilt/Koin)
- [ ] Add Unit Tests cho ViewModel
- [ ] Add UI Tests cho Fragment

---

**Created:** 2026-01-23  
**Pattern:** MVVM (Model-View-ViewModel)  
**Version:** 2.0
