# 📝 EPC Trimming Documentation

## ✅ Cập nhật: Chỉ lấy 24 ký tự đầu từ EPC

### 🎯 Mục đích
RFID reader có thể trả về EPC code dài hơn 24 ký tự, nhưng API chỉ cần 24 ký tự đầu tiên để tra cứu thông tin sản phẩm.

---

## 🔧 Implementation

### Location: `RfidViewModel.kt`

**Function:** `onTagRead()`

```kotlin
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
```

---

## 📊 Ví dụ

### Case 1: EPC dài hơn 24 ký tự
```
Input:  "30340BF28C4EE0CAB41ECC86ABCDEF1234567890"  (40 chars)
           ↓ substring(0, 24)
Output: "30340BF28C4EE0CAB41ECC86"                  (24 chars)
        └─────────────────────┘
        Chỉ lấy 24 ký tự đầu
```

### Case 2: EPC ngắn hơn hoặc bằng 24 ký tự
```
Input:  "30340BF28C4EE0CAB41E"       (20 chars)
           ↓ Giữ nguyên
Output: "30340BF28C4EE0CAB41E"       (20 chars)
```

### Case 3: EPC đúng 24 ký tự
```
Input:  "30340BF28C4EE0CAB41ECC86"  (24 chars)
           ↓ Giữ nguyên
Output: "30340BF28C4EE0CAB41ECC86"  (24 chars)
```

---

## 🔍 Logging

### Full Log Example:
```
Tag Read (Full): EPC=30340BF28C4EE0CAB41ECC86ABCDEF, RSSI=-45, Ant=1, Ch=1
Tag Read (Trimmed): EPC=30340BF28C4EE0CAB41ECC86 (24 chars)
Fetching RFID info for EPC: 30340BF28C4EE0CAB41ECC86
```

---

## 🎨 UI Display

### Dashboard RFID Card
```
┌─────────────────────────────────┐
│ EPC CODE                        │
│ 30340BF28C4EE0CAB41ECC86        │  ← Chỉ 24 ký tự
└─────────────────────────────────┘
```

### RfidScannerFragment (Dialog)
```
┌─────────────────────────────────┐
│ LAST SCANNED TAG                │
│ EPC: 30340BF28C4EE0CAB41ECC86   │  ← Chỉ 24 ký tự
│ RSSI: -45 dBm                   │
└─────────────────────────────────┘
```

---

## 🌐 API Call

### Request URL
```
GET http://192.168.30.169:3000/api/info-rfid?rfid=30340BF28C4EE0CAB41ECC86
                                                  └─────────────────────┘
                                                        24 characters
```

---

## ✅ Benefits

1. **Consistency:** API luôn nhận đúng 24 ký tự
2. **UI Clean:** Không bị dài quá mức
3. **Performance:** Không cần xử lý chuỗi dài không cần thiết
4. **Database Match:** Match với format trong database

---

## 🧪 Test Cases

### Test 1: Long EPC
```kotlin
val epc = "30340BF28C4EE0CAB41ECC86FFFFFFFFFFFFFFFFFF" // 42 chars
viewModel.onTagRead(epc, -50, 1, 1)

assertEquals("30340BF28C4EE0CAB41ECC86", viewModel.lastEpc.value) // 24 chars ✅
```

### Test 2: Short EPC
```kotlin
val epc = "30340BF28C" // 10 chars
viewModel.onTagRead(epc, -50, 1, 1)

assertEquals("30340BF28C", viewModel.lastEpc.value) // Keep as is ✅
```

### Test 3: Exact 24 chars
```kotlin
val epc = "30340BF28C4EE0CAB41ECC86" // 24 chars
viewModel.onTagRead(epc, -50, 1, 1)

assertEquals("30340BF28C4EE0CAB41ECC86", viewModel.lastEpc.value) // 24 chars ✅
```

---

## 📝 Notes

- EPC trimming xảy ra ở ViewModel layer (business logic)
- UI chỉ hiển thị giá trị đã được trim
- API nhận giá trị đã được trim
- Log ghi cả full EPC và trimmed EPC để debug

---

**Version:** 3.1  
**Updated:** 2026-01-23  
**Feature:** EPC Code Trimming (24 characters)
