# 📡 RFID Integration Documentation

## 📋 Tổng quan

Ứng dụng hiện đã tích hợp **SDK RFID** để đọc thẻ RFID và tự động lấy thông tin sản phẩm từ server.

---

## 🔧 Cấu hình thiết bị RFID

### VID/PID mặc định
Cấu hình tại: `RfidConnectionManager.kt`

```kotlin
private const val DEFAULT_VENDOR_ID = 0x483   // Hex format
private const val DEFAULT_PRODUCT_ID = 0x5750  // Hex format
```

> ⚠️ **Lưu ý**: Thay đổi giá trị này theo VID/PID thực tế của thiết bị RFID của bạn.

---

## 🚀 Quy trình hoạt động

### 1️⃣ Khởi động ứng dụng
```
App Start
    ↓
RfidConnectionManager.initialize()
    ↓
Quét USB devices
    ↓
Tìm thấy VID/PID mặc định?
    ├─ YES → Kết nối tự động
    └─ NO  → Retry 3 lần → Hiển thị Settings Dialog
```

### 2️⃣ Khi cắm USB RFID vào
```
USB Device Attached
    ↓
MainActivity detects ACTION_USB_DEVICE_ATTACHED
    ↓
Trigger RfidConnectionManager.autoConnect()
    ↓
Tự động kết nối (nếu đúng VID/PID)
```

### 3️⃣ Đọc thẻ RFID
```
RFID Tag được đưa vào đầu đọc
    ↓
SDK đọc EPC (ví dụ: 30340BF28C4EE0CAB41ECC86)
    ↓
Callback onTagRead() được gọi
    ↓
Hiển thị EPC trên Dashboard
    ↓
Gọi API: GET /api/info-rfid?rfid=30340BF28C4EE0CAB41ECC86
    ↓
Nhận response JSON:
{
  "rfid": "30340BF28C4EE0CAB41ECC86",
  "model": "Air Max 270",
  "article": "JH9767",
  "po": "0900005032",
  "color": "Black/White",
  "size": "08"
}
    ↓
Cập nhật UI Dashboard với thông tin sản phẩm
```

---

## 📂 Kiến trúc code

### Class chính

#### 1. **RfidConnectionManager** (Singleton)
- Quản lý kết nối RFID
- Auto-connect với VID/PID mặc định
- Retry logic (3 lần)
- Callback events: `onConnected`, `onDisconnected`, `onTagRead`, `onError`, `onAutoConnectFailed`

#### 2. **RfidManager** (Singleton)
- Wrapper cho SDK RFID chính thức
- Xử lý USB permission
- Parse dữ liệu từ SDK (EPC, RSSI, Antenna, Channel)
- Implement `IUsbConnectDone`, `IReadDataCallback`

#### 3. **RfidSettingsFragment** (DialogFragment)
- UI cho cấu hình thủ công
- Hiển thị danh sách USB devices
- Cho phép chọn thiết bị khi auto-connect thất bại
- Hiển thị trạng thái kết nối

#### 4. **DemoFragment**
- Tích hợp RFID vào Dashboard
- Hiển thị EPC code
- Gọi API `/api/info-rfid` khi đọc được thẻ
- Cập nhật UI với dữ liệu sản phẩm

---

## 🌐 API Endpoint

### GET `/api/info-rfid`

**Request:**
```
GET http://192.168.30.169:3000/api/info-rfid?rfid=30340BF28C4EE0CAB41ECC86
```

**Response (Success - 200):**
```json
{
  "rfid": "30340BF28C4EE0CAB41ECC86",
  "model": "Air Max 270",
  "article": "JH9767",
  "po": "0900005032",
  "color": "Black/White",
  "size": "08"
}
```

**Response (Not Found - 404):**
```json
{
  "error": "RFID not found"
}
```

---

## 🎨 UI Components

### Dashboard (layout_dashboard.xml)

**RFID Display Area (Column 4):**
```xml
<TextView 
    android:id="@+id/tv_dashboard_last_rfid"
    android:text="---"
    android:textSize="18sp"
    android:textStyle="bold"/>
```

**Updated fields from API:**
- `tv_po_value` → PO Number
- `tv_art` → Article
- `tv_size_value` → Size
- (Optional) Model, Color nếu có UI element

---

## ⚙️ Settings Dialog

### Trạng thái hiển thị:

1. **Chưa kết nối:**
   - Status: ❌ Disconnected
   - Button "Connect" enabled
   - Spinner hiển thị danh sách USB devices

2. **Đã kết nối:**
   - Status: ✅ Connected - Scanning
   - Hiển thị PID/VID hiện tại
   - Button "Disconnect" enabled

3. **Auto-connect Failed:**
   - Settings tự động mở
   - Cho phép chọn thiết bị thủ công

---

## 🛡️ Error Handling

### 1. **Infinite Loop Prevention**
- Đã loại bỏ callback `onConnectionStatus` trong `startScanning()`/`stopScanning()`
- Flag `rfidScanningStarted` ngăn gọi `startScanning()` nhiều lần

### 2. **USB Disconnect Handling**
- Broadcast receiver cho `ACTION_USB_DEVICE_DETACHED`
- Tự động cleanup và release resources
- Reset scanning flag

### 3. **API Failure Handling**
- Toast notification khi API call thất bại
- Logging đầy đủ tại mỗi bước
- Graceful degradation (vẫn hiển thị EPC dù API fail)

---

## 📊 Data Flow

```
┌─────────────────────┐
│  RFID Reader (SDK)  │
└──────────┬──────────┘
           │ EPC Code
           ↓
┌─────────────────────┐
│   RfidManager       │ (Parse SDK data)
└──────────┬──────────┘
           │ Callback
           ↓
┌─────────────────────┐
│ RfidConnection      │ (Manage connection)
│    Manager          │
└──────────┬──────────┘
           │ Event
           ↓
┌─────────────────────┐
│   DemoFragment      │ (UI Handler)
└──────────┬──────────┘
           │ HTTP GET
           ↓
┌─────────────────────┐
│  API Server         │ (/api/info-rfid)
└──────────┬──────────┘
           │ JSON Response
           ↓
┌─────────────────────┐
│   Dashboard UI      │ (Display)
└─────────────────────┘
```

---

## 🧪 Testing Checklist

- [ ] Cắm RFID reader → Auto-connect thành công
- [ ] Quét thẻ RFID → Hiển thị EPC trên Dashboard
- [ ] API được gọi → Thông tin sản phẩm hiển thị đúng
- [ ] Rút USB → App không crash, icon mờ đi
- [ ] Cắm lại USB → Auto-connect lại
- [ ] Auto-connect thất bại → Settings dialog tự mở
- [ ] Chọn device thủ công → Kết nối thành công
- [ ] Quét thẻ không có trong DB → Toast "RFID not found"
- [ ] API server offline → Toast thông báo lỗi, vẫn hiển thị EPC

---

## 🔐 Security Notes

- USB permission request được handle đúng cách
- PendingIntent sử dụng `FLAG_MUTABLE` cho Android 12+
- Không lưu trữ thông tin nhạy cảm trong log

---

## 📝 Maintenance

### Thay đổi VID/PID:
```kotlin
// File: RfidConnectionManager.kt
private const val DEFAULT_VENDOR_ID = 0xYOUR_VID
private const val DEFAULT_PRODUCT_ID = 0xYOUR_PID
```

### Thay đổi API endpoint:
```kotlin
// File: PoApiService.kt
private const val BASE_URL = "http://YOUR_SERVER_IP:PORT/"
```

### Thêm field hiển thị:
```kotlin
// File: DemoFragment.kt → callRfidInfoApi()
mViewBinding?.apply {
    tvYourField.text = rfidData.yourField
}
```

---

## 🎯 Future Enhancements

- [ ] Cache RFID data locally (Room Database)
- [ ] Offline mode support
- [ ] Batch RFID scanning
- [ ] RFID scan history
- [ ] Sound/vibration feedback tuỳ chỉnh
- [ ] Auto-retry logic cho API failures

---

**Created:** 2026-01-23  
**Version:** 1.0  
**Maintainer:** Development Team
