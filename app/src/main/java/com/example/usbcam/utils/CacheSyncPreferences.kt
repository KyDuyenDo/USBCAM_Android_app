package com.example.usbcam.utils

import android.content.Context

/**
 * Quản lý trạng thái đồng bộ cache để có thể tải tiếp (resume) khi gặp lỗi.
 */
object CacheSyncPreferences {
    private const val PREF_NAME = "cache_sync_prefs"
    private const val KEY_FAILED_PAGES = "failed_pages"
    private const val KEY_TOTAL_PAGES = "total_pages"

    fun saveFailedPages(context: Context, pages: Set<Int>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_FAILED_PAGES, pages.map { it.toString() }.toSet()).apply()
    }

    fun getFailedPages(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAILED_PAGES, null) ?: return mutableSetOf()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    fun saveTotalPages(context: Context, total: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_TOTAL_PAGES, total).apply()
    }

    fun getTotalPages(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_TOTAL_PAGES, 0)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /** Đánh dấu một trang đã tải xong */
    fun markPageSuccess(context: Context, page: Int) {
        val failed = getFailedPages(context)
        if (failed.remove(page)) {
            saveFailedPages(context, failed)
        }
    }

    /** Đánh dấu một trang bị lỗi */
    fun markPageFailed(context: Context, page: Int) {
        val failed = getFailedPages(context)
        failed.add(page)
        saveFailedPages(context, failed)
    }
}
