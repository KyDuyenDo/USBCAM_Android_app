# Project Overview Table - USBCAM_Android_app

| Area | Mo ta | Duong dan / Tep chinh | Ghi chu |
| --- | --- | --- | --- |
| Muc tieu | Ung dung Android doc USB camera, quet barcode + OCR PO, ket hop RFID de doi chieu va luu du lieu, ho tro offline-first | `README.md`, `ARCHITECTURE_PROPOSAL.md` | Flow: scan -> API -> validate RFID -> save -> sync |
| Nen tang | Android app Kotlin (ViewModel/LiveData, ViewBinding) | `app/build.gradle.kts`, `app/src/main` | minSdk 24, targetSdk 33, compileSdk 36 |
| Modules | App va cac module native/vision | `app/`, `libuvc/`, `libausbc/`, `libnative/`, `openCV/` | USB camera + native libs + OpenCV |
| Xu ly hinh anh | Xu ly frame MJPEG/YUV, barcode, OCR, blur/presence detect | `app/src/main/java/com/example/usbcam/DemoFragment.kt`, `BoxProcessor.kt`, `BarcodeDecoder.kt`, `POExtractor.kt`, `BlurDetector.kt`, `PresenceDetector.kt` | OpenCV + ML Kit |
| RFID | Ket noi USB RFID, doc EPC, doi chieu du lieu | `app/src/main/java/com/example/usbcam/rfid`, `RfidViewModel.kt`, `repository/RfidRepository.kt` | Tu dong ket noi, cap nhat UI, validate | 
| API | Goi API lay PO/target va dong bo du lieu | `app/src/main/java/com/example/usbcam/api/PoApiService.kt` | Retrofit + Gson |
| Data local | Room DB, entities, cache offline | `app/src/main/java/com/example/usbcam/data/db`, `data/model` | Synced flag, bang mismatch RFID |
| Domain | Use cases xu ly flow | `app/src/main/java/com/example/usbcam/domain/usecase` | ProcessCameraDataUseCase, ValidateWithRfidUseCase, SyncDataUseCase |
| Repository | Tong hop API + DB + cache | `app/src/main/java/com/example/usbcam/repository/ShoeboxRepository.kt` | Fallback offline va sync |
| Sync | Dong bo nen | `app/src/main/java/com/example/usbcam/worker/SyncWorker.kt` | WorkManager, retry/backoff |
| UI | Man hinh chinh + dashboard | `MainActivity.kt`, `DemoFragment.kt`, `res/layout` | Hien thi trang thai, so luong, hinh anh |
| Permissions | Camera, audio, storage, USB host, network | `app/src/main/AndroidManifest.xml` | usesCleartextTraffic = true |
| Tai lieu | Kien truc, integration, RFID | `IMPLEMENTATION_SUMMARY.md`, `INTEGRATION_GUIDE.md`, `README_RFID_SYSTEM.md`, `RFID_INTEGRATION.md`, `RFID_MVVM_ARCHITECTURE.md` | Huong dan va flow chi tiet |
