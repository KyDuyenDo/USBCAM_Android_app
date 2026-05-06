package com.example.usbcam

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.SearchRyItem

class QueueSidebarAdapter(
    private val items: List<SearchRyItem>,
    private val onRySelected: (Int) -> Unit
) : RecyclerView.Adapter<QueueSidebarAdapter.SidebarViewHolder>() {

    var selectedIndex: Int = 0
        set(value) {
            val oldIndex = field
            field = value
            notifyItemChanged(oldIndex)
            notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_ry_sidebar, parent, false)
        return SidebarViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSidebarRy: TextView = itemView.findViewById(R.id.tv_sidebar_ry)

        fun bind(item: SearchRyItem, isSelected: Boolean) {
            tvSidebarRy.text = item.zlbh ?: item.ry ?: "---"
            
            if (isSelected) {
                tvSidebarRy.setTextColor(Color.parseColor("#3A82F6")) // primary_blue
                tvSidebarRy.setBackgroundColor(Color.parseColor("#22252A"))
            } else {
                tvSidebarRy.setTextColor(Color.WHITE)
                tvSidebarRy.setBackgroundResource(android.R.color.transparent)
            }

            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition != selectedIndex) {
                    onRySelected(adapterPosition)
                }
            }
        }
    }
}
