package com.example.usbcam.rfid

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RfidConnectionManager
 *
 * Quản lý kết nối RFID tự động với VID/PID mặc định.
 * - Tự động kết nối khi app khởi động
 * - Chỉ mở UI chọn thiết bị khi kết nối thất bại
 * - Cung cấp callbacks cho events
 */
class RfidConnectionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RfidConnectionManager"
        // ============================================
        // CẤU HÌNH VID/PID MẶC ĐỊNH CỦA RFID READER
        // ============================================
        // TODO: Thay đổi theo thiết bị RFID thực tế của bạn

        private const val DEFAULT_VENDOR_ID = 0x483 // VendorId = 1155
        private const val DEFAULT_PRODUCT_ID = 0x5750 // ProductId = 22345

        // Retry settings
        private const val AUTO_CONNECT_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1000L

        // Watchdog reconnect settings
        /** Khoảng thời gian watchdog kiểm tra định kỳ (ms) */
        private const val WATCHDOG_CHECK_INTERVAL_MS = 2000L
        /** Độ trễ ban đầu trước lần reconnect đầu tiên (ms) */
        private const val RECONNECT_INITIAL_DELAY_MS = 3000L
        /** Độ trễ tối đa giữa các lần reconnect (ms) */
        private const val RECONNECT_MAX_DELAY_MS = 30000L

        @Volatile private var instance: RfidConnectionManager? = null

        fun getInstance(context: Context): RfidConnectionManager {
            return instance
                    ?: synchronized(this) {
                        instance
                                ?: RfidConnectionManager(context.applicationContext).also {
                                    instance = it
                                }
                    }
        }
    }

    /** Callback interface cho các sự kiện RFID */
    interface RfidEventCallback {
        /**
         * Kết nối thành công
         * @param isAutoConnect true nếu kết nối tự động, false nếu kết nối thủ công
         */
        fun onConnected(isAutoConnect: Boolean)

        /** Ngắt kết nối */
        fun onDisconnected()

        /**
         * Đọc được thẻ RFID
         * @param epc Mã EPC của thẻ
         * @param rssi Cường độ tín hiệu
         * @param antenna Số antenna
         * @param channel Kênh tần số
         */
        fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int)

        /** Lỗi xảy ra */
        fun onError(message: String)

        /** Kết nối tự động thất bại - cần mở UI chọn thiết bị */
        fun onAutoConnectFailed()
    }

    private val rfidManager: RfidManager = RfidManager.getInstance(context)
    private var eventCallback: RfidEventCallback? = null
    private var autoConnectJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var isInitialized = false

    /** Số lần reconnect liên tiếp thất bại (để tính backoff) */
    @Volatile private var reconnectFailCount = 0

    /** true = đang trong quá trình reconnect, tránh chạy song song */
    @Volatile private var isReconnecting = false

    // Connection state
    var isConnected: Boolean = false
        private set

    var isScanning: Boolean = false
        private set

    init {
        // Setup RfidManager callback
        rfidManager.setCallback(
                object : RfidManager.TagReadCallback {
                    override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
                        eventCallback?.onTagRead(epc, rssi, antenna, channel)
                    }

                    override fun onError(message: String) {
                        eventCallback?.onError(message)
                    }

                    override fun onConnectionStatus(connected: Boolean, message: String) {
                        isConnected = connected
                        if (connected) {
                            Log.i(TAG, "Connected: $message")
                            reconnectFailCount = 0
                            isReconnecting = false
                            val isAuto =
                                    message.contains("auto", ignoreCase = true) ||
                                            message.contains("mặc định", ignoreCase = true)
                            eventCallback?.onConnected(isAuto)
                        } else {
                            Log.w(TAG, "Disconnected: $message")
                            eventCallback?.onDisconnected()
                            // Kích hoạt reconnect ngay khi nhận tín hiệu mất kết nối
                            scheduleReconnect()
                        }
                    }
                }
        )
    }

    /** Khởi tạo SDK và thử kết nối tự động */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        Log.i(TAG, "🔧 Initializing RFID Connection Manager...")
        rfidManager.init()
        isInitialized = true

        // Tự động kết nối sau khi init
        autoConnect()

        // Khởi động watchdog kiểm tra kết nối định kỳ
        startWatchdog()
    }

    /** Tự động kết nối với VID/PID mặc định */
    fun autoConnect() {
        if (isConnected) {
            Log.i(TAG, "Already connected, skipping auto-connect")
            return
        }

        autoConnectJob?.cancel()
        autoConnectJob =
                scope.launch {
                    Log.i(TAG, "🔄 Auto-connecting to default RFID device...")
                    Log.i(TAG, "   VID: 0x${DEFAULT_VENDOR_ID.toString(16).uppercase()}")
                    Log.i(TAG, "   PID: 0x${DEFAULT_PRODUCT_ID.toString(16).uppercase()}")

                    var retryCount = 0
                    var connected = false

                    while (retryCount < AUTO_CONNECT_RETRY_COUNT && !connected) {
                        try {
                            // Kiểm tra xem device có tồn tại không
                            val devices = rfidManager.getAllDevices()
                            val targetDevice =
                                    devices.find {
                                        it.first == DEFAULT_PRODUCT_ID &&
                                                it.second == DEFAULT_VENDOR_ID
                                    }

                            if (targetDevice != null) {
                                Log.i(
                                        TAG,
                                        "Found default RFID device, connecting... (attempt ${retryCount + 1}/$AUTO_CONNECT_RETRY_COUNT)"
                                )
                                rfidManager.connectDevice(DEFAULT_PRODUCT_ID, DEFAULT_VENDOR_ID)

                                // Đợi một chút để kiểm tra kết quả
                                delay(2000)

                                if (isConnected) {
                                    connected = true
                                    Log.i(TAG, "Auto-connect SUCCESS!")
                                    return@launch
                                }
                            } else {
                                Log.w(
                                        TAG,
                                        "Default RFID device not found (VID: 0x${DEFAULT_VENDOR_ID.toString(16)}, PID: 0x${DEFAULT_PRODUCT_ID.toString(16)})"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-connect error: ${e.message}", e)
                        }

                        retryCount++
                        if (retryCount < AUTO_CONNECT_RETRY_COUNT) {
                            Log.i(TAG, "⏳ Retrying in ${RETRY_DELAY_MS}ms...")
                            delay(RETRY_DELAY_MS)
                        }
                    }

                    // Nếu vẫn không kết nối được sau nhiều lần thử
                    if (!connected) {
                        Log.w(
                                TAG,
                                "⚠️ Auto-connect FAILED after $AUTO_CONNECT_RETRY_COUNT attempts"
                        )
                        reconnectFailCount++
                        isReconnecting = false
                        eventCallback?.onAutoConnectFailed()
                    }
                }
    }

    // ─── RFID Watchdog & Auto-Reconnect ───────────────────────────────────────

    /**
     * Khởi động watchdog định kỳ kiểm tra trạng thái kết nối.
     * Nếu phát hiện mất kết nối mà chưa có job reconnect, sẽ tự lên lịch kết nối lại.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            Log.d(TAG, "🐕 RFID Watchdog started")
            while (isActive) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                if (!isConnected && !isReconnecting && isInitialized) {
                    Log.w(TAG, "🐕 Watchdog: RFID disconnected — scheduling reconnect")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        Log.d(TAG, "🐕 RFID Watchdog stopped")
    }

    private fun scheduleReconnect() {
        if (isReconnecting || isConnected) return
        isReconnecting = true

        val backoffMs = minOf(
            RECONNECT_INITIAL_DELAY_MS * (1L shl reconnectFailCount.coerceAtMost(4)),
            RECONNECT_MAX_DELAY_MS
        )
        Log.i(TAG, "🔄 RFID reconnect in ${backoffMs}ms (attempt #${reconnectFailCount + 1})")

        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            delay(backoffMs)
            if (!isConnected && isInitialized) {
                Log.i(TAG, "🔌 Attempting RFID reconnect — soft-resetting SDK first...")

                // [BUG#2 FIX] Soft-reset: giải phóng usbCore cũ, GIỮ receiver
                // → tránh zombie usbCore và tránh double-register receiver (BUG#1)
                try {
                    rfidManager.softReset()
                } catch (e: Exception) {
                    Log.e(TAG, "softReset error: ${e.message}", e)
                }

                // Delay ngắn để USB stack ổn định sau khi release
                delay(200)

                // Khởi tạo lại usbCore (receiver đã có, softReset giữ lại)
                try {
                    rfidManager.init()
                } catch (e: Exception) {
                    Log.e(TAG, "init error during reconnect: ${e.message}", e)
                    reconnectFailCount++
                    isReconnecting = false
                    return@launch
                }

                // Thực hiện kết nối lại
                rfidManager.connectDevice(DEFAULT_PRODUCT_ID, DEFAULT_VENDOR_ID)

                // Đợi kết quả từ onUsbConnectDone callback
                delay(2500)

                if (!isConnected) {
                    reconnectFailCount++
                    isReconnecting = false
                    Log.w(TAG, "⚠️ RFID reconnect attempt failed (total fails: $reconnectFailCount)")
                }
                // Nếu thành công → onConnectionStatus(true) reset reconnectFailCount + isReconnecting
            } else {
                isReconnecting = false
            }
        }
    }

    /** Kết nối thủ công với PID/VID chỉ định */
    fun connectManually(pid: Int, vid: Int) {
        Log.i(TAG, "🔌 Manual connect: PID=0x${pid.toString(16)}, VID=0x${vid.toString(16)}")
        autoConnectJob?.cancel()
        rfidManager.connectDevice(pid, vid)
    }

    /** Ngắt kết nối */
    fun disconnect() {
        Log.i(TAG, "🔌 Disconnecting...")
        stopScanning()
        rfidManager.release()
        isConnected = false
    }

    /** Bắt đầu quét thẻ RFID */
    fun startScanning() {
        if (!isConnected) {
            eventCallback?.onError("Chưa kết nối với đầu đọc RFID")
            return
        }

        Log.i(TAG, "📡 Starting RFID scan...")
        rfidManager.startScanning()
        isScanning = true
    }

    /** Dừng quét thẻ RFID */
    fun stopScanning() {
        if (!isScanning) return

        Log.i(TAG, "⏸️ Stopping RFID scan...")
        rfidManager.stopScanning()
        isScanning = false
    }

    fun forceStopScanning() {
        if (isConnected) {
            Log.i(TAG, "⏹️ Force-stopping RFID scan...")
            rfidManager.stopScanning()
        }
        isScanning = false
    }

    /** Reset/Clear the device state when transitioning to IDLE using Module Init Cmd */
    fun clearDeviceState() {
        if (!isConnected) return
        Log.i(TAG, "🧹 Clearing device memory in preparation for a new scan...")
        rfidManager.clearDeviceState()
    }

    /** Lấy danh sách tất cả USB devices (PID/VID) */
    fun getAllDevices(): List<Pair<Int, Int>> {
        return rfidManager.getAllDevices()
    }

    /** Đặt callback cho events */
    fun setEventCallback(callback: RfidEventCallback?) {
        this.eventCallback = callback
    }

    /** Giải phóng tài nguyên */
    fun release() {
        Log.i(TAG, "🧹 Releasing resources...")
        autoConnectJob?.cancel()
        stopWatchdog()
        isReconnecting = false
        stopScanning()
        rfidManager.release()
        isInitialized = false
        isConnected = false
        reconnectFailCount = 0
    }

    /** Lấy VID/PID mặc định */
    fun getDefaultDevice(): Pair<Int, Int> {
        return Pair(DEFAULT_PRODUCT_ID, DEFAULT_VENDOR_ID)
    }
}
