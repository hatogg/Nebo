package ua.nebo.watch

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.topstep.fitcloud.sdk.v2.model.message.FcNotificationType

/**
 * Пересилає сповіщення телефона на годинник (замість FitCloudPro, поки годинник прив'язаний до «Неба»).
 */
class NotifyListener : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Watch.prefs.forwardNotifications) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_SERVICE) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val content = (if (title.isNotEmpty() && text.isNotEmpty()) "$title: $text" else title + text).take(240)
        val key = sbn.packageName + "|" + content
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 5_000 }
            if (recent.containsKey(key)) return
            recent[key] = now
        }
        Watch.forward(typeFor(sbn.packageName), content)
    }

    private fun typeFor(pkg: String): Int = when (pkg) {
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
        "nekox.messenger", "tw.nekomimi.nekogram" -> FcNotificationType.TELEGRAM
        "com.viber.voip" -> FcNotificationType.VIBER
        "com.whatsapp" -> FcNotificationType.WHATSAPP
        "com.whatsapp.w4b" -> FcNotificationType.WHATSAPP_BUSINESS
        "com.instagram.android" -> FcNotificationType.INSTAGRAM
        "com.facebook.katana" -> FcNotificationType.FACEBOOK
        "com.facebook.orca" -> FcNotificationType.FACEBOOK_MESSENGER
        "com.google.android.gm" -> FcNotificationType.GMAIL
        "com.microsoft.office.outlook" -> FcNotificationType.OUTLOOK
        "com.google.android.youtube" -> FcNotificationType.YOUTUBE
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> FcNotificationType.TIKTOK
        "com.snapchat.android" -> FcNotificationType.SNAPCHAT
        "com.twitter.android" -> FcNotificationType.TWITTER
        "com.linkedin.android" -> FcNotificationType.LINKEDIN
        "com.skype.raider" -> FcNotificationType.SKYPE
        "us.zoom.videomeetings" -> FcNotificationType.ZOOM
        "com.microsoft.teams" -> FcNotificationType.MICROSOFT_TEAMS
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms" -> FcNotificationType.SMS
        "com.google.android.calendar", "com.samsung.android.calendar" -> FcNotificationType.CALENDAR
        else -> FcNotificationType.OTHERS_APP
    }
}
