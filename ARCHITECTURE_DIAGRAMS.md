# 📊 ARCHITECTURE DIAGRAMS

## 🏗️ System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         📱 PRESENTATION LAYER                        │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────────┐  │
│  │  DemoFragment  │  │  MainActivity   │  │  RfidScanner        │  │
│  │  • Camera UI   │  │  • Main coord.  │  │  Fragment           │  │
│  │  • Box display │  │  • Navigation   │  │  • RFID UI          │  │
│  └────────┬───────┘  └────────────────┘  └──────────┬──────────┘  │
│           │                                           │              │
│           └─────────────────┬─────────────────────────┘              │
│                             ↓                                        │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                      ViewModels                                │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐│ │
│  │  │MainViewModel │  │RfidViewModel │  │  ProcessingState     ││ │
│  │  │• State mgmt  │  │• RFID scan   │  │  • Idle              ││ │
│  │  │• API calls   │  │• Validation  │  │  • Processing        ││ │
│  │  │• UI updates  │  │• Display     │  │  • Success/Warning   ││ │
│  │  └──────────────┘  └──────────────┘  └──────────────────────┘│ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                  ↓
┌─────────────────────────────────────────────────────────────────────┐
│                          🎯 DOMAIN LAYER                             │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                        Use Cases                               │ │
│  │  ┌─────────────────────────────────────────────────────────┐  │ │
│  │  │  ProcessCameraDataUseCase                               │  │ │
│  │  │  • Call API for PO details                              │  │ │
│  │  │  • Fallback to local cache                              │  │ │
│  │  │  • Return Result<PoResponse>                            │  │ │
│  │  └─────────────────────────────────────────────────────────┘  │ │
│  │  ┌─────────────────────────────────────────────────────────┐  │ │
│  │  │  ValidateWithRfidUseCase                                │  │ │
│  │  │  1. Fetch RFID data from API                            │  │ │
│  │  │  2. Compare camera vs RFID data                         │  │ │
│  │  │     ├─ PO match?                                        │  │ │
│  │  │     ├─ Size match?                                      │  │ │
│  │  │     ├─ Article match?                                   │  │ │
│  │  │     └─ UPC match?                                       │  │ │
│  │  │  3. Save based on result:                               │  │ │
│  │  │     ├─ Match → ShoeboxDetail                            │  │ │
│  │  │     └─ Mismatch → ShoeboxDetailRfid                     │  │ │
│  │  └─────────────────────────────────────────────────────────┘  │ │
│  │  ┌─────────────────────────────────────────────────────────┐  │ │
│  │  │  SyncDataUseCase                                        │  │ │
│  │  │  • Get all unsynced data (Synced = 0)                   │  │ │
│  │  │  • Upload to server                                     │  │ │
│  │  │  • Mark as synced (Synced = 1)                          │  │ │
│  │  │  • Return SyncStats                                     │  │ │
│  │  └─────────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                  ↓
┌─────────────────────────────────────────────────────────────────────┐
│                          💾 DATA LAYER                               │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                      Repositories                              │ │
│  │  ┌──────────────────────────────────────────────────────────┐ │ │
│  │  │  ShoeboxRepository                                       │ │ │
│  │  │  • processScan(po, barcode) → Result<PoResponse>        │ │ │
│  │  │  • saveToMainTable(cameraData, rfidData?)               │ │ │
│  │  │  • saveToMismatchTable(camera, rfid, fields)            │ │ │
│  │  │  • syncPendingData() → Result<SyncStats>                │ │ │
│  │  │  • getLocalData(po, barcode) → PoResponse?              │ │ │
│  │  └──────────────────────────────────────────────────────────┘ │ │
│  │  ┌──────────────────────────────────────────────────────────┐ │ │
│  │  │  RfidRepository                                          │ │ │
│  │  │  • getRfidInfo(rfidCode) → Result<RfidData>             │ │ │
│  │  └──────────────────────────────────────────────────────────┘ │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                  ↓                                   │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                      Data Sources                              │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐  │ │
│  │  │ ShoeboxDao   │  │ PoApiService │  │ NetworkMonitor     │  │ │
│  │  │ (Room)       │  │ (Retrofit)   │  │ (Connectivity)     │  │ │
│  │  └──────────────┘  └──────────────┘  └────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                  ↓
┌─────────────────────────────────────────────────────────────────────┐
│                      🗄️ STORAGE & NETWORK                            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │
│  │  Room Database   │  │  Backend API     │  │  WorkManager    │  │
│  │  v2 (Migration)  │  │  (Node.js)       │  │  (Background)   │  │
│  │                  │  │                  │  │                 │  │
│  │  • Data_Shoebox  │  │  • GET /select-po│  │  • Periodic sync│  │
│  │    _Detail       │  │  • GET /info-rfid│  │  • Network req. │  │
│  │                  │  │  • POST /sync/*  │  │  • Exponential  │  │
│  │  • Data_Shoebox  │  │                  │  │    backoff      │  │
│  │    _RFID_Detail  │  │                  │  │                 │  │
│  │                  │  │                  │  │                 │  │
│  │  • Data_Shoebox  │  │                  │  │                 │  │
│  │    _Total        │  │                  │  │                 │  │
│  └──────────────────┘  └──────────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Data Flow Sequence Diagrams

### Flow 1: Camera Scan WITH RFID Validation (Match)

```
User          DemoFragment    BoxProcessor    RfidViewModel    UseCases         Repository      Database      API
 │                 │                │                │              │                │              │            │
 │ Scan Barcode    │                │                │              │                │              │            │
 ├────────────────>│                │                │              │                │              │            │
 │                 │  updateLogic() │                │              │                │              │            │
 │                 ├───────────────>│                │              │                │              │            │
 │                 │                │  State: VERIFYING             │                │              │            │
 │                 │<───────────────┤                │              │                │              │            │
 │                 │                │                │              │                │              │            │
 │ Scan RFID       │                │                │              │                │              │            │
 ├────────────────────────────────────────────────>│              │                │              │            │
 │                 │                │                │ onTagRead()  │                │              │            │
 │                 │                │                ├─ Store EPC   │                │              │            │
 │                 │                │                │              │                │              │            │
 │                 │ processWithRfidValidation()    │              │                │              │            │
 │                 │                │                │              │                │              │            │
 │                 │  Step 1: Process Camera Data   │              │                │              │            │
 │                 ├────────────────────────────────────────────────>│               │              │            │
 │                 │                │                │              │ processScan()  │              │            │
 │                 │                │                │              ├───────────────>│              │            │
 │                 │                │                │              │                │ GET /select-po│            │
 │                 │                │                │              │                ├─────────────────────────>│
 │                 │                │                │              │                │              │  200 OK    │
 │                 │                │                │              │                │<─────────────────────────┤
 │                 │                │                │              │                │ saveLocal()  │            │
 │                 │                │                │              │                ├─────────────>│            │
 │                 │                │                │              │                │              │            │
 │                 │                │                │              │<───────────────┤              │            │
 │                 │                │                │  Result.Success(PoResponse)  │              │            │
 │                 │<────────────────────────────────────────────────┤              │              │            │
 │                 │                │                │              │                │              │            │
 │                 │  Step 2: Validate with RFID    │              │                │              │            │
 │                 ├────────────────────────────────────────────────────────────────>│             │            │
 │                 │                │                │              │ getRfidInfo()  │              │            │
 │                 │                │                │              │                ├─────────────────────────>│
 │                 │                │                │              │                │  GET /info-rfid          │
 │                 │                │                │              │                │<─────────────────────────┤
 │                 │                │                │              │                │  200 OK (DataRfid)       │
 │                 │                │                │              │ compareData()  │              │            │
 │                 │                │                │              │ ✅ MATCH!      │              │            │
 │                 │                │                │              │ saveToMain()   │              │            │
 │                 │                │                │              ├───────────────>│              │            │
 │                 │                │                │              │                │ insertDetail()│            │
 │                 │                │                │              │                ├─────────────>│            │
 │                 │                │                │              │                │  Synced = 0  │            │
 │                 │                │                │  ValidationResult.Success     │              │            │
 │                 │<────────────────────────────────────────────────┤ (isMatch=true)│              │            │
 │                 │                │                │              │                │              │            │
 │  showSuccess() │                │                │              │                │              │            │
 │  "✅ Match!"    │                │                │              │                │              │            │
 │<────────────────┤                │                │              │                │              │            │
 │                 │                │                │              │                │              │            │
```

### Flow 2: Camera Scan WITH RFID Validation (Mismatch)

```
[Similar to Flow 1 until compareData()]

 │                 │                │                │              │ compareData()  │              │            │
 │                 │                │                │              │ ⚠️ MISMATCH   │              │            │
 │                 │                │                │              │ Fields: [PO]   │              │            │
 │                 │                │                │              │ saveToMismatch()│             │            │
 │                 │                │                │              ├───────────────>│              │            │
 │                 │                │                │              │                │ insertRfidDetail()       │
 │                 │                │                │              │                ├─────────────>│            │
 │                 │                │                │              │                │  Camera + RFID data      │
 │                 │                │                │              │                │  MismatchFields: ["PO"]  │
 │                 │                │                │              │                │  Synced = 0  │            │
 │                 │                │                │  ValidationResult.Success     │              │            │
 │                 │<────────────────────────────────────────────────┤(isMatch=false)│              │            │
 │                 │                │                │              │                │              │            │
 │  showWarning() │                │                │              │                │              │            │
 │  "⚠️ Mismatch" │                │                │              │                │              │            │
 │<────────────────┤                │                │              │                │              │            │
```

### Flow 3: Camera Scan WITHOUT RFID

```
User          DemoFragment    BoxProcessor    UseCases         Repository      Database      API
 │                 │                │              │                │              │            │
 │ Scan Barcode    │                │              │                │              │            │
 ├────────────────>│                │              │                │              │            │
 │                 │  updateLogic() │              │                │              │            │
 │                 ├───────────────>│              │                │              │            │
 │                 │  State: VERIFYING             │                │              │            │
 │                 │<───────────────┤              │                │              │            │
 │                 │                │              │                │              │            │
 │                 │ (No RFID scan - timeout)      │                │              │            │
 │                 │                │              │                │              │            │
 │                 │ processWithoutRfid()          │                │              │            │
 │                 ├────────────────────────────────>│               │              │            │
 │                 │                │              │ processScan()  │              │            │
 │                 │                │              ├───────────────>│              │            │
 │                 │                │              │                │ GET /select-po│            │
 │                 │                │              │                ├─────────────────────────>│
 │                 │                │              │                │<─────────────────────────┤
 │                 │                │              │                │  200 OK      │            │
 │                 │                │  Result.Success               │              │            │
 │                 │<────────────────────────────────┤              │              │            │
 │                 │  saveToMainTable(cameraData, null)            │              │            │
 │                 ├───────────────────────────────────────────────>│              │            │
 │                 │                │              │                │ insertDetail()│            │
 │                 │                │              │                ├─────────────>│            │
 │                 │                │              │                │  Synced = 0  │            │
 │  showSuccess() │                │              │                │              │            │
 │  "✅ Saved"     │                │              │                │              │            │
 │<────────────────┤                │              │                │              │            │
```

### Flow 4: Offline Mode (API Failure)

```
User          DemoFragment    UseCases         Repository      Database      API
 │                 │              │                │              │            │
 │ Scan Barcode    │              │                │              │            │
 ├────────────────>│              │                │              │            │
 │                 │ processScan()│                │              │            │
 │                 ├─────────────>│                │              │            │
 │                 │              │ processScan()  │              │            │
 │                 │              ├───────────────>│              │            │
 │                 │              │                │ GET /select-po│            │
 │                 │              │                ├─────────────────────────>│
 │                 │              │                │              │  ❌ TIMEOUT│
 │                 │              │                │<─────────────────────────┤
 │                 │              │                │ getLocalData()│            │
 │                 │              │                ├─────────────>│            │
 │                 │              │                │  Query cache │            │
 │                 │              │                │<─────────────┤            │
 │                 │              │  Result.Success(cached)       │            │
 │                 │<─────────────┤                │              │            │
 │                 │              │ saveToMainTable()             │            │
 │                 ├───────────────────────────────>│              │            │
 │                 │              │                │ insertDetail()│            │
 │                 │              │                ├─────────────>│            │
 │                 │              │                │  Synced = 0  │  (Will sync later)
 │  showOffline() │              │                │              │            │
 │  "📴 Offline"  │              │                │              │            │
 │<────────────────┤              │                │              │            │
```

### Flow 5: Background Sync (WorkManager)

```
WorkManager    SyncWorker    SyncUseCase    Repository      Database      API
    │              │              │              │              │            │
    │ Periodic     │              │              │              │            │
    │ Trigger      │              │              │              │            │
    │ (15 min)     │              │              │              │            │
    ├─────────────>│              │              │              │            │
    │              │ doWork()     │              │              │            │
    │              ├─────────────>│              │              │            │
    │              │              │ syncPending()│              │            │
    │              │              ├─────────────>│              │            │
    │              │              │              │ getUnsynced()│            │
    │              │              │              ├─────────────>│            │
    │              │              │              │ [item1, item2]            │
    │              │              │              │<─────────────┤            │
    │              │              │              │              │            │
    │              │              │              │ POST /sync/detail         │
    │              │              │              ├─────────────────────────>│
    │              │              │              │              │  200 OK    │
    │              │              │              │<─────────────────────────┤
    │              │              │              │ updateSynced()│            │
    │              │              │              ├─────────────>│            │
    │              │              │              │  Synced = 1  │            │
    │              │              │              │              │            │
    │              │              │              │ POST /sync/rfid-mismatch  │
    │              │              │              ├─────────────────────────>│
    │              │              │              │              │  200 OK    │
    │              │              │              │<─────────────────────────┤
    │              │              │              │ updateRfidSynced()        │
    │              │              │              ├─────────────>│            │
    │              │              │  Result.Success(SyncStats)  │            │
    │              │<─────────────┤              │              │            │
    │              │ Result.success()            │              │            │
    │<─────────────┤              │              │              │            │
    │              │              │              │              │            │
```

---

## 📊 Database Schema Diagram

```
┌─────────────────────────────────────────────────────────────┐
│               Data_Shoebox_Detail (Main Table)              │
│─────────────────────────────────────────────────────────────│
│ id (PK)              │ BIGINT AUTO_INCREMENT                │
│ RY                   │ TEXT                                 │
│ Size                 │ TEXT                                 │
│ PO                   │ TEXT                                 │
│ UPC                  │ TEXT                                 │
│ Qty                  │ INT                                  │
│ DateScan             │ TEXT (yyyy-MM-dd HH:mm:ss)           │
│ Modify               │ TEXT                                 │
│ Article              │ TEXT                                 │
│ ShoeImage            │ TEXT                                 │
│ User_Serial_Key      │ TEXT                                 │
│ Line                 │ TEXT                                 │
│ Synced               │ INT (0 = No, 1 = Yes)               │
└─────────────────────────────────────────────────────────────┘
                            ↑
                            │ Used when:
                            │ • No RFID scan
                            │ • RFID matches camera data
                            │
┌─────────────────────────────────────────────────────────────┐
│         Data_Shoebox_RFID_Detail (Mismatch Table)           │
│─────────────────────────────────────────────────────────────│
│ id (PK)              │ BIGINT AUTO_INCREMENT                │
│                                                             │
│ ┌─── Camera Data ───────────────────────────────────────┐  │
│ │ RY                 │ TEXT                             │  │
│ │ Size               │ TEXT                             │  │
│ │ PO                 │ TEXT                             │  │
│ │ UPC                │ TEXT                             │  │
│ │ Qty                │ INT                              │  │
│ │ Article            │ TEXT                             │  │
│ └───────────────────────────────────────────────────────┘  │
│                                                             │
│ ┌─── RFID Data ─────────────────────────────────────────┐  │
│ │ RFID               │ TEXT                             │  │
│ │ Size_RFID          │ TEXT                             │  │
│ │ PO_RFID            │ TEXT                             │  │
│ │ UPC_RFID           │ TEXT                             │  │
│ │ Article_RFID       │ TEXT                             │  │
│ │ RY_RFID            │ TEXT                             │  │
│ └───────────────────────────────────────────────────────┘  │
│                                                             │
│ MismatchFields       │ TEXT (JSON: ["PO", "Size"])         │
│ DateScan             │ TEXT (yyyy-MM-dd HH:mm:ss)           │
│ Modify               │ TEXT                                 │
│ ShoeImage            │ TEXT                                 │
│ User_Serial_Key      │ TEXT                                 │
│ Line                 │ TEXT                                 │
│ Synced               │ INT (0 = No, 1 = Yes)               │
└─────────────────────────────────────────────────────────────┘
                            ↑
                            │ Used when:
                            │ • RFID does NOT match camera data
                            │
┌─────────────────────────────────────────────────────────────┐
│             Data_Shoebox_Total (Summary Table)              │
│─────────────────────────────────────────────────────────────│
│ id (PK)              │ BIGINT AUTO_INCREMENT                │
│ RY                   │ TEXT                                 │
│ Size                 │ TEXT                                 │
│ PO                   │ TEXT                                 │
│ UPC                  │ TEXT                                 │
│ Total_Qty_Scan       │ INT                                  │
│ Total_Qty_ERP        │ INT                                  │
│ Article              │ TEXT                                 │
│ DateScan             │ TEXT                                 │
│ Modify               │ TEXT                                 │
│ User_Serial_Key      │ TEXT                                 │
│ Line                 │ TEXT                                 │
│ Synced               │ INT (0 = No, 1 = Yes)               │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔄 State Machine Diagram

```
┌─────────────────────────────────────────────────────────────┐
│              BoxProcessor State Machine                     │
└─────────────────────────────────────────────────────────────┘

    ┌────────┐
    │  IDLE  │ ◄──────────────────────────────┐
    └────┬───┘                                 │
         │ Box detected                        │
         ↓                                     │
    ┌─────────┐                                │
    │SCANNING │                          ┌─────┴────┐
    └────┬────┘                          │RESETTING │
         │ Barcode decoded               └──────────┘
         ↓                                     ↑
    ┌──────────┐                               │
    │ DECODED  │                               │
    └────┬─────┘                               │
         │ PO extracted                        │
         ↓                                     │
    ┌───────────┐                              │
    │VERIFYING  │──────────────────────────────┤
    └───────────┘  Verification complete       │
         │         (with or without RFID)      │
         │                                     │
         └─────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────┐
│           ProcessingState State Machine (UI)                │
└─────────────────────────────────────────────────────────────┘

    ┌────────┐
    │  Idle  │ ◄──────────────────────┐
    └────┬───┘                        │
         │ Start processing           │
         ↓                            │
    ┌────────────┐                    │
    │Processing  │                    │
    └────┬───────┘                    │
         │                            │
         │                            │
    ┌────┴────┬───────────────────────┴─────────┐
    │         │                                  │
    ↓         ↓                                  ↓
┌─────────┐ ┌─────────┐                    ┌────────┐
│Success  │ │Warning  │                    │Error   │
│         │ │(Mismatch│                    │        │
└─────────┘ └─────────┘                    └────────┘
    │         │                                  │
    │         │                                  │
    └─────────┴──────────────────────────────────┘
                        │
                        ↓
                   ┌────────┐
                   │  Idle  │
                   └────────┘
```

---

**📌 These diagrams provide a visual guide to understanding the architecture, data flows, and state transitions in the RFID Validation System.**
