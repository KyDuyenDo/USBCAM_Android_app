package com.example.usbcam

import java.util.Calendar

/**
 * Converts current local time to a production time-slot (ts) number.
 *
 * Schedule (each slot = 1 hour starting at :30):
 *   07:30 – 08:29  → ts = 1
 *   08:30 – 09:29  → ts = 2
 *   09:30 – 10:29  → ts = 3
 *   10:30 – 11:29  → ts = 4
 *   11:30 – 12:29  → ts = 5
 *   12:30 – 13:29  → ts = 6
 *   13:30 – 14:29  → ts = 7
 *   14:30 – 15:29  → ts = 8
 *   15:30 – 16:29  → ts = 9
 *   16:30 – 17:29  → ts = 10
 *   (returns 0 if outside production hours)
 */
object TimeSlotUtil {

    fun getCurrentTs(): Int {
        val cal = Calendar.getInstance()
        val hour   = cal.get(Calendar.HOUR_OF_DAY)   // 0-23
        val minute = cal.get(Calendar.MINUTE)

        // Convert to minutes since 07:30
        val totalMin = (hour - 7) * 60 + minute - 30
        if (totalMin < 0) return 0           // before 07:30

        val ts = totalMin / 60 + 1           // integer division → slot index
        return if (ts > 10) 0 else ts        // after 17:29 → 0 (out of range)
    }

    /** Returns how many milliseconds until the next xx:29 mark */
    fun millisUntilNextMinute29(): Long {
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val ms     = cal.get(Calendar.MILLISECOND)

        // Minutes remaining until :29 (or :29 + 60 if already past)
        val minutesUntil = if (minute < 29) 29 - minute
                           else             29 + 60 - minute

        val secondsRemaining = minutesUntil * 60 - second
        return secondsRemaining * 1000L - ms
    }
}
