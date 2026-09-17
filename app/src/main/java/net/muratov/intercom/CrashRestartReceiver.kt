package net.muratov.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import net.muratov.intercom.logging.IntercomFileLogger
import java.io.File

class CrashRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CrashRestartReceiver"
        private const val NOTIFICATION_CHANNEL_ID = "crash_recovery"
        private const val NOTIFICATION_ID = 1001
        private const val ACTIVITY_REQUEST_CODE = 1003
        private const val NOTIFICATION_TIMEOUT_MS = 120_000L

        fun dismissRecoveryNotification(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        IntercomFileLogger.i(
            TAG,
            "onReceive action=${intent.action} package=${context.packageName}",
        )
        if (intent.action != AppCrashRestarter.ACTION_RESTART_APP) return
        if (!AppCrashRestarter.shouldLaunchAfterRestartIntent(intent)) {
            IntercomFileLogger.d(
                TAG,
                "Ignoring watchdog alarm because the watched process is still running",
            )
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra("restart_after_crash", true)
            putExtra(
                AppCrashRestarter.EXTRA_RESTART_REASON,
                intent.getStringExtra(AppCrashRestarter.EXTRA_RESTART_REASON),
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            context,
            ACTIVITY_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Intercom crash recovery",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Restarts Intercom after an unexpected process termination"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setPriority(Notification.PRIORITY_MAX)
        }
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Перезапуск приложения после сбоя")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(launchPendingIntent)
            .setFullScreenIntent(launchPendingIntent, true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)

        runCatching {
            context.startActivity(launchIntent)
        }.onFailure { error ->
            IntercomFileLogger.w(TAG, "Direct activity restart was rejected; full-screen intent remains active", error)
        }
        startWithSystemPrivileges(
            context = context,
            reason = intent.getStringExtra(AppCrashRestarter.EXTRA_RESTART_REASON).orEmpty(),
        )
    }

    private fun startWithSystemPrivileges(context: Context, reason: String) {
        val suPath = sequenceOf("/system/xbin/su", "/system/bin/su")
            .map(::File)
            .firstOrNull(File::canExecute)
            ?.absolutePath
            ?: return
        val pendingResult = goAsync()
        val componentName = ComponentName(context, MainActivity::class.java).flattenToShortString()
        Thread {
            try {
                val process = ProcessBuilder(
                    suPath,
                    "0",
                    "am",
                    "start",
                    "--user",
                    "0",
                    "-n",
                    componentName,
                    "--ez",
                    "restart_after_crash",
                    "true",
                    "--es",
                    AppCrashRestarter.EXTRA_RESTART_REASON,
                    reason,
                )
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                IntercomFileLogger.i(TAG, "System-privileged activity restart finished exitCode=$exitCode")
            } catch (error: Throwable) {
                IntercomFileLogger.w(TAG, "System-privileged activity restart failed", error)
            } finally {
                pendingResult.finish()
            }
        }.apply {
            name = "intercom-crash-restart"
            isDaemon = true
            start()
        }
    }
}
