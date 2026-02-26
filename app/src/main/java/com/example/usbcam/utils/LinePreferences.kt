package com.example.usbcam.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Quản lý lưu/đọc Line (dây chuyền sản xuất) đã chọn vào SharedPreferences.
 *
 * Danh sách line mặc định: LHGG4G01, LHGG4G02, LHGG4G03, ...
 * Line được chọn sẽ được dùng để:
 *  - Lưu vào ShoeboxDetail.Line / ShoeboxTotal.Line khi scan
 *  - Gọi API getTargetByLean(depno) để lấy mục tiêu sản xuất theo dây chuyền
 */
object LinePreferences {

    private const val PREF_NAME = "line_prefs"
    private const val KEY_SELECTED_LINE = "selected_line"
    const val DEFAULT_LINE = "LHGG4G01"

    /** Danh sách các line có thể chọn */
    val availableLines: List<String> = listOf(
        "LHGG4G01",
        "LHGG4G02",
        "LHGG4G03",
        "LHGG4G04",
        "LHGG4G05",
        "LHGG4G06",
        "LHGG4G07",
        "LHGG4G08"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Lấy line đã chọn, trả về DEFAULT_LINE nếu chưa có */
    fun getSelectedLine(context: Context): String =
        prefs(context).getString(KEY_SELECTED_LINE, DEFAULT_LINE) ?: DEFAULT_LINE

    /** Lưu line được chọn */
    fun saveSelectedLine(context: Context, line: String) {
        prefs(context).edit().putString(KEY_SELECTED_LINE, line).apply()
    }

    /** Lấy index của line hiện tại trong danh sách availableLines */
    fun getSelectedLineIndex(context: Context): Int {
        val current = getSelectedLine(context)
        val idx = availableLines.indexOf(current)
        return if (idx >= 0) idx else 0
    }
}
