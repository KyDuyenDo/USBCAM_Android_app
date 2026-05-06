package com.example.usbcam

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the AutoSaveWorker to run at the next :29 mark of the current hour.
 * After running, it reschedules itself for the following :29.
 * Call [start] once (e.g., on app launch or when the queue dialog opens).
 */
object AutoSaveScheduler {

    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    fun start(context: Context) {
        if (scheduled) return
        scheduled = true
        scheduleNext(context)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        scheduled = false
    }

    private fun scheduleNext(context: Context) {
        val delay = TimeSlotUtil.millisUntilNextMinute29()
        Log.d("AutoSaveScheduler", "Next auto-save in ${delay / 1000}s")

        handler.postDelayed({
            // Enqueue WorkManager job immediately (delay=0 from WorkManager's perspective)
            val request = OneTimeWorkRequestBuilder<AutoSaveWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d("AutoSaveScheduler", "AutoSaveWorker enqueued at ${java.util.Date()}")

            // Schedule the next :29 (in ~60 minutes)
            scheduleNext(context)
        }, delay)
    }
}
