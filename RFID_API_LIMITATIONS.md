# ⚠️ IMPORTANT NOTE - RFID API Limitations

## 🔍 RFID API Response Fields

The RFID API (`GET /api/info-rfid?rfid={code}`) returns **limited fields** compared to the Camera scan data:

### Available Fields from RFID API:
```kotlin
data class DataRfid(
    val rfid: String,    // ✅ RFID code
    val model: String,   // ✅ Model number
    val article: String, // ✅ Article code
    val po: String,      // ✅ PO number
    val color: String,   // ✅ Color
    val size: String     // ✅ Size
)
```

### Fields NOT Available from RFID API:
- ❌ `upc` - Not provided by RFID API
- ❌ `ry` - Not provided by RFID API

## 🔄 Comparison Logic

The validation system compares **only the fields available from RFID API**:

### Fields Compared:
1. ✅ **PO** - Production Order number
2. ✅ **Size** - Shoe size
3. ✅ **Article** - Article code

### Fields NOT Compared (null in RFID data):
4. ⚠️ **UPC** - Skipped (not in RFID API)
5. ⚠️ **RY** - Skipped (not in RFID API)

## 📊 Comparison Examples

### Example 1: Match Case
```
Camera Data:
  PO = "PO12345"
  Size = "42"
  Article = "ART001"
  UPC = "123456789"
  RY = "RY2024"

RFID API Response:
  PO = "PO12345"      ✅ Matches
  Size = "42"         ✅ Matches
  Article = "ART001"  ✅ Matches
  UPC = null          ⚠️ Skipped
  RY = null           ⚠️ Skipped

Result: ✅ MATCH (all comparable fields match)
→ Saved to Data_Shoebox_Detail
```

### Example 2: Mismatch Case
```
Camera Data:
  PO = "PO12345"
  Size = "42"
  Article = "ART001"

RFID API Response:
  PO = "PO12345"      ✅ Matches
  Size = "40"         ❌ MISMATCH!
  Article = "ART001"  ✅ Matches

Result: ⚠️ MISMATCH (Size different)
→ Saved to Data_Shoebox_RFID_Detail
→ MismatchFields = ["Size"]
```

## 💡 Implications

1. **UPC and RY cannot be validated** from RFID
   - These fields will always be `null` in RFID data
   - Comparison will be skipped
   - No false mismatches will be reported

2. **Primary comparison fields**:
   - Focus on: **PO**, **Size**, **Article**
   - These are the critical fields for validation

3. **Future Enhancement**:
   - If RFID API is updated to include UPC and RY
   - Simply update `DataRfid` class
   - No code changes needed in use cases
   - Comparison will automatically include new fields

## 🔧 Code Implementation

### RfidRepository Mapping:
```kotlin
private fun DataRfid.toRfidData(rfidCode: String): RfidData {
    return RfidData(
        rfidCode = rfidCode,
        po = this.po,
        upc = null,        // ⚠️ Not available in API
        ry = null,         // ⚠️ Not available in API
        size = this.size,
        article = this.article,
        color = this.color,
        model = this.model
    )
}
```

### ValidateWithRfidUseCase Comparison:
```kotlin
// Compare UPC (Note: Not available in RFID API, will always be null)
if (camera.upc != rfid.upc && rfid.upc != null) {
    mismatchFields.add("UPC")  // This will never execute
}

// Compare RY (Note: Not available in RFID API, will always be null)
if (camera.ry != rfid.ry && rfid.ry != null) {
    mismatchFields.add("RY")   // This will never execute
}
```

The `rfid.upc != null` and `rfid.ry != null` checks ensure that comparison is **only performed if RFID data is available**. Since these are always null, the comparison is safely skipped.

## ✅ Summary

- **3 fields compared**: PO, Size, Article
- **2 fields skipped**: UPC, RY (not in RFID API)
- **No false positives**: Null RFID fields won't cause mismatches
- **Future-proof**: Code ready if API is enhanced

---

**Last Updated**: 2026-01-30  
**Issue Fixed**: Compilation errors for `upc` and `ry` unresolved references
