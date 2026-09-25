package io.github.puflik.plinth.diagnostics.vendor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.puflik.plinth.diagnostics.CrashStore
import io.github.puflik.plinth.diagnostics.log.AppLog

/**
 * Проверка при старте процесса (G2.1): осталась метка «звук идёт» — прошлый
 * процесс умер посреди игры; причину уточняет `ApplicationExitInfo`
 * (Android 11+). Метка после проверки снимается. Вызывать до [KillWatch]:
 * иначе новая игра поставит метку заново.
 */
class ServiceKillDetector(
    private val context: Context,
    private val marker: KillMarker,
    private val crashes: CrashStore,
) {
    fun detect(): KillReport {
        val wasPlaying = marker.isSet()
        val rebooted = wasPlaying && marker.rebootedSinceSet()
        marker.clear()
        if (!wasPlaying) return KillReport(detected = false)
        val exit = lastExit()
        val crashed = crashes.pending() != null
        val killed = KillVerdict.killed(wasPlaying = true, exit = exit, crashed = crashed, rebooted = rebooted)
        AppLog.w(TAG, "process died while playing: exit=$exit, rebooted=$rebooted, killed=$killed")
        return KillReport(detected = killed)
    }

    private fun lastExit(): ExitReason {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ExitReason.UNKNOWN
        val manager = context.getSystemService(ActivityManager::class.java)
        val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        return last?.reason?.let(::exitReason) ?: ExitReason.UNKNOWN
    }

    private fun exitReason(reason: Int): ExitReason =
        when (reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.USER
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR ->
                ExitReason.CRASH
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
                ExitReason.UPDATE
            else -> ExitReason.SYSTEM
        }

    private companion object {
        const val TAG = "Vendor"
    }
}
