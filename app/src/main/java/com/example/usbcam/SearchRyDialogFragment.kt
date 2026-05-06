package com.example.usbcam

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.ReportApiService
import com.example.usbcam.api.SearchRyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchRyDialogFragment : DialogFragment() {

    private lateinit var rvSearchRy: RecyclerView
    private lateinit var etSearchRy: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var btnCloseDialog: ImageView
    private lateinit var btnQueue: Button
    private lateinit var btnServerCode: Button
    private lateinit var tvResultCount: TextView
    private lateinit var adapter: SearchRyAdapter
    
    private val apiService = ReportApiService.create()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_search_ry, container, false)
        
        rvSearchRy = view.findViewById(R.id.rv_search_ry)
        etSearchRy = view.findViewById(R.id.et_search_ry)
        btnClearSearch = view.findViewById(R.id.btn_clear_search)
        btnCloseDialog = view.findViewById(R.id.btn_close_dialog)
        btnQueue = view.findViewById(R.id.btn_queue)
        btnServerCode = view.findViewById(R.id.btn_server_code)
        tvResultCount = view.findViewById(R.id.tv_search_result_count)

        setupRecyclerView()
        updateLineDisplay()
        setupListeners()
        
        // Initial search to display "a" as requested in screenshot
        etSearchRy.setText("a")
        
        return view
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupRecyclerView() {
        adapter = SearchRyAdapter(emptyList()) { item ->
            // Deprecated callback, now handled directly in Adapter via QueueManager
        }
        rvSearchRy.layoutManager = LinearLayoutManager(context)
        rvSearchRy.adapter = adapter
    }

    private fun setupListeners() {
        btnCloseDialog.setOnClickListener {
            dismiss()
        }

        btnClearSearch.setOnClickListener {
            etSearchRy.text.clear()
        }
        
        btnQueue.setOnClickListener {
            if (QueueManager.queuedItems.isEmpty()) {
                Toast.makeText(context, "Hàng chờ trống", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            QueueInfoDialogFragment.newInstance()
                .show(parentFragmentManager, "QueueInfoDialog")
        }

        btnServerCode.setOnClickListener {
            val dialog = LineSelectionDialogFragment()
            dialog.onLineSelected = { selectedLine ->
                updateLineDisplay()
                // Re-trigger search with new factory if query exists
                val query = etSearchRy.text.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                }
            }
            dialog.show(parentFragmentManager, "LineSelectionDialog")
        }

        etSearchRy.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                } else {
                    adapter.updateData(emptyList())
                    tvResultCount.text = "Kết quả tìm kiếm (0)"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateLineDisplay() {
        val currentLine = com.example.usbcam.utils.LinePreferences.getSelectedLine(requireContext())
        btnServerCode.text = currentLine
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            try {
                val factory = com.example.usbcam.utils.LinePreferences.getSelectedFactory(requireContext()) ?: "LHG"
                val response = withContext(Dispatchers.IO) {
                    apiService.searchRy(query, factory)
                }
                if (response.isSuccessful) {
                    val data = response.body() ?: emptyList()
                    adapter.updateData(data)
                    tvResultCount.text = "Kết quả tìm kiếm (${data.size})"
                } else {
                    Log.e("SearchRyDialog", "Error fetching data: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("SearchRyDialog", "Exception fetching data", e)
            }
        }
    }
}
