package net.muratov.intercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.muratov.intercom.logging.IntercomFileLogger

class CrashRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IntercomFileLogger.i(
            "CrashRestartReceiver",
            "onReceive action=${intent.action} package=${context.packageName}",
        )
        if (intent.action != AppCrashRestarter.ACTION_RESTART_APP) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra("restart_after_crash", true)
        }
        context.startActivity(launchIntent)
    }
}
