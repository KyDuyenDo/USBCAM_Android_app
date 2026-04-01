package com.example.usbcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: com.example.usbcam.viewmodel.MainViewModel

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

        // 6. Initial Ping (Heartbeat Online)
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
                mutableListOf(
                                android.Manifest.permission.CAMERA,
                                android.Manifest.permission.RECORD_AUDIO
                        )
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P
                            ) {
                                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                        .toTypedArray()
    }
}
