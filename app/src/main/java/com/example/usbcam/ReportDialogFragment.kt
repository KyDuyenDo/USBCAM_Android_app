package com.example.usbcam

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.*
import kotlinx.coroutines.launch
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class ReportDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "ReportDialog"
        private const val ARG_LINE = "line"
        fun newInstance(line: String): ReportDialogFragment =
            ReportDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_LINE, line) }
            }
    }

    private val api by lazy { ReportApiService.create() }
    private var line: String = "LHGG4G05"
    private var selectedDate: String = todayStr()
    private var selectedHourlyDate: String = todayStr()
    private var lastProNo: String = ""

    // Adapters
    private val scanAdapter   = ScanTableAdapter()
    private val sizeAdapter   = SizeDetailAdapter()
    private val erpAdapter    = ErpTableAdapter()
    private val erpSizeAdapter = SizeDetailAdapter()
    private val qtyAdapter    = QtyTableAdapter()
    private val qtyDetailAdapter = QtyDetailAdapter()
    private val hourlyAdapter = HourlyAdapter()

    private var currentSubTab = 0 // 0=scan, 1=erp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Material_Light_NoActionBar)
        line = arguments?.getString(ARG_LINE) ?: line
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.dialog_report, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClose(view)
        setupMainTabs(view)
        setupDailyPanel(view)
        setupQtyPanel(view)
        setupHourlyPanel(view)
        showMainTab(view, 0)
    }

    // ── Close ────────────────────────────────────────────────────────────────────
    private fun setupClose(v: View) {
        v.findViewById<ImageView>(R.id.btn_close_report).setOnClickListener { dismiss() }
    }

    // ── Main Tabs ────────────────────────────────────────────────────────────────
    private fun setupMainTabs(v: View) {
        v.findViewById<Button>(R.id.btn_tab_daily).setOnClickListener  { showMainTab(v, 0) }
        v.findViewById<Button>(R.id.btn_tab_qty).setOnClickListener    { showMainTab(v, 1) }
        v.findViewById<Button>(R.id.btn_tab_hourly).setOnClickListener { showMainTab(v, 2) }
    }

    private fun showMainTab(v: View, tab: Int) {
        val daily  = v.findViewById<View>(R.id.panel_daily)
        val qty    = v.findViewById<View>(R.id.panel_qty)
        val hourly = v.findViewById<View>(R.id.panel_hourly)
        val bDaily  = v.findViewById<Button>(R.id.btn_tab_daily)
        val bQty    = v.findViewById<Button>(R.id.btn_tab_qty)
        val bHourly = v.findViewById<Button>(R.id.btn_tab_hourly)

        daily.visibility  = if (tab == 0) View.VISIBLE else View.GONE
        qty.visibility    = if (tab == 1) View.VISIBLE else View.GONE
        hourly.visibility = if (tab == 2) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(requireContext(), R.color.primary_blue)
        val idle   = ContextCompat.getColor(requireContext(), R.color.bg_surface)
        val white  = ContextCompat.getColor(requireContext(), R.color.white)
        val grey   = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        listOf(bDaily to (tab == 0), bQty to (tab == 1), bHourly to (tab == 2)).forEach { (btn, sel) ->
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (sel) active else idle)
            btn.setTextColor(if (sel) white else grey)
        }

        when (tab) {
            0 -> loadDailyData(v)
            1 -> { /* qty is triggered by search */ }
            2 -> loadHourlyData(v)
        }
    }

    // ── Daily Panel ──────────────────────────────────────────────────────────────
    private fun setupDailyPanel(v: View) {
        // date picker
        v.findViewById<Button>(R.id.btn_pick_date).setOnClickListener { pickDate(v) }
        v.findViewById<Button>(R.id.btn_refresh_daily).setOnClickListener { loadDailyData(v) }

        // sub-tabs
        v.findViewById<Button>(R.id.btn_subtab_scan).setOnClickListener { switchSubTab(v, 0) }
        v.findViewById<Button>(R.id.btn_subtab_erp).setOnClickListener  { switchSubTab(v, 1) }

        // RecyclerViews
        v.findViewById<RecyclerView>(R.id.rv_scan_table).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = scanAdapter
        }
        v.findViewById<RecyclerView>(R.id.rv_size_detail).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sizeAdapter
        }
        v.findViewById<RecyclerView>(R.id.rv_erp_table).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = erpAdapter
        }
        v.findViewById<RecyclerView>(R.id.rv_erp_size_detail).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = erpSizeAdapter
        }

        // tap on erp row → load size detail
        erpAdapter.onItemClick = { item ->
            loadErpSizeDetail(v, item.ry ?: "")
        }

        // tap on scan row → load size detail
        scanAdapter.onItemClick = { item ->
            val ry = item.ry
            if (ry != null) {
                loadSizeDetail(v, ry)
            }
        }

        v.findViewById<TextView>(R.id.tv_selected_date).text = selectedDate
    }

    private fun switchSubTab(v: View, sub: Int) {
        currentSubTab = sub
        v.findViewById<View>(R.id.panel_scan).visibility = if (sub == 0) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.panel_erp).visibility  = if (sub == 1) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(requireContext(), R.color.primary_blue)
        val idle   = ContextCompat.getColor(requireContext(), R.color.bg_main)
        val white  = ContextCompat.getColor(requireContext(), R.color.white)
        val grey   = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        val bScan = v.findViewById<Button>(R.id.btn_subtab_scan)
        val bErp  = v.findViewById<Button>(R.id.btn_subtab_erp)
        bScan.backgroundTintList = android.content.res.ColorStateList.valueOf(if (sub == 0) active else idle)
        bScan.setTextColor(if (sub == 0) white else grey)
        bErp.backgroundTintList  = android.content.res.ColorStateList.valueOf(if (sub == 1) active else idle)
        bErp.setTextColor(if (sub == 1) white else grey)
    }

    private fun pickDate(v: View) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            selectedDate = "%04d-%02d-%02d".format(y, m + 1, d)
            v.findViewById<TextView>(R.id.tv_selected_date).text = selectedDate
            loadDailyData(v)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun loadDailyData(v: View) {
        val pb = v.findViewById<ProgressBar>(R.id.pb_daily)
        pb.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // Load scan summary
                val scanResp = api.getDataScan(line, selectedDate)
                if (scanResp.isSuccessful) {
                    val body = scanResp.body()
                    android.util.Log.d(TAG, "getDataScan success: size=${body?.size}, data=$body")
                    val item = body?.firstOrNull()
                    val sumLayout = v.findViewById<View>(R.id.layout_scan_summary)
                    if (item != null) {
                        sumLayout.visibility = View.VISIBLE
                        v.findViewById<TextView>(R.id.tv_scan_dep).text   = item.depName ?: "--"
                        v.findViewById<TextView>(R.id.tv_scan_date).text  = formatDate(item.scDate)
                        v.findViewById<TextView>(R.id.tv_scan_qty).text   = "${item.scannedQty ?: 0}"
                        v.findViewById<TextView>(R.id.tv_scan_erp).text   = "${item.qtyErp ?: 0}"
                        v.findViewById<TextView>(R.id.tv_scan_adjust).text = "${item.adjustQty ?: 0}"
                    } else {
                        sumLayout.visibility = View.GONE
                    }
                }

                // Load scan table
                val tableResp = api.getDataScanTable(line, selectedDate)
                if (tableResp.isSuccessful) {
                    val body = tableResp.body()
                    android.util.Log.d(TAG, "getDataScanTable success: size=${body?.size}")
                    scanAdapter.submitList(body ?: emptyList())
                }

                // Load ERP header
                val erpHeaderResp = api.getDataErpHeader(line, selectedDate)
                if (erpHeaderResp.isSuccessful) {
                    val body = erpHeaderResp.body()
                    android.util.Log.d(TAG, "getDataErpHeader success: size=${body?.size}, data=$body")
                    val h = body?.firstOrNull()
                    val erpSum = v.findViewById<View>(R.id.layout_erp_summary)
                    if (h != null) {
                        erpSum.visibility = View.VISIBLE
                        v.findViewById<TextView>(R.id.tv_sum_scdate).text = formatDate(h.scDate)
                        v.findViewById<TextView>(R.id.tv_sum_fty).text    = h.fty ?: "--"
                        v.findViewById<TextView>(R.id.tv_sum_dep).text    = h.depName ?: "--"
                        v.findViewById<TextView>(R.id.tv_sum_hours).text  = "${h.hours ?: 0.0}"
                        v.findViewById<TextView>(R.id.tv_sum_pairs).text  = "${h.pairs ?: 0}"
                        v.findViewById<TextView>(R.id.tv_sum_userid).text = h.userId ?: "--"
                        v.findViewById<TextView>(R.id.tv_sum_userdate).text = formatTime(h.userDate)
                        
                        lastProNo = h.proNo ?: ""
                    } else {
                        erpSum.visibility = View.GONE
                    }
                }

                // Load ERP table
                val erpTableResp = api.getDataErpTable(line)
                if (erpTableResp.isSuccessful) {
                    val body = erpTableResp.body()
                    android.util.Log.d(TAG, "getDataErpTable success: size=${body?.size}")
                    erpAdapter.submitList(body ?: emptyList())
                }

            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pb.visibility = View.GONE
            }
        }
    }

    private fun loadErpSizeDetail(v: View, ry: String) {
        lifecycleScope.launch {
            try {
                if (lastProNo.isNotEmpty()) {
                    val sizeResp = api.getDetailDataErp(lastProNo, ry)
                    if (sizeResp.isSuccessful) {
                        erpSizeAdapter.submitList(sizeResp.body() ?: emptyList())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadSizeDetail(v: View, ry: String) {
        lifecycleScope.launch {
            try {
                android.util.Log.d(TAG, "loadSizeDetail request: line=$line, date=$selectedDate, ry=$ry")
                val resp = api.getDataScanDetail(line, selectedDate)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    android.util.Log.d(TAG, "getDataScanDetail success: size=${body?.size}, date=$selectedDate")
                    // filter by RY and show size breakdown
                    val filtered = resp.body()?.filter { it.ry == ry } ?: emptyList()
                    android.util.Log.d(TAG, "getDataScanDetail filtered: size=${filtered.size}, ry=$ry")
                    // For size we use getDetailDataErp if needed; for now show scan detail
                    sizeAdapter.submitList(emptyList<ErpDetailItem>()) // reset
                    // Actually load size detail via ERP detail endpoint
                    // We need prono - try from cached erp header
                }
                // Load actual size detail from scan detail for the selected RY
                if (lastProNo.isNotEmpty()) {
                    val sizeResp = api.getDetailDataErp(lastProNo, ry)
                    android.util.Log.d(TAG, "getDetailDataErp request: prono=$lastProNo, ry=$ry")
                    if (sizeResp.isSuccessful) {
                        val body = sizeResp.body()
                        android.util.Log.d(TAG, "getDetailDataErp success: size=${body?.size}, ry=$ry")
                        sizeAdapter.submitList(body ?: emptyList())
                    }
                } else {
                    Toast.makeText(context, "Không có ProNo để lấy chi tiết size", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }
    }

    // ── Qty Panel ────────────────────────────────────────────────────────────────
    private fun setupQtyPanel(v: View) {
        v.findViewById<RecyclerView>(R.id.rv_qty_summary).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = qtyAdapter
        }
        v.findViewById<RecyclerView>(R.id.rv_qty_detail).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = qtyDetailAdapter
        }

        v.findViewById<Button>(R.id.btn_query_ry).setOnClickListener {
            val ry = v.findViewById<EditText>(R.id.et_ry_query).text.toString().trim()
            if (ry.isEmpty()) {
                Toast.makeText(context, "Nhập mã RY", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadQtyData(v, ry)
        }
    }

    private fun loadQtyData(v: View, ry: String) {
        lifecycleScope.launch {
            try {
                // Load Summary
                val resp = api.getReportByRY(ry)
                if (resp.isSuccessful) {
                    qtyAdapter.submitList(resp.body() ?: emptyList())
                }
                // Load Detail
                val detResp = api.getReportDetailByRY(ry)
                if (detResp.isSuccessful) {
                    qtyDetailAdapter.submitList(detResp.body() ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Qty data: ${e.message}")
            }
        }
    }

    // ── Hourly Panel ─────────────────────────────────────────────────────────────
    private fun setupHourlyPanel(v: View) {
        v.findViewById<RecyclerView>(R.id.rv_hourly_table).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = hourlyAdapter
        }
        v.findViewById<Button>(R.id.btn_pick_hourly_date).setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedHourlyDate = "%04d-%02d-%02d".format(y, m + 1, d)
                v.findViewById<TextView>(R.id.tv_hourly_date).text = selectedHourlyDate
                loadHourlyData(v)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        v.findViewById<Button>(R.id.btn_refresh_hourly).setOnClickListener { loadHourlyData(v) }
        v.findViewById<TextView>(R.id.tv_hourly_date).text = selectedHourlyDate
    }

    private fun loadHourlyData(v: View) {
        val pb = v.findViewById<ProgressBar>(R.id.pb_hourly)
        pb.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = api.getReportByHours(line, selectedHourlyDate)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    android.util.Log.d(TAG, "getReportByHours success: size=${body?.size}, date=$selectedHourlyDate")
                    hourlyAdapter.submitList(body ?: emptyList())
                } else {
                    Toast.makeText(context, "Không có dữ liệu hàng giờ", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG,"Lỗi: ${e.message}")
            } finally {
                pb.visibility = View.GONE
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private fun todayStr(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrEmpty()) return "--"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso) ?: return iso
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        } catch (_: Exception) { iso.take(10) }
    }

    private fun formatTime(iso: String?): String {
        if (iso.isNullOrEmpty()) return "--"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso) ?: return iso
            val out = SimpleDateFormat("HH:mm", Locale.getDefault())
            out.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            out.format(date)
        } catch (_: Exception) { iso }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  ADAPTERS
    // ════════════════════════════════════════════════════════════════════════════

    // ── Scan Table Adapter ───────────────────────────────────────────────────────
    inner class ScanTableAdapter : RecyclerView.Adapter<ScanTableAdapter.VH>() {
        var onItemClick: ((ScanTableItem) -> Unit)? = null
        private var list = listOf<ScanTableItem>()
        private var selectedRy: String? = null
        fun submitList(l: List<ScanTableItem>) { list = l; notifyDataSetChanged() }
        fun clearSelection() { selectedRy = null; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ry        = v.findViewById<TextView>(R.id.tv_row_ry)
            val art       = v.findViewById<TextView>(R.id.tv_row_art)
            val model     = v.findViewById<TextView>(R.id.tv_row_model)
            val order     = v.findViewById<TextView>(R.id.tv_row_order)
            val scan      = v.findViewById<TextView>(R.id.tv_row_scan)
            val erp       = v.findViewById<TextView>(R.id.tv_row_erp)
            val adjust    = v.findViewById<TextView>(R.id.tv_row_adjust)
            val remaining = v.findViewById<TextView>(R.id.tv_row_remaining)
            val dateId    = v.findViewById<TextView>(R.id.tv_row_dateid)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_scan_row, p, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = list[pos]
            val isSel = item.ry == selectedRy
            
            h.ry.text        = item.ry ?: "--"
            h.art.text       = item.article ?: "--"
            h.model.text     = item.model ?: "--"
            h.order.text     = "${item.orderQty ?: 0}"
            h.scan.text      = "${item.scannedQty ?: 0}"
            h.erp.text       = "${item.erpQty ?: 0}"
            h.adjust.text    = "${item.adjustQty ?: 0}"
            h.remaining.text = "${item.remaining ?: 0}"
            h.dateId.text    = item.dateId ?: ""

            val ctx = h.itemView.context
            val rem = item.remaining ?: 0
            
            if (isSel) {
                h.itemView.setBackgroundColor(ContextCompat.getColor(ctx, R.color.primary_blue))
                listOf(h.ry, h.art, h.model, h.order, h.scan, h.erp, h.adjust, h.remaining, h.dateId).forEach {
                    it.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                }
            } else {
                h.itemView.setBackgroundColor(if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
                listOf(h.ry, h.art, h.order, h.scan, h.erp, h.adjust, h.dateId).forEach {
                    it.setTextColor(0xFF1A202C.toInt())
                }
                h.model.setTextColor(0xFF4A5568.toInt())
                h.remaining.setTextColor(ContextCompat.getColor(ctx, if (rem > 0) R.color.error_red else R.color.success_green))
            }
            
            h.itemView.setOnClickListener { 
                selectedRy = item.ry
                notifyDataSetChanged()
                onItemClick?.invoke(item) 
            }
        }
    }

    // ── Size Detail Adapter ───────────────────────────────────────────────────────
    inner class SizeDetailAdapter : RecyclerView.Adapter<SizeDetailAdapter.VH>() {
        private var list = listOf<SizeRow>()
        fun submitList(l: List<ErpDetailItem>) {
            list = l.map { SizeRow(it.size ?: "--", it.quantity ?: 0, it.qtyed ?: 0) }
            notifyDataSetChanged()
        }
        fun submitErpList(l: List<ErpDetailItem>) = submitList(l)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val size = v.findViewById<TextView>(R.id.tv_size_label)
            val qty  = v.findViewById<TextView>(R.id.tv_size_qty)
            val ok   = v.findViewById<TextView>(R.id.tv_size_ok)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_size_row, p, false))

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            h.size.text = list[pos].size
            h.qty.text  = "${list[pos].qty}"
            h.ok.text   = "${list[pos].ok}"
            h.itemView.setBackgroundColor(
                if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
        }
    }

    // ── ERP Table Adapter ─────────────────────────────────────────────────────────
    inner class ErpTableAdapter : RecyclerView.Adapter<ErpTableAdapter.VH>() {
        var onItemClick: ((ErpTableItem) -> Unit)? = null
        private var list = listOf<ErpTableItem>()
        fun submitList(l: List<ErpTableItem>) { list = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ry      = v.findViewById<TextView>(R.id.tv_erp_ry)
            val gxlb    = v.findViewById<TextView>(R.id.tv_erp_gxlb)
            val article = v.findViewById<TextView>(R.id.tv_erp_article)
            val model   = v.findViewById<TextView>(R.id.tv_erp_model)
            val qty     = v.findViewById<TextView>(R.id.tv_erp_qty)
            val time    = v.findViewById<TextView>(R.id.tv_erp_time)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_erp_row, p, false))

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = list[pos]
            h.ry.text      = item.ry ?: "--"
            h.gxlb.text    = item.gxlb ?: "--"
            h.article.text = item.article ?: "--"
            h.model.text   = item.model ?: "--"
            h.qty.text     = "${item.qty ?: 0}"
            h.time.text    = formatTime(item.userDate)
            h.itemView.setBackgroundColor(
                if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
            h.itemView.setOnClickListener { onItemClick?.invoke(item) }
        }
    }

    // ── Qty Table Adapter ─────────────────────────────────────────────────────────
    inner class QtyTableAdapter : RecyclerView.Adapter<QtyTableAdapter.VH>() {
        private var list = listOf<RyReportItem>()
        fun submitList(l: List<RyReportItem>) { list = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ry      = v.findViewById<TextView>(R.id.tv_sum_ry)
            val po      = v.findViewById<TextView>(R.id.tv_sum_po)
            val qtyed   = v.findViewById<TextView>(R.id.tv_sum_qtyed)
            val order   = v.findViewById<TextView>(R.id.tv_sum_order)
            val remain  = v.findViewById<TextView>(R.id.tv_sum_remain)
            val art     = v.findViewById<TextView>(R.id.tv_sum_art)
            val model   = v.findViewById<TextView>(R.id.tv_sum_model)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_qty_summary_row, p, false))

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = list[pos]
            h.ry.text      = item.ry ?: "--"
            h.po.text      = item.po ?: "--"
            h.qtyed.text   = "${item.scannedQty ?: 0}"
            h.order.text   = "${item.orderQty ?: 0}"
            h.remain.text  = "${item.remaining ?: 0}"
            h.art.text     = item.article ?: "--"
            h.model.text   = item.model ?: "--"
            h.itemView.setBackgroundColor(
                if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
        }
    }

    // ── Qty Detail Adapter ────────────────────────────────────────────────────────
    inner class QtyDetailAdapter : RecyclerView.Adapter<QtyDetailAdapter.VH>() {
        private var list = listOf<RyDetailItem>()
        fun submitList(l: List<RyDetailItem>) { list = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val size   = v.findViewById<TextView>(R.id.tv_det_size)
            val qtyed  = v.findViewById<TextView>(R.id.tv_det_qtyed)
            val order  = v.findViewById<TextView>(R.id.tv_det_order)
            val remain = v.findViewById<TextView>(R.id.tv_det_remain)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_qty_detail_row, p, false))

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = list[pos]
            h.size.text   = item.size ?: "--"
            h.qtyed.text  = "${item.qtyed ?: 0}"
            h.order.text  = "${item.orderQty ?: 0}"
            h.remain.text = "${item.remain ?: 0}"
            h.itemView.setBackgroundColor(
                if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
        }
    }

    // ── Hourly Adapter ────────────────────────────────────────────────────────────
    inner class HourlyAdapter : RecyclerView.Adapter<HourlyAdapter.VH>() {
        private var list = listOf<HourlyReportItem>()
        fun submitList(l: List<HourlyReportItem>) { list = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val hour   = v.findViewById<TextView>(R.id.tv_hour_label)
            val target = v.findViewById<TextView>(R.id.tv_hour_target)
            val actual = v.findViewById<TextView>(R.id.tv_hour_actual)
            val diff   = v.findViewById<TextView>(R.id.tv_hour_diff)
            val status = v.findViewById<TextView>(R.id.tv_hour_status)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_hourly_row, p, false))

        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = list[pos]
            val actualVal = item.output ?: 0
            val targetVal = item.target ?: 0
            val diffVal = actualVal - targetVal
            
            h.hour.text   = item.timespan ?: "--"
            h.target.text = "$targetVal"
            h.actual.text = "$actualVal"
            h.diff.text   = if (diffVal >= 0) "+$diffVal" else "$diffVal"
            val ctx = h.itemView.context
            val green = ContextCompat.getColor(ctx, R.color.success_green)
            val red   = ContextCompat.getColor(ctx, R.color.error_red)
            h.diff.setTextColor(if (diffVal >= 0) green else red)
            h.status.text = if (diffVal >= 0) "ĐẠT" else "THIẾU"
            h.status.setTextColor(if (diffVal >= 0) green else red)
            h.itemView.setBackgroundColor(
                if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFAFBFC.toInt())
        }
    }
}

private data class SizeRow(val size: String, val qty: Int, val ok: Int = 0)

