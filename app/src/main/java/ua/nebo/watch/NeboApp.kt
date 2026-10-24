package ua.nebo.watch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.polidea.rxandroidble3.LogConstants
import com.polidea.rxandroidble3.LogOptions
import com.polidea.rxandroidble3.RxBleClient
import com.topstep.fitcloud.sdk.v2.FcSDK
import com.topstep.fitcloud.sdk.v2.features.FcBuiltInFeatures
import com.topstep.wearkit.base.ProcessLifecycleManager
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import timber.log.Timber

class NeboApp : Application() {

    /** Стан «застосунок на екрані / у фоні», потрібен SDK для режимів сканування й перепідключення. */
    val lifecycleManager = object : ProcessLifecycleManager() {}

    lateinit var sdk: FcSDK
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Timber.plant(object : Timber.DebugTree() {
            override fun isLoggable(tag: String?, priority: Int): Boolean =
                BuildConfig.DEBUG || priority > Log.DEBUG
        })
        RxBleClient.updateLogOptions(
            LogOptions.Builder()
                .setLogLevel(if (BuildConfig.DEBUG) LogConstants.DEBUG else LogConstants.WARN)
                .setMacAddressLogSetting(LogConstants.MAC_ADDRESS_TRUNCATED)
                .setUuidsLogSetting(LogConstants.NONE)
                .setShouldLogAttributeValues(false)
                .setShouldLogScannedPeripherals(false)
                .build()
        )

        sdk = FcSDK.Builder(this, lifecycleManager)
            .setBuiltInFeatures(
                FcBuiltInFeatures(
                    autoSetLanguage = true,
                    autoSetTime = true,
                    telephonyControl = true,
                )
            )
            .build()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) lifecycleManager.setForeground(true)
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) lifecycleManager.setForeground(false)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val ignored = HashSet<Class<out Throwable>>(FcSDK.rxJavaPluginsIgnoreExceptions())
        RxJavaPlugins.setErrorHandler { error ->
            val cause = (if (error is UndeliverableException) error.cause else error) ?: return@setErrorHandler
            val list = if (cause is CompositeException) cause.exceptions else listOf(cause)
            if (list.any { e -> ignored.any { it.isAssignableFrom(e.javaClass) } }) return@setErrorHandler
            Timber.w(cause, "Rx undeliverable")
        }

        // Конектор має створюватися в головному потоці
        sdk.connector
        Watch.init(this)
    }

    companion object {
        lateinit var instance: NeboApp
            private set
    }
}
