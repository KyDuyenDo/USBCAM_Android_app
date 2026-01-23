package com.example.usbcam.rfid

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.usbcam.R

/**
 * RfidSettingsFragment
 * 
 * Màn hình SETTING để:
 * - Hiển thị trạng thái kết nối RFID
 * - Cho phép chọn thiết bị USB RFID thủ công
 * - Kiểm tra danh sách thiết bị đang cắm
 */
class RfidSettingsFragment : DialogFragment() {

    companion object {
        private const val TAG = "RfidSettingsFragment"
        
        fun newInstance() = RfidSettingsFragment()
    }

    private lateinit var connectionManager: RfidConnectionManager
    
    // UI Components
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClose: Button
    private lateinit var llDeviceSelection: LinearLayout
    
    private var deviceList: List<Pair<Int, Int>> = emptyList()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_rfid_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status)
        tvDeviceInfo = view.findViewById(R.id.tv_device_info)
        spinnerDevices = view.findViewById(R.id.spinner_usb_devices)
        btnConnect = view.findViewById(R.id.btn_connect)
        btnDisconnect = view.findViewById(R.id.btn_disconnect)
        btnRefresh = view.findViewById(R.id.btn_refresh_devices)
        btnClose = view.findViewById(R.id.btn_close_settings)
        llDeviceSelection = view.findViewById(R.id.ll_device_selection)
        
        // Initialize connection manager
        connectionManager = RfidConnectionManager.getInstance(requireContext())
        
        setupUI()
        refreshDeviceList()
        updateConnectionStatus()
    }

    private fun setupUI() {
        btnConnect.setOnClickListener {
            connectToSelectedDevice()
        }
        
        btnDisconnect.setOnClickListener {
            disconnectDevice()
        }
        
        btnRefresh.setOnClickListener {
            refreshDeviceList()
        }
        
        btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun refreshDeviceList() {
        deviceList = connectionManager.getAllDevices()
        
        Log.d(TAG, "Found ${deviceList.size} USB devices")
        
        val deviceStrings = if (deviceList.isNotEmpty()) {
            deviceList.mapIndexed { index, device ->
                val defaultDevice = connectionManager.getDefaultDevice()
                val isDefault = device.first == defaultDevice.first && device.second == defaultDevice.second
                val marker = if (isDefault) " ⭐ (Mặc định)" else ""
                "${index + 1}. PID: 0x${device.first.toString(16).uppercase()} | VID: 0x${device.second.toString(16).uppercase()}$marker"
            }
        } else {
            listOf("Không tìm thấy USB device")
        }
        
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            deviceStrings
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
        
        // Auto-select default device if exists
        val defaultDevice = connectionManager.getDefaultDevice()
        deviceList.forEachIndexed { index, device ->
            if (device.first == defaultDevice.first && device.second == defaultDevice.second) {
                spinnerDevices.setSelection(index)
                return@forEachIndexed
            }
        }
        
        updateConnectionStatus()
    }

    private fun connectToSelectedDevice() {
        if (deviceList.isEmpty()) {
            updateStatus("❌ Không có thiết bị để kết nối")
            return
        }
        
        val selectedPosition = spinnerDevices.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= deviceList.size) {
            updateStatus("❌ Vui lòng chọn thiết bị")
            return
        }
        
        val device = deviceList[selectedPosition]
        updateStatus("🔄 Đang kết nối...")
        
        connectionManager.connectManually(device.first, device.second)
        
        // Update status after a delay
        view?.postDelayed({
            updateConnectionStatus()
        }, 2000)
    }

    private fun disconnectDevice() {
        updateStatus("🔌 Đang ngắt kết nối...")
        connectionManager.disconnect()
        
        view?.postDelayed({
            updateConnectionStatus()
        }, 500)
    }

    private fun updateConnectionStatus() {
        val isConnected = connectionManager.isConnected
        val isScanning = connectionManager.isScanning
        
        if (isConnected) {
            val statusText = if (isScanning) {
                "✅ Đã kết nối - Đang quét thẻ"
            } else {
                "✅ Đã kết nối RFID"
            }
            updateStatus(statusText)
            
            // Show device info
            val defaultDevice = connectionManager.getDefaultDevice()
            tvDeviceInfo.text = "PID: 0x${defaultDevice.first.toString(16).uppercase()} | VID: 0x${defaultDevice.second.toString(16).uppercase()}"
            tvDeviceInfo.visibility = View.VISIBLE
            
            btnConnect.isEnabled = false
            btnDisconnect.isEnabled = true
            llDeviceSelection.alpha = 0.5f
            
        } else {
            updateStatus("❌ Chưa kết nối")
            tvDeviceInfo.visibility = View.GONE
            
            btnConnect.isEnabled = deviceList.isNotEmpty()
            btnDisconnect.isEnabled = false
            llDeviceSelection.alpha = 1.0f
        }
    }

    private fun updateStatus(message: String) {
        tvConnectionStatus.text = message
        Log.d(TAG, message)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't release connection manager here - it's singleton
    }
}
