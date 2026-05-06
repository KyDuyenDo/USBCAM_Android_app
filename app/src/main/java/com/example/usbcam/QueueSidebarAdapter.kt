package com.example.usbcam

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.SearchRyItem

class QueueSidebarAdapter(
    private var queuedItems: List<SearchRyItem>,
    private val onRySelected: (SearchRyItem) -> Unit
) : RecyclerView.Adapter<QueueSidebarAdapter.SidebarViewHolder>() {

    private var dirtyRys: Set<String> = emptySet()

    private var apiResults: List<SearchRyItem> = emptyList()
    private var displayItems: List<SearchRyItem> = queuedItems
    
    var selectedRy: SearchRyItem? = queuedItems.getOrNull(0)
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun updateQueuedItems(newQueued: List<SearchRyItem>) {
        this.queuedItems = newQueued
        updateDisplayItems("")
    }

    fun updateDirtyRys(dirty: Set<String>) {
        this.dirtyRys = dirty
        notifyDataSetChanged()
    }

    fun updateApiResults(results: List<SearchRyItem>, query: String) {
        this.apiResults = results.filter { res -> !queuedItems.any { it.ry == res.ry } }
        updateDisplayItems(query)
    }

    private fun updateDisplayItems(query: String) {
        val filteredQueued = if (query.isEmpty()) {
            queuedItems
        } else {
            queuedItems.filter { 
                (it.zlbh ?: "").contains(query, ignoreCase = true) || 
                (it.ry ?: "").contains(query, ignoreCase = true) 
            }
        }
        
        displayItems = filteredQueued + apiResults
        notifyDataSetChanged()
    }

    fun filterData(query: String) {
        updateDisplayItems(query)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_ry_sidebar, parent, false)
        return SidebarViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        val item = displayItems[position]
        holder.bind(item, item == selectedRy)
    }

    override fun getItemCount(): Int = displayItems.size

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSidebarRy: TextView = itemView.findViewById(R.id.tv_sidebar_ry)
        private val tvSyncStatus: TextView = itemView.findViewById(R.id.tv_sync_status)
        private val btnAdd: View = itemView.findViewById(R.id.btn_add_to_queue_sidebar)

        fun bind(item: SearchRyItem, isSelected: Boolean) {
            tvSidebarRy.text = item.zlbh ?: item.ry ?: "---"
            
            val isDirty = dirtyRys.contains(item.ry ?: "")
            tvSyncStatus.visibility = View.VISIBLE
            if (isDirty) {
                tvSyncStatus.text = "Chờ"
                tvSyncStatus.setTextColor(Color.parseColor("#FFA500")) // Orange
            } else {
                tvSyncStatus.text = "Lưu"
                tvSyncStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
            }
            
            val isQueued = QueueManager.queuedItems.any { it.ry == item.ry }
            btnAdd.visibility = if (isQueued) View.GONE else View.VISIBLE
            
            if (isSelected) {
                tvSidebarRy.setTextColor(Color.parseColor("#3A82F6")) // primary_blue
                itemView.setBackgroundColor(Color.parseColor("#22252A"))
            } else {
                tvSidebarRy.setTextColor(Color.WHITE)
                itemView.setBackgroundResource(android.R.color.transparent)
            }

            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    if (isQueued) {
                        onRySelected(item)
                    } else {
                        // If not queued, maybe just highlight?
                        // The user said "can add to queue", so maybe we add it when they click?
                        // Let's just make it selectable to view data, and they use the '+' button to add.
                        onRySelected(item)
                    }
                }
            }

            btnAdd.setOnClickListener {
                if (!QueueManager.queuedItems.any { it.ry == item.ry }) {
                    QueueManager.addQueueItem(item)
                    queuedItems = QueueManager.queuedItems
                    apiResults = apiResults.filter { it.ry != item.ry }
                    updateDisplayItems("")
                    // We might need a callback to Fragment to update other things if needed
                }
            }
        }
    }
}
