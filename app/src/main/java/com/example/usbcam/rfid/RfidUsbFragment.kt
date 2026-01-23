package com.example.usbcam.rfid

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.usbcam.R

/**
 * RfidUsbFragment - USB RFID Scanner Dialog
 * 
 * Provides UI for connecting to USB RFID reader and scanning tags
 */
class RfidUsbFragment : DialogFragment(), RfidManager.TagReadCallback {

    private lateinit var rfidManager: RfidManager
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnStartScan: Button
    private lateinit var btnStopScan: Button
    private lateinit var btnClose: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLastEpc: TextView
    private lateinit var llLastResult: LinearLayout
    
    private var deviceList: List<Pair<Int, Int>> = emptyList()
    private var tagCallback: TagCallback? = null

    interface TagCallback {
        fun onRfidTagRead(epc: String)
    }

    fun setTagCallback(callback: TagCallback) {
        this.tagCallback = callback
    }

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
        return inflater.inflate(R.layout.fragment_rfid_usb, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        spinnerDevices = view.findViewById(R.id.spinner_usb_devices)
        btnConnect = view.findViewById(R.id.btn_connect_usb)
        btnStartScan = view.findViewById(R.id.btn_start_scan)
        btnStopScan = view.findViewById(R.id.btn_stop_scan)
        btnClose = view.findViewById(R.id.btn_close_usb)
        tvStatus = view.findViewById(R.id.tv_status)
        tvLastEpc = view.findViewById(R.id.tv_last_epc)
        llLastResult = view.findViewById(R.id.ll_last_result)

        // Initialize RFID Manager
        rfidManager = RfidManager.getInstance(requireContext())
        rfidManager.setCallback(this)
        rfidManager.init()

        setupUI()
        loadDevices()
    }

    private fun setupUI() {
        btnConnect.setOnClickListener {
            connectToSelectedDevice()
        }

        btnStartScan.setOnClickListener {
            rfidManager.startScanning()
        }

        btnStopScan.setOnClickListener {
            rfidManager.stopScanning()
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        updateButtonStates()
    }

    private fun loadDevices() {
        deviceList = rfidManager.getAllDevices()
        
        val deviceStrings = if (deviceList.isNotEmpty()) {
            deviceList.map { "PID: ${it.first} | VID: ${it.second}" }
        } else {
            listOf("No USB devices found")
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            deviceStrings
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
    }

    private fun connectToSelectedDevice() {
        if (deviceList.isEmpty()) {
            updateStatus("No devices to connect")
            return
        }

        val selectedPosition = spinnerDevices.selectedItemPosition
        val device = deviceList[selectedPosition]
        
        updateStatus("Connecting...")
        rfidManager.connectDevice(device.first, device.second)
    }

    private fun updateButtonStates() {
        val connected = rfidManager.isConnected()
        val scanning = rfidManager.isScanning()

        btnConnect.isEnabled = !connected
        btnStartScan.isEnabled = connected && !scanning
        btnStopScan.isEnabled = connected && scanning
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        Log.d("RfidUsbFragment", message)
    }

    // RfidManager.TagReadCallback implementations
    override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
        activity?.runOnUiThread {
            tvLastEpc.text = "EPC: $epc\nRSSI: ${rssi}dBm | Ant: $antenna | Ch: $channel"
            llLastResult.visibility = View.VISIBLE
            
            // Send to parent callback
            tagCallback?.onRfidTagRead(epc)
            
            Log.i("RfidUsbFragment", "Tag: $epc, RSSI: $rssi, Antenna: $antenna, Channel: $channel")
        }
    }

    override fun onError(message: String) {
        activity?.runOnUiThread {
            updateStatus("Error: $message")
            updateButtonStates()
        }
    }

    override fun onConnectionStatus(connected: Boolean, message: String) {
        activity?.runOnUiThread {
            updateStatus(message)
            updateButtonStates()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rfidManager.stopScanning()
        rfidManager.setCallback(null)
    }

    companion object {
        fun newInstance() = RfidUsbFragment()
    }
}
