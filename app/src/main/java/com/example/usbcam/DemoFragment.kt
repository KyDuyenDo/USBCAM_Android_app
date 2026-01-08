package com.example.usbcam

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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

    private var mViewBinding: LayoutDashboardBinding? = null
    private lateinit var boxProcessor: BoxProcessor

    // OpenCV Data
    private var mRgba: Mat? = null
    private var mYuvMat: Mat? = null

    // Threading
    private val frameQueue = ArrayBlockingQueue<ByteArray>(3)
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    // Scan flags
    @Volatile private var isScanningBarcode = false
    @Volatile private var isScanningPO = false
    
    // Timing
    private var lastProcessTime = 0L
    private var lastScanTime = 0L

    private var toneGen: android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator? = null
    private var lastState = AppState.IDLE

    private val apiService = PoApiService.create()
    private var isApiCalling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
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
            if (target != null) boxProcessor.target = target.quantityTarget
        }
        viewModel.totalScan.observe(viewLifecycleOwner) { total ->
            if (total != null) boxProcessor.totalCount = total
        }

        viewModel.loadTotal()
        viewModel.loadTarget()
        viewModel.loadAllTimeSlots()

        com.example.usbcam.utils.NetworkConnectionMonitor(requireContext())
            .observe(viewLifecycleOwner) { isConnected ->
                mViewBinding?.tvNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE
            }
    }

    override fun getCameraView(): IAspectRatio? = mViewBinding?.tvCameraRender
    override fun getCameraViewContainer(): ViewGroup? = null
    override fun getGravity(): Int = Gravity.TOP

    override fun onCameraState(self: com.jiangdg.ausbc.MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                addPreviewDataCallBack(this)
                startProcessingThread()
            }
            ICameraStateCallBack.State.CLOSED -> {
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

    override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        if (data == null) return
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            mYuvMat = Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1)
            mRgba = Mat()
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
                }
            }
        }.apply {
            name = "FrameProcessing"
            start()
        }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        processingThread = null
        frameQueue.clear()
        mYuvMat?.release()
        mRgba?.release()
    }

    private fun processFrame(data: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < (1000L / Config.MAX_PROCESSING_FPS)) return
        lastProcessTime = now

        var decoded = Mat()
        try {
            val buf = MatOfByte(*data)
            decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release()

            if (!decoded.empty()) {
                Imgproc.cvtColor(decoded, mRgba, Imgproc.COLOR_BGR2RGBA)
            } else {
                mYuvMat?.put(0, 0, data)
                Imgproc.cvtColor(mYuvMat, mRgba, Imgproc.COLOR_YUV2RGBA_NV21)
            }
        } catch (e: Exception) {
            decoded.release()
            return
        }
        decoded.release()
        if (mRgba == null || mRgba!!.empty()) return

        val gray = Mat()
        Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY)

        // 1. Cập nhật logic chuyển động (Optical Flow)
        boxProcessor.updateLogic(gray)

        // 2. Nếu trạng thái là SCANNING, kích hoạt ML Kit
        if (boxProcessor.currentState == AppState.SCANNING) {
            // Throttle: Không gọi liên tục, nhưng cũng không nên chậm quá
            if (!isScanningBarcode && !isScanningPO && (now - lastScanTime > Config.SCAN_THROTTLE_MS)) {
                lastScanTime = now
                val bmp = convertMatToBitmap(mRgba!!)
                if (bmp != null) {
                    isScanningBarcode = true
                    scanBarcode(bmp)
                }
            }
        }

        updateUI()
        gray.release()
    }

    private fun scanBarcode(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull()?.rawValue
                if (!barcode.isNullOrEmpty()) {
                    Log.d(TAG, "Barcode Found: $barcode")
                    // Có Barcode -> Scan tiếp PO (Text)
                    isScanningPO = true
                    scanPOInCenter(bitmap, barcode)
                } else {
                    isScanningBarcode = false
                }
            }
            .addOnFailureListener {
                isScanningBarcode = false
            }
    }

    private fun scanPOInCenter(fullBitmap: Bitmap, barcode: String) {
        // Crop Center
        val w = fullBitmap.width
        val h = fullBitmap.height
        val roiW = (w * 0.8).toInt()
        val roiH = (h * 0.6).toInt()
        val roiX = (w - roiW) / 2
        val roiY = (h - roiH) / 2

        try {
            var poBitmap = Bitmap.createBitmap(fullBitmap, roiX, roiY, roiW, roiH)
            
            // --- CẢI TIẾN: TĂNG ĐỘ SÁNG (BRIGHTNESS BOOST) ---
            // Ánh sáng nhà máy yếu -> Tăng sáng bằng ColorMatrix
            if (Config.BRIGHTNESS_BOOST > 0) {
                val brightBitmap = Bitmap.createBitmap(roiW, roiH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(brightBitmap)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                val boost = Config.BRIGHTNESS_BOOST
                // Ma trận: [R,0,0,0,boost, 0,G,0,0,boost, ...]
                colorMatrix.set(floatArrayOf(
                    1f, 0f, 0f, 0f, boost, 
                    0f, 1f, 0f, 0f, boost,
                    0f, 0f, 1f, 0f, boost,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_ATOP) // Reset logic
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(poBitmap, 0f, 0f, paint)
                
                // Dùng ảnh đã tăng sáng
                poBitmap = brightBitmap
            }
            // ------------------------------------------------

            val image = InputImage.fromBitmap(poBitmap, 0)
            val options = TextRecognizerOptions.Builder().build()

            TextRecognition.getClient(options)
                .process(image)
                .addOnSuccessListener { visionText ->
                    processOcrResult(visionText.text, barcode)
                }
                .addOnCompleteListener {
                    isScanningPO = false
                    isScanningBarcode = false
                }
        } catch (e: Exception) {
            isScanningPO = false
            isScanningBarcode = false
        }
    }

    private fun processOcrResult(text: String, barcode: String) {
        val lines = text.split("\n", " ")
        for (line in lines) {
            val clean = line.trim()
            if (clean.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH && clean.all { it.isDigit() }) {
                boxProcessor.onScanSuccess(barcode, clean)
                return
            }
        }
        // Fallback: Có barcode nhưng ko thấy PO
        boxProcessor.onScanSuccess(barcode, null)
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            val state = boxProcessor.currentState

            // Âm thanh / Rung khi thay đổi trạng thái
            if (state != lastState) {
                handleStateFeedback(state)
                lastState = state
            }

            mViewBinding?.let { binding ->
                // Chỉ hiển thị loading motion khi thực sự đang xử lý
                val isBusy = state == AppState.SLIDING || state == AppState.SCANNING || state == AppState.VALIDATING
                binding.pbMotion.visibility = if (isBusy) View.VISIBLE else View.GONE
                
                // Status Text
                binding.tvStatusOk.text = when (state) {
                    AppState.IDLE -> "READY"
                    AppState.SLIDING -> "INCOMING..." // Đang trượt vào
                    AppState.SCANNING -> "CAPTURING..."
                    AppState.VALIDATING -> "VALIDATING..."
                    AppState.SUCCESS -> "SUCCESS"
                    AppState.ERROR -> "ERROR"
                    else -> ""
                }

                // Màu sắc status
                val color = when (state) {
                    AppState.SLIDING -> Color.parseColor("#FF9800") // Cam
                    AppState.SCANNING, AppState.VALIDATING -> Color.BLUE
                    AppState.SUCCESS -> Color.GREEN
                    AppState.ERROR -> Color.RED
                    else -> Color.DKGRAY
                }
                binding.tvStatusOk.setTextColor(color)

                // Fill data
                binding.tvUpcValue.text = boxProcessor.currentBarcode ?: "--"
                binding.tvPoValue.text = boxProcessor.currentPO ?: "--"
                binding.tvTotalActual.text = "${boxProcessor.totalCount}"

                // API Data binding (Giữ nguyên logic cũ)
                val resp = boxProcessor.apiResponse
                binding.tvQtyValue.text = "${resp?.quantity}"
                binding.tvRyValue.text = "${resp?.ry}"
                binding.tvSizeValue.text = "${resp?.size}"
                binding.tvArt.text = "${resp?.article ?: "--"}"
                binding.tvRemaining.text = "${resp?.remainInternal}"
                binding.tvCompleted.text = "${resp?.doneInternal}"
                binding.tvCountry.text = "${resp?.country}"
                binding.tvLean.text = "${resp?.lean}"
                binding.tvTotalOrder.text = "${resp?.qtyOrder}"
                binding.tvTotalTarget.text = "${boxProcessor.target}"

                if (resp?.articleImage != null) {
                     Glide.with(this)
                        .load("http://192.168.30.19:5000/shoes-photos/${resp.articleImage}")
                        .into(binding.ivShoeImage)
                }

                // Gọi API 1 lần duy nhất khi vào trạng thái VALIDATING
                if (state == AppState.VALIDATING && !isApiCalling) {
                    callApi()
                }
            }
        }
    }

    private fun callApi() {
        val po = boxProcessor.currentPO ?: return
        val barcode = boxProcessor.currentBarcode ?: return
        isApiCalling = true

        apiService.getPoDetails(po, barcode).enqueue(object : retrofit2.Callback<com.example.usbcam.api.PoResponse> {
            override fun onResponse(call: retrofit2.Call<com.example.usbcam.api.PoResponse>, response: retrofit2.Response<com.example.usbcam.api.PoResponse>) {
                isApiCalling = false
                if (response.isSuccessful && response.body() != null) {
                    boxProcessor.apiResponse = response.body()
                    boxProcessor.markSuccess()
                    viewModel.saveScanData(po, barcode, response.body()!!)
                } else {
                    boxProcessor.markError("API ERROR: ${response.code()}")
                }
            }
            override fun onFailure(call: retrofit2.Call<com.example.usbcam.api.PoResponse>, t: Throwable) {
                isApiCalling = false
                // Offline fallback logic here if needed
                boxProcessor.markError("NET ERROR")
            }
        })
    }

    private fun handleStateFeedback(newState: AppState) {
        if (toneGen == null) toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, Config.BEEP_VOLUME)
        if (vibrator == null) vibrator = activity?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator?

        when (newState) {
            AppState.SUCCESS -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            }
            AppState.ERROR -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(500L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            else -> {}
        }
    }

    private fun convertMatToBitmap(mat: Mat): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bmp)
            bmp
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingThread()
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