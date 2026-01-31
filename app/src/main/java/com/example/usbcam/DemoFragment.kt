package com.example.usbcam

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class DemoFragment : CameraFragment(), IPreviewDataCallBack {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(requireActivity().application)
    }

    private val rfidViewModel: com.example.usbcam.viewmodel.RfidViewModel by viewModels()

    private var mViewBinding: LayoutDashboardBinding? = null
    private lateinit var boxProcessor: BoxProcessor

    // OPTIMIZATION 1: Reuse MatOfByte buffer (avoid allocation per frame)
    // Lazy initialization after OpenCV loads
    private lateinit var mMatOfByte: MatOfByte

    //  OPTIMIZATION 2: Pre-allocate all Mats once
    private lateinit var mBgr: Mat // Decoded BGR from MJPEG
    private lateinit var mRgba: Mat // RGBA for display/bitmap
    private lateinit var mGray: Mat // Grayscale for processing
    private lateinit var mBoosted: Mat // Brightness boosted version

    // YUV fallback (only if MJPEG fails)
    private var mYuv: Mat? = null
    private var isUsingYuvFallback = false

    // Threading
    private val frameQueue = ArrayBlockingQueue<ByteArray>(3)
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    // Timing
    private var lastProcessTime = 0L

    private var toneGen: android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator? = null
    private var lastState = AppState.IDLE

    // Cache
    private var lastImageLoadingUrl: String? = null

    private val apiService = PoApiService.create()
    private var isApiCalling = false

    // USB Camera Management
    private var lastFrameTime = 0L
    private var signalCheckJob: kotlinx.coroutines.Job? = null
    private var countdownJob: kotlinx.coroutines.Job? = null
    private var isSignalLostDialogShowing = false // Prevent duplicate dialogs

    // FIX 1: State guard to prevent race condition during camera shutdown
    @Volatile private var isCameraStopping = false

    // Local Data
    private var targetQuantity = 0
    private var totalProcessedCount = 0
    private var currentApiResponse: com.example.usbcam.api.PoResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")

            // Initialize Mat objects after OpenCV library is loaded
            mMatOfByte = MatOfByte()
            mBgr = Mat()
            mRgba = Mat()
            mGray = Mat()
            mBoosted = Mat()
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
        }
        boxProcessor = BoxProcessor()
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (mViewBinding == null) {
            mViewBinding = LayoutDashboardBinding.inflate(inflater, container, false)
        }
        return mViewBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mViewBinding?.swCamera?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && viewModel.isCameraEnabled.value == false) {
                viewModel.setCameraEnabled(true)
            } else if (!isChecked && viewModel.isCameraEnabled.value == true) {
                viewModel.setCameraEnabled(false)
            }
        }

        val adapter = TimeSlotAdapter()
        mViewBinding?.recyclerTimeSlot?.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }

        viewModel.timeSlotList.observe(viewLifecycleOwner) { list -> adapter.submitList(list) }
        viewModel.targetData.observe(viewLifecycleOwner) { target ->
            if (target != null) targetQuantity = target.quantityTarget
        }
        viewModel.totalScan.observe(viewLifecycleOwner) { total ->
            if (total != null) totalProcessedCount = total
        }

        viewModel.loadTotal()
        viewModel.loadTarget()
        viewModel.loadAllTimeSlots()

        com.example.usbcam.utils.NetworkConnectionMonitor(requireContext()).observe(
                        viewLifecycleOwner
                ) { isConnected ->
            mViewBinding?.tvNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE
        }

        // Camera Management Observers
        viewModel.isCameraEnabled.observe(viewLifecycleOwner) { enabled ->
            mViewBinding?.swCamera?.isChecked = enabled
            mViewBinding?.tvLiveBadge?.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) {
                reopenCamera()
            } else {
                shutdownCamera()
            }
        }

        viewModel.cameraSignalError.observe(viewLifecycleOwner) { error ->
            mViewBinding?.tvCameraError?.apply {
                visibility = if (error != null) View.VISIBLE else View.GONE
                text = error
            }
        }

        viewModel.cameraCountdown.observe(viewLifecycleOwner) { value ->
            mViewBinding?.tvCountdown?.apply {
                if (value != null) {
                    visibility = View.VISIBLE
                    text = value.toString()
                } else {
                    visibility = View.GONE
                }
            }
        }

        viewModel.usbNotification.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                android.widget.Toast.makeText(
                                requireContext(),
                                message,
                                android.widget.Toast.LENGTH_LONG
                        )
                        .show()
                viewModel.clearUsbNotification()
            }
        }

        setupRfidManager()
        rfidViewModel.initBoxProcessor(boxProcessor)
    }

    private lateinit var rfidConnectionManager: com.example.usbcam.rfid.RfidConnectionManager
    private var rfidScanningStarted = false  // Protection flag

    private fun setupRfidManager() {
        rfidConnectionManager = com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())
        
        // Setup observers for RfidViewModel
        setupRfidObservers()
        
        rfidConnectionManager.setEventCallback(object : com.example.usbcam.rfid.RfidConnectionManager.RfidEventCallback {
            override fun onConnected(isAutoConnect: Boolean) {
                activity?.runOnUiThread {
                    android.widget.Toast.makeText(requireContext(), "RFID Connected ✅", android.widget.Toast.LENGTH_SHORT).show()
                    rfidViewModel.setConnected(true)
                    
                    // Start scanning only once after connection
                    if (!rfidScanningStarted) {
                        rfidScanningStarted = true
                        rfidConnectionManager.startScanning()
                        rfidViewModel.setScanning(true)
                    }
                }
            }

            override fun onDisconnected() {
                activity?.runOnUiThread {
                    rfidViewModel.setConnected(false)
                    rfidScanningStarted = false  // Reset flag on disconnect
                }
            }

            override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
                activity?.runOnUiThread {
                    Log.i(TAG, "SDK Tag Read: $epc (RSSI: $rssi)")
                    
                    // Update ViewModel - it will handle API call automatically
                    rfidViewModel.onTagRead(epc, rssi, antenna, channel)
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "RFID Error: $message")
            }

            override fun onAutoConnectFailed() {
                activity?.runOnUiThread {
                    // Requirement 6: Mở màn hình chọn thiết bị khi kết nối thất bại
                    Log.w(TAG, "Auto-connect failed. Opening selection UI.")
                    if (isAdded && !isSignalLostDialogShowing) {
                        val settingsDialog = com.example.usbcam.rfid.RfidSettingsFragment.newInstance()
                        settingsDialog.show(parentFragmentManager, "RfidSettingsDialog")
                    }
                }
            }
        })

        // Initialize and try auto-connect
        rfidConnectionManager.initialize()

        // Setup the RFID Settings icon button
        mViewBinding?.btnUsbRfid?.setOnClickListener {
            val settingsDialog = com.example.usbcam.rfid.RfidSettingsFragment.newInstance()
            settingsDialog.show(parentFragmentManager, "RfidSettingsDialog")
        }
    }

    private fun setupRfidObservers() {
        // Connection status
        rfidViewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            mViewBinding?.tvRfidStatus?.text = status
        }

        // Last EPC
        rfidViewModel.lastEpc.observe(viewLifecycleOwner) { epc ->
            mViewBinding?.tvDashboardLastRfid?.text = if (epc.isNotEmpty()) epc else "---"
            
            // Update icon alpha
            mViewBinding?.ivRfidIcon?.alpha = if (epc.isNotEmpty()) 1.0f else 0.3f
        }

        // RFID Product Data from API
        rfidViewModel.rfidData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                mViewBinding?.apply {
                    llRfidProductInfo.visibility = View.VISIBLE
                    tvRfidModel.text = data.model
                    tvRfidArticle.text = data.article
                    tvRfidPo.text = data.po
                    tvRfidUpc.text = data.barcode
                    tvRfidSize.text = data.size
                }
            } else {
                mViewBinding?.llRfidProductInfo?.visibility = View.GONE
            }
        }

        // Error messages (show as toast)
        rfidViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_SHORT).show()
                rfidViewModel.clearError()
            }
        }

        // Info messages
        rfidViewModel.infoMessage.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                android.widget.Toast.makeText(requireContext(), info, android.widget.Toast.LENGTH_SHORT).show()
                rfidViewModel.clearInfo()
            }
        }
        
        // 🔹 RFID Validation Result Observer
        viewModel.validationResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                when (result) {
                    is com.example.usbcam.data.model.ValidationResult.Success -> {
                        if (result.isMatch) {
                            // Data matched or no RFID validation needed
                            // 🔹 Update last success tracking

                            android.widget.Toast.makeText(
                                requireContext(), 
                                result.message, 
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            
                            // 🔹 Reset RFID after successful save 
                            rfidViewModel.clearRfidData()
                        } else {
                            // Data mismatch - warning
                            android.widget.Toast.makeText(
                                requireContext(), 
                                result.message, 
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    is com.example.usbcam.data.model.ValidationResult.Error -> {
                        // Validation error
                        android.widget.Toast.makeText(
                            requireContext(), 
                            "${result.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                viewModel.clearValidationResult()
            }
        }
        
        // Field match states (Visual feedback: Red text on mismatch)
        rfidViewModel.isPoMatch.observe(viewLifecycleOwner) { isMatch ->
            updateFieldColor(mViewBinding?.tvRfidPo, isMatch)
            updateFieldColor(mViewBinding?.tvPoValue, isMatch)
        }

        rfidViewModel.isArtMatch.observe(viewLifecycleOwner) { isMatch ->
            updateFieldColor(mViewBinding?.tvRfidArticle, isMatch)
            updateFieldColor(mViewBinding?.tvArt, isMatch)
        }

        rfidViewModel.isSizeMatch.observe(viewLifecycleOwner) { isMatch ->
            updateFieldColor(mViewBinding?.tvRfidSize, isMatch)
            updateFieldColor(mViewBinding?.tvSizeValue, isMatch)
        }

        rfidViewModel.isUpcMatch.observe(viewLifecycleOwner){ isMatch ->
            updateFieldColor(mViewBinding?.tvRfidUpc, isMatch)
            updateFieldColor(mViewBinding?.tvUpcValue, isMatch)
        }

    }

    private fun updateFieldColor(textView: android.widget.TextView?, isMatch: Boolean) {
        val colorInt = if (isMatch) {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary)
        } else {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red)
        }
        textView?.setTextColor(colorInt)
    }

    private fun reopenCamera() {
        // FIX 2: Don't reopen camera if fragment is not resumed
        if (!isResumed) {
            Log.w(TAG, "Fragment not resumed, skip reopenCamera")
            return
        }

        Log.d(TAG, "Reopening camera: initiating countdown...")
        shutdownCamera()

        countdownJob =
                viewLifecycleOwner.lifecycleScope.launch {
                    // Wait for shutdown to complete
                    while (isCameraStopping) {
                        kotlinx.coroutines.delay(100)
                    }

                    for (i in 3 downTo 1) {
                        viewModel.setCameraCountdown(i)
                        kotlinx.coroutines.delay(1000)
                    }
                    viewModel.setCameraCountdown(null)

                    Log.d(TAG, "Countdown finished, opening camera...")
                    try {
                        openCamera()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open camera", e)
                    }
                    startSignalDetection()
                }
    }

    private fun shutdownCamera() {
        // FIX 1: Guard to prevent concurrent shutdown race conditions
        if (isCameraStopping) {
            Log.d(TAG, "Camera shutdown already in progress, skipping...")
            return
        }
        isCameraStopping = true

        Log.d(TAG, "Shutting down camera safely...")

        countdownJob?.cancel()
        countdownJob = null
        viewModel.setCameraCountdown(null)
        stopSignalDetection()

        // ❗ CRITICAL: Stop processing thread FIRST. 
        // Use a flag and interrupt, but don't release Mats here as they might still be in use for 1-2ms.
        stopProcessingThread()

        // ❗ CRITICAL: Remove callback BEFORE closing camera.
        removePreviewDataCallBack(this)

        // ❗ CRITICAL: Delay to let native USB thread die completely.
        // Increased to 1000ms for more stability on slower devices.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(1000) // ⭐ Allow native thread cleanup
            try {
                closeCamera()
                Log.d(TAG, "Camera closed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing camera", e)
            } finally {
                isCameraStopping = false
            }
        }

        viewModel.setCameraSignalError(null)
    }

    private fun startSignalDetection() {
        signalCheckJob?.cancel()
        lastFrameTime = System.currentTimeMillis()
        signalCheckJob =
                viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(Config.SIGNAL_DETECTION_INITIAL_DELAY_MS)
                    while (true) {
                        kotlinx.coroutines.delay(Config.SIGNAL_CHECK_INTERVAL_MS)
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime > Config.CAMERA_SIGNAL_TIMEOUT_MS) {
                            viewModel.setCameraSignalError("No Camera Signal - Auto Shutting Down")
                            Log.w(TAG, "Signal lost for too long. Auto shutting down.")

                            // FIX 3: STOP CAMERA IMMEDIATELY when signal lost
                            if (!isCameraStopping) {
                                shutdownCamera()
                            }

                            // Hiển thị popup thông báo
                            activity?.runOnUiThread { showSignalLostDialog() }

                            // Auto-shutdown requirement
                            if (Config.AUTO_DISABLE_ON_SIGNAL_LOSS) {
                                activity?.runOnUiThread {
                                    mViewBinding?.swCamera?.isChecked = false
                                }
                            }
                            
                            // Exit detection loop after shutdown
                            return@launch
                        } else {
                            viewModel.setCameraSignalError(null)
                        }
                    }
                }
    }

    /**
     * Hiển thị popup thông báo khi mất tín hiệu camera. Cho phép user chọn "Thử Lại" hoặc "Đóng".
     */
    private fun showSignalLostDialog() {
        // Prevent multiple dialogs
        if (activity?.isFinishing == true || !isAdded || isSignalLostDialogShowing) return

        isSignalLostDialogShowing = true

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Mất Tín Hiệu Camera")
                .setMessage(
                        "Không nhận được tín hiệu từ USB Camera.\n\n" +
                                "Nguyên nhân có thể:\n" +
                                "• Camera bị rút ra\n" +
                                "• Camera không phản hồi\n" +
                                "• Kết nối USB lỗi\n\n" +
                                "Bạn có muốn thử kết nối lại không?"
                )
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Thử Lại") { dialog, _ ->
                    Log.d(TAG, "User chose to retry camera connection")
                    isSignalLostDialogShowing = false
                    dialog.dismiss()
                    // Thử bật lại camera
                    viewModel.setCameraEnabled(true)
                }
                .setNegativeButton("Đóng") { dialog, _ ->
                    Log.d(TAG, "User dismissed signal lost dialog")
                    isSignalLostDialogShowing = false
                    dialog.dismiss()
                }
                .setOnDismissListener { isSignalLostDialogShowing = false }
                .setCancelable(false) // Force user to choose
                .show()
    }

    private fun stopSignalDetection() {
        signalCheckJob?.cancel()
        signalCheckJob = null
    }

    override fun getCameraView(): IAspectRatio? = mViewBinding?.tvCameraRender
    override fun getCameraViewContainer(): ViewGroup? = null
    override fun getGravity(): Int = Gravity.TOP

    // Camera reference for focus control
    private var cameraInstance: com.jiangdg.ausbc.MultiCameraClient.ICamera? = null

    override fun onCameraState(
            self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                if (viewModel.isCameraEnabled.value == false) {
                    Log.i(
                            TAG,
                            "Camera opened by library auto-connect, but UI is OFF. Shutting down."
                    )
                    shutdownCamera()
                    return
                }
                Log.i(TAG, "Camera opened")
                cameraInstance = self // Store camera reference
                addPreviewDataCallBack(this)
                startProcessingThread()
            }
            ICameraStateCallBack.State.CLOSED -> {
                cameraInstance = null
                removePreviewDataCallBack(this)
                stopProcessingThread()
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
        // FIX 5: Don't process data if fragment is detached or camera is stopping
        if (data == null || !isAdded || isCameraStopping) return
        lastFrameTime = System.currentTimeMillis() // Signal detected

        //OPTIMIZATION 3: Only initialize YUV fallback if needed
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            Log.i(TAG, "Frame size: ${width}x${height}, format=$format, bytes=${data.size}")

            // Pre-allocate YUV fallback buffer (lazy init)
            if (mYuv == null) {
                mYuv = Mat(height + height / 2, width, CvType.CV_8UC1)
            }
        }

        // FIX 4: Prevent queue backlog when USB lags - drop oldest frame if full
        if (!frameQueue.offer(data)) {
            frameQueue.poll()  // Remove oldest
            frameQueue.offer(data)  // Add newest
        }
    }

    private fun startProcessingThread() {
        stopProcessingThread()
        isProcessingThreadRunning = true
        processingThread =
                Thread {
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
                }
                        .apply {
                            name = "FrameProcessing"
                            priority = Thread.NORM_PRIORITY + 1 // Slightly higher priority
                            start()
                        }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        processingThread = null
        frameQueue.clear()

        // ❗ REFINEMENT: Don't release OpenCV Mats immediately here.
        // They are shared objects and the thread might still be in the middle of a processFrame loop.
        // Let onDestroy or a safe lifecycle event handle it, or just let them stay allocated for the next session.
        // Releasing them here while a thread is interrupted often causes illegal state/memory access.
        Log.d(TAG, "Processing thread stopped")
    }

    private fun processFrame(data: ByteArray) {
        // FIX 5: Don't decode if fragment is detached or camera is stopping
        if (!isAdded || isCameraStopping) return

        val now = System.currentTimeMillis()

        // FPS limiter
        if (now - lastProcessTime < (1000L / Config.MAX_PROCESSING_FPS)) return
        lastProcessTime = now

        try {
            // Decode frame
            mMatOfByte.fromArray(*data)
            val decoded = Imgcodecs.imdecode(mMatOfByte, Imgcodecs.IMREAD_COLOR)

            if (decoded.empty()) {
                decoded.release()
                if (!isUsingYuvFallback) {
                    Log.w(TAG, "[FALLBACK] MJPEG decode failed, switching to YUV mode")
                    isUsingYuvFallback = true
                }
                mYuv?.put(0, 0, data)
                Imgproc.cvtColor(mYuv, mRgba, Imgproc.COLOR_YUV2RGBA_NV21)
            } else {
                if (isUsingYuvFallback) {
                    Log.i(TAG, "[RECOVERY] MJPEG decode restored")
                    isUsingYuvFallback = false
                }
                decoded.copyTo(mBgr)
                decoded.release()
                Imgproc.cvtColor(mBgr, mRgba, Imgproc.COLOR_BGR2RGBA)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
            return
        }

        if (mRgba.empty()) return

        // Grayscale conversion
        Imgproc.cvtColor(mRgba, mGray, Imgproc.COLOR_RGBA2GRAY)

        // FIX: Create bitmap và đảm bảo cleanup
        var scanBitmap: Bitmap? = null
        try {
            scanBitmap = createScanBitmap()

            // Call Update Logic
            boxProcessor.updateLogic(mGray, scanBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processing logic", e)
        } finally {
            //CRITICAL: Always recycle bitmap
            scanBitmap?.recycle()
        }

        // Update UI
        updateUI()
    }

    //OPTIMIZATION 7: Smart brightness boost
    private fun createScanBitmap(): Bitmap? {
        return try {
            val srcMat =
                    if (Config.BRIGHTNESS_BOOST > 0) {
                        mRgba.convertTo(mBoosted, -1, 1.0, Config.BRIGHTNESS_BOOST.toDouble())
                        mBoosted
                    } else {
                        mRgba
                    }

            val bmp = Bitmap.createBitmap(srcMat.cols(), srcMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(srcMat, bmp)
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error creating scan bitmap", e)
            null
        }
    }

    private fun updateTextView(tv: TextView, newText: String) {
        if (tv.text.toString() != newText) {
            tv.text = newText
        }
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            val state = boxProcessor.currentState

            if (state == AppState.IDLE) {
                if (currentApiResponse != null) {
                    currentApiResponse = null
                    rfidViewModel.setCurrentCameraResponse(null)
                }
                // 🔹 REQUIREMENT: If no box in view (IDLE), reset RFID data
                // This ensures that "next time" a box comes, the old tag is not used.
                if (rfidViewModel.lastEpc.value?.isNotEmpty() == true) {
                    Log.d(TAG, "System IDLE -> Clearing leftover RFID data")
                    rfidViewModel.clearRfidData()
                }
            }

            if (state != lastState) {
                handleStateFeedback(state)
                lastState = state
            }

            mViewBinding?.let { binding ->
                val isBusy = state == AppState.SCANNING
                binding.pbMotion.visibility = if (isBusy) View.VISIBLE else View.GONE

                val statusText =
                        when (state) {
                            AppState.IDLE -> "READY"
                            AppState.SCANNING -> "SCANNING..."
                            AppState.VERIFYING -> "VERIFYING..."
                            AppState.DECODED -> "SUCCESS"
                            AppState.RESETTING -> "RESET"
                        }
                updateTextView(binding.tvStatusOk, statusText)

                val colorInt =
                        when (state) {
                            AppState.SCANNING ->
                                    androidx.core.content.ContextCompat.getColor(
                                            requireContext(),
                                            R.color.primary_blue
                                    )
                            AppState.VERIFYING ->
                                    androidx.core.content.ContextCompat.getColor(
                                            requireContext(),
                                            R.color.orange_warning
                                    )
                            AppState.DECODED ->
                                    androidx.core.content.ContextCompat.getColor(
                                            requireContext(),
                                            R.color.success_green
                                    )
                            else ->
                                    androidx.core.content.ContextCompat.getColor(
                                            requireContext(),
                                            R.color.text_secondary
                                    )
                        }

                if (binding.tvStatusOk.currentTextColor != colorInt) {
                    binding.tvStatusOk.setTextColor(colorInt)
                }

                updateTextView(binding.tvUpcValue, boxProcessor.barcode ?: "--")
                updateTextView(binding.tvPoValue, boxProcessor.po ?: "--")
                updateTextView(binding.tvTotalActual, "$totalProcessedCount")

                // API Response UI
                val resp = currentApiResponse
                updateTextView(binding.tvQtyValue, "${resp?.quantity ?: ""}")
                updateTextView(binding.tvRyValue, "${resp?.ry ?: ""}")
                updateTextView(binding.tvSizeValue, "${resp?.size ?: ""}")
                updateTextView(binding.tvArt, "${resp?.article ?: "--"}")
                updateTextView(binding.tvRemaining, "${resp?.remainInternal ?: ""}")
                updateTextView(binding.tvCompleted, "${resp?.doneInternal ?: ""}")
                updateTextView(binding.tvCountry, "${resp?.country ?: ""}")
                updateTextView(binding.tvLean, "${resp?.lean ?: ""}")
                updateTextView(binding.tvTotalOrder, "${resp?.qtyOrder ?: ""}")
                updateTextView(binding.tvTotalTarget, "$targetQuantity")

                // Image Loading
                if (resp?.articleImage != null) {
                    val newUrl = "http://192.168.30.19:5000/shoes-photos/${resp.articleImage}"
                    if (newUrl != lastImageLoadingUrl) {
                        lastImageLoadingUrl = newUrl
                        Glide.with(this).load(newUrl).into(binding.ivShoeImage)
                    }
                } else {
                    if (lastImageLoadingUrl != null) {
                        lastImageLoadingUrl = null
                        binding.ivShoeImage.setImageDrawable(null)
                    }
                }

//                if (
//                    state == AppState.VERIFYING &&
//                    !isApiCalling &&
//                    currentApiResponse == null
//                ) {
//                    isApiCalling = true // khóa ngay lập tức
//                    val po = boxProcessor.po
//                    val barcode = boxProcessor.barcode
//
//                    if (!po.isNullOrBlank() && !barcode.isNullOrBlank()) {
//                        viewModel.handleScan(po, barcode)
//                    }
//                }

                if (state == AppState.VERIFYING && !isApiCalling && currentApiResponse == null) {
                    callApi()
                }
            }
        }
    }

    private fun callApi() {
        val barcode = boxProcessor.barcode ?: return
        val po  = boxProcessor.po ?: return

        
        isApiCalling = true

        apiService
                .getPoDetails(po, barcode)
                .enqueue(
                        object : retrofit2.Callback<com.example.usbcam.api.PoResponse> {
                            override fun onResponse(
                                    call: retrofit2.Call<com.example.usbcam.api.PoResponse>,
                                    response: retrofit2.Response<com.example.usbcam.api.PoResponse>
                            ) {
                                isApiCalling = false
                                if (response.isSuccessful && response.body() != null) {
                                    val body = response.body()!!
                                    currentApiResponse = body
                                    rfidViewModel.setCurrentCameraResponse(body)
                                    
                                    // 🔖 Get scanned RFID code from RfidViewModel
                                    val scannedRfid = rfidViewModel.lastEpc.value?.takeIf { it.isNotBlank() }
                                    
                                    // Pass RFID code to validation logic
                                    viewModel.saveScanData(po, barcode, body, scannedRfid)
                                    boxProcessor.onApiVerification(true)
                                } else {
                                    // Fallback: API returned Check for local data
                                    fallbackToLocal(po, barcode)
                                }
                            }

                            override fun onFailure(
                                    call: retrofit2.Call<com.example.usbcam.api.PoResponse>,
                                    t: Throwable
                            ) {
                                isApiCalling = false
                                // Fallback: Network failure
                                Log.e(TAG, "API Failure: ${t.message}, trying fallback...")
                                fallbackToLocal(po, barcode)
                            }
                        }
                )
    }

    private fun fallbackToLocal(po: String, barcode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val localData = viewModel.getLocalData(po, barcode)
            if (localData != null) {
                Log.d(TAG, "Fallback Success: Loaded local data")
                currentApiResponse = localData
                rfidViewModel.setCurrentCameraResponse(localData)
                // Just refresh UI, no need to save again as it's already in DB
                viewModel.loadAllTimeSlots()
                viewModel.loadTotal()
                boxProcessor.onApiVerification(true)
            } else {
                Log.e(TAG, "Fallback Failed: No local data found")
                boxProcessor.onApiVerification(false)
            }
        }
    }


    private fun handleStateFeedback(newState: AppState) {
        if (toneGen == null)
                toneGen =
                        android.media.ToneGenerator(
                                android.media.AudioManager.STREAM_ALARM,
                                Config.BEEP_VOLUME
                        )
        if (vibrator == null)
                vibrator =
                        activity?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as
                                android.os.Vibrator?

        when (newState) {
            AppState.VERIFYING -> {
                // Short beep for entering verification
                toneGen?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            }
            AppState.DECODED -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            }
            // Add ERROR beep if we have an ERROR state? (New AppState doesn't have explicit ERROR,
            // tracking reset is silent/logged)
            else -> {}
        }
    }

    // Note: Manual focus trigger removed - camera library doesn't support setAutoFocus()
    private fun triggerFocus() {
        // Camera autofocus is handled automatically by the hardware
        Log.d(TAG, "[FOCUS] Relying on camera's automatic focus")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingThread()
        releaseOpenCvResources()
        boxProcessor.release()

        toneGen?.release()
    }

    private fun releaseOpenCvResources() {
        try {
            if (::mMatOfByte.isInitialized) {
                mMatOfByte.release()
                mBgr.release()
                mRgba.release()
                mGray.release()
                mBoosted.release()
            }
            mYuv?.release()
            Log.d(TAG, "OpenCV resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OpenCV resources", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mViewBinding = null
        if (::rfidConnectionManager.isInitialized) {
            rfidConnectionManager.stopScanning()
            rfidConnectionManager.setEventCallback(null)
        }
    }

    companion object {
        private const val TAG = "BoxScanner"
    }
}
