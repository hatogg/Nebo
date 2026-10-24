package ua.nebo.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Фонова служба: раз на 15 с перевіряє тривоги, тримає зв'язок з годинником,
 * оновлює циферблат і шле сповіщення про тривогу в моїй області.
 */
class NeboService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "nebo-alerts") }
    private var running = false
    private var throttledPushPending = false
    @Volatile private var pauseUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(current))
        Watch.autoConnect()
        if (!running) {
            running = true
            main.post(tick)
        } else if (intent?.action == ACTION_REFRESH && System.currentTimeMillis() - current.fetchedAt > 5_000L) {
            net.execute { poll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        net.shutdownNow()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            net.execute { poll() }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun poll() {
        val prefs = Watch.prefs
        if (System.currentTimeMillis() < pauseUntil) return
        val st = try {
            Alerts.fetch(this)
        } catch (e: Alerts.RateLimited) {
            Timber.w("alerts rate limited, retry in ${e.retryAfterSec}s")
            pauseUntil = System.currentTimeMillis() + e.retryAfterSec * 1000L
            if (current.fetchedAt == 0L) { fetchError = "Джерело тривог перевантажене, повтор за хвилину"; broadcast() }
            return
        } catch (e: Throwable) {
            Timber.w(e, "alerts fetch failed")
            fetchError = Watch.humanError(e)
            broadcast()
            return
        }
        fetchError = null
        val prev = current
        // Час початку: беремо з джерела або запам'ятовуємо сами
        val since = HashMap(st.since)
        for (r in st.active) {
            if (since[r] == null) {
                var t = prefs.alertSince(r)
                if (t == 0L) { t = System.currentTimeMillis(); prefs.setAlertSince(r, t) }
                since[r] = t
            }
        }
        for (r in prev.active - st.active) prefs.setAlertSince(r, 0L)
        val now = st.copy(since = since)
        current = now
        main.post { onNewState(prev, now) }
    }

    private fun onNewState(prev: AlertState, now: AlertState) {
        val prefs = Watch.prefs
        val home = MapData.get(this).find(prefs.home)?.name ?: prefs.home
        val wasHome = prev.isActive(home)
        val isHome = now.isActive(home)
        val first = prev.fetchedAt == 0L
        val homeChanged = !first && wasHome != isHome

        if (homeChanged && prefs.alertNotify) {
            val short = MapData.get(this).find(home)?.short ?: home
            if (isHome) {
                Watch.sendAlert("ПОВІТРЯНА ТРИВОГА — $short. Пройдіть в укриття!")
                notifyPhone("Повітряна тривога — $short", "Оголошено о " + hhmm(now.since[home] ?: System.currentTimeMillis()))
            } else {
                Watch.sendAlert("ВІДБІЙ тривоги — $short")
                notifyPhone("Відбій тривоги — $short", "О " + hhmm(System.currentTimeMillis()))
            }
        }

        updateNotification()
        broadcast()

        if (!prefs.autoPush || prefs.deviceAddress == null) return
        val sig = now.signature + "#" + prefs.home + "#" + prefs.faceStyle + "#" + prefs.timeStyle
        if (sig == prefs.lastPushSignature) return
        val mapChanged = prev.signature != now.signature
        when {
            // Перший запуск або змінились налаштування — оновлюємо одразу
            first || homeChanged || prefs.lastPushSignature == null -> Watch.pushFace(now, "home")
            prefs.pushPolicy == Prefs.POLICY_ALL && (mapChanged || !throttledPushPending) -> schedulePush()
        }
    }

    /** Зміни в інших областях збираємо разом, щоб не перезаписувати циферблат щохвилини. */
    private fun schedulePush() {
        if (throttledPushPending) return
        val since = System.currentTimeMillis() - Watch.prefs.lastPushTime
        val delay = (MIN_PUSH_INTERVAL_MS - since).coerceAtLeast(5_000L)
        throttledPushPending = true
        main.postDelayed({
            throttledPushPending = false
            val prefs = Watch.prefs
            val sig = current.signature + "#" + prefs.home + "#" + prefs.faceStyle + "#" + prefs.timeStyle
            if (sig != prefs.lastPushSignature) Watch.pushFace(current, "map")
        }, delay)
    }

    private fun broadcast() {
        val data = stateJson(this)
        listeners.forEach { runCatching { it(data) } }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(current))
    }

    private fun buildNotification(st: AlertState): Notification {
        val prefs = Watch.prefs
        val map = MapData.get(this)
        val home = map.find(prefs.home)
        val n = map.oblastCount(st.active)
        val title = when {
            st.fetchedAt == 0L -> "Небо працює"
            home != null && st.isActive(home.name) -> "Тривога: ${home.short}"
            else -> "${home?.short ?: prefs.home}: тихо"
        }
        val text = if (st.fetchedAt == 0L) "Отримую дані про тривоги…"
        else if (n == 0) "Тривог в Україні немає" else "Тривога в $n " + FaceRenderer.plural(n, "області", "областях", "областях")
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_STATUS) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyPhone(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_ALERT) else @Suppress("DEPRECATION") Notification.Builder(this)
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val canBt = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val type = if (canBt) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            try {
                startForeground(NOTIF_ID, n, type)
            } catch (e: Exception) {
                Timber.w(e, "startForeground typed failed")
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_REFRESH = "ua.nebo.watch.REFRESH"
        private const val POLL_MS = 15_000L
        private const val MIN_PUSH_INTERVAL_MS = 3 * 60_000L
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alerts"

        @Volatile var current: AlertState = AlertState.EMPTY
            private set
        @Volatile var fetchError: String? = null
            private set

        val listeners = java.util.concurrent.CopyOnWriteArraySet<(JSONObject) -> Unit>()

        fun start(context: Context, refresh: Boolean = false) {
            val i = Intent(context, NeboService::class.java)
            if (refresh) i.action = ACTION_REFRESH
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stateJson(context: Context): JSONObject {
            val prefs = Watch.prefs
            val map = MapData.get(context)
            val st = current
            val home = map.find(prefs.home)
            val active = org.json.JSONArray()
            st.active.forEach { active.put(it) }
            return JSONObject()
                .put("fetchedAt", st.fetchedAt)
                .put("active", active)
                .put("count", map.oblastCount(st.active))
                .put("home", prefs.home)
                .put("homeShort", home?.short ?: prefs.home)
                .put("homeActive", home != null && st.isActive(home.name))
                .put("homeSince", home?.let { st.since[it.name] } ?: 0L)
                .put("error", fetchError ?: JSONObject.NULL)
        }

        private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Стан", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постійне сповіщення фонової роботи"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Тривоги в моїй області", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Початок і відбій тривоги"
            })
        }
    }
}
