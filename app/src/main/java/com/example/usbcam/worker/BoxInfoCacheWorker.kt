package com.example.usbcam.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.usbcam.api.PoApiService
import com.example.usbcam.data.db.AppDatabase
import com.example.usbcam.data.model.BoxInfoCache
import com.example.usbcam.utils.CacheSyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker chạy ngầm khi mở app để tải toàn bộ danh mục hộp giày từ API
 * [all-info-box] và lưu vào SQLite (bảng Box_Info_Cache).
 *
 * Chiến lược:
 *  1. Kiểm tra cache hiện tại — nếu dữ liệu còn mới (< CACHE_TTL_MS) thì bỏ qua.
 *  2. Gọi API page 1 để biết tổng số trang (totalPages).
 *  3. Xoá cache cũ, rồi nạp lần lượt từng page và upsert vào DB.
 *  4. Nếu thất bại ở page nào thì dừng và retry lần sau.
 */
class BoxInfoCacheWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BoxInfoCacheWorker"
        private const val PAGE_SIZE = 10000

        /** Thời gian cache hợp lệ: 4 tiếng */
        private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L

        const val WORK_NAME = "BoxInfoCacheWork"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val cacheDao = db.boxInfoCacheDao()
        val apiService = PoApiService.create()

        try {
            Log.i(TAG, "Đang kiểm tra dữ liệu với server...")

            // 1. Lấy page 1 để biết tổng số lượng (total) và số trang (totalPages) trên server
            val firstPage = apiService.getAllInfoBox(page = 1, pageSize = PAGE_SIZE)
            if (!firstPage.isSuccessful || firstPage.body() == null) {
                Log.e(TAG, "Lỗi API page 1: ${firstPage.code()} ${firstPage.message()}")
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            val firstBody = firstPage.body()!!
            val serverTotal = firstBody.total
            val localTotal = cacheDao.count()
            val totalPagesOnServer = firstBody.totalPages

            // 2. Kiểm tra TTL kết hợp với Số lượng
            val lastCached = cacheDao.lastCachedAt() ?: 0L
            val age = System.currentTimeMillis() - lastCached

            val failedPages = CacheSyncPreferences.getFailedPages(applicationContext)
            val pagesToDownload: List<Int>

            // Quyết định danh sách trang cần tải
            if (serverTotal != localTotal || age >= CACHE_TTL_MS || localTotal == 0) {
                // Trường hợp cần tải mới hoàn toàn (số lượng lệch hoặc hết hạn)
                Log.i(TAG, "Cần đồng bộ mới (Server: $serverTotal, Local: $localTotal). Xoá cache cũ.")
                cacheDao.clearAll()
                CacheSyncPreferences.clear(applicationContext)
                
                pagesToDownload = (1..totalPagesOnServer).toList()
                // Lưu toàn bộ danh sách vào bộ nhớ lỗi để tải dần
                CacheSyncPreferences.saveFailedPages(applicationContext, pagesToDownload.toSet())
            } else if (failedPages.isNotEmpty()) {
                // Trường hợp số lượng khớp nhưng vẫn còn trang lỗi từ lần trước
                Log.i(TAG, "Số lượng khớp nhưng còn ${failedPages.size} trang lỗi cần tải bù.")
                pagesToDownload = failedPages.sorted()
            } else {
                // Dữ liệu đã đầy đủ và khớp
                Log.i(TAG, "Dữ liệu khớp ($localTotal) và không có trang lỗi. Hoàn tất.")
                return@withContext Result.success()
            }

            Log.i(TAG, "Bắt đầu tải danh sách ${pagesToDownload.size} trang...")

            // Báo progress bắt đầu
            setProgress(workDataOf("progress" to cacheDao.count(), "total" to serverTotal))

            // 3. Tiến hành tải các trang trong danh sách
            for (page in pagesToDownload) {
                val response = apiService.getAllInfoBox(page = page, pageSize = PAGE_SIZE)
                if (response.isSuccessful && response.body() != null) {
                    val batch = response.body()!!.data.map { it.toEntity() }
                    cacheDao.insertAll(batch)
                    
                    // Đánh dấu thành công trang này
                    CacheSyncPreferences.markPageSuccess(applicationContext, page)
                    
                    val currentCount = cacheDao.count()
                    setProgress(workDataOf("progress" to currentCount, "total" to serverTotal))
                    Log.d(TAG, "Đã lưu page $page (Tiến độ: $currentCount/$serverTotal)")
                } else {
                    Log.e(TAG, "Lỗi API page $page: ${response.code()}")
                    // Ghi nhớ trang này bị lỗi để lần sau tải lại
                    CacheSyncPreferences.markPageFailed(applicationContext, page)
                    // Nếu lỗi mạng thì dừng loop để retry cả Worker sau
                    break 
                }
            }

            val finalCount = cacheDao.count()
            val isFinished = CacheSyncPreferences.getFailedPages(applicationContext).isEmpty()
            
            setProgress(workDataOf("progress" to finalCount, "total" to serverTotal, "finished" to isFinished))
            Log.i(TAG, " Kết thúc chu kỳ tải. Hiện có: $finalCount bản ghi. Hoàn tất: $isFinished")
            
            if (isFinished) Result.success() else Result.retry()

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi hệ thống khi tải cache: ${e.message}", e)
            Result.retry()
        }
    }

    /** Chuyển BoxInfoItem (API) sang BoxInfoCache (Room entity) */
    private fun com.example.usbcam.api.BoxInfoItem.toEntity() = BoxInfoCache(
        UPC = upc,
        SIZE = size,
        PO = po,
        RY = ry,
        Article = article,
        Article_Image = articleImage,
        Quantity = quantity,
        CachedAt = System.currentTimeMillis()
    )
}
