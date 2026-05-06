package com.example.usbcam

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.usbcam.api.ReportApiService
import com.example.usbcam.api.ScbbContextSaverRequest
import com.example.usbcam.db.ProductionDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager worker that runs at minute :29 of each production hour.
 * It reads all pending (unsynced) entries from SQLite and POSTs them to the API.
 */
class AutoSaveWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = ProductionDbHelper(applicationContext)
    private val api = ReportApiService.create()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override suspend fun doWork(): Result {
        val pending = db.getPendingEntries()
        if (pending.isEmpty()) {
            Log.d("AutoSaveWorker", "No pending entries to sync.")
            return Result.success()
        }

        var allOk = true
        for (entry in pending) {
            try {
                val request = ScbbContextSaverRequest(
                    scbh        = entry.scbh,
                    depNo       = entry.depNo,
                    gsbh        = entry.gsbh,
                    xxcc        = entry.xxcc,
                    userId      = entry.userId,
                    inputSource = entry.inputSource,
                    gxlb        = entry.gxlb,
                    qty         = entry.qty,
                    userDate    = entry.userDate
                )
                val response = api.saveStitchingData(entry.serverCode, request)
                if (response.isSuccessful) {
                    db.deleteEntry(entry.id)
                    Log.d("AutoSaveWorker", "Synced and deleted entry id=${entry.id} size=${entry.gsbh}")
                } else {
                    Log.e("AutoSaveWorker", "Failed entry id=${entry.id}: ${response.code()}")
                    allOk = false
                }
            } catch (e: Exception) {
                Log.e("AutoSaveWorker", "Exception syncing entry id=${entry.id}", e)
                allOk = false
            }
        }

        return if (allOk) Result.success() else Result.retry()
    }
}
