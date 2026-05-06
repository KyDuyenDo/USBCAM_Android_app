package com.example.usbcam

import com.example.usbcam.api.SearchRyItem

object QueueManager {
    // Stores a list of RY items that have been added to the queue
    val queuedItems = mutableListOf<SearchRyItem>()

    fun addQueueItem(item: SearchRyItem): Boolean {
        // Prevent adding duplicate RYs
        if (!queuedItems.any { it.ry == item.ry }) {
            queuedItems.add(item)
            return true
        }
        return false
    }

    fun removeQueueItem(item: SearchRyItem) {
        queuedItems.removeAll { it.ry == item.ry }
    }

    fun clearQueue() {
        queuedItems.clear()
    }
}
