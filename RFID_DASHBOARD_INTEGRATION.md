# 📱 RFID Dashboard Integration - MVVM Pattern

## ✅ Hoàn thành

Đã tích hợp **RFID Scanner vào Dashboard** theo kiến trúc MVVM với các thay đổi sau:

---

## 🎨 UI Changes

### Layout: `layout_dashboard.xml` - Column 4 (RFID Card)

#### Before:
```
┌─────────────────────────────┐
│ RFID Scanner                │
├─────────────────────────────┤
│ [RFID Settings Button]      │  ← Button to tràn chiếm không gian
│                             │
│       🏷️                    │
│    LAST SCAN                │
│    30340BF2...              │
└─────────────────────────────┘
```

#### After:
```
┌─────────────────────────────┐
│ 🏷️ RFID Scanner        ⚙️  │  ← Icon Settings ở góc phải
├─────────────────────────────┤
│ • Connected - Scanning...   │  ← Status real-time
│                             │
│ EPC CODE                    │
│ 30340BF28C4EE0CAB41ECC86   │
│                             │
│ PRODUCT INFO                │  ← Thông tin từ API
│ Model:   Air Max 270        │
│ Article: JH9767             │
│ PO:      0900005032         │
│ Color:   Black/White        │
│ Size:    08                 │
└─────────────────────────────┘
```

---

## 📂 Component Structure

### 1. XML Layout Updates

#### Header with Icon Button
```xml
<LinearLayout orientation="horizontal">
    <ImageView id="iv_rfid_icon" 24dp />          <!-- RFID icon -->
    <TextView "RFID Scanner" weight="1" />
    <ImageView id="btn_usb_rfid" 32dp            <!-- Settings icon -->
               src="ic_menu_manage" />
</LinearLayout>
```

#### Content Area (ScrollView)
```xml
<ScrollView>
    <!-- Connection Status -->
    <TextView id="tv_rfid_status" />
    
    <!-- EPC Code -->
    <TextView id="tv_dashboard_last_rfid" 
              fontFamily="monospace" />
    
    <!-- Product Info (initially hidden) -->
    <LinearLayout id="ll_rfid_product_info" 
                  visibility="gone">
        <TextView id="tv_rfid_model" />
        <TextView id="tv_rfid_article" />
        <TextView id="tv_rfid_po" />
        <TextView id="tv_rfid_color" />
        <TextView id="tv_rfid_size" />
    </LinearLayout>
</ScrollView>
```

---

### 2. ViewModel Integration

#### RfidViewModel (New)
**File:** `viewmodel/RfidViewModel.kt`

**LiveData:**
```kotlin
val connectionStatus: LiveData<String>
val lastEpc: LiveData<String>
val lastRssi: LiveData<Int>
val rfidData: LiveData<DataRfid?>      // API response
val isLoadingRfidInfo: LiveData<Boolean>
val errorMessage: LiveData<String?>
val infoMessage: LiveData<String?>
```

**Methods:**
```kotlin
setConnected(Boolean)
setScanning(Boolean)
onTagRead(epc, rssi, antenna, channel)  // Auto calls API
fetchRfidInfo(epc)                      // API call
clearRfidData()
```

---

### 3. DemoFragment Updates

#### Added:
```kotlin
private val rfidViewModel: RfidViewModel by viewModels()
```

#### setupRfidManager():
```kotlin
// ViewModel updates instead of direct UI manipulation
rfidViewModel.setConnected(true)
rfidViewModel.setScanning(true)
rfidViewModel.onTagRead(epc, rssi, antenna, channel)
```

#### setupRfidObservers() (NEW):
```kotlin
// Connection status → tv_rfid_status
rfidViewModel.connectionStatus.observe { status ->
    mViewBinding?.tvRfidStatus?.text = status
}

// EPC code → tv_dashboard_last_rfid
rfidViewModel.lastEpc.observe { epc ->
    mViewBinding?.tvDashboardLastRfid?.text = epc
    mViewBinding?.ivRfidIcon?.alpha = if (epc.isNotEmpty()) 1.0f else 0.3f
}

// API data → Product info section
rfidViewModel.rfidData.observe { data ->
    if (data != null) {
        mViewBinding?.apply {
            llRfidProductInfo.visibility = View.VISIBLE
            tvRfidModel.text = data.model
            tvRfidArticle.text = data.article
            tvRfidPo.text = data.po
            tvRfidColor.text = data.color
            tvRfidSize.text = data.size
        }
    } else {
        mViewBinding?.llRfidProductInfo?.visibility = View.GONE
    }
}
```

---

## 🔄 Data Flow

### Complete Flow (MVVM Pattern)

```
┌────────────────┐
│  RFID Reader   │ (Hardware SDK)
└───────┬────────┘
        │
        ↓ onTagRead(epc, rssi, ...)
┌───────────────────┐
│ RfidConnection    │ (Manager)
│    Manager        │
└───────┬───────────┘
        │
        ↓ Callback
┌───────────────────┐
│  DemoFragment     │ (View)
│ activity.runOnUi  │
│   viewModel       │
│   .onTagRead()    │
└───────┬───────────┘
        │
        ↓
┌───────────────────┐
│  RfidViewModel    │ (ViewModel)
│                   │
│ _lastEpc.value =  │
│ _lastRssi.value = │
│       ↓           │
│ fetchRfidInfo()   │
└───────┬───────────┘
        │
        ↓ HTTP GET
┌───────────────────┐
│   API Server      │
│ /api/info-rfid    │
└───────┬───────────┘
        │
        ↓ JSON Response
┌───────────────────┐
│  RfidViewModel    │
│ _rfidData.value = │
│    response.body()│
└───────┬───────────┘
        │
        ↓ LiveData notification
┌───────────────────┐
│  DemoFragment     │
│  Observer         │
│  updateUI()       │
└───────────────────┘
        ↓
    📱 Dashboard RFID Card Updated!
```

---

## 🎯 UI States

### 1. **Initial / Disconnected**
```
• Not Connected
EPC CODE
---
(Product info hidden)
```

### 2. **Connected & Scanning**
```
✅ Connected - Scanning...
EPC CODE
---
(Product info hidden)
```

### 3. **Tag Scanned - Loading API**
```
✅ Connected - Scanning...
EPC CODE
30340BF28C4EE0CAB41ECC86
(Loading indicator in ViewModel)
```

### 4. **API Success - Product Info Displayed**
```
✅ Connected - Scanning...
EPC CODE
30340BF28C4EE0CAB41ECC86

PRODUCT INFO
Model:   Air Max 270
Article: JH9767
PO:      0900005032
Color:   Black/White
Size:    08
```

### 5. **API Error**
```
✅ Connected - Scanning...
EPC CODE
30340BF28C4EE0CAB41ECC86

(Product info hidden)
(Toast: "RFID not found in database")
```

---

## 🛠️ Technical Details

### Removed from DemoFragment:
- ❌ `callRfidInfoApi()` function (moved to ViewModel)
- ❌ `apiService` instance (ViewModel handles API)
- ❌ Direct UI updates in callbacks

### Added to DemoFragment:
- ✅ `RfidViewModel` initialization
- ✅ `setupRfidObservers()` function
- ✅ LiveData observers for all RFID states

### Benefits:
1. **Separation of Concerns**: UI logic tách khỏi business logic
2. **Testability**: ViewModel có thể unit test dễ dàng
3. **Lifecycle Aware**: LiveData tự động cleanup
4. **Reactive UI**: UI tự động update khi data thay đổi
5. **Single Source of Truth**: ViewModel là nguồn dữ liệu duy nhất

---

## 📊 Comparison

### Old Approach (Before)
```kotlin
// Direct UI manipulation in callback
override fun onTagRead(epc, rssi, ...) {
    activity?.runOnUiThread {
        tvDashboardLastRfid.text = epc  // ❌ Direct access
        ivRfidIcon.alpha = 1.0f         // ❌ Direct access
        callRfidInfoApi(epc)            // ❌ In Fragment
    }
}
```

### New Approach (MVVM)
```kotlin
// Update ViewModel - it handles everything
override fun onTagRead(epc, rssi, ...) {
    activity?.runOnUiThread {
        rfidViewModel.onTagRead(epc, rssi, ...) // ✅ Clean
    }
}

// ViewModel handles API automatically
fun onTagRead(...) {
    _lastEpc.value = epc
    fetchRfidInfo(epc)  // Auto API call
}

// UI observes changes
rfidViewModel.lastEpc.observe { epc ->
    tvDashboardLastRfid.text = epc  // ✅ Reactive
}
```

---

## 🧪 Testing

### Unit Test Example (ViewModel)
```kotlin
@Test
fun `when tag read, should fetch API data`() {
    val viewModel = RfidViewModel(app)
    
    viewModel.onTagRead("TEST123", -50, 1, 1)
    
    assertEquals("TEST123", viewModel.lastEpc.value)
    assertEquals(-50, viewModel.lastRssi.value)
    // Verify API call was made
}
```

---

## 🚀 How to Use

### 1. Khởi động App
- Auto-connect RFID (VID=0x483, PID=0x5750)
- Dashboard hiển thị "• Not Connected"

### 2. Khi kết nối thành công
- Status: "✅ Connected - Scanning..."
- Icon RFID sáng (alpha = 1.0)

### 3. Quét thẻ RFID
- EPC hiển thị ngay
- API được gọi tự động
- Product info xuất hiện sau vài giây

### 4. Click icon Settings (⚙️)
- Mở RfidSettingsFragment
- Có thể chọn device khác
- Connect/Disconnect thủ công

---

## 📝 Files Modified

### Created:
- `viewmodel/RfidViewModel.kt` (MVVM layer)

### Modified:
- `layout/layout_dashboard.xml` (RFID card redesign)
- `DemoFragment.kt` (ViewModel integration)

### Unchanged:
- `RfidConnectionManager.kt`
- `RfidManager.kt`
- `RfidSettingsFragment.kt`
- `api/PoApiService.kt`
- `api/DataRfid.kt`

---

## 🎯 Key Features

✅ **Settings Icon** (⚙️) ở góc phải header  
✅ **Real-time connection status**  
✅ **EPC code** hiển thị với monospace font  
✅ **Product information** từ API  
✅ **MVVM pattern** cho maintainability  
✅ **LiveData observers** cho reactive UI  
✅ **Automatic API calls** khi đọc tag  
✅ **Error handling** với Toast messages  
✅ **Clean separation** of concerns  

---

**Version:** 3.0  
**Pattern:** MVVM (Model-View-ViewModel)  
**Created:** 2026-01-23  
**Architecture:** Clean, Testable, Maintainable
