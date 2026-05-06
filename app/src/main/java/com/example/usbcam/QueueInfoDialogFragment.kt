package com.example.usbcam

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.ReportApiService
import com.example.usbcam.api.ScbbContextSaverRequest
import com.example.usbcam.db.ProductionDbHelper
import com.example.usbcam.utils.LinePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QueueInfoDialogFragment : DialogFragment() {

    // ── UI references ──────────────────────────────────────────────────────
    private lateinit var tvHeaderZlbh: TextView
    private lateinit var tvHeaderDon: TextView
    private lateinit var tvMainZlbh: TextView
    private lateinit var btnBack: View
    private lateinit var btnSettings: Button
    private lateinit var btnSync: Button
    private lateinit var btnConfirm: Button
    private lateinit var rvQueueSizes: RecyclerView
    private lateinit var rvQueuedRys: RecyclerView

    // ── Adapters ───────────────────────────────────────────────────────────
    private lateinit var sizesAdapter: QueueInfoAdapter
    private lateinit var sidebarAdapter: QueueSidebarAdapter

    // ── Dependencies ───────────────────────────────────────────────────────
    private val apiService = ReportApiService.create()
    private lateinit var dbHelper: ProductionDbHelper
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ── State ──────────────────────────────────────────────────────────────
    private var currentIndex: Int = 0

    companion object {
        const val SERVER_CODE  = "LHG"
        const val DEFAULT_USER = "SYSTEM"

        fun newInstance(): QueueInfoDialogFragment = QueueInfoDialogFragment()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_queue_info, container, false)

        tvHeaderZlbh = view.findViewById(R.id.tv_header_zlbh)
        tvHeaderDon  = view.findViewById(R.id.tv_header_don)
        tvMainZlbh   = view.findViewById(R.id.tv_main_zlbh)
        btnBack      = view.findViewById(R.id.btn_back)
        btnSettings  = view.findViewById(R.id.btn_settings)
        btnSync      = view.findViewById(R.id.btn_sync)
        btnConfirm   = view.findViewById(R.id.btn_confirm)
        rvQueueSizes = view.findViewById(R.id.rv_queue_sizes)
        rvQueuedRys  = view.findViewById(R.id.rv_queued_rys)

        dbHelper = ProductionDbHelper(requireContext())

        currentIndex = 0
        setupUI()
        setupSidebar()
        setupSizesRecyclerView()
        loadCurrentRy()

        // Start hourly auto-save scheduler
        AutoSaveScheduler.start(requireContext())

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

    // ── UI Setup ───────────────────────────────────────────────────────────
    private fun setupUI() {
        btnBack.setOnClickListener { dismiss() }
        btnSettings.setOnClickListener {
            Toast.makeText(context, "Thiết Lập clicked", Toast.LENGTH_SHORT).show()
        }
        btnSync.setOnClickListener {
            saveCurrentToDb(source = "MANUAL_SYNC")
            Toast.makeText(context, "Đã lưu cục bộ & lên lịch đồng bộ", Toast.LENGTH_SHORT).show()
        }
        btnConfirm.setOnClickListener {
            confirmAndSend()
        }
    }

    private fun setupSidebar() {
        sidebarAdapter = QueueSidebarAdapter(QueueManager.queuedItems) { index ->
            currentIndex = index
            sidebarAdapter.selectedIndex = index
            loadCurrentRy()
        }
        rvQueuedRys.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvQueuedRys.adapter = sidebarAdapter
        sidebarAdapter.selectedIndex = currentIndex
    }

    private fun setupSizesRecyclerView() {
        sizesAdapter = QueueInfoAdapter(emptyList())
        rvQueueSizes.layoutManager = GridLayoutManager(context, 3)
        rvQueueSizes.adapter = sizesAdapter
    }

    // ── Data Loading ───────────────────────────────────────────────────────
    private fun loadCurrentRy() {
        if (QueueManager.queuedItems.isEmpty()) return

        val currentItem = QueueManager.queuedItems[currentIndex]
        val zlbh = currentItem.zlbh ?: "---"

        tvHeaderZlbh.text = zlbh
        tvHeaderDon.text  = "Đơn: $zlbh"
        tvMainZlbh.text   = zlbh

        val ry = currentItem.ry ?: return
        fetchData(ry)
    }

    private fun fetchData(ry: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.getInfoForRy(ry, "A")
                }
                if (response.isSuccessful) {
                    sizesAdapter.updateData(response.body() ?: emptyList())
                } else {
                    Log.e("QueueInfoDialog", "Error fetching data: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("QueueInfoDialog", "Exception fetching data", e)
            }
        }
    }

    // ── Save Logic ─────────────────────────────────────────────────────────

    /**
     * Saves all edited entries to local SQLite with the current ts slot.
     * Called automatically at :29 via [AutoSaveScheduler], or manually via Đồng Bộ.
     */
    private fun saveCurrentToDb(source: String = "CAMERA") {
        val currentItem = QueueManager.queuedItems.getOrNull(currentIndex) ?: return
        val scbh = currentItem.ry ?: return
        val gxlb = currentItem.lean

        // Use the line selected in app Settings
        val depNo    = LinePreferences.getSelectedLine(requireContext())
        val ts       = TimeSlotUtil.getCurrentTs()
        val userDate = dateFormatter.format(Date())

        for ((size, qty) in sizesAdapter.getInputEntries()) {
            dbHelper.upsertEntry(
                scbh        = scbh,
                depNo       = depNo,
                gsbh        = size,
                xxcc        = size,
                userId      = DEFAULT_USER,
                inputSource = source,
                gxlb        = gxlb,
                qty         = qty,
                userDate    = userDate,
                ts          = ts,
                serverCode  = SERVER_CODE
            )
        }
        Log.d("QueueInfoDialog", "saveCurrentToDb: source=$source ts=$ts depNo=$depNo items=${sizesAdapter.getInputEntries().size}")
    }

    /**
     * Confirm button: saves to local DB then immediately POSTs each entry to the API.
     */
    private fun confirmAndSend() {
        saveCurrentToDb(source = "CONFIRM")

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) { dbHelper.getPendingEntries() }
            var successCount = 0
            var failCount = 0

            for (entry in pending) {
                try {
                    val request = ScbbContextSaverRequest(
                        scbh        = entry.scbh,
                        depNo       = entry.depNo,
                        gsbh        = entry.gsbh,
                        xxcc        = entry.xxcc,
                        userId      = entry.userId,
                        inputSource = "CAMERA",
                        gxlb        = entry.gxlb,
                        qty         = entry.qty,
                        userDate    = entry.userDate
                    )
                    val response = withContext(Dispatchers.IO) {
                        apiService.saveStitchingData(entry.serverCode, request)
                    }
                    if (response.isSuccessful) {
                        withContext(Dispatchers.IO) { dbHelper.markSynced(entry.id) }
                        successCount++
                    } else {
                        Log.e("QueueInfoDialog", "confirm fail ${entry.id}: ${response.code()}")
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e("QueueInfoDialog", "confirm exception ${entry.id}", e)
                    failCount++
                }
            }

            val msg = if (failCount == 0)
                "✔ Đã lưu $successCount size thành công!"
            else
                "⚠ Thành công: $successCount / Lỗi: $failCount"

            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}
