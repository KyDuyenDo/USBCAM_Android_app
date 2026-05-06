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
    private const val KEY_SELECTED_FACTORY = "selected_factory"
    private const val KEY_SELECTED_DEP_TYPE = "selected_dep_type"
    private const val KEY_SELECTED_LOCATION = "selected_location"
    private const val KEY_GXLB = "selected_gxlb"
    const val DEFAULT_GXLB = "A"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Lấy line đã chọn, trả về null nếu chưa có */
    fun getSelectedLine(context: Context): String? =
        prefs(context).getString(KEY_SELECTED_LINE, null)

    /** Lưu line được chọn */
    fun saveSelectedLine(context: Context, line: String) {
        prefs(context).edit().putString(KEY_SELECTED_LINE, line).apply()
    }

    fun saveSelections(context: Context, factory: String?, depType: Int?, location: String?, gxlb: String?) {
        prefs(context).edit().apply {
            putString(KEY_SELECTED_FACTORY, factory)
            putInt(KEY_SELECTED_DEP_TYPE, depType ?: -1)
            putString(KEY_SELECTED_LOCATION, location)
            putString(KEY_GXLB, gxlb ?: DEFAULT_GXLB)
        }.apply()
    }

    fun getSelectedFactory(context: Context): String? = prefs(context).getString(KEY_SELECTED_FACTORY, null)
    fun getSelectedDepType(context: Context): Int = prefs(context).getInt(KEY_SELECTED_DEP_TYPE, -1)
    fun getSelectedLocation(context: Context): String? = prefs(context).getString(KEY_SELECTED_LOCATION, null)
    fun getSelectedGxlb(context: Context): String = prefs(context).getString(KEY_GXLB, DEFAULT_GXLB) ?: DEFAULT_GXLB
    
    /** Fallback constant for UI when nothing is selected yet */
    const val DEFAULT_LINE_LABEL = "LHGG4G01"

    /** Kiểm tra xem đã cấu hình đầy đủ chưa */
    fun isConfigured(context: Context): Boolean {
        return getSelectedFactory(context) != null &&
               getSelectedDepType(context) != -1 &&
               getSelectedLocation(context) != null &&
               getSelectedLine(context) != null
    }
}
