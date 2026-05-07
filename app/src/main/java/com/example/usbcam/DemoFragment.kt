package com.example.usbcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.usbcam.api.PoApiService
import com.example.usbcam.databinding.LayoutDashboardBinding
import com.example.usbcam.viewmodel.MainViewModel
import com.example.usbcam.viewmodel.MainViewModelFactory
import com.example.usbcam.viewmodel.TimeSlotAdapter
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.launch
class DemoFragment : CameraFragment(), IPreviewDataCallBack {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(requireActivity().application)
    }

    private val rfidViewModel: com.example.usbcam.viewmodel.RfidViewModel by viewModels {
        com.example.usbcam.viewmodel.RfidViewModelFactory(requireActivity().application)
    }

    private var mViewBinding: LayoutDashboardBinding? = null
    private lateinit var boxProcessor: BoxProcessor

    // ─── Threading ────────────────────────────────────────────────────────────────
    private val frameQueue = ArrayBlockingQueue<ByteArray>(3)
    private var frameWidth  = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    // ─── Camera Watchdog ──────────────────────────────────────────────────────────
    @Volatile private var lastFrameReceivedMs  = 0L
    @Volatile private var isCameraReconnecting = false

    // [BUG-6] Backoff counter cho camera reconnect
    @Volatile private var cameraReconnectFailCount = 0
    private val MAX_CAMERA_RECONNECT_ATTEMPTS = 5

    private val cameraWatchdogHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val cameraReconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val cameraWatchdogRunnable = object : Runnable {
        override fun run() {
            checkCameraFrameTimeout()
            cameraWatchdogHandler.postDelayed(this, Config.CAMERA_WATCHDOG_INTERVAL_MS)
        }
    }

    // ─── RFID Direct Connection ───────────────────────────────────────────────────
    private val RFID_VENDOR_ID   = 0x483
    private val RFID_PRODUCT_ID  = 0x5750
    private val ACTION_USB_PERMISSION = "com.example.usbcam.USB_PERMISSION_RFID"

    private lateinit var usbManager: UsbManager
    private var rfidUsbDevice: UsbDevice? = null
    @Volatile private var rfidConnected    = false
    @Volatile private var rfidReconnecting = false
    private var rfidReconnectFailCount = 0

    private val rfidWatchdogHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val rfidReconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val usbRfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device  = getUsbDeviceFromIntent(intent) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.i(TAG, "✅ USB Permission granted: ${device.deviceName}")
                        doConnectRfid(device)
                    } else {
                        Log.w(TAG, "❌ USB Permission denied for: ${device.deviceName}")
                        // [BUG-3 FIX] Nếu permission bị từ chối, reset flag để watchdog có thể retry
                        rfidReconnecting = false
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDeviceFromIntent(intent) ?: return
                    Log.i(TAG, "🔌 USB Attached: ${device.deviceName} VID=${device.vendorId} PID=${device.productId}")
                    if (isRfidDevice(device)) {
                        Log.i(TAG, "📡 RFID device detected on attach — connecting...")
                        rfidReconnecting = false
                        rfidReconnectHandler.removeCallbacksAndMessages(null)
                        requestRfidPermissionAndConnect(device)
                    } else {
                        Log.d(TAG, "Non-RFID device attached, checking hub for RFID...")
                        scanAndConnectRfidInHub()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDeviceFromIntent(intent) ?: return
                    if (rfidUsbDevice != null &&
                        device.vendorId  == rfidUsbDevice?.vendorId &&
                        device.productId == rfidUsbDevice?.productId) {
                        Log.w(TAG, "🔌 RFID device detached")
                        onRfidDisconnected()
                    }
                }
            }
        }
    }
    private var usbReceiverRegistered = false

    private var toneGen:  android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator?         = null
    private var lastState: AppState = AppState.RESETTING

    private var lastImageLoadingUrl: String? = null
    private val apiService   = PoApiService.create()
    private var isApiCalling = false

    private var targetQuantity      = 0
    private var totalProcessedCount = 0
    private var currentApiResponse: com.example.usbcam.api.PoResponse? = null

    private var rfidScanningStarted = false
    private val verificationTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ─── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        boxProcessor = BoxProcessor()
        Log.i(TAG, "BoxProcessor initialized")
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (mViewBinding == null) {
            mViewBinding = LayoutDashboardBinding.inflate(inflater, container, false)
        }
        return mViewBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = TimeSlotAdapter()
        mViewBinding?.recyclerTimeSlot?.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter  = adapter
        }

        viewModel.timeSlotList.observe(viewLifecycleOwner) { list -> adapter.submitList(list) }
        viewModel.totalTarget.observe(viewLifecycleOwner)  { total -> targetQuantity = total }
        viewModel.totalScan.observe(viewLifecycleOwner)    { total ->
            if (total != null) totalProcessedCount = total
        }

        viewModel.loadTotal()
        viewModel.loadTarget()
        viewModel.loadAllTimeSlots()
        viewModel.initSelectedLine(requireContext())
        setupLineSelector()

        viewModel.selectedLine.observe(viewLifecycleOwner) { line ->
            mViewBinding?.tvLineSelector?.text = line ?: com.example.usbcam.utils.LinePreferences.DEFAULT_LINE_LABEL
        }

        com.example.usbcam.utils.NetworkConnectionMonitor(requireContext())
            .observe(viewLifecycleOwner) { isConnected ->
                mViewBinding?.tvNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE
            }

        viewModel.usbNotification.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearUsbNotification()
            }
        }

        // Theo dõi tiến trình tải cache dữ liệu từ BoxInfoCacheWorker
        androidx.work.WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(com.example.usbcam.worker.BoxInfoCacheWorker.WORK_NAME)
            .observe(viewLifecycleOwner) { workInfos ->
                val workInfo = workInfos?.firstOrNull()
                if (workInfo != null && workInfo.state == androidx.work.WorkInfo.State.RUNNING) {
                    val progress = workInfo.progress.getInt("progress", 0)
                    val total = workInfo.progress.getInt("total", 0)
                    mViewBinding?.llCacheLoading?.visibility = View.VISIBLE
                    mViewBinding?.tvCacheProgress?.text = "Đang tải dữ liệu: $progress / $total"
                } else {
                    mViewBinding?.llCacheLoading?.visibility = View.GONE
                }
            }

        // [BUG-1 FIX] Trì hoãn setupRfidDirect() bằng view.post{} để CameraFragment
        // (base class) kịp hoàn tất khởi tạo USB stack trước khi scan device list.
        view.post {
            if (isAdded && !isDetached) {
                setupRfidDirect()
            }
        }
        rfidViewModel.initBoxProcessor(boxProcessor)

        // ── Report Button ──────────────────────────────────────────────────────
        mViewBinding?.btnOpenReport?.setOnClickListener {
            val currentLine = viewModel.selectedLine.value
                ?: com.example.usbcam.utils.LinePreferences.getSelectedLine(requireContext())
            ReportDialogFragment.newInstance(currentLine ?: "")
                .show(parentFragmentManager, "ReportDialog")
        }

        // ── Input Production Button ────────────────────────────────────────────
        mViewBinding?.btnInputProduction?.setOnClickListener {
            SearchRyDialogFragment().show(parentFragmentManager, "SearchRyDialog")
        }

        // Check if production line is configured, if not, force selection
        view.post {
            if (isAdded && !com.example.usbcam.utils.LinePreferences.isConfigured(requireContext())) {
                val dialog = LineSelectionDialogFragment()
                dialog.isCancelable = false
                dialog.onLineSelected = { line ->
                    viewModel.setSelectedLine(line)
                }
                dialog.show(parentFragmentManager, "LineSelectionDialog")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraWatchdog()
        cameraReconnectHandler.removeCallbacksAndMessages(null)
        stopProcessingThread()
        boxProcessor.release()
        toneGen?.release()
        Log.d(TAG, "DemoFragment destroyed")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mViewBinding = null
        verificationTimeoutHandler.removeCallbacksAndMessages(null)
        stopRfidWatchdog()  // [BUG-3 FIX] bên trong sẽ reset rfidReconnecting=false

        if (rfidConnected) {
            com.example.usbcam.rfid.RfidConnectionManager
                .getInstance(requireContext()).let {
                    it.forceStopScanning()
                    it.setEventCallback(null)
                }
        }

        if (usbReceiverRegistered) {
            try { requireContext().unregisterReceiver(usbRfidReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }

        isCameraReconnecting = false
    }

    // ─── Line Selector ─────────────────────────────────────────────────────────────

    private fun setupLineSelector() {
        mViewBinding?.tvLineSelector?.setOnClickListener {
            val dialog = LineSelectionDialogFragment()
            dialog.onLineSelected = { selectedLine ->
                Log.d(TAG, "Line changed via dialog -> $selectedLine")
                viewModel.setSelectedLine(selectedLine)
            }
            dialog.show(parentFragmentManager, "LineSelectionDialog")
        }
    }

    // ─── Camera ───────────────────────────────────────────────────────────────────

    override fun getCameraView(): IAspectRatio? = mViewBinding?.tvCameraRender
    override fun getCameraViewContainer(): ViewGroup? = null
    override fun getGravity(): Int = Gravity.TOP

    private var cameraInstance: com.jiangdg.ausbc.MultiCameraClient.ICamera? = null

    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "✅ Camera opened")
                cameraInstance       = self
                isCameraReconnecting = false
                // [BUG-5 FIX] lastFrameReceivedMs reset tại đây (sau khi mở thành công),
                // không reset sớm trong checkCameraFrameTimeout() trước khi openCamera() xong.
                lastFrameReceivedMs  = System.currentTimeMillis()
                // [BUG-6 FIX] Reset backoff counter khi mở thành công
                cameraReconnectFailCount = 0
                addPreviewDataCallBack(this)
                startProcessingThread()
                startCameraWatchdog()
                com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = true
                com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
//                self.setAutoFocus(false)
//                self.setFocus(450)
//                Log.i(TAG, "Camera focus fixed to 450")
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "Camera closed (reconnecting=$isCameraReconnecting)")
                cameraInstance = null
                removePreviewDataCallBack(this)
                stopProcessingThread()
                com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = false

                if (!isCameraReconnecting) {
                    stopCameraWatchdog()
                }
            }
            else -> {}
        }
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setRawPreviewData(true)
            .create()
    }

    override fun onPreviewData(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        if (data == null || !isAdded) return
        lastFrameReceivedMs = System.currentTimeMillis()
        com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = true

        if (frameWidth != width || frameHeight != height) {
            frameWidth  = width
            frameHeight = height
            Log.i(TAG, "Frame size: ${width}x${height}, format=$format, bytes=${data.size}")
        }

        if (!frameQueue.offer(data)) {
            frameQueue.poll()
            frameQueue.offer(data)
        }
    }

    // ─── Camera Watchdog ──────────────────────────────────────────────────────────

    private fun startCameraWatchdog() {
        cameraWatchdogHandler.removeCallbacks(cameraWatchdogRunnable)
        cameraWatchdogHandler.postDelayed(cameraWatchdogRunnable, Config.CAMERA_WATCHDOG_INTERVAL_MS)
        Log.d(TAG, "Camera watchdog started")
    }

    private fun stopCameraWatchdog() {
        cameraWatchdogHandler.removeCallbacks(cameraWatchdogRunnable)
        Log.d(TAG, "Camera watchdog stopped")
    }

    /**
     * Kiểm tra timeout frame camera và trigger reconnect nếu cần.
     *
     * [BUG-5 FIX] Thêm isRemoving() vào guard để tránh openCamera() khi Fragment
     *   đang bị pop khỏi backstack (isAdded=true, isDetached=false nhưng isRemoving=true).
     *
     * [BUG-5 FIX] lastFrameReceivedMs KHÔNG reset ở đây nữa — chỉ reset trong
     *   onCameraState(OPENED) sau khi mở thực sự thành công.
     *   Nếu openCamera() ném exception và isCameraReconnecting=false, watchdog vẫn
     *   thấy lastFrameReceivedMs > 0 và sẽ retry đúng cách.
     *
     * [BUG-6 FIX] Exponential backoff cho cameraReconnectDelay; notify user sau
     *   MAX_CAMERA_RECONNECT_ATTEMPTS lần thất bại liên tiếp.
     */
    private fun checkCameraFrameTimeout() {
        if (isCameraReconnecting) return
        if (lastFrameReceivedMs == 0L) return
        if (cameraInstance == null) return

        val elapsed = System.currentTimeMillis() - lastFrameReceivedMs
        if (elapsed < Config.CAMERA_FRAME_TIMEOUT_MS) return

        // [BUG-6] Kiểm tra không vượt quá max retry
        if (cameraReconnectFailCount >= MAX_CAMERA_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "❌ Camera reconnect gave up after $MAX_CAMERA_RECONNECT_ATTEMPTS attempts")
            return
        }

        Log.w(TAG, "⚠️ No camera frame for ${elapsed}ms — triggering reconnect (attempt ${cameraReconnectFailCount + 1})")
        isCameraReconnecting = true
        // [BUG-5 FIX] Không reset lastFrameReceivedMs ở đây. Reset sẽ xảy ra trong onCameraState(OPENED).

        try {
            closeCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera during reconnect", e)
        }

        // [BUG-6] Exponential backoff delay (giống RFID)
        val backoffDelay = minOf(
            Config.CAMERA_RECONNECT_DELAY_MS * (1L shl cameraReconnectFailCount.coerceAtMost(4)),
            30_000L
        )
        Log.i(TAG, "🔄 Camera reconnect scheduled in ${backoffDelay}ms")

        cameraReconnectHandler.removeCallbacksAndMessages(null)
        cameraReconnectHandler.postDelayed({
            // [BUG-5 FIX] Thêm isRemoving() vào guard
            if (!isAdded || isDetached || isRemoving) {
                Log.w(TAG, "Fragment not active — aborting camera reconnect")
                isCameraReconnecting = false
                return@postDelayed
            }
            Log.i(TAG, "🔄 Re-opening camera after frame timeout")
            try {
                openCamera()
                // isCameraReconnecting và lastFrameReceivedMs reset tại onCameraState(OPENED)
                // cameraReconnectFailCount reset tại onCameraState(OPENED)
            } catch (e: Exception) {
                Log.e(TAG, "Error re-opening camera (attempt ${cameraReconnectFailCount + 1})", e)
                cameraReconnectFailCount++
                isCameraReconnecting = false
                // [BUG-6 FIX] Report status và notify user nếu đã vượt ngưỡng
                com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = false
                com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
                if (cameraReconnectFailCount >= MAX_CAMERA_RECONNECT_ATTEMPTS) {
                    activity?.runOnUiThread {
                        if (isAdded) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "⚠️ Camera không phản hồi sau $MAX_CAMERA_RECONNECT_ATTEMPTS lần thử. Vui lòng kiểm tra kết nối.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }, backoffDelay)
    }

    // ─── Processing Thread ────────────────────────────────────────────────────────

    private fun startProcessingThread() {
        stopProcessingThread()
        isProcessingThreadRunning = true
        processingThread = Thread {
            while (isProcessingThreadRunning) {
                try {
                    val data = frameQueue.take()
                    processFrame(data)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in processing thread", e)
                }
            }
        }.apply {
            name     = "FrameProcessing"
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        processingThread = null
        frameQueue.clear()
        Log.d(TAG, "Processing thread stopped")
    }

    private fun processFrame(data: ByteArray) {
        if (!isAdded) return
        val startTime = System.currentTimeMillis()
        try {
            boxProcessor.updateLogic(data, frameWidth, frameHeight)
            val duration = System.currentTimeMillis() - startTime
            if (duration > 100) Log.w(TAG, "Slow frame processing: ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error in processing logic", e)
        }
        updateUI()
    }

    // ─── RFID Direct Setup ────────────────────────────────────────────────────────

    /**
     * Khởi tạo RFID: đăng ký receiver và scan hub.
     *
     * [BUG-1 FIX 1] Dùng ContextCompat.registerReceiver với RECEIVER_NOT_EXPORTED
     *   để tương thích Android 13+ (không cần flag thủ công, tránh SecurityException).
     *
     * [BUG-1 FIX 2] scanAndConnectRfidInHub() đã được trì hoãn bằng view.post{}
     *   trong onViewCreated() — method này chỉ gọi lần đầu, không scan lại ở đây.
     */
    private fun setupRfidDirect() {
        usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        setupRfidObservers()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // [BUG-1 FIX] Android 13+ yêu cầu flag RECEIVER_NOT_EXPORTED hoặc RECEIVER_EXPORTED.
        // Dùng androidx.core để xử lý backward-compat tự động.
        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(),
            usbRfidReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbReceiverRegistered = true

        // [BUG-1 FIX] Scan ngay sau khi receiver đã đăng ký xong
        scanAndConnectRfidInHub()
        startRfidWatchdog()

        mViewBinding?.btnUsbRfid?.setOnClickListener {
            com.example.usbcam.rfid.RfidSettingsFragment.newInstance()
                .show(parentFragmentManager, "RfidSettingsDialog")
        }
    }

    private fun isRfidDevice(device: UsbDevice): Boolean =
        device.vendorId == RFID_VENDOR_ID && device.productId == RFID_PRODUCT_ID

    private fun scanAndConnectRfidInHub() {
        if (rfidConnected || rfidReconnecting) return
        val allDevices = usbManager.deviceList.values.toList()
        Log.d(TAG, "🔍 Scanning USB hub: ${allDevices.size} device(s)")
        allDevices.forEachIndexed { i, dev ->
            Log.d(TAG, "  [$i] ${dev.deviceName} VID=0x${dev.vendorId.toString(16)} PID=0x${dev.productId.toString(16)}")
        }
        val rfidDevice = allDevices.firstOrNull { isRfidDevice(it) }
        if (rfidDevice != null) {
            Log.i(TAG, "📡 Found RFID reader: ${rfidDevice.deviceName}")
            requestRfidPermissionAndConnect(rfidDevice)
        } else {
            Log.w(TAG, "⚠️ RFID reader not found in hub")
        }
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun requestRfidPermissionAndConnect(device: UsbDevice) {
        if (rfidConnected) return
        if (usbManager.hasPermission(device)) {
            doConnectRfid(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi    = PendingIntent.getBroadcast(requireContext(), 0, Intent(ACTION_USB_PERMISSION), flags)
            Log.i(TAG, "🔐 Requesting USB permission...")
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Thực hiện kết nối RFID sau khi đã có USB permission.
     *
     * [BUG-2 FIX] Xoá nhánh else optimistic (set rfidConnected=true trực tiếp
     *   khi mgr.isConnected=true). Thay bằng: luôn gọi connectManually() và để
     *   callback onConnected() là nguồn duy nhất xác nhận kết nối thành công.
     *   Điều này đảm bảo rfidConnected=true chỉ khi hardware thực sự handshake xong.
     */
    private fun doConnectRfid(device: UsbDevice) {
        rfidUsbDevice    = device
        rfidReconnecting = false
        Log.i(TAG, "🔌 Connecting to RFID: ${device.deviceName}")

        val mgr = com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())
        mgr.setEventCallback(object : com.example.usbcam.rfid.RfidConnectionManager.RfidEventCallback {
            override fun onConnected(isAutoConnect: Boolean) {
                activity?.runOnUiThread {
                    rfidConnected          = true
                    rfidReconnectFailCount = 0
                    rfidReconnecting       = false
                    Log.i(TAG, "✅ RFID Connected")
                    android.widget.Toast.makeText(requireContext(), "RFID Connected ✅", android.widget.Toast.LENGTH_SHORT).show()
                    rfidViewModel.setConnected(true)
                    com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
                    resumeRfidScanIfNeeded()
                }
            }
            override fun onDisconnected() { activity?.runOnUiThread { onRfidDisconnected() } }
            override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
                activity?.runOnUiThread {
                    Log.i(TAG, "Tag Read: $epc (RSSI:$rssi)")
                    rfidViewModel.onTagRead(epc, rssi, antenna, channel)
                }
            }
            override fun onError(message: String)  { Log.e(TAG, "RFID Error: $message") }
            override fun onAutoConnectFailed()     { Log.w(TAG, "RFID auto-connect failed") }
        })

        // [BUG-2 FIX] Luôn gọi connectManually() bất kể mgr.isConnected.
        // Không set rfidConnected=true ở đây — chỉ set trong callback onConnected().
        // mgr.isConnected là trạng thái cached của Manager, không phải hardware thực tế.
        mgr.connectManually(device.productId, device.vendorId)
    }

    private fun onRfidDisconnected() {
        rfidConnected       = false
        // [BUG-4c FIX] Reset rfidScanningStarted khi disconnected
        rfidScanningStarted = false
        rfidViewModel.setConnected(false)
        verificationTimeoutHandler.removeCallbacksAndMessages(null)
        com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
        Log.w(TAG, "⚠️ RFID disconnected — scheduling reconnect")
        scheduleRfidReconnect()
    }

    /**
     * Lên lịch reconnect RFID với exponential backoff.
     *
     * [BUG-3 FIX] Mọi nhánh thoát sớm đều reset rfidReconnecting=false để
     *   watchdog có thể kích hoạt lại lần sau. Bản gốc chỉ reset ở một số nhánh,
     *   dẫn đến flag bị kẹt = true vĩnh viễn.
     */
    private fun scheduleRfidReconnect() {
        if (rfidReconnecting || rfidConnected) return
        rfidReconnecting = true
        rfidReconnectHandler.removeCallbacksAndMessages(null)

        val delayMs = minOf(3000L * (1L shl rfidReconnectFailCount.coerceAtMost(4)), 30000L)
        Log.i(TAG, "🔄 RFID reconnect in ${delayMs}ms (fail#${rfidReconnectFailCount + 1})")

        rfidReconnectHandler.postDelayed({
            // [BUG-3 FIX] Reset trước mọi nhánh return
            if (!isAdded || rfidConnected) {
                rfidReconnecting = false  // ← FIX: reset flag ở nhánh này
                return@postDelayed
            }
            try { com.example.usbcam.rfid.RfidManager.getInstance(requireContext()).softReset() } catch (_: Exception) {}
            rfidReconnectHandler.postDelayed({
                if (!isAdded) {
                    rfidReconnecting = false  // ← FIX: reset flag ở nhánh này
                    return@postDelayed
                }
                val device = usbManager.deviceList.values.firstOrNull { isRfidDevice(it) }
                if (device != null) {
                    Log.i(TAG, "Found RFID in hub, reconnecting: ${device.deviceName}")
                    // rfidReconnecting sẽ được reset trong doConnectRfid() → onConnected()
                    requestRfidPermissionAndConnect(device)
                } else {
                    rfidReconnectFailCount++
                    rfidReconnecting = false  // ← FIX: reset ở nhánh không tìm thấy device
                    Log.w(TAG, "⚠️ RFID not found (fail#$rfidReconnectFailCount)")
                }
            }, 300L)
        }, delayMs)
    }

    private fun startRfidWatchdog() {
        rfidWatchdogHandler.removeCallbacksAndMessages(null)
        rfidWatchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isAdded && !rfidConnected && !rfidReconnecting) {
                    Log.w(TAG, "🐕 RFID watchdog — triggering reconnect")
                    scheduleRfidReconnect()
                }
                rfidWatchdogHandler.postDelayed(this, 5000L)
            }
        }, 5000L)
    }

    /**
     * Dừng RFID watchdog và huỷ reconnect handler.
     *
     * [BUG-3 FIX] Reset rfidReconnecting=false tại đây.
     *   Bản gốc huỷ rfidReconnectHandler (xoá Runnable) nhưng không reset flag,
     *   nên watchdog lần sau vẫn thấy rfidReconnecting=true và skip.
     */
    private fun stopRfidWatchdog() {
        rfidWatchdogHandler.removeCallbacksAndMessages(null)
        rfidReconnectHandler.removeCallbacksAndMessages(null)
        rfidReconnecting = false  // [BUG-3 FIX]
    }

    /**
     * Resume RFID scan nếu state machine đang ở SCANNING hoặc VERIFYING
     * và RFID vừa reconnect xong.
     *
     * [BUG-4b FIX] Chỉ gọi clearRfidData() khi chưa có dữ liệu hợp lệ,
     *   tránh xoá dữ liệu scan hiện tại khi reconnect giữa chừng.
     */
    private fun resumeRfidScanIfNeeded() {
        val state = boxProcessor.currentState
        if (!rfidScanningStarted && (state == AppState.SCANNING || state == AppState.VERIFYING)) {
            Log.i(TAG, "RFID reconnected during $state — resuming scan")
            rfidScanningStarted = true
            // [BUG-4b FIX] Chỉ clear nếu chưa có EPC data — không xoá dữ liệu đang có
            if (rfidViewModel.lastEpc.value.isNullOrEmpty()) {
                rfidViewModel.clearRfidData()
            }
            val rfidManager = com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())
            if (!rfidManager.isScanning) {
                rfidManager.startScanning()
            }
            rfidViewModel.setScanning(true)

            if (state == AppState.VERIFYING) {
                val resp = currentApiResponse; val po = boxProcessor.po; val barcode = boxProcessor.barcode
                if (resp != null && po != null && barcode != null) {
                    verificationTimeoutHandler.removeCallbacksAndMessages(null)
                    verificationTimeoutHandler.postDelayed({
                        if (isAdded && rfidScanningStarted) {
                            val bestEpc = rfidViewModel.processAndGetBestEpc()
                            Log.i(TAG, "RFID timeout (reconnect) — best EPC: $bestEpc")
                            completeRfidVerification(po, barcode, resp, bestEpc)
                            rfidScanningStarted = false
                        }
                    }, Config.RFID_SCAN_WINDOW_MS)
                }
            }
        }
    }

    // ─── RFID Observers ───────────────────────────────────────────────────────────

    private fun setupRfidObservers() {
        rfidViewModel.connectionStatus.observe(viewLifecycleOwner) { mViewBinding?.tvRfidStatus?.text = it }

        rfidViewModel.lastEpc.observe(viewLifecycleOwner) { epc ->
            mViewBinding?.tvDashboardLastRfid?.text = if (epc.isNotEmpty()) epc else "---"
            mViewBinding?.ivRfidIcon?.alpha          = if (epc.isNotEmpty()) 1.0f else 0.3f
        }

        rfidViewModel.rfidData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                mViewBinding?.apply {
                    llRfidProductInfo.visibility = View.VISIBLE
                    tvRfidModel.text   = data.model
                    tvRfidArticle.text = data.article
                    tvRfidPo.text      = data.po
                    tvRfidUpc.text     = data.barcode
                    tvRfidSize.text    = data.size
                }
            } else {
                mViewBinding?.llRfidProductInfo?.visibility = View.GONE
            }
        }

        rfidViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) { android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_SHORT).show(); rfidViewModel.clearError() }
        }

        rfidViewModel.infoMessage.observe(viewLifecycleOwner) { info ->
            if (info != null) { android.widget.Toast.makeText(requireContext(), info, android.widget.Toast.LENGTH_SHORT).show(); rfidViewModel.clearInfo() }
        }

        viewModel.validationResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                when (result) {
                    is com.example.usbcam.data.model.ValidationResult.Success -> {
                        android.widget.Toast.makeText(requireContext(), result.message,
                            if (result.isMatch) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG).show()
                        rfidViewModel.updateFromValidationResult(result)
                    }
                    is com.example.usbcam.data.model.ValidationResult.Error ->
                        android.widget.Toast.makeText(requireContext(), result.message, android.widget.Toast.LENGTH_LONG).show()
                }
                viewModel.clearValidationResult()
            }
        }

        rfidViewModel.isPoMatch.observe(viewLifecycleOwner)   { updateFieldColor(mViewBinding?.tvRfidPo,      it); updateFieldColor(mViewBinding?.tvPoValue,  it) }
        rfidViewModel.isArtMatch.observe(viewLifecycleOwner)  { updateFieldColor(mViewBinding?.tvRfidArticle, it); updateFieldColor(mViewBinding?.tvArt,       it) }
        rfidViewModel.isSizeMatch.observe(viewLifecycleOwner) { updateFieldColor(mViewBinding?.tvRfidSize,    it); updateFieldColor(mViewBinding?.tvSizeValue, it) }
        rfidViewModel.isUpcMatch.observe(viewLifecycleOwner)  { updateFieldColor(mViewBinding?.tvRfidUpc,     it); updateFieldColor(mViewBinding?.tvUpcValue,  it) }
    }

    private fun updateFieldColor(textView: android.widget.TextView?, isMatch: Boolean) {
        textView?.setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(),
                if (isMatch) R.color.text_primary else R.color.error_red)
        )
    }

    // ─── UI Update ────────────────────────────────────────────────────────────────

    private fun updateTextView(tv: TextView, newText: String) {
        if (tv.text.toString() != newText) tv.text = newText
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            val state = boxProcessor.currentState

            if (state == AppState.IDLE && state != lastState) {
                // [BUG-4a FIX] Chỉ reset khi MỚI chuyển vào IDLE, không phải mỗi tick
                currentApiResponse = null
                rfidViewModel.setCurrentCameraResponse(null)
                Log.d(TAG, "IDLE → Reset RFID state")
                rfidScanningStarted = false
                verificationTimeoutHandler.removeCallbacksAndMessages(null)
                rfidViewModel.clearRfidData()
            }

            // [BUG-4c FIX] Reset flag trong RESETTING để tránh kẹt sau API failure
            if (state == AppState.RESETTING && state != lastState) {
                rfidScanningStarted = false
                verificationTimeoutHandler.removeCallbacksAndMessages(null)
            }

            if (state == AppState.SCANNING && lastState != AppState.SCANNING) {
                if (rfidConnected && !rfidScanningStarted) {
                    Log.i(TAG, "SCANNING → Starting RFID scan")
                    rfidScanningStarted = true
                    // [BUG-4a FIX] clearRfidData() ở đây là đúng — mới bắt đầu scan mới
                    rfidViewModel.clearRfidData()
                    val rfidManager = com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())
                    if (!rfidManager.isScanning) {
                        rfidManager.startScanning()
                    }
                    rfidViewModel.setScanning(true)
                }
            }

            if (state != lastState) {
                handleStateFeedback(state)
                lastState = state
            }

            mViewBinding?.let { binding ->
                binding.pbMotion.visibility = if (state == AppState.SCANNING) View.VISIBLE else View.GONE

                updateTextView(binding.tvStatusOk, when (state) {
                    AppState.IDLE      -> "READY"
                    AppState.SCANNING  -> "SCANNING..."
                    AppState.VERIFYING -> "VERIFYING..."
                    AppState.DECODED   -> "OKE"
                    AppState.RESETTING -> "RESET"
                })

                val colorInt = when (state) {
                    AppState.SCANNING  -> androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary_blue)
                    AppState.VERIFYING -> androidx.core.content.ContextCompat.getColor(requireContext(), R.color.orange_warning)
                    AppState.DECODED   -> androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green)
                    else               -> androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary)
                }
                binding.tvStatusOk.backgroundTintList = android.content.res.ColorStateList.valueOf(colorInt)
                binding.tvStatusOk.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.white))

                updateTextView(binding.tvUpcValue,    boxProcessor.barcode ?: "--")
                updateTextView(binding.tvPoValue,     boxProcessor.po ?: "--")
                updateTextView(binding.tvTotalActual, "$totalProcessedCount")

                val resp = currentApiResponse
                updateTextView(binding.tvQtyValue,    "${resp?.quantity ?: ""}")
                updateTextView(binding.tvRyValue,     "${resp?.ry ?: ""}")
                updateTextView(binding.tvSizeValue,   "${resp?.size ?: ""}")
                updateTextView(binding.tvArt,         "${resp?.article ?: "--"}")
                updateTextView(binding.tvRemaining,   "${resp?.remainInternal ?: ""}")
                updateTextView(binding.tvCompleted,   "${resp?.doneInternal ?: ""}")
                updateTextView(binding.tvCountry,     "${resp?.country ?: ""}")
                updateTextView(binding.tvLean,        "${resp?.lean ?: ""}")
                updateTextView(binding.tvTotalOrder,  "${resp?.qtyOrder ?: ""}")
                updateTextView(binding.tvTotalTarget, "$targetQuantity")

                if (resp?.articleImage != null) {
                    val newUrl = "http://192.168.30.19:5000/shoes-photos/${resp.articleImage}"
                    if (newUrl != lastImageLoadingUrl) {
                        lastImageLoadingUrl = newUrl
                        Glide.with(this).load(newUrl).into(binding.ivShoeImage)
                    }
                } else if (lastImageLoadingUrl != null) {
                    lastImageLoadingUrl = null
                    binding.ivShoeImage.setImageDrawable(null)
                }

                if (state == AppState.VERIFYING && !isApiCalling && currentApiResponse == null) callApi()
            }
        }
    }

    // ─── Verification Flow ────────────────────────────────────────────────────────

    private fun finalizeVerification(po: String, barcode: String, data: com.example.usbcam.api.PoResponse) {
        if (!isAdded) return
        currentApiResponse = data
        rfidViewModel.setCurrentCameraResponse(data)

        if (rfidConnected) {
            if (!rfidScanningStarted) {
                Log.i(TAG, "API ready → Starting RFID scan")
                rfidScanningStarted = true
                rfidViewModel.clearRfidData()
                val rfidManager = com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())
                if (!rfidManager.isScanning) {
                    rfidManager.startScanning()
                }
                rfidViewModel.setScanning(true)
            }
            verificationTimeoutHandler.removeCallbacksAndMessages(null)
            verificationTimeoutHandler.postDelayed({
                if (isAdded && rfidScanningStarted) {
                    val bestEpc = rfidViewModel.processAndGetBestEpc()
                    Log.i(TAG, "RFID Timeout → best EPC: $bestEpc")
                    completeRfidVerification(po, barcode, data, bestEpc)
                    rfidScanningStarted = false
                }
            }, Config.RFID_SCAN_WINDOW_MS)
        } else {
            viewModel.saveScanData(po, barcode, data, emptySet())
            boxProcessor.onApiVerification(true)
        }
    }

    private fun completeRfidVerification(po: String, barcode: String, data: com.example.usbcam.api.PoResponse, epc: String?) {
        viewModel.saveScanData(po, barcode, data, if (epc.isNullOrEmpty()) emptySet() else setOf(epc))
        boxProcessor.onApiVerification(true)
    }

    private fun callApi() {
        val barcode = boxProcessor.barcode ?: return
        val po      = boxProcessor.po      ?: return
        isApiCalling = true
        apiService.getPoDetails(po, barcode).enqueue(object : retrofit2.Callback<com.example.usbcam.api.PoResponse> {
            override fun onResponse(call: retrofit2.Call<com.example.usbcam.api.PoResponse>, response: retrofit2.Response<com.example.usbcam.api.PoResponse>) {
                isApiCalling = false
                if (response.isSuccessful && response.body() != null) finalizeVerification(po, barcode, response.body()!!)
                else fallbackToLocal(po, barcode)
            }
            override fun onFailure(call: retrofit2.Call<com.example.usbcam.api.PoResponse>, t: Throwable) {
                isApiCalling = false
                Log.e(TAG, "API Failure: ${t.message}")
                fallbackToLocal(po, barcode)
            }
        })
    }

    private fun fallbackToLocal(po: String, barcode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val localData = viewModel.getLocalData(po, barcode)
            if (localData != null) {
                Log.d(TAG, "Fallback: loaded local data")
                viewModel.loadAllTimeSlots(); viewModel.loadTotal()
                finalizeVerification(po, barcode, localData)
            } else {
                Log.e(TAG, "Fallback: no local data")
                // [BUG-4c FIX] Reset rfidScanningStarted khi verification thất bại hoàn toàn
                rfidScanningStarted = false
                boxProcessor.onApiVerification(false)
            }
        }
    }

    private fun handleStateFeedback(newState: AppState) {
        if (toneGen  == null) toneGen  = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, Config.BEEP_VOLUME)
        if (vibrator == null) vibrator = activity?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        when (newState) {
            AppState.VERIFYING -> toneGen?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            AppState.DECODED   -> toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            else -> {}
        }
    }

    companion object {
        private const val TAG = "BoxScanner"
    }
}