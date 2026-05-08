package com.example.usbcam

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.usbcam.api.AppVersionResponse
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: com.example.usbcam.viewmodel.MainViewModel

    // DownloadManager download ID
    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val factory = com.example.usbcam.viewmodel.MainViewModelFactory(application)
        viewModel =
                androidx.lifecycle.ViewModelProvider(this, factory)[
                        com.example.usbcam.viewmodel.MainViewModel::class.java]

        // 5. Start Sync Worker
        viewModel.startSyncWorker(this)

        // 5.1 Start Box Info Cache Worker (Load data from API all-info-box)
        viewModel.startBoxInfoCacheWorker(this)

        // 5.2 Start Config Cache Worker (Load Factory, DepType, Location, Dept)
        viewModel.startConfigCacheWorker(this)

        // 6. Check for app updates
        viewModel.checkAppVersion(this)
        viewModel.updateAvailable.observe(this) { update ->
            if (update != null) showUpdateDialog(update)
        }

        // 7. Initial Ping (Heartbeat Online)
        viewModel.pingNow(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Register USB Receiver
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, filter)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
            )
        }

        // Xử lý intent nếu app được mở do cắm camera
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device =
                    intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                            UsbManager.EXTRA_DEVICE
                    )
            android.util.Log.d("MainActivity", "App started/resumed by USB Attachment: ${device?.deviceName}")
            
            // Nếu đã đủ quyền thì đảm bảo camera được khởi tạo
            if (allPermissionsGranted()) {
                startCamera()
            }
        }
    }

    private fun startCamera() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_container, DemoFragment())
                    .commit()
        }
    }

    private fun allPermissionsGranted() =
            REQUIRED_PERMISSIONS.all {
                androidx.core.content.ContextCompat.checkSelfPermission(baseContext, it) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                android.widget.Toast.makeText(
                                this,
                                "Permissions not granted by the user.",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                finish()
            }
        }
    }

    private val usbReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                            android.util.Log.d("MainActivity", "USB Device Attached: ${device?.deviceName}")
                            
                            // Hiển thị thông báo khi nhận diện được Camera/Thiết bị USB
                            android.widget.Toast.makeText(context, "USB Device Attached: ${device?.deviceName}", android.widget.Toast.LENGTH_SHORT).show()
                            
                            // Trigger RFID auto-connect if device is attached
                            com.example.usbcam.rfid.RfidConnectionManager.getInstance(context).autoConnect()
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val device =
                                    intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                                            UsbManager.EXTRA_DEVICE
                                    )
                            android.util.Log.d(
                                    "MainActivity",
                                    "USB Device Detached: ${device?.deviceName}"
                             )
                            viewModel.onUsbDeviceDetached(device)
                        }
                    }
                }
            }

    // =========================================================================
    // AUTO-UPDATE — Tải và cài APK trong app
    // =========================================================================

    /**
     * Hiển thị dialog thông báo có phiên bản mới với nút "Cập nhật".
     * Sau khi xác nhận, sẽ tải APK qua DownloadManager và tự động cài đặt.
     */
    private fun showUpdateDialog(update: AppVersionResponse) {
        if (isFinishing || isDestroyed) return

        val message = buildString {
            append("Phiên bản mới: ${update.versionName}\n\n")
            if (!update.description.isNullOrBlank()) {
                append(update.description)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("🆕 Có bản cập nhật mới!")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Cập nhật ngay") { _, _ ->
                if (!update.apkUrl.isNullOrBlank()) {
                    startDownloadApk(update.apkUrl, update.versionName ?: "mới")
                } else {
                    Toast.makeText(this, "Không tìm thấy link tải", Toast.LENGTH_SHORT).show()
                }
                viewModel.clearUpdateAvailable()
            }
            .setNegativeButton("Để sau") { dialog, _ ->
                dialog.dismiss()
                viewModel.clearUpdateAvailable()
            }
            .show()
    }

    /**
     * Tải APK bằng DownloadManager hệ thống và hiển thị dialog tiến trình.
     * Sau khi tải xong, tự động gọi Intent cài đặt.
     */
    private fun startDownloadApk(apkUrl: String, versionName: String) {
        Log.i("MainActivity", "Bắt đầu tải APK: $apkUrl")

        // Xoá file APK cũ nếu còn
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (apkFile.exists()) apkFile.delete()

        // Thiết lập DownloadManager request
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Cập nhật ứng dụng")
            setDescription("Đang tải phiên bản $versionName...")
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                "update.apk"
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)

        // Hiển thị dialog tiến trình đang tải
        val progressDialog = showDownloadProgressDialog()

        // Theo dõi tiến trình tải bằng Thread
        val progressThread = Thread {
            var isDownloading = true
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )

                    if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                        val downloadedMb = bytesDownloaded / 1024 / 1024
                        val totalMb = bytesTotal / 1024 / 1024
                        runOnUiThread {
                            progressDialog.first.progress = progress
                            progressDialog.second.text = "$downloadedMb MB / $totalMb MB ($progress%)"
                        }
                    }

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                        }
                    }
                }
                cursor.close()
                if (isDownloading) Thread.sleep(500)
            }
        }
        progressThread.start()

        // Đăng ký BroadcastReceiver nhận sự kiện tải xong
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId == downloadId) {
                    progressDialog.third.dismiss()
                    unregisterReceiver(this)
                    downloadReceiver = null

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(apkFile)
                        } else {
                            Toast.makeText(this@MainActivity, "Tải về thất bại!", Toast.LENGTH_LONG).show()
                        }
                    }
                    cursor.close()
                }
            }
        }
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    /**
     * Hiển thị dialog tiến trình tải APK.
     * Trả về Pair<ProgressBar, TextView, AlertDialog>
     */
    private fun showDownloadProgressDialog(): Triple<ProgressBar, TextView, AlertDialog> {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val textView = TextView(this).apply {
            text = "Đang chuẩn bị tải..."
            setPadding(0, 16, 0, 0)
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            addView(progressBar)
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("⬇️ Đang tải bản cập nhật...")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        return Triple(progressBar, textView, dialog)
    }

    /**
     * Cài đặt APK đã tải về thông qua FileProvider (Android 7+)
     */
    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Lỗi cài APK: ${e.message}", e)
            Toast.makeText(this, "Không thể cài đặt APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        // Huỷ đăng ký download receiver nếu còn
        downloadReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
                mutableListOf(
                                android.Manifest.permission.CAMERA,
                                android.Manifest.permission.RECORD_AUDIO
                        )
                        .apply {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                        .toTypedArray()
    }
}
