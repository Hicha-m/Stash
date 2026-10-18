package com.stash.data.download.lossless.quota

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/**
 * The words for the daily lossless allowance, in one place so the app-wide snackbar and the
 * Settings line say the same thing. Times arrive already formatted ([resetTime]) so the copy
 * stays testable on the JVM.
 */
object LosslessQuotaText {

    /** Both heads-ups end with it. */
    const val SUPPORTER_NUDGE = "Unlimited lossless for supporters: any amount on Ko-fi"

    /** The snackbar action: opens Settings → Audio & Quality, where the supporter claim is. */
    const val NOTICE_ACTION = "Unlock"

    /** The snackbar for [n]; [time] is its reset in the user's local time ("8:00 PM"). */
    fun notice(n: LosslessQuotaNotice, time: String): String = when (n) {
        is LosslessQuotaNotice.RunningLow ->
            "${n.left} lossless ${if (n.left == 1) "song" else "songs"} left today. $SUPPORTER_NUDGE"
        is LosslessQuotaNotice.Spent ->
            "Daily lossless limit reached — playing in high quality until $time. $SUPPORTER_NUDGE"
    }

    /**
     * Settings → Audio & Quality: "Lossless today: 412 of 500 · resets 8:00 PM", or
     * "Lossless today: Unlimited (supporter)". [time] is [a]'s reset in local time.
     */
    fun todayLine(a: LosslessAllowance, time: String): String =
        if (a.limit == null) "Lossless today: Unlimited (supporter)"
        else "Lossless today: ${a.used} of ${a.limit} · resets $time"

    /** [epochMs] as a short local time, following the device's 12 / 24-hour setting. */
    fun resetTime(context: Context, epochMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMs))
}
