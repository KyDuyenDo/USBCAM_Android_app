# RFID-PO Data Comparison Feature

This document describes the implementation of the RFID-PO data comparison feature, which ensures that scanned RFID tags contain information that matches the current Purchase Order (PO) data in the system.

## 1. Overview
The comparison feature automatically triggers whenever an RFID tag is read. It fetches product details from two different API endpoints and compares critical fields to detect discrepancies.

## 2. Comparison Workflow
1.  **RFID Scan**: A tag is read, and the EPC code is trimmed to 24 characters.
2.  **RFID API Call**: The app calls `/api/info-rfid?rfid={EPC}` to get the product details associated with that tag (`DataRfid`).
3.  **PO API Call**: Using the `po` field from the RFID data, the app calls `/api/select-po?po={PO}` to fetch the official PO record (`PoResponse`).
4.  **Background Comparison**: The app compares the following fields on a background thread (`Dispatchers.Default`):
    *   **PO Number**: `DataRfid.po` vs `PoResponse.po`
    *   **Article**: `DataRfid.article` vs `PoResponse.article`
    *   **Size**: `DataRfid.size` vs `PoResponse.size`
5.  **Result Notification**:
    *   **Mismatch**: If any field differs, a visual warning is displayed on the Dashboard, and a transient notification (Toast) is shown containing the details of the mismatch.
    *   **Match**: If all fields match, the warning is hidden, and no notification is shown.

## 3. Technical Implementation

### `RfidPoComparator.kt`
A utility object that encapsulates the logic for comparing two data models and formatting the result.
```kotlin
fun compare(rfidData: DataRfid, poData: PoResponse): List<Difference>
fun formatDifferences(differences: List<Difference>): String
```

### `RfidViewModel.kt`
Manages the orchestration of API calls and comparison:
*   `fetchRfidInfo(epc)` -> `fetchPoInfo(rfidData)` -> `performComparison(rfidData, poData)`
*   Uses `viewModelScope` with a background dispatcher `Dispatchers.Default` for the comparison logic.
*   Implements duplicate prevention to avoid redundant API calls and notifications if the same tag is scanned repeatedly.

### Dashboard UI (`fragment_rfid.xml` integrated in dashboard)
*   `tv_rfid_comparison_result`: A warning label that only becomes visible when a mismatch is detected.

## 4. Key Benefits
*   **Data Integrity**: Immediately identifies tags with incorrect PO or product info.
*   **Modular Design**: Comparison logic is separated from UI and networking code.
*   **UI Performance**: All logic runs in the background, keeping the dashboard responsive.
*   **Reduced Noise**: Filters out redundant notifications for the same tag.
