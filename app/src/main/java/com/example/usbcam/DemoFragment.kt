package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
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

    // Frame size từ camera
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



    // Local Data
    private var targetQuantity = 0
    private var totalProcessedCount = 0
    private var currentApiResponse: com.example.usbcam.api.PoResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        boxProcessor = BoxProcessor()
        Log.i(TAG, "BoxProcessor initialized (ML Kit only, no OpenCV)")
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
            this.adapter = adapter
        }

        viewModel.timeSlotList.observe(viewLifecycleOwner) { list -> adapter.submitList(list) }
        viewModel.totalTarget.observe(viewLifecycleOwner) { total ->
            targetQuantity = total
        }
        viewModel.totalScan.observe(viewLifecycleOwner) { total ->
            if (total != null) totalProcessedCount = total
        }

        viewModel.loadTotal()
        viewModel.loadTarget()
        viewModel.loadAllTimeSlots()

        // Khởi tạo Spinner chọn dây chuyền sản xuất
        viewModel.initSelectedLine(requireContext())
        setupLineSpinner()

        com.example.usbcam.utils.NetworkConnectionMonitor(requireContext()).observe(
                        viewLifecycleOwner
                ) { isConnected ->
            mViewBinding?.tvNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE
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
    /**
     * Thiết lập Spinner chọn dây chuyền (Line).
     * - Đọc line đã lưu từ SharedPreferences và hiển thị trước
     * - Lưu lựa chọn mới và gọi viewModel.setSelectedLine() để reload target API
     */
    private fun setupLineSpinner() {
        val spinner = mViewBinding?.spinnerLine ?: return
        val lines = com.example.usbcam.utils.LinePreferences.availableLines

        val spinnerAdapter =
                ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, lines).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
        spinner.adapter = spinnerAdapter

        // Đặt line đã lưu trước đó
        val savedIndex =
                com.example.usbcam.utils.LinePreferences.getSelectedLineIndex(requireContext())
        spinner.setSelection(savedIndex, false) // false = không trigger listener lún đầu

        spinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                    ) {
                        val selectedLine = lines[position]
                        val currentLine = viewModel.selectedLine.value
                        if (selectedLine != currentLine) {
                            Log.d(TAG, "Line changed: $currentLine -> $selectedLine")
                            // Lưu vào SharedPreferences
                            com.example.usbcam.utils.LinePreferences.saveSelectedLine(
                                    requireContext(),
                                    selectedLine
                            )
                            // Cập nhật ViewModel và reload target API
                            viewModel.setSelectedLine(selectedLine)
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    private lateinit var rfidConnectionManager: com.example.usbcam.rfid.RfidConnectionManager
    private var rfidScanningStarted = false // Protection flag
    private val verificationTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun setupRfidManager() {
        rfidConnectionManager =
                com.example.usbcam.rfid.RfidConnectionManager.getInstance(requireContext())

        // Setup observers for RfidViewModel
        setupRfidObservers()

        rfidConnectionManager.setEventCallback(
                object : com.example.usbcam.rfid.RfidConnectionManager.RfidEventCallback {
                    override fun onConnected(isAutoConnect: Boolean) {
                        activity?.runOnUiThread {
                            android.widget.Toast.makeText(
                                            requireContext(),
                                            "RFID Connected ✅",
                                            android.widget.Toast.LENGTH_SHORT
                                    )
                                    .show()
                            rfidViewModel.setConnected(true)
                            com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
                        }
                    }

                    override fun onDisconnected() {
                        activity?.runOnUiThread {
                            rfidViewModel.setConnected(false)
                            rfidScanningStarted = false // Reset flag on disconnect
                            com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
                        }
                    }

                    override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
                        activity?.runOnUiThread {
                            Log.i(TAG, "SDK Tag Read: $epc (RSSI: $rssi)")

                            // Update ViewModel
                            rfidViewModel.onTagRead(epc, rssi, antenna, channel)

                            // 🔹 REQUIREMENT: Stop physical scanning immediately after reading 1 tag
                            if (rfidScanningStarted) {
                                if (::rfidConnectionManager.isInitialized) {
                                    rfidConnectionManager.stopScanning()
                                }
                                rfidViewModel.setScanning(false)
                            }

                            // 🔹 REQUIREMENT: Complete immediately if we have API data
                            checkAndCompleteRfidVerification(epc)
                        }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "RFID Error: $message")
                    }

                    override fun onAutoConnectFailed() {
                        activity?.runOnUiThread {
                            // Requirement 6: Mở màn hình chọn thiết bị khi kết nối thất bại
                            Log.w(TAG, "Auto-connect failed. Opening selection UI.")
                            if (isAdded) {
                                // val settingsDialog =
                                // com.example.usbcam.rfid.RfidSettingsFragment.newInstance()
                                // settingsDialog.sehow(parentFragmentManager, "RfidSettingsDialog")
                            }
                        }
                    }
                }
        )

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
                android.widget.Toast.makeText(
                                requireContext(),
                                error,
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                rfidViewModel.clearError()
            }
        }

        // Info messages
        rfidViewModel.infoMessage.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                android.widget.Toast.makeText(
                                requireContext(),
                                info,
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
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
                                            "${result.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                    )
                                    .show()

                            // 🔹 Sync RfidViewModel UI with the fetched data
                            rfidViewModel.updateFromValidationResult(result)

                            // 🔹 Reset RFID after successful save
                            // rfidViewModel.clearRfidData()
                        } else {
                            // Data mismatch - warning
                            android.widget.Toast.makeText(
                                            requireContext(),
                                            result.message,
                                            android.widget.Toast.LENGTH_LONG
                                    )
                                    .show()

                            // 🔹 Sync RfidViewModel UI with the mismatch data/fields
                            rfidViewModel.updateFromValidationResult(result)
                        }
                    }
                    is com.example.usbcam.data.model.ValidationResult.Error -> {
                        // Validation error
                        android.widget.Toast.makeText(
                                        requireContext(),
                                        "${result.message}",
                                        android.widget.Toast.LENGTH_LONG
                                )
                                .show()
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

        rfidViewModel.isUpcMatch.observe(viewLifecycleOwner) { isMatch ->
            updateFieldColor(mViewBinding?.tvRfidUpc, isMatch)
            updateFieldColor(mViewBinding?.tvUpcValue, isMatch)
        }
    }

    private fun updateFieldColor(textView: android.widget.TextView?, isMatch: Boolean) {
        val colorInt =
                if (isMatch) {
                    androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.text_primary
                    )
                } else {
                    androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.error_red
                    )
                }
        textView?.setTextColor(colorInt)
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
                Log.i(TAG, "Camera opened")
                cameraInstance = self // Store camera reference
                addPreviewDataCallBack(this)
                startProcessingThread()
                com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = true
                com.example.usbcam.utils.DeviceStatusTracker.reportStatus(requireContext())
                
                // Set GPU brightness boost
                if (Config.BRIGHTNESS_BOOST > 0) {
                    self.setBrightness(Config.BRIGHTNESS_BOOST / 255f)
                }
                
                // Set fixed focus to 450
                self.setAutoFocus(false)
                self.setFocus(450)
                Log.i(TAG, "Camera focus fixed to 450")
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
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_NV21)
                .setRawPreviewData(true)
                .create()
    }

    override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
    ) {
        // Don't process data if fragment is detached
        if (data == null || !isAdded) return
        com.example.usbcam.utils.DeviceStatusTracker.isCameraConnected = true

        // FIX 3: FPS Limiter moved here to prevent queue backlog and waste of resources
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < 1000 / Config.MAX_PROCESSING_FPS) {
            return
        }
        lastFrameTime = now

        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            Log.i(TAG, "Frame size: ${width}x${height}, format=$format, bytes=${data.size}")
        }

        // FIX 4: Prevent queue backlog when USB lags - drop oldest frame if full
        if (!frameQueue.offer(data)) {
            frameQueue.poll() // Remove oldest
            frameQueue.offer(data) // Add newest
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
        // They are shared objects and the thread might still be in the middle of a processFrame
        // loop.
        // Let onDestroy or a safe lifecycle event handle it, or just let them stay allocated for
        // the next session.
        // Releasing them here while a thread is interrupted often causes illegal state/memory
        // access.
        Log.d(TAG, "Processing thread stopped")
    }

    private fun processFrame(data: ByteArray) {
        if (!isAdded) return

        val startTime = System.currentTimeMillis()
        try {
            // FIX 1 & 2: Pass raw NV21 bytes directly to BoxProcessor
            // No more decoding here!
            boxProcessor.updateLogic(data, frameWidth, frameHeight)
            
            val duration = System.currentTimeMillis() - startTime
            if (duration > 100) {
                Log.w(TAG, "Slow frame processing: ${duration}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processing logic", e)
        }

        updateUI()
    }

    /**
     * Decode NV21 YUV byte array → Bitmap (fallback khi MJPEG thất bại).
     */
    private fun decodeYuvToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "YUV decode error", e)
            null
        }
    }

    /**
     * Áp dụng brightness boost bằng Android ColorMatrix.
     * Nếu boost <= 0, trả về bitmap gốc (không tạo bản sao).
     */
    private fun applyBrightnessBoost(source: Bitmap, boost: Float): Bitmap {
        if (boost <= 0f) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, boost,
            0f, 1f, 0f, 0f, boost,
            0f, 0f, 1f, 0f, boost,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
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

                // 🔹 REQUIREMENT: Once state transitions to IDLE (or if there happens to be
                // leftover EPC data),
                // clear App side memory AND Device side memory to completely reset for the next
                // loop.
                if (state != lastState || rfidViewModel.lastEpc.value?.isNotEmpty() == true) {
                    Log.d(
                            TAG,
                            "System IDLE / New Cycle -> Clearing leftover RFID App Data and Restoring Device State to standard configuration"
                    )
                    rfidViewModel.clearRfidData()

                    // REMOVED clearDeviceState() -- this hardware command may cause the module
                    // to ignore the next startScanning request and result in empty reads.
                    // if (::rfidConnectionManager.isInitialized) {
                    //    rfidConnectionManager.clearDeviceState()
                    // }
                }
            }

            // 🔹 REQUIREMENT: Start RFID scanning earlier (in SCANNING state)
            if (state == AppState.SCANNING && lastState != AppState.SCANNING) {
                if (rfidViewModel.isConnected.value == true && !rfidScanningStarted) {
                    Log.i(TAG, "Entering SCANNING state -> Starting RFID scan early")
                    rfidScanningStarted = true
                    rfidViewModel.clearRfidData()
                    if (::rfidConnectionManager.isInitialized) {
                        rfidConnectionManager.startScanning()
                    }
                    rfidViewModel.setScanning(true)
                }
            }

            if (state != lastState) {
                handleStateFeedback(state)
                lastState = state
            }

            // 🔹 REQUIREMENT: Only start scanning when motion is physically detected (SCANNING
            // state)
            // (Removed: Scanning now starts after camera processing completes)

            // 🔹 REQUIREMENT: Stop scanning if system goes back to IDLE (or resets)
            if ((state == AppState.IDLE || state == AppState.RESETTING) && rfidScanningStarted) {
                Log.i(TAG, "System $state -> Stopping RFID sequence.")
                rfidScanningStarted = false
                if (::rfidConnectionManager.isInitialized) {
                    rfidConnectionManager.stopScanning()
                }
                rfidViewModel.setScanning(false)
            }

            mViewBinding?.let { binding ->
                val isBusy = state == AppState.SCANNING
                binding.pbMotion.visibility = if (isBusy) View.VISIBLE else View.GONE

                val statusText =
                        when (state) {
                            AppState.IDLE -> "READY"
                            AppState.SCANNING -> "SCANNING..."
                            AppState.VERIFYING -> "VERIFYING..."
                            AppState.DECODED -> "OKE"
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

                // Apply colorInt to BACKGROUND tint (not text color) so text remains readable
                binding.tvStatusOk.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(colorInt)
                // Keep text always WHITE for contrast on colored background
                binding.tvStatusOk.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.white)
                )

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

    private fun finalizeVerification(
            po: String,
            barcode: String,
            data: com.example.usbcam.api.PoResponse
    ) {
        if (!isAdded) return
        currentApiResponse = data
        rfidViewModel.setCurrentCameraResponse(data)

        if (rfidViewModel.isConnected.value == true) {
            // Check if we already have an EPC from early scan
            val existingEpc = rfidViewModel.lastEpc.value
            if (!existingEpc.isNullOrEmpty()) {
                Log.i(TAG, "API Data Ready & Tag Already Found ($existingEpc) -> Completing Immediately")
                completeRfidVerification(po, barcode, data, existingEpc)
                return
            }

            // If scanning hasn't started yet (maybe missed state transition), start now
            if (!rfidScanningStarted) {
                Log.i(TAG, "API Data Ready -> Starting RFID scan sequence")
                rfidScanningStarted = true
                rfidViewModel.clearRfidData()
                if (::rfidConnectionManager.isInitialized) {
                    rfidConnectionManager.startScanning()
                }
                rfidViewModel.setScanning(true)
            }

            // Set safety timeout (if no tag is found within window)
            verificationTimeoutHandler.removeCallbacksAndMessages(null)
            verificationTimeoutHandler.postDelayed(
                    {
                        if (isAdded && rfidScanningStarted) {
                            val bestEpc = rfidViewModel.processAndGetBestEpc()
                            Log.i(TAG, "RFID Scan Timeout -> Finalizing with best available: $bestEpc")
                            completeRfidVerification(po, barcode, data, bestEpc)
                        }
                    },
                    Config.RFID_SCAN_WINDOW_MS
            )
        } else {
            // No RFID connected
            val scannedRfids = emptySet<String>()
            viewModel.saveScanData(po, barcode, data, scannedRfids)
            boxProcessor.onApiVerification(true)
        }
    }

    private fun checkAndCompleteRfidVerification(epc: String) {
        if (!rfidScanningStarted) return

        val data = currentApiResponse ?: return
        val po = boxProcessor.po ?: return
        val barcode = boxProcessor.barcode ?: return

        Log.i(TAG, "RFID Tag Read ($epc) and API Data Ready -> Stopping immediately.")

        // Cancel the safety timeout
        verificationTimeoutHandler.removeCallbacksAndMessages(null)

        // Complete the process
        completeRfidVerification(po, barcode, data, epc)
    }

    private fun completeRfidVerification(
            po: String,
            barcode: String,
            data: com.example.usbcam.api.PoResponse,
            epc: String?
    ) {
        if (!rfidScanningStarted) return
        rfidScanningStarted = false

        if (::rfidConnectionManager.isInitialized) {
            rfidConnectionManager.stopScanning()
        }
        rfidViewModel.setScanning(false)

        val scannedRfids = if (epc.isNullOrEmpty()) emptySet() else setOf(epc)
        viewModel.saveScanData(po, barcode, data, scannedRfids)
        boxProcessor.onApiVerification(true)
    }

    private fun callApi() {
        val barcode = boxProcessor.barcode ?: return
        val po = boxProcessor.po ?: return

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

                                    // 🔹 REQUIREMENT: Start scanning for duration after API success
                                    finalizeVerification(po, barcode, body)
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
                viewModel.loadAllTimeSlots()
                viewModel.loadTotal()
                finalizeVerification(po, barcode, localData)
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
        boxProcessor.release()
        toneGen?.release()
        Log.d(TAG, "DemoFragment destroyed, resources released")
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
