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
 * Фонова служба: раз на 15 с перевіряє тривоги по районах, тримає зв'язок з годинником,
 * оновлює циферблат (не частіше за обраний інтервал) і шле сповіщення про зміни в моєму районі чи області.
 */
class NeboService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "nebo-alerts") }
    private var running = false
    private var scheduledPush = false
    @Volatile private var pauseUntil = 0L
    /** район -> до коли показувати «нещодавній відбій» */
    private val recentUntil = HashMap<Int, Long>()
    /** Коли вперше побачили тривогу, якщо джерело не дало часу початку. */
    private val firstSeen = HashMap<String, Long>()

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
        if (System.currentTimeMillis() < pauseUntil) return
        val fetched = try {
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
        val now = System.currentTimeMillis()

        // Нещодавній відбій: район був у тривозі, а тепер тихо
        val raions = HashMap(fetched.raions)
        for ((i, lvl) in prev.raions) {
            if (lvl >= AlertState.YELLOW && (raions[i] ?: 0) < AlertState.YELLOW) recentUntil[i] = now + RECENT_MS
        }
        recentUntil.entries.removeAll { it.value < now || (raions[it.key] ?: 0) >= AlertState.YELLOW }
        for (i in recentUntil.keys) if ((raions[i] ?: 0) < AlertState.RECENT) raions[i] = AlertState.RECENT

        // Час початку, якщо джерело його не дало
        val since = HashMap(fetched.since)
        val activeKeys = raions.filterValues { it >= AlertState.YELLOW }.keys.map { "r$it" } +
            fetched.cities.filterValues { it >= AlertState.YELLOW }.keys.map { "c$it" }
        firstSeen.keys.retainAll(activeKeys.toSet())
        for (k in activeKeys) if (since[k] == null) since[k] = firstSeen.getOrPut(k) { now }

        val st = fetched.copy(raions = raions, since = since)
        current = st
        main.post { onNewState(prev, st) }
    }

    /** 0 — тихо, 1 — жовтий рівень, 2 — тривога. */
    private fun category(level: Int) = when {
        level >= AlertState.PARTIAL -> 2
        level == AlertState.YELLOW -> 1
        else -> 0
    }

    private fun onNewState(prev: AlertState, now: AlertState) {
        val prefs = Watch.prefs
        val map = MapData.get(this)
        val home = map.home(prefs.home)
        val short = home?.short ?: prefs.home
        val was = category(prev.homeLevel(map, home))
        val isNow = category(now.homeLevel(map, home))
        val first = prev.fetchedAt == 0L
        val homeChanged = !first && was != isNow

        if (homeChanged && prefs.alertNotify) {
            val time = hhmm(System.currentTimeMillis())
            when {
                isNow == 2 -> {
                    Watch.sendAlert("ПОВІТРЯНА ТРИВОГА — $short. Пройдіть в укриття!")
                    notifyPhone("Повітряна тривога — $short", "Оголошено о $time")
                }
                isNow == 1 && was == 2 -> {
                    Watch.sendAlert("Тривогу знято, лишається ЖОВТИЙ рівень — $short")
                    notifyPhone("Жовтий рівень — $short", "Тривогу знято о $time, загроза дронів лишається")
                }
                isNow == 1 && prefs.yellowNotify -> {
                    Watch.sendAlert("ЖОВТИЙ РІВЕНЬ — $short: загроза дронів")
                    notifyPhone("Жовтий рівень — $short", "Загроза дронів з $time")
                }
                isNow == 0 && was == 2 -> {
                    Watch.sendAlert("ВІДБІЙ тривоги — $short")
                    notifyPhone("Відбій тривоги — $short", "О $time")
                }
                isNow == 0 && prefs.yellowNotify -> {
                    Watch.sendAlert("Жовтий рівень знято — $short")
                    notifyPhone("Жовтий рівень знято — $short", "О $time")
                }
            }
        }

        updateNotification()
        broadcast()
        if (prefs.deviceAddress != null) Watch.sendStatus(statusFor(this, now))

        if (!prefs.autoPush || prefs.deviceAddress == null) return
        if (signatureOf(now) == prefs.lastPushSignature) return
        when {
            // Перший запуск, змінились налаштування або статус дому — одразу
            first || homeChanged || prefs.lastPushSignature == null -> Watch.pushFace(now, "home")
            prefs.pushPolicy == Prefs.POLICY_ALL -> schedulePush()
        }
    }

    /** Зміни в інших районах надсилаємо не частіше за обраний інтервал (30 с, 1 хв, 3 хв). */
    private fun schedulePush() {
        if (scheduledPush) return
        val interval = Watch.prefs.pushIntervalSec * 1000L
        val sinceLast = System.currentTimeMillis() - Watch.prefs.lastPushTime
        val delay = (interval - sinceLast).coerceAtLeast(1_000L)
        scheduledPush = true
        main.postDelayed({
            scheduledPush = false
            if (signatureOf(current) != Watch.prefs.lastPushSignature) Watch.pushFace(current, "map")
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
        val home = map.home(prefs.home)
        val short = home?.short ?: prefs.home
        val (red, yellow) = st.counts(map)
        val title = if (st.fetchedAt == 0L) "Небо працює" else when (category(st.homeLevel(map, home))) {
            2 -> "Тривога: $short"
            1 -> "Жовтий рівень: $short"
            else -> "$short: тихо"
        }
        val text = when {
            st.fetchedAt == 0L -> "Отримую дані про тривоги…"
            red == 0 && yellow == 0 -> "Тривог в Україні немає"
            yellow == 0 -> "Тривога в $red " + FaceRenderer.plural(red, "області", "областях", "областях")
            red == 0 -> "Жовтий рівень в $yellow " + FaceRenderer.plural(yellow, "області", "областях", "областях")
            else -> "Тривога в $red обл., жовтий рівень ще в $yellow"
        }
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
        private const val RECENT_MS = 20 * 60_000L
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alerts"

        @Volatile var current: AlertState = AlertState.EMPTY
            private set
        @Volatile var fetchError: String? = null
            private set

        val listeners = java.util.concurrent.CopyOnWriteArraySet<(JSONObject) -> Unit>()

        /** Підпис усього, що видно на циферблаті: тривоги + налаштування. */
        fun signatureOf(st: AlertState): String {
            val p = Watch.prefs
            val sig = if (p.showRecent) st.signature else st.copy(raions = st.raions.filterValues { it != AlertState.RECENT }).signature
            return sig + "#" + p.home + "#" + p.faceStyle + "#" + p.timeStyle + "#" + p.showRecent
        }

        fun start(context: Context, refresh: Boolean = false) {
            val i = Intent(context, NeboService::class.java)
            if (refresh) i.action = ACTION_REFRESH
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stateJson(context: Context): JSONObject {
            val prefs = Watch.prefs
            val map = MapData.get(context)
            val st = current
            val home = map.home(prefs.home)
            val (red, yellow) = st.counts(map)
            return JSONObject()
                .put("fetchedAt", st.fetchedAt)
                .put("source", st.source)
                .put("red", red)
                .put("yellow", yellow)
                .put("home", prefs.home)
                .put("homeShort", home?.short ?: prefs.home)
                .put("homeLevel", st.homeLevel(map, home))
                .put("homePartial", st.homePartial(map, home))
                .put("homeSince", st.homeSince(map, home))
                .put("sig", st.signature)
                .put("error", fetchError ?: JSONObject.NULL)
        }

        private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

        /** Текст для картки «Небо» і екрана погоди на годиннику. */
        fun statusFor(context: Context, st: AlertState): Watch.Status {
            val prefs = Watch.prefs
            val map = MapData.get(context)
            val home = map.home(prefs.home)
            val short = home?.short ?: prefs.home
            val level = st.homeLevel(map, home)
            val since = st.homeSince(map, home)
            val (red, yellow) = st.counts(map)
            val word = when {
                level >= AlertState.PARTIAL -> "ТРИВОГА"
                level == AlertState.YELLOW -> "ЖОВТИЙ РІВЕНЬ"
                level == AlertState.RECENT -> "ВІДБІЙ"
                else -> "ТИХО"
            }
            val line1 = buildString {
                append(short).append(": ").append(word)
                if (level >= AlertState.YELLOW && since > 0) append(" з ").append(FaceRenderer.sinceText(since))
                if (level == AlertState.YELLOW) append(" (дрони)")
                if (level >= AlertState.PARTIAL && (level == AlertState.PARTIAL || st.homePartial(map, home))) append(" (частина)")
            }
            val line2 = when {
                red == 0 && yellow == 0 -> "По Україні тривог немає"
                yellow == 0 -> "Тривога в $red обл."
                red == 0 -> "Жовтий рівень в $yellow обл."
                else -> "Тривога в $red обл., жовтий ще в $yellow"
            }
            val text = line1 + "\n" + line2 + "\nОновлено " + hhmm(System.currentTimeMillis())
            val cityWord = when {
                level >= AlertState.PARTIAL -> "Тривога"
                level == AlertState.YELLOW -> "Жовтий"
                level == AlertState.RECENT -> "Відбій"
                else -> "Тихо"
            }
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val night = hour >= 21 || hour < 6
            val code = when {
                level >= AlertState.PARTIAL -> 0x05      // гроза
                level == AlertState.YELLOW -> 0x03       // хмарно
                level == AlertState.RECENT -> if (night) 0x0e else 0x02
                else -> if (night) 0x0f else 0x01        // ясно
            }
            return Watch.Status("Небо", text, "$cityWord: $short", code, red, red, red + yellow)
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Стан", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постійне сповіщення фонової роботи"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Тривоги в моїй області", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Початок і відбій тривоги, жовтий рівень"
            })
        }
    }
}
