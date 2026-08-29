package de.rdoe.weeklydjshows

import android.content.Context

internal data class BluetoothAutostartDiagnostic(
    val attemptId: Long,
    val attemptedAtEpochMs: Long,
    val dispatched: Boolean,
    val reachedForeground: Boolean,
)

/**
 * Tiny local breadcrumb for a physical-device-only path. Android deliberately gives no callback
 * when a background activity launch is policy-blocked, so the receiver records the request and
 * MainActivity records whether that exact request actually reached it.
 */
internal object BluetoothAutostartDiagnostics {
    private const val FILE = "bluetooth_autostart_diagnostics"
    private const val ATTEMPT_ID = "attempt_id"
    private const val ATTEMPT_AT = "attempt_at"
    private const val DISPATCHED_ID = "dispatched_id"
    private const val REACHED_ID = "reached_id"

    fun newAttempt(context: Context): Long {
        val now = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(ATTEMPT_ID, now)
            .putLong(ATTEMPT_AT, now)
            .remove(DISPATCHED_ID)
            .apply()
        return now
    }

    fun markDispatched(context: Context, attemptId: Long) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(DISPATCHED_ID, attemptId)
            .apply()
    }

    fun markReached(context: Context, attemptId: Long) {
        if (attemptId <= 0L) return
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(REACHED_ID, attemptId)
            .apply()
    }

    fun read(context: Context): BluetoothAutostartDiagnostic? {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val attemptId = prefs.getLong(ATTEMPT_ID, 0L)
        if (attemptId <= 0L) return null
        return BluetoothAutostartDiagnostic(
            attemptId = attemptId,
            attemptedAtEpochMs = prefs.getLong(ATTEMPT_AT, attemptId),
            dispatched = prefs.getLong(DISPATCHED_ID, 0L) == attemptId,
            reachedForeground = prefs.getLong(REACHED_ID, 0L) == attemptId,
        )
    }
}
