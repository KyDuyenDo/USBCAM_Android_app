package com.example.usbcam.rfid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.cf.beans.AllParamBean
import com.cf.beans.CmdData
import com.cf.beans.GeneralBean
import com.cf.beans.TagInfoBean
import com.cf.usb.interfaces.IReadDataCallback
import com.cf.usb.interfaces.IUsbConnectDone
import com.cf.zsdk.CfSdk
import com.cf.zsdk.SdkC
import com.cf.zsdk.UsbCore
import com.cf.zsdk.cmd.CmdBuilder
import com.cf.zsdk.cmd.CmdHandler
import com.cf.zsdk.cmd.CmdType
import com.cf.zsdk.uitl.FormatUtil

/**
 * RfidManager - USB RFID Tag Reader Manager
 *
 * Singleton quản lý kết nối USB đến RFID reader và cung cấp chức năng scan tag.
 *
 * ═══════════════════════════════════════════════════════════════
 * CHANGELOG — BUG FIXES
 * ═══════════════════════════════════════════════════════════════
 *
 * [BUG-R1] Double-register BroadcastReceiver gây crash / missed events
 *   Root cause:
 *     • init() gọi registerReceiver() mỗi lần được gọi. Vì RfidManager là
 *       singleton và init() có thể bị gọi nhiều lần (connectDevice, softReset,
 *       reconnect flow), receiver bị đăng ký nhiều lần → IllegalArgumentException
 *       khi unregister, hoặc nhận event nhiều lần gây xử lý trùng.
 *   Fix:
 *     • Guard bằng isReceiverRegistered flag. registerReceiver() chỉ chạy một lần
 *       cho đến khi release() được gọi.
 *
 * [BUG-R2] softReset() unregister receiver → receiver bị mất sau reset
 *   Root cause:
 *     • Bản gốc không có softReset(), chỉ có release() — release() unregister
 *       receiver, sau đó khi connectDevice() → init() cố đăng ký lại nhưng nếu
 *       isReceiverRegistered vẫn = true (sau bug-fix R1), receiver không được
 *       đăng ký lại → miss USB_PERMISSION và DEVICE_DETACHED events.
 *   Fix:
 *     • softReset() giải phóng usbCore nhưng KHÔNG unregister receiver.
 *       isReceiverRegistered giữ nguyên = true → init() biết không cần re-register.
 *
 * [BUG-R3] startScanning() không stop thiết bị trước → device ở trạng thái lỗi
 *   Root cause:
 *     • Nếu thiết bị vẫn đang inventory từ session trước (sau reconnect, crash...),
 *       gửi lệnh inventory mới lên thiết bị đang busy → thiết bị bỏ qua hoặc
 *       báo lỗi → không đọc được tag mặc dù isScanning=true.
 *   Fix:
 *     • Gửi stop command và chờ 150ms trước khi gửi start command.
 *     • Retry 3 lần nếu writeData thất bại.
 *
 * [BUG-R4] handleInventoryResponse() không unwrap CmdData → ClassCastException
 *   Root cause:
 *     • CmdHandler.handleCmd() trả về Object bọc trong CmdData, không phải
 *       TagInfoBean trực tiếp. Cast thẳng sang TagInfoBean → ClassCastException
 *       → tất cả tag read bị drop vào catch block → không callback.
 *   Fix:
 *     • Unwrap qua CmdData(obj).data trước khi cast sang TagInfoBean.
 *     • Xử lý GeneralBean (status codes: 0x12 done, 0x13 no tag, 0x14 timeout).
 *
 * [BUG-R5] Android 13+ registerReceiver() thiếu flag → SecurityException
 *   Root cause:
 *     • registerReceiver() không truyền RECEIVER_EXPORTED hoặc
 *       RECEIVER_NOT_EXPORTED trên Android 13+ → SecurityException → receiver
 *       không đăng ký → USB events không được nhận.
 *   Fix:
 *     • Dùng ContextCompat.registerReceiver() với RECEIVER_NOT_EXPORTED.
 * ═══════════════════════════════════════════════════════════════
 */
class RfidManager private constructor(private val context: Context) :
    IUsbConnectDone, IReadDataCallback {

    companion object {
        private const val TAG = "RfidManager"
        private const val ACTION_USB_PERMISSION = "com.example.usbcam.USB_PERMISSION"

        @Volatile private var instance: RfidManager? = null

        fun getInstance(context: Context): RfidManager {
            return instance
                ?: synchronized(this) {
                    instance ?: RfidManager(context.applicationContext).also { instance = it }
                }
        }
    }

    interface TagReadCallback {
        fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int)
        fun onError(message: String)
        fun onConnectionStatus(connected: Boolean, message: String)
    }

    private var usbCore: UsbCore? = null
    private var targetDevice: UsbDevice? = null
    private var callback: TagReadCallback? = null
    private var isScanning = false
    private var isConnected = false

    // [BUG-R1 FIX] Track receiver registration để tránh double-register
    private var isReceiverRegistered = false

    // Tag deduplication cache
    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val TAG_CACHE_EXPIRY_MS = 1000L

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = getDeviceFromIntent(intent)

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                                connectToDevice(it)
                            }
                        } else {
                            Log.w(TAG, "USB permission denied")
                            callback?.onError("USB permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getDeviceFromIntent(intent)
                    if (device != null &&
                        targetDevice != null &&
                        device.productId == targetDevice?.productId &&
                        device.vendorId == targetDevice?.vendorId
                    ) {
                        Log.w(TAG, "RFID device detached")
                        isConnected = false
                        isScanning = false
                        callback?.onConnectionStatus(false, "RFID device disconnected")
                        // [BUG-R2 FIX] Dùng softReset thay vì release() để giữ receiver
                        softReset()
                    }
                }
            }
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────────

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    // ─── Public API ───────────────────────────────────────────────────────────────

    /**
     * Khởi tạo CF SDK và đăng ký BroadcastReceiver.
     *
     * [BUG-R1 FIX] Receiver chỉ được đăng ký một lần nhờ isReceiverRegistered guard.
     * [BUG-R5 FIX] Dùng ContextCompat.registerReceiver() với RECEIVER_NOT_EXPORTED
     *   để tương thích Android 13+ (targetSdk 33+).
     */
    fun init() {
        try {
            if (usbCore == null) {
                CfSdk.load()
                usbCore = CfSdk.get(SdkC.USB)
                usbCore?.init(context)
                Log.d(TAG, "CF SDK initialized successfully")
            }

            // [BUG-R1 FIX] Chỉ register nếu chưa registered
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(ACTION_USB_PERMISSION)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                // [BUG-R5 FIX] Android 13+ cần flag rõ ràng
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    usbReceiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isReceiverRegistered = true
                Log.d(TAG, "USB receiver registered")
            } else {
                Log.d(TAG, "USB receiver already registered — skipping")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SDK: ${e.message}", e)
            callback?.onError("Failed to initialize RFID SDK: ${e.message}")
        }
    }

    /**
     * Soft-reset: giải phóng usbCore cũ nhưng GIỮ LẠI receiver đã đăng ký.
     *
     * [BUG-R2 FIX] Dùng method này trước khi reconnect thay vì release().
     *   release() unregister receiver → init() sau đó không re-register được
     *   (nếu isReceiverRegistered vẫn = true do BUG-R1 fix).
     *   softReset() chỉ reset hardware connection, receiver vẫn sống để
     *   nhận ACTION_USB_PERMISSION khi requestPermission() gọi lại.
     */
    fun softReset() {
        try {
            if (isScanning) {
                // Cố gắng stop scan trước khi reset
                try {
                    val stopCmd = CmdBuilder.buildStopInventoryCmd()
                    usbCore?.writeData(stopCmd, 200)
                } catch (_: Exception) {}
            }
            isScanning = false
            isConnected = false
            tagCache.clear()
            usbCore?.setIReadDataCallback(null)
            usbCore?.release()
            usbCore = null
            targetDevice = null
            // [BUG-R2 FIX] isReceiverRegistered KHÔNG reset → init() sẽ không re-register
            Log.d(TAG, "RFID soft-reset complete (receiver kept, isReceiverRegistered=$isReceiverRegistered)")
        } catch (e: Exception) {
            Log.e(TAG, "Error during soft reset: ${e.message}", e)
        }
    }

    fun setCallback(callback: TagReadCallback?) {
        this.callback = callback
    }

    fun getAllDevices(): List<Pair<Int, Int>> {
        return try {
            usbCore?.getAllDevicePidAndVid()?.map { androidPair ->
                Pair(androidPair.first, androidPair.second)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting USB devices: ${e.message}", e)
            emptyList()
        }
    }

    /** Kết nối đến RFID device theo PID/VID */
    fun connectDevice(pid: Int, vid: Int) {
        try {
            if (usbCore == null) {
                init()
            }

            targetDevice = usbCore?.findTargetDevice(pid, vid)
            if (targetDevice != null) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION), flags
                )

                if (usbManager.hasPermission(targetDevice)) {
                    connectToDevice(targetDevice!!)
                } else {
                    usbManager.requestPermission(targetDevice, permissionIntent)
                }
            } else {
                callback?.onError("RFID device not found (PID: $pid, VID: $vid)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${e.message}", e)
            callback?.onError("Connection failed: ${e.message}")
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            usbCore?.connectDevice(context, device, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectDevice: ${e.message}", e)
            callback?.onError("Failed to connect: ${e.message}")
        }
    }

    /**
     * Bắt đầu scan RFID tag (inventory mode liên tục).
     *
     * [BUG-R3 FIX] Gửi stop command trước để đưa device về trạng thái sạch,
     *   tránh lỗi khi device vẫn đang inventory từ session cũ (sau reconnect,
     *   crash...). Sau đó retry start command tối đa 3 lần.
     */
    fun startScanning() {
        if (!isConnected) {
            callback?.onError("RFID device not connected")
            return
        }
        try {
            tagCache.clear()

            // [BUG-R3 FIX] Stop trước để đảm bảo device về trạng thái sạch
            val stopCmd = CmdBuilder.buildStopInventoryCmd()
            usbCore?.writeData(stopCmd, 200)
            Thread.sleep(150) // Chờ device xử lý stop

            // Sau đó mới gửi start
            val cmdBytes = CmdBuilder.buildInventoryISOContinueCmd(0.toByte(), 0)
            var success = false
            for (i in 0 until 3) {
                if (i > 0) Thread.sleep(100)
                success = usbCore?.writeData(cmdBytes, 200) ?: false
                if (success) break
                Log.w(TAG, "startScanning retry ${i + 1}/3")
            }

            if (success) {
                isScanning = true
                Log.d(TAG, "RFID scan started (continuous mode)")
            } else {
                callback?.onError("Failed to start scanning after 3 retries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}", e)
            callback?.onError("Failed to start scanning: ${e.message}")
        }
    }

    fun stopScanning() {
        if (!isConnected) return

        try {
            val cmdBytes = CmdBuilder.buildStopInventoryCmd()
            var success = false
            for (i in 0 until 3) {
                success = usbCore?.writeData(cmdBytes, 200) ?: false
                if (success) break
                Thread.sleep(10)
            }

            if (success) {
                isScanning = false
                Log.d(TAG, "Stopped RFID scanning")
            } else {
                callback?.onError("Failed to send stop command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
            callback?.onError("Failed to stop scanning: ${e.message}")
        }
    }

    fun isScanning(): Boolean = isScanning
    fun isConnected(): Boolean = isConnected

    fun clearDeviceState() {
        if (!isConnected) return
        try {
            val cmdBytes = CmdBuilder.buildModuleInitCmd()
            val success = usbCore?.writeData(cmdBytes, 50) ?: false
            if (success) {
                Log.d(TAG, "Sent Module Init Command to clear device state")
            } else {
                Log.e(TAG, "Failed to send Module Init command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing device state: ${e.message}", e)
        }
    }

    /**
     * Giải phóng toàn bộ tài nguyên kể cả receiver.
     * Chỉ gọi khi thực sự muốn shutdown hoàn toàn (không phải reconnect).
     * Để reconnect, dùng softReset() thay thế.
     */
    fun release() {
        try {
            if (isScanning) {
                try {
                    val stopCmd = CmdBuilder.buildStopInventoryCmd()
                    usbCore?.writeData(stopCmd, 200)
                } catch (_: Exception) {}
            }
            isScanning = false
            isConnected = false
            tagCache.clear()
            usbCore?.setIReadDataCallback(null)
            usbCore?.release()
            usbCore = null
            targetDevice = null

            // [BUG-R1 FIX] Chỉ unregister nếu đã register
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(usbReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Receiver not registered: ${e.message}")
                }
                isReceiverRegistered = false
            }

            Log.d(TAG, "RFID Manager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing: ${e.message}", e)
        }
    }

    // ─── IUsbConnectDone ──────────────────────────────────────────────────────────

    override fun onUsbConnectDone(success: Boolean) {
        isConnected = success

        if (success) {
            Log.d(TAG, "USB RFID device connected successfully")
            callback?.onConnectionStatus(true, "Connected to RFID reader")

            try {
                val paramCmd = CmdBuilder.buildGetAllParamCmd()
                usbCore?.writeData(paramCmd, 500)
                usbCore?.readDataAsync(10)
                usbCore?.setIReadDataCallback(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying device params: ${e.message}", e)
            }
        } else {
            Log.e(TAG, "USB RFID device connection failed")
            callback?.onConnectionStatus(false, "Connection failed")
        }
    }

    // ─── IReadDataCallback ────────────────────────────────────────────────────────

    override fun onDataBack(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        try {
            val cmdType = CmdHandler.getCmdType(bytes)

            when (cmdType) {
                CmdType.TYPE_GET_ALL_PARAM -> handleAllParamResponse(bytes)
                CmdType.TYPE_INVENTORY     -> handleInventoryResponse(bytes)
                CmdType.TYPE_STOP_INVENTORY -> {
                    Log.d(TAG, "Received STOP_INVENTORY confirm")
                    isScanning = false
                }
                else -> Log.d(TAG, "Received command type: $cmdType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing USB data: ${e.message}", e)
        }
    }

    // ─── Private Handlers ─────────────────────────────────────────────────────────

    private fun handleAllParamResponse(bytes: ByteArray) {
        try {
            val obj = CmdHandler.handleCmd(CmdType.TYPE_GET_ALL_PARAM, bytes)
            val cmdData = CmdData(obj)
            val allParam = cmdData.data as? AllParamBean

            if (allParam != null && allParam.mStatus == 0) {
                if (allParam.mInterface.toInt() != 0x01) {
                    Log.d(TAG, "Switching from mode ${allParam.mInterface} to USB App Mode (0x01)")
                    allParam.mInterface = 0x01
                    val setBytes = CmdBuilder.buildSetAllParamCmd(allParam)
                    usbCore?.writeData(setBytes, 500)
                    callback?.onConnectionStatus(true, "Switching to USB mode...")
                } else {
                    Log.d(TAG, "Already in USB App Mode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling param response: ${e.message}", e)
        }
    }

    /**
     * Xử lý response inventory (tag read).
     *
     * [BUG-R4 FIX] CmdHandler.handleCmd() trả về Object bọc trong CmdData.
     *   Phải unwrap qua CmdData(obj).data trước khi cast sang TagInfoBean.
     *   Bản gốc cast trực tiếp → ClassCastException → toàn bộ tag read bị
     *   drop vào catch block → không bao giờ gọi callback.onTagRead().
     *
     *   Cũng xử lý GeneralBean cho các status code đặc biệt:
     *     0x12 = inventory cycle complete (normal)
     *     0x13 = no tag in range (normal)
     *     0x14 = tag response timeout (warning)
     */
    private fun handleInventoryResponse(bytes: ByteArray) {
        try {
            // [BUG-R4 FIX] Unwrap đúng cách: handleCmd() → Object → CmdData → .data
            val obj = CmdHandler.handleCmd(CmdType.TYPE_INVENTORY, bytes)
            val cmdData = CmdData(obj)
            val tagInfo = cmdData.data as? TagInfoBean ?: run {
                // Có thể là GeneralBean báo trạng thái (no tag, done, timeout...)
                val general = cmdData.data as? GeneralBean
                if (general != null) {
                    when (general.mStatus) {
                        0x13 -> Log.d(TAG, "No tag found in range")
                        0x12 -> Log.d(TAG, "Inventory cycle complete")
                        0x14 -> Log.w(TAG, "Tag response timeout")
                        else -> Log.w(TAG, "Inventory status: 0x${general.mStatus.toString(16)}")
                    }
                } else {
                    Log.w(TAG, "Unknown inventory response type: ${cmdData.data?.javaClass?.simpleName}")
                }
                return
            }

            // Validate EPC
            if (tagInfo.mEPCNum == null || tagInfo.mEPCNum.isEmpty()) return
            val epc = FormatUtil.bytesToHexStrNoSpace(tagInfo.mEPCNum)
            if (!epc.matches("^[0-9A-Fa-f]+$".toRegex())) {
                Log.w(TAG, "Invalid EPC format: $epc")
                return
            }

            // Deduplication
            val now = System.currentTimeMillis()
            val lastSeen = tagCache[epc]
            if (lastSeen != null && (now - lastSeen) < TAG_CACHE_EXPIRY_MS) return
            tagCache[epc] = now

            val rssi    = tagInfo.mRSSI
            val antenna = tagInfo.mAntenna
            val channel = tagInfo.mChannel

            Log.d(TAG, "Tag Read — EPC: $epc, RSSI: $rssi, Ant: $antenna, Ch: $channel")
            callback?.onTagRead(epc, rssi, antenna, channel)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling inventory response: ${e.message}", e)
        }
    }
}