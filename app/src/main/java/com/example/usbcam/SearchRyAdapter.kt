package com.example.usbcam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.usbcam.api.SearchRyItem

class SearchRyAdapter(
    private var items: List<SearchRyItem>,
    private val onAddClicked: (SearchRyItem) -> Unit
) : RecyclerView.Adapter<SearchRyAdapter.SearchRyViewHolder>() {

    fun updateData(newItems: List<SearchRyItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchRyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_ry, parent, false)
        return SearchRyViewHolder(view)
    }

    override fun onBindViewHolder(holder: SearchRyViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SearchRyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvZlbh: TextView = itemView.findViewById(R.id.tv_item_zlbh)
        private val tvQty: TextView = itemView.findViewById(R.id.tv_item_qty)
        private val tvPo: TextView = itemView.findViewById(R.id.tv_item_po)
        private val tvLean: TextView = itemView.findViewById(R.id.tv_item_lean)
        private val tvArticle: TextView = itemView.findViewById(R.id.tv_item_article)
        private val tvStart: TextView = itemView.findViewById(R.id.tv_item_start)
        private val tvCountry: TextView = itemView.findViewById(R.id.tv_item_country)
        private val tvEnd: TextView = itemView.findViewById(R.id.tv_item_end)
        private val btnAddQueue: Button = itemView.findViewById(R.id.btn_add_queue)
        private val ivShoe: ImageView = itemView.findViewById(R.id.iv_item_shoe)

        fun bind(item: SearchRyItem) {
            tvZlbh.text = item.zlbh ?: "---"
            tvQty.text = "QTY: ${item.qty ?: 0}"
            tvPo.text = "PO: ${item.khpo ?: "---"}"
            tvLean.text = "Lean: ${item.lean ?: "---"}"
            tvArticle.text = "Article: ${item.article ?: "---"}"
            tvStart.text = "Start: ${formatDate(item.psdt)}"
            tvCountry.text = "Country: ${item.country ?: "---"}"
            tvEnd.text = "End: ${formatDate(item.pedt)}"

            // Load Image using Glide
            if (!item.imageUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(item.imageUrl)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(ivShoe)
            } else {
                ivShoe.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            val isQueued = QueueManager.queuedItems.any { it.ry == item.ry }
            if (isQueued) {
                btnAddQueue.text = "ĐÃ THÊM VÀO HÀNG CHỜ"
                btnAddQueue.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
            } else {
                btnAddQueue.text = "THÊM VÀO HÀNG CHỜ"
                btnAddQueue.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF8C00"))
            }

            btnAddQueue.setOnClickListener {
                if (!QueueManager.queuedItems.any { it.ry == item.ry }) {
                    QueueManager.addQueueItem(item)
                    notifyItemChanged(adapterPosition)
                }
            }
        }
        
        private fun formatDate(dateStr: String?): String {
            if (dateStr.isNullOrEmpty()) return "---"
            return try {
                // "2025-12-12T00:00:00.000Z" -> "2025-12-12"
                dateStr.split("T")[0]
            } catch (e: Exception) {
                dateStr
            }
        }
    }
}
