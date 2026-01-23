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
 * Manages USB connection to RFID reader and provides tag scanning functionality.
 * Singleton pattern to ensure only one instance manages the RFID device.
 */
class RfidManager private constructor(private val context: Context) : IUsbConnectDone, IReadDataCallback {

    companion object {
        private const val TAG = "RfidManager"
        private const val ACTION_USB_PERMISSION = "com.example.usbcam.USB_PERMISSION"
        
        @Volatile
        private var instance: RfidManager? = null

        fun getInstance(context: Context): RfidManager {
            return instance ?: synchronized(this) {
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
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
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
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (device != null && targetDevice != null &&
                        device.productId == targetDevice?.productId &&
                        device.vendorId == targetDevice?.vendorId) {
                        Log.w(TAG, "RFID device detached")
                        isConnected = false
                        callback?.onConnectionStatus(false, "RFID device disconnected")
                        release()
                    }
                }
            }
        }
    }

    /**
     * Initialize the RFID SDK
     */
    fun init() {
        try {
            if (usbCore == null) {
                // Initialize SDK first before getting USB core (as per cf-sdk manual)
                CfSdk.load()
                
                usbCore = CfSdk.get(SdkC.USB)
                usbCore?.init(context)
                Log.d(TAG, "CF SDK initialized successfully")
            }
            
            // Register USB receiver
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            context.registerReceiver(usbReceiver, filter)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SDK: ${e.message}", e)
            callback?.onError("Failed to initialize RFID SDK: ${e.message}")
        }
    }

    /**
     * Set callback for tag reading events
     */
    fun setCallback(callback: TagReadCallback?) {
        this.callback = callback
    }

    /**
     * Get all connected USB devices (PID/VID pairs)
     */
    fun getAllDevices(): List<Pair<Int, Int>> {
        return try {
            // Convert android.util.Pair to kotlin.Pair
            usbCore?.getAllDevicePidAndVid()?.map { androidPair ->
                Pair(androidPair.first, androidPair.second)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting USB devices: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Connect to RFID device by PID and VID
     */
    fun connectDevice(pid: Int, vid: Int) {
        try {
            if (usbCore == null) {
                init()
            }
            
            targetDevice = usbCore?.findTargetDevice(pid, vid)
            if (targetDevice != null) {
                // Request USB permission
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
                
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

    /**
     * Internal method to connect to device after permission granted
     */
    private fun connectToDevice(device: UsbDevice) {
        try {
            usbCore?.connectDevice(context, device, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectDevice: ${e.message}", e)
            callback?.onError("Failed to connect: ${e.message}")
        }
    }

    /**
     * Start RFID tag inventory (scanning)
     */
    fun startScanning() {
        if (!isConnected) {
            callback?.onError("RFID device not connected")
            return
        }
        
        try {
            val cmdBytes = CmdBuilder.buildInventoryISOContinueCmd(0.toByte(), 0)
            val success = usbCore?.writeData(cmdBytes, 50) ?: false
            
            if (success) {
                isScanning = true
                Log.d(TAG, "Started RFID scanning")
                callback?.onConnectionStatus(true, "Scanning started")
            } else {
                callback?.onError("Failed to send scan command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}", e)
            callback?.onError("Failed to start scanning: ${e.message}")
        }
    }

    /**
     * Stop RFID tag inventory (scanning)
     */
    fun stopScanning() {
        try {
            val cmdBytes = CmdBuilder.buildStopInventoryCmd()
            val success = usbCore?.writeData(cmdBytes, 50) ?: false
            
            if (success) {
                isScanning = false
                Log.d(TAG, "Stopped RFID scanning")
                callback?.onConnectionStatus(true, "Scanning stopped")
            } else {
                callback?.onError("Failed to send stop command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
            callback?.onError("Failed to stop scanning: ${e.message}")
        }
    }

    /**
     * Check if currently scanning
     */
    fun isScanning(): Boolean = isScanning

    /**
     * Check if device is connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Release resources
     */
    fun release() {
        try {
            isScanning = false
            isConnected = false
            usbCore?.setIReadDataCallback(null)
            usbCore?.release()
            usbCore = null
            targetDevice = null
            
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
            
            Log.d(TAG, "RFID Manager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing: ${e.message}", e)
        }
    }

    // IUsbConnectDone implementation
    override fun onUsbConnectDone(success: Boolean) {
        isConnected = success
        
        if (success) {
            Log.d(TAG, "USB RFID device connected successfully")
            callback?.onConnectionStatus(true, "Connected to RFID reader")
            
            // Query device parameters to check/switch to USB App Mode
            try {
                val paramCmd = CmdBuilder.buildGetAllParamCmd()
                usbCore?.writeData(paramCmd, 500)
                
                // Start reading data asynchronously
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

    // IReadDataCallback implementation
    override fun onDataBack(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        
        try {
            val cmdType = CmdHandler.getCmdType(bytes)
            
            when (cmdType) {
                CmdType.TYPE_GET_ALL_PARAM -> {
                    handleAllParamResponse(bytes)
                }
                CmdType.TYPE_INVENTORY -> {
                    handleInventoryResponse(bytes)
                }
                else -> {
                    Log.d(TAG, "Received command type: $cmdType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing USB data: ${e.message}", e)
        }
    }

    /**
     * Handle device parameter response and auto-switch to USB mode if needed
     */
    private fun handleAllParamResponse(bytes: ByteArray) {
        try {
            val obj = CmdHandler.handleCmd(CmdType.TYPE_GET_ALL_PARAM, bytes)
            val cmdData = CmdData(obj)
            val allParam = cmdData.data as? AllParamBean
            
            if (allParam != null && allParam.mStatus == 0) {
                // Check if Interface is not USB App Mode (0x01)
                if (allParam.mInterface.toInt() != 0x01) {
                    Log.d(TAG, "Switching from mode ${allParam.mInterface} to USB App Mode (0x01)")
                    
                    // Force set to USB mode
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
     * Handle inventory (tag read) response
     */
    private fun handleInventoryResponse(bytes: ByteArray) {
        try {
            val obj = CmdHandler.handleCmd(CmdType.TYPE_INVENTORY, bytes)
            
            if (obj is TagInfoBean) {
                if (obj.mEPCNum != null && obj.mEPCNum.isNotEmpty()) {
                    val epc = FormatUtil.bytesToHexStrNoSpace(obj.mEPCNum)
                    val rssi = obj.mRSSI?.toInt() ?: 0
                    val antenna = obj.mAntenna?.toInt() ?: 0
                    val channel = obj.mChannel?.toInt() ?: 0
                    
                    Log.d(TAG, "Tag Read - EPC: $epc, RSSI: $rssi, Antenna: $antenna, Channel: $channel")
                    callback?.onTagRead(epc, rssi, antenna, channel)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling inventory response: ${e.message}", e)
        }
    }
}
