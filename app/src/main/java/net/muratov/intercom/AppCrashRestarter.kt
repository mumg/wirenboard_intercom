package net.muratov.intercom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import net.muratov.intercom.logging.IntercomFileLogger
import java.util.concurrent.atomic.AtomicBoolean

object AppCrashRestarter {
    private const val TAG = "AppCrashRestarter"
    private const val RESTART_DELAY_MS = 1_500L
    private const val WATCHDOG_HEARTBEAT_MS = 15_000L
    private const val WATCHDOG_TIMEOUT_MS = 45_000L
    private const val JAVA_CRASH_REQUEST_CODE = 1001
    private const val WATCHDOG_REQUEST_CODE = 1002
    internal const val ACTION_RESTART_APP = "net.muratov.intercom.action.RESTART_APP"
    internal const val EXTRA_RESTART_REASON = "restart_reason"
    internal const val RESTART_REASON_WATCHDOG = "watchdog"

    private const val RESTART_REASON_JAVA_CRASH = "java_crash"
    private const val EXTRA_PROCESS_TOKEN = "process_token"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var processToken = ""

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        processToken = "${Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}"
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        IntercomFileLogger.i(TAG, "Installing uncaught exception handler previousHandler=${previousHandler?.javaClass?.name}")
        startNativeCrashWatchdog(appContext)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            IntercomFileLogger.e(
                TAG,
                "Uncaught exception received thread=${thread.name}, scheduling app restart",
                throwable,
            )
            runCatching {
                scheduleJavaCrashRestart(appContext)
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

    internal fun shouldLaunchAfterRestartIntent(intent: Intent): Boolean {
        if (intent.getStringExtra(EXTRA_RESTART_REASON) != RESTART_REASON_WATCHDOG) {
            return true
        }

        val watchedProcessToken = intent.getStringExtra(EXTRA_PROCESS_TOKEN)
        return watchedProcessToken.isNullOrBlank() || watchedProcessToken != processToken
    }

    private fun startNativeCrashWatchdog(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        val heartbeat = object : Runnable {
            override fun run() {
                runCatching {
                    scheduleWatchdogAlarm(context)
                }.onFailure { error ->
                    IntercomFileLogger.e(TAG, "Unable to refresh native crash watchdog", error)
                    Log.e(TAG, "Unable to refresh native crash watchdog", error)
                }
                handler.postDelayed(this, WATCHDOG_HEARTBEAT_MS)
            }
        }
        handler.post(heartbeat)
        IntercomFileLogger.i(
            TAG,
            "Native crash watchdog started timeoutMs=$WATCHDOG_TIMEOUT_MS heartbeatMs=$WATCHDOG_HEARTBEAT_MS",
        )
    }

    private fun scheduleJavaCrashRestart(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            JAVA_CRASH_REQUEST_CODE,
            restartBroadcastIntent(context, RESTART_REASON_JAVA_CRASH, processToken),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val triggerAtMillis = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        IntercomFileLogger.i(TAG, "Scheduling restart triggerAtElapsed=$triggerAtMillis delayMs=$RESTART_DELAY_MS")
        scheduleAlarm(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
        )
    }

    private fun scheduleWatchdogAlarm(context: Context) {
        val token = processToken
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            restartBroadcastIntent(context, RESTART_REASON_WATCHDOG, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAlarm(
            context = context,
            triggerAtMillis = SystemClock.elapsedRealtime() + WATCHDOG_TIMEOUT_MS,
            pendingIntent = pendingIntent,
        )
    }

    private fun scheduleAlarm(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactAlarmAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (exactAlarmAllowed) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    private fun restartBroadcastIntent(context: Context, reason: String, token: String): Intent {
        return Intent(context, CrashRestartReceiver::class.java).apply {
            action = ACTION_RESTART_APP
            `package` = context.packageName
            data = restartUri(reason, token)
            putExtra(EXTRA_RESTART_REASON, reason)
            putExtra(EXTRA_PROCESS_TOKEN, token)
        }
    }

    private fun restartUri(reason: String, token: String): Uri {
        return Uri.Builder()
            .scheme("intercom")
            .authority("restart")
            .appendPath(reason)
            .appendPath(token)
            .build()
    }
}
