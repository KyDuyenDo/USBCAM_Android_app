package com.example.usbcam.rfid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.example.usbcam.R
import com.example.usbcam.databinding.FragmentRfidScannerBinding
import com.example.usbcam.viewmodel.RfidViewModel

/**
 * RfidScannerFragment - RFID Scanner UI with MVVM
 * 
 * Features:
 * - Displays RFID connection status
 * - Shows scanned tag information
 * - Displays product info from API
 * - Settings button in title bar
 */
class RfidScannerFragment : DialogFragment() {

    companion object {
        private const val TAG = "RfidScannerFragment"
        
        fun newInstance() = RfidScannerFragment()
    }

    private var _binding: FragmentRfidScannerBinding? = null
    private val binding get() = _binding!!
    
    private val rfidViewModel: RfidViewModel by viewModels()
    private lateinit var connectionManager: RfidConnectionManager
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRfidScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupConnectionManager()
        setupObservers()
        setupClickListeners()
    }

    private fun setupConnectionManager() {
        connectionManager = RfidConnectionManager.getInstance(requireContext())
        
        connectionManager.setEventCallback(object : RfidConnectionManager.RfidEventCallback {
            override fun onConnected(isAutoConnect: Boolean) {
                activity?.runOnUiThread {
                    rfidViewModel.setConnected(true)
                    rfidViewModel.setScanning(true)
                    
                    // Auto-start scanning
                    if (!connectionManager.isScanning) {
                        connectionManager.startScanning()
                    }
                }
            }

            override fun onDisconnected() {
                activity?.runOnUiThread {
                    rfidViewModel.setConnected(false)
                }
            }

            override fun onTagRead(epc: String, rssi: Int, antenna: Int, channel: Int) {
                activity?.runOnUiThread {
                    rfidViewModel.onTagRead(epc, rssi, antenna, channel)
                }
            }

            override fun onError(message: String) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Error: $message", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAutoConnectFailed() {
                activity?.runOnUiThread {
                    // Auto-open settings if connection failed
                    openSettings()
                }
            }
        })
    }

    private fun setupObservers() {
        // Connection status
        rfidViewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.tvConnectionStatus.text = status
        }

        // Last scanned EPC
        rfidViewModel.lastEpc.observe(viewLifecycleOwner) { epc ->
            binding.tvEpcValue.text = if (epc.isNotEmpty()) epc else "---"
        }

        // RSSI
        rfidViewModel.lastRssi.observe(viewLifecycleOwner) { rssi ->
            binding.tvRssiValue.text = "$rssi dBm"
        }

        // RFID product data
        rfidViewModel.rfidData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                binding.llProductInfo.visibility = View.VISIBLE
                binding.tvProductModel.text = data.model
                binding.tvProductArticle.text = data.article
                binding.tvProductPo.text = data.po
                binding.tvProductColor.text = data.color
                binding.tvProductSize.text = data.size
            } else {
                binding.llProductInfo.visibility = View.GONE
            }
        }

        // Loading state
        rfidViewModel.isLoadingRfidInfo.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // Error messages
        rfidViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.tvError.text = error
                binding.tvError.visibility = View.VISIBLE
                rfidViewModel.clearError()
            } else {
                binding.tvError.visibility = View.GONE
            }
        }

        // Info messages
        rfidViewModel.infoMessage.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                Toast.makeText(requireContext(), info, Toast.LENGTH_SHORT).show()
                rfidViewModel.clearInfo()
            }
        }
    }

    private fun setupClickListeners() {
        // Settings button in title bar
        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        // Close button
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            rfidViewModel.refresh()
        }

        // Clear button
        binding.btnClear.setOnClickListener {
            rfidViewModel.clearRfidData()
        }
    }

    private fun openSettings() {
        val settingsDialog = RfidSettingsFragment.newInstance()
        settingsDialog.show(parentFragmentManager, "RfidSettingsDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionManager.setEventCallback(null)
        _binding = null
    }
}
