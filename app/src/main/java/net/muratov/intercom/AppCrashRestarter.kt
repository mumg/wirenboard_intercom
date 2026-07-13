package net.muratov.intercom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import net.muratov.intercom.logging.IntercomFileLogger

object AppCrashRestarter {
    private const val TAG = "AppCrashRestarter"
    private const val RESTART_DELAY_MS = 1_500L
    internal const val ACTION_RESTART_APP = "net.muratov.intercom.action.RESTART_APP"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        IntercomFileLogger.i(TAG, "Installing uncaught exception handler previousHandler=${previousHandler?.javaClass?.name}")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            IntercomFileLogger.e(
                TAG,
                "Uncaught exception received thread=${thread.name}, scheduling app restart",
                throwable,
            )
            runCatching {
                scheduleRestart(appContext)
            }.onFailure { error ->
                IntercomFileLogger.e(TAG, "Unable to schedule app restart after crash", error)
                Log.e(TAG, "Unable to schedule app restart after crash", error)
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun scheduleRestart(context: Context) {
        val intent = Intent(context, CrashRestartReceiver::class.java).apply {
            action = ACTION_RESTART_APP
            `package` = context.packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        IntercomFileLogger.i(TAG, "Scheduling restart triggerAtElapsed=$triggerAtMillis delayMs=$RESTART_DELAY_MS")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
    }
}
