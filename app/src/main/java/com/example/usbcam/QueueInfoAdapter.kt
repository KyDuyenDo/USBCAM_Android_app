package com.example.usbcam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.usbcam.api.QueueInfoItem

class QueueInfoAdapter(
    private var items: List<QueueInfoItem>,
    private val onQtyChanged: (String, Int) -> Unit
) : RecyclerView.Adapter<QueueInfoAdapter.QueueInfoViewHolder>() {

    // Map of size -> current qty entered by user (in this session)
    private val inputQtyMap = mutableMapOf<String, Int>()

    fun resetInputs() {
        inputQtyMap.clear()
        notifyDataSetChanged()
    }
 
    fun updateData(newItems: List<QueueInfoItem>, pendingMap: Map<String, Int> = emptyMap()) {
        // Reset input map and load pending values from DB
        inputQtyMap.clear()
        inputQtyMap.putAll(pendingMap)
        this.items = newItems
        notifyDataSetChanged()
    }

    /** Returns a map of size -> qty for all entries with qty > 0 */
    fun getInputEntries(): Map<String, Int> = inputQtyMap.filter { it.value > 0 }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueInfoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_size, parent, false)
        return QueueInfoViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueInfoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class QueueInfoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSizeLabel: TextView = itemView.findViewById(R.id.tv_size_label)
        private val tvTotal: TextView = itemView.findViewById(R.id.tv_total)
        private val tvCompleted: TextView = itemView.findViewById(R.id.tv_completed)
        private val tvRemaining: TextView = itemView.findViewById(R.id.tv_remaining)
        private val tvToday: TextView = itemView.findViewById(R.id.tv_today)
        private val tvInputQty: TextView = itemView.findViewById(R.id.tv_input_qty)

        private val btnMinus10: Button = itemView.findViewById(R.id.btn_minus_10)
        private val btnMinus1: Button = itemView.findViewById(R.id.btn_minus_1)
        private val btnPlus1: Button = itemView.findViewById(R.id.btn_plus_1)
        private val btnPlus10: Button = itemView.findViewById(R.id.btn_plus_10)

        fun bind(item: QueueInfoItem) {
            val sizeKey = item.size ?: return
            tvSizeLabel.text = sizeKey
            tvTotal.text = (item.total ?: 0).toString()
            tvCompleted.text = (item.qtyed ?: 0).toString()
            tvRemaining.text = (item.balQty ?: 0).toString()
            tvToday.text = (item.today ?: 0).toString()

            val currentQty = inputQtyMap.getOrPut(sizeKey) { 0 }
            tvInputQty.text = currentQty.toString()

            val updateQty = { change: Int ->
                val newQty = (inputQtyMap[sizeKey] ?: 0) + change
                val clamped = if (newQty < 0) 0 else newQty
                inputQtyMap[sizeKey] = clamped
                tvInputQty.text = clamped.toString()
                onQtyChanged(sizeKey, clamped)
            }

            btnMinus10.setOnClickListener { updateQty(-10) }
            btnMinus1.setOnClickListener  { updateQty(-1)  }
            btnPlus1.setOnClickListener   { updateQty(1)   }
            btnPlus10.setOnClickListener  { updateQty(10)  }
        }
    }
}
