package net.muratov.intercom.browser

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getOrCreate(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .isolatedProcessEnabled(false)
                    .appZygoteProcessEnabled(false)
                    .build(),
            ).also { runtime = it }
        }
    }
}
