package com.example.usbcam

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.usbcam.api.PoApiService
import com.example.usbcam.databinding.LayoutDashboardBinding
import com.example.usbcam.viewmodel.MainViewModel
import com.example.usbcam.viewmodel.MainViewModelFactory
import com.example.usbcam.viewmodel.TimeSlotAdapter
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class DemoFragment : CameraFragment(), IPreviewDataCallBack {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(requireActivity().application)
    }

    private var mViewBinding: LayoutDashboardBinding? = null
    private lateinit var boxProcessor: BoxProcessor

    // ✅ OPTIMIZATION 1: Reuse MatOfByte buffer (avoid allocation per frame)
    // Lazy initialization after OpenCV loads
    private lateinit var mMatOfByte: MatOfByte
    
    // ✅ OPTIMIZATION 2: Pre-allocate all Mats once
    private lateinit var mBgr: Mat      // Decoded BGR from MJPEG
    private lateinit var mRgba: Mat     // RGBA for display/bitmap
    private lateinit var mGray: Mat     // Grayscale for processing
    private lateinit var mBoosted: Mat  // Brightness boosted version
    
    // YUV fallback (only if MJPEG fails)
    private var mYuv: Mat? = null
    private var isUsingYuvFallback = false

    // Threading
    private val frameQueue = ArrayBlockingQueue<ByteArray>(3)
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    // Background executor for ML Kit
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Scan flags
    @Volatile private var isScanningBarcode = false
    @Volatile private var isScanningPO = false

    // Timing
    private var lastProcessTime = 0L
    private var lastScanTime = 0L

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

    // Thread-safe OCR result
    private val pendingOcrResult = AtomicReference<String?>(null)

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
        if (data == null) return
        
        // ✅ OPTIMIZATION 3: Only initialize YUV fallback if needed
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            Log.i(TAG, "Frame size: ${width}x${height}, format=$format, bytes=${data.size}")
            
            // Pre-allocate YUV fallback buffer (lazy init)
            if (mYuv == null) {
                mYuv = Mat(height + height / 2, width, CvType.CV_8UC1)
            }
        }
        
        frameQueue.offer(data)
    }

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
        
        // Release all OpenCV resources
        try {
            if (::mMatOfByte.isInitialized) {
                mMatOfByte.release()
                mBgr.release()
                mRgba.release()
                mGray.release()
                mBoosted.release()
            }
            mYuv?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OpenCV resources", e)
        }
    }

    private fun processFrame(data: ByteArray) {
        val now = System.currentTimeMillis()
        
        // FPS limiter
        if (now - lastProcessTime < (1000L / Config.MAX_PROCESSING_FPS)) return
        lastProcessTime = now

        // ✅ OPTIMIZATION 4: Efficient MJPEG decode with minimal allocations
        try {
            // Reuse MatOfByte buffer (no new allocation)
            mMatOfByte.fromArray(*data)
            
            // Decode MJPEG → BGR
            // Note: Standard imdecode returns new Mat, we copy to reused mBgr
            val decoded = Imgcodecs.imdecode(mMatOfByte, Imgcodecs.IMREAD_COLOR)
            
            if (decoded.empty()) {
                decoded.release()
                
                // ✅ FALLBACK: Switch to YUV mode permanently (until camera restart)
                if (!isUsingYuvFallback) {
                    Log.w(TAG, "[FALLBACK] MJPEG decode failed, switching to YUV mode")
                    isUsingYuvFallback = true
                }
                
                mYuv?.put(0, 0, data)
                Imgproc.cvtColor(mYuv, mRgba, Imgproc.COLOR_YUV2RGBA_NV21)
            } else {
                // ✅ SUCCESS: MJPEG decoded
                if (isUsingYuvFallback) {
                    Log.i(TAG, "[RECOVERY] MJPEG decode restored")
                    isUsingYuvFallback = false
                }
                
                // Copy decoded result to reused mBgr buffer
                decoded.copyTo(mBgr)
                decoded.release()
                
                // BGR → RGBA (in-place, reuses mRgba)
                Imgproc.cvtColor(mBgr, mRgba, Imgproc.COLOR_BGR2RGBA)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
            return
        }

        if (mRgba.empty()) {
            Log.w(TAG, "mRgba is empty after conversion")
            return
        }

        // ✅ OPTIMIZATION 5: Direct grayscale conversion (in-place)
        Imgproc.cvtColor(mRgba, mGray, Imgproc.COLOR_RGBA2GRAY)

        // Core box detection logic
        val ocrResult = pendingOcrResult.getAndSet(null)
        if (ocrResult != null) {
            Log.d(TAG, "[OCR] Result: $ocrResult")
        }
        
        val prevState = boxProcessor.currentState
        boxProcessor.updateLogic(mGray, ocrResult)
        val newState = boxProcessor.currentState
        
        if (prevState != newState) {
            Log.i(TAG, "[STATE] $prevState → $newState")
            
            // ✅ TRIGGER FOCUS when box becomes stable
            if (newState == AppState.STABLE) {
                triggerFocus()
            }
        }

        // Trigger ML Kit scanning if needed
        if (boxProcessor.currentState == AppState.SCANNING) {
            if (!isScanningBarcode && !isScanningPO && 
                (now - lastScanTime > Config.SCAN_THROTTLE_MS)) {
                lastScanTime = now
                
                // ✅ OPTIMIZATION 6: Only create bitmap when scanning
                val bitmapToScan = createScanBitmap()
                
                if (bitmapToScan != null) {
                    isScanningBarcode = true
                    Log.d(TAG, "[SCAN] Start: ${bitmapToScan.width}x${bitmapToScan.height}")
                    scanBarcode(bitmapToScan)
                }
            }
        }

        updateUI()
    }

    // ✅ OPTIMIZATION 7: Smart brightness boost (only when needed)
    private fun createScanBitmap(): Bitmap? {
        return try {
            val srcMat = if (Config.BRIGHTNESS_BOOST > 0) {
                // Apply brightness boost in-place
                mRgba.convertTo(mBoosted, -1, 1.0, Config.BRIGHTNESS_BOOST.toDouble())
                mBoosted
            } else {
                mRgba
            }
            
            // Convert to Bitmap
            val bmp = Bitmap.createBitmap(srcMat.cols(), srcMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(srcMat, bmp)
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error creating scan bitmap", e)
            null
        }
    }

    private fun scanBarcode(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        BarcodeScanning.getClient()
            .process(image)
            .addOnSuccessListener(backgroundExecutor) { barcodes -> 
                val barcode = barcodes.firstOrNull()?.rawValue
                if (!barcode.isNullOrEmpty()) {
                    Log.d(TAG, "[BARCODE] Found: $barcode")
                    boxProcessor.barcode = barcode
                    isScanningPO = true
                    scanPOInCenter(bitmap, barcode)
                } else {
                    Log.v(TAG, "[BARCODE] None detected")
                    isScanningBarcode = false
                    bitmap.recycle()
                }
            }
            .addOnFailureListener(backgroundExecutor) { e ->
                Log.e(TAG, "[BARCODE] Failed", e)
                isScanningBarcode = false
                bitmap.recycle()
            }
    }

    private fun scanPOInCenter(fullBitmap: Bitmap, barcode: String) {
        val w = fullBitmap.width
        val h = fullBitmap.height
        val roiW = (w * 0.8).toInt()
        val roiH = (h * 0.6).toInt()
        val roiX = (w - roiW) / 2
        val roiY = (h - roiH) / 2

        try {
            val poBitmap = Bitmap.createBitmap(fullBitmap, roiX, roiY, roiW, roiH)
            val image = InputImage.fromBitmap(poBitmap, 0)
            val options = TextRecognizerOptions.Builder().build()

            TextRecognition.getClient(options)
                .process(image)
                .addOnSuccessListener(backgroundExecutor) { visionText ->
                    Log.v(TAG, "[OCR] Text: ${visionText.text}")
                    processOcrResult(visionText.text, barcode)
                }
                .addOnCompleteListener(backgroundExecutor) {
                    isScanningPO = false
                    isScanningBarcode = false
                    poBitmap.recycle()
                    fullBitmap.recycle()
                }
        } catch (e: Exception) {
            Log.e(TAG, "[OCR] Error", e)
            isScanningPO = false
            isScanningBarcode = false
            fullBitmap.recycle()
        }
    }

    private fun processOcrResult(text: String, barcode: String) {
        val lines = text.split("\n", " ")
        for (line in lines) {
            val clean = line.trim()
            if (clean.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH &&
                clean.all { it.isDigit() }
            ) {
                Log.d(TAG, "[OCR] Valid PO: $clean")
                pendingOcrResult.set(clean)
                return
            }
        }
        Log.v(TAG, "[OCR] No valid PO found")
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
                if (currentApiResponse != null) currentApiResponse = null
            }

            if (state != lastState) {
                handleStateFeedback(state)
                lastState = state
            }

            mViewBinding?.let { binding ->
                val isBusy = state == AppState.MOVING || state == AppState.SCANNING
                binding.pbMotion.visibility = if (isBusy) View.VISIBLE else View.GONE

                val statusText = when (state) {
                    AppState.IDLE -> "READY"
                    AppState.MOVING -> "INCOMING..."
                    AppState.STABLE -> "STABLE"
                    AppState.SCANNING -> "CAPTURING..."
                    AppState.SUCCESS -> "SUCCESS"
                    AppState.ERROR -> "ERROR"
                }
                updateTextView(binding.tvStatusOk, statusText)

                val color = when (state) {
                    AppState.MOVING -> Color.parseColor("#FF9800")
                    AppState.SCANNING -> Color.BLUE
                    AppState.SUCCESS -> Color.GREEN
                    AppState.ERROR -> Color.RED
                    else -> Color.DKGRAY
                }
                if (binding.tvStatusOk.currentTextColor != color) {
                    binding.tvStatusOk.setTextColor(color)
                }

                updateTextView(binding.tvUpcValue, boxProcessor.barcode ?: "--")
                updateTextView(binding.tvPoValue, boxProcessor.po ?: "--")
                updateTextView(binding.tvTotalActual, "$totalProcessedCount")

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

                if (resp?.articleImage != null) {
                    val newUrl = "http://192.168.30.19:5000/shoes-photos/${resp.articleImage}"
                    if (newUrl != lastImageLoadingUrl) {
                        lastImageLoadingUrl = newUrl
                        Glide.with(this)
                            .load(newUrl)
                            .into(binding.ivShoeImage)
                    }
                } else {
                    if (lastImageLoadingUrl != null) {
                        lastImageLoadingUrl = null
                        binding.ivShoeImage.setImageDrawable(null)
                    }
                }

                if (state == AppState.SUCCESS && !isApiCalling && currentApiResponse == null) {
                    callApi()
                }
            }
        }
    }

    private fun callApi() {
        val po = boxProcessor.po ?: return
        val barcode = boxProcessor.barcode ?: return
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
                            currentApiResponse = response.body()
                            viewModel.saveScanData(po, barcode, response.body()!!)
                        }
                    }

                    override fun onFailure(
                        call: retrofit2.Call<com.example.usbcam.api.PoResponse>,
                        t: Throwable
                    ) {
                        isApiCalling = false
                    }
                }
            )
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
            AppState.SUCCESS -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            }
            AppState.ERROR -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            500L,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                }
            }
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
        backgroundExecutor.shutdownNow()
        toneGen?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mViewBinding = null
    }

    companion object {
        private const val TAG = "BoxScanner"
    }
}