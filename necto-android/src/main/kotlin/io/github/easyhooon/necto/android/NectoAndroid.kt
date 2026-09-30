package io.github.easyhooon.necto.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.easyhooon.necto.sdk.NectoPlugin
import io.github.easyhooon.necto.sdk.NectoSDK
import io.github.easyhooon.necto.transport.NectoDeviceListener
import java.lang.ref.WeakReference

/**
 * The Android entry point: supplies the app's identity to [NectoSDK], tracks the
 * foreground activity for UI Control, registers plugins and starts listening.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         if (BuildConfig.DEBUG) NectoAndroid.start(this)
 *     }
 * }
 * ```
 *
 * Then, on the Mac running Necto: `adb forward tcp:9979 tcp:9979`. Necto probes that
 * loopback port and shows the app as it would a simulator app.
 */
public object NectoAndroid {
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    private var tracking = false

    /** The activity in the foreground, if any. UI Control acts on its window. */
    public val currentActivity: Activity? get() = resumed?.get()

    /**
     * Starts Necto with [plugins], which default to every plugin Necto ships for Android.
     * Safe to call more than once; plugins already registered are skipped.
     */
    public fun start(
        context: Context,
        plugins: List<NectoPlugin> = NectoAndroidPlugins.defaults(context),
        port: Int = NectoDeviceListener.DEFAULT_PORT,
    ) {
        val application = context.applicationContext as Application
        track(application)
        NectoSDK.identity = NectoAndroidAppIdentity(application)
        plugins.forEach { plugin ->
            if (NectoSDK.plugins.none { it.id == plugin.id }) NectoSDK.register(plugin)
        }
        NectoSDK.start(port)
    }

    public fun stop() {
        NectoSDK.stop()
    }

    internal fun track(application: Application) {
        synchronized(this) {
            if (tracking) return
            tracking = true
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
