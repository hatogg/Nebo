package ua.nebo.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Запускає фонову службу після перезавантаження телефона чи оновлення застосунку. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Watch.prefs.deviceAddress == null) return
        runCatching { NeboService.start(context) }
    }
}
