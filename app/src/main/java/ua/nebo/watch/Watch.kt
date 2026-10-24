package ua.nebo.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.topstep.fitcloud.sdk.v2.FcConnector
import com.topstep.fitcloud.sdk.v2.dfu.FcDfuManager
import com.topstep.fitcloud.sdk.v2.model.config.FcDeviceInfo
import com.topstep.fitcloud.sdk.v2.model.config.FcNotificationConfig
import com.topstep.fitcloud.sdk.v2.model.config.toBuilder
import com.topstep.fitcloud.sdk.v2.model.message.FcNotificationType
import com.topstep.fitcloud.sdk.v2.model.settings.dial.FcDialSpace
import com.topstep.fitcloud.sdk.v2.model.settings.dial.FcShape
import com.topstep.fitcloud.sdk.v2.utils.dial.DialDrawer
import com.topstep.fitcloud.sdk.v2.utils.dial.DialWriter
import com.topstep.wearkit.base.connector.ConnectorState
import com.topstep.wearkit.base.connector.DisconnectedReason
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Усе, що стосується годинника: пошук, прив'язка, генерація й надсилання циферблата, сповіщення.
 * Працює через офіційний FitCloudPro SDK.
 */
object Watch {

    interface Listener {
        fun onWatchEvent(type: String, data: JSONObject)
    }

    private lateinit var app: NeboApp
    lateinit var prefs: Prefs
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nebo-watch") }
    private val listeners = LinkedHashSet<Listener>()

    val connector: FcConnector get() = app.sdk.connector

    @Volatile var state: ConnectorState = ConnectorState.DISCONNECTED
        private set
    @Volatile var authFailed = false
        private set
    @Volatile var pushing = false
        private set
    @Volatile var pushProgress = 0
        private set
    @Volatile var pushStage = ""
        private set
    @Volatile var lastError: String? = null
        private set

    /** Стан, який чекає на надсилання (наприклад, годинник був не на зв'язку). */
    @Volatile private var pending: AlertState? = null

    private var scanDisposable: Disposable? = null
    private val scanFound = LinkedHashMap<String, JSONObject>()

    fun init(app: NeboApp) {
        this.app = app
        prefs = Prefs(app)
        connector.observerConnectorState()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ s -> onState(s) }, { Timber.w(it) })
        connector.observerConnectorError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ e ->
                lastError = humanError(e.throwable)
                emit("error", JSONObject().put("message", lastError))
            }, { Timber.w(it) })
    }

    fun addListener(l: Listener) = synchronized(listeners) { listeners += l }
    fun removeListener(l: Listener) = synchronized(listeners) { listeners -= l }

    private fun emit(type: String, data: JSONObject = JSONObject()) {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { runCatching { it.onWatchEvent(type, data) } } }
    }

    private fun onState(s: ConnectorState) {
        state = s
        if (s == ConnectorState.CONNECTED) {
            authFailed = false
            if (!prefs.bound) prefs.bound = true
            applyNotificationConfig()
            val pc = prefs.pendingComponent
            if (pc >= 0 && prefs.guiFormat && !pushing) worker.execute {
                if (applyComponent(prefs.dialPosition, pc)) prefs.pendingComponent = -1
            }
            pending?.let { st -> pending = null; pushFace(st, "reconnect") }
        } else if (s == ConnectorState.DISCONNECTED) {
            val reason = runCatching { connector.getDisconnectedReason() }.getOrNull()
            if (reason == DisconnectedReason.AUTH_LOGIN_FAILED) authFailed = true
            if (reason == DisconnectedReason.AUTH_BIND_FAILED) lastError = "Годинник відхилив прив'язку. Підтвердьте її на екрані годинника і спробуйте ще раз."
        }
        emit("state", statusJson())
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("state", state.name)
        .put("address", prefs.deviceAddress ?: JSONObject.NULL)
        .put("name", prefs.deviceName ?: JSONObject.NULL)
        .put("bound", prefs.bound)
        .put("authFailed", authFailed)
        .put("pushing", pushing)
        .put("progress", pushProgress)
        .put("stage", pushStage)
        .put("lastPush", prefs.lastPushTime)
        .put("error", lastError ?: JSONObject.NULL)
        .put("diag", diag ?: JSONObject.NULL)

    // ---------- пошук і прив'язка ----------

    fun startScan() {
        stopScan()
        scanFound.clear()
        scanDisposable = app.sdk.scanner.scan(25, TimeUnit.SECONDS, false, true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ r ->
                val name = r.name?.takeIf { it.isNotBlank() } ?: "Годинник"
                scanFound[r.address] = JSONObject().put("address", r.address).put("name", name).put("rssi", r.rssi)
                val arr = JSONArray()
                scanFound.values.sortedByDescending { it.optInt("rssi") }.forEach { arr.put(it) }
                emit("scan", JSONObject().put("devices", arr).put("done", false))
            }, { e ->
                emit("scan", JSONObject().put("devices", JSONArray(scanFound.values)).put("done", true).put("error", humanError(e)))
            }, {
                emit("scan", JSONObject().put("devices", JSONArray(scanFound.values)).put("done", true))
            })
    }

    fun stopScan() {
        scanDisposable?.dispose()
        scanDisposable = null
    }

    /** Перша прив'язка: годинник забуде попередній застосунок і очистить збережені на ньому дані. */
    fun bind(address: String, name: String?) {
        stopScan()
        runCatching { connector.close() }
        prefs.deviceAddress = address
        prefs.deviceName = name
        prefs.bound = false
        prefs.pushBinFlag = -1
        authFailed = false
        lastError = null
        connector.connect(address, prefs.userId, true, true, 25, 170f, 65f)
        emit("state", statusJson())
    }

    /** Підключення до вже прив'язаного годинника (після перезапуску телефона чи застосунку). */
    fun autoConnect() {
        val addr = prefs.deviceAddress ?: return
        val s = connector.getConnectorState()
        if (s != ConnectorState.DISCONNECTED && connector.getDevice()?.address == addr) return
        connector.connect(addr, prefs.userId, !prefs.bound, true, 25, 170f, 65f)
    }

    fun unbind() {
        val c = connector
        c.settingsFeature().unbindUser().ignoreElement().onErrorComplete()
            .andThen(c.settingsFeature().unbindAudioDevice().onErrorComplete())
            .timeout(8, TimeUnit.SECONDS)
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                c.close()
                prefs.deviceAddress = null
                prefs.deviceName = null
                prefs.bound = false
                prefs.pushBinFlag = -1
                prefs.lastPushSignature = null
                emit("state", statusJson())
            }
    }

    private fun applyNotificationConfig() {
        runCatching {
            val cf = connector.configFeature()
            val b = cf.getNotificationConfig().toBuilder()
            if (prefs.forwardNotifications) b.setAllEnabled(true)
            b.setFlagEnabled(FcNotificationConfig.Flag.OTHERS_APP, true)
            b.setFlagEnabled(FcNotificationConfig.Flag.TELEPHONY, true)
            cf.setNotificationConfig(b.create()).onErrorComplete().subscribe()
        }.onFailure { Timber.w(it) }
    }

    fun onForwardChanged() {
        if (state == ConnectorState.CONNECTED) applyNotificationConfig()
    }

    // ---------- сповіщення ----------

    fun sendAlert(text: String) {
        if (state != ConnectorState.CONNECTED || pushing) return
        connector.messageFeature().sendNotification(FcNotificationType.OTHERS_APP, "Небо", text)
            .onErrorComplete().subscribe()
    }

    fun forward(type: Int, content: String) {
        if (state != ConnectorState.CONNECTED || pushing) return
        val info = runCatching { connector.configFeature().getDeviceInfo() }.getOrNull()
        val t = if (info != null && type != FcNotificationType.OTHERS_APP && !info.isSupportNotification(type)) FcNotificationType.OTHERS_APP else type
        connector.messageFeature().sendNotification(t, null, content).onErrorComplete().subscribe()
    }

    // ---------- циферблат ----------

    private val queued = java.util.concurrent.atomic.AtomicReference<Pair<AlertState, String>?>(null)

    /**
     * Ставить у чергу генерацію й надсилання циферблата для стану [st].
     * Якщо в черзі вже щось чекає — просто підміняє стан на найсвіжіший.
     */
    fun pushFace(st: AlertState, reason: String) {
        if (prefs.deviceAddress == null) return
        if (state != ConnectorState.CONNECTED) {
            pending = st
            return
        }
        if (queued.getAndSet(st to reason) == null) {
            worker.execute {
                val job = queued.getAndSet(null) ?: return@execute
                val force = job.second == "manual" || job.second == "settings"
                if (!force && NeboService.signatureOf(job.first) == prefs.lastPushSignature) return@execute
                doPush(job.first, job.second)
            }
        }
    }

    private fun stage(text: String, progress: Int) {
        pushStage = text
        pushProgress = progress
        emit("push", statusJson())
    }

    private fun doPush(st: AlertState, reason: String) {
        if (pushing) return
        pushing = true
        lastError = null
        val started = System.currentTimeMillis()
        try {
            stage("Готую циферблат…", 0)
            val c = connector
            val info: FcDeviceInfo = c.configFeature().getDeviceInfo()
            if (!info.isSupportFeature(FcDeviceInfo.Feature.DIAL_PUSH)) throw WatchException("Цей годинник не підтримує завантаження циферблатів")
            if (!info.isSupportFeature(FcDeviceInfo.Feature.DIAL_CUSTOM)) throw WatchException("Цей годинник не підтримує власні циферблати")
            val gui = info.isSupportFeature(FcDeviceInfo.Feature.GUI)
            val push = c.settingsFeature().requestDialPushInfo().timeout(20, TimeUnit.SECONDS).blockingGet()
            val shape: FcShape = push.shape ?: throw WatchException("Невідома форма екрана — оновіть застосунок")

            stage("Завантажую шаблон…", 5)
            val tpl = template(push.lcd, push.toolVersion, gui, info, shape)

            stage("Малюю карту…", 15)
            val face = FaceRenderer(app).render(shape.width, shape.height, st, prefs.home, prefs.faceStyle, prefs.showRecent)
            val background = DialDrawer.createDialBackground(face, shape, DialDrawer.ScaleType.CENTER_CROP)!!
            val preview = DialDrawer.createDialPreview(
                face, tpl.style, shape, DialDrawer.ScaleType.CENTER_CROP, DialDrawer.Position.TOP,
                tpl.styleBaseOnWidth, shape.width, shape.height
            )!!
            val temp = File(app.cacheDir, "nebo_dial.bin")
            temp.delete()
            val writer = DialWriter(tpl.bin, background, preview, DialDrawer.Position.TOP, gui)
            writer.setCopyFile(temp)
            writer.setAutoScaleBackground(true)
            writer.setAutoScalePreview(true)
            val out: File = writer.execute()

            // Обираємо слот на годиннику
            val spaces: List<FcDialSpace> = push.dialSpaces ?: emptyList()
            var binFlag: Byte = 0
            var position = 0
            if (spaces.isNotEmpty()) {
                val sizeKb = (out.length() + 1023) / 1024
                val usable = spaces.withIndex().filter { (_, s) ->
                    s.dialType != FcDialSpace.DIAL_TYPE_NONE && (s.spaceSize <= 0 || s.spaceSize >= sizeKb)
                }
                if (usable.isEmpty()) throw WatchException("На годиннику немає вільного місця для циферблата")
                val customTypes = setOf(
                    FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_WHITE, FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_BLACK,
                    FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_YELLOW, FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_GREEN,
                    FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_GRAY
                )
                val chosen = usable.firstOrNull { it.value.binFlag.toInt() == prefs.pushBinFlag }
                    ?: usable.firstOrNull { it.value.dialType in customTypes }
                    ?: usable.last()
                binFlag = chosen.value.binFlag
                position = chosen.index
            }

            stage("Надсилаю на годинник…", 20)
            diag = "формат ${if (gui) "GUI" else "базовий"} · lcd ${push.lcd} · tool ${push.toolVersion} · " +
                "чип ${runCatching { info.getIcType().toString() }.getOrDefault("?")} · слот ${position + 1}/${spaces.size.coerceAtLeast(1)} · " +
                "стиль ${tpl.styleIndex + 1}/${tpl.styleCount} · ${(out.length() + 1023) / 1024} КБ"
            Timber.i("push: $diag")
            prefs.guiFormat = gui
            prefs.dialPosition = position
            runDfu(c, out, binFlag)
            prefs.pushBinFlag = binFlag.toInt()

            // Після запису годинник часто перепідключається — чекаємо зв'язку
            if (state != ConnectorState.CONNECTED) {
                stage("Годинник застосовує циферблат…", 99)
                runCatching { c.reconnect() }
                waitConnected(40_000L)
            }
            if (gui) {
                if (state == ConnectorState.CONNECTED && applyComponent(position, tpl.styleIndex)) {
                    prefs.pendingComponent = -1
                } else {
                    // Застосуємо стиль часу, щойно годинник знову буде на зв'язку
                    prefs.pendingComponent = tpl.styleIndex
                }
            }
            prefs.lastPushSignature = NeboService.signatureOf(st)
            prefs.lastPushTime = System.currentTimeMillis()
            Timber.i("Dial pushed ($reason) in ${System.currentTimeMillis() - started} ms")
            stage("Готово", 100)
        } catch (e: Throwable) {
            Timber.w(e, "push failed")
            lastError = humanError(e)
            if (state != ConnectorState.CONNECTED) pending = st
            emit("error", JSONObject().put("message", lastError))
        } finally {
            pushing = false
            emit("push", statusJson())
        }
    }

    /**
     * Надсилає файл циферблата. Деякі годинники після 100% одразу застосовують циферблат і рвуть зв'язок,
     * не надіславши підтвердження, — тоді вважаємо запис успішним через 20 с після 100%.
     */
    private fun runDfu(c: FcConnector, file: File, binFlag: Byte) {
        val dfu = c.newDfuManager(true)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val fullAt = java.util.concurrent.atomic.AtomicLong(0L)
        val progressSub = dfu.observerStateProgress().subscribe({ p ->
            Timber.d("dfu ${p.state} ${p.progress}")
            when (p.state) {
                FcDfuManager.DfuState.DFU_ING -> if (p.progress >= 0) {
                    stage("Надсилаю на годинник…", 20 + p.progress * 78 / 100)
                    if (p.progress >= 100) fullAt.compareAndSet(0L, System.currentTimeMillis())
                }
                FcDfuManager.DfuState.DFU_SUCCESS -> completed.set(true)
                else -> {}
            }
        }, {})
        val job = dfu.start(FcDfuManager.DfuType.DIAL, Uri.fromFile(file), binFlag)
            .subscribe({ completed.set(true) }, { failed.set(it) })
        val begin = System.currentTimeMillis()
        try {
            while (true) {
                if (completed.get()) return
                val e = failed.get()
                if (e != null) {
                    // Обрив зв'язку вже після повної передачі — циферблат на годиннику є
                    if (fullAt.get() > 0L) { Timber.w(e, "dfu error after 100%, treat as success"); return }
                    throw e
                }
                val now = System.currentTimeMillis()
                if (fullAt.get() > 0L && now - fullAt.get() > 20_000L) { Timber.w("dfu: no confirmation after 100%, treat as success"); return }
                if (now - begin > 6 * 60_000L) throw WatchException("Годинник не прийняв циферблат за 6 хвилин")
                Thread.sleep(300)
            }
        } finally {
            job.dispose()
            progressSub.dispose()
            runCatching { dfu.release() }
        }
    }

    private fun waitConnected(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (state == ConnectorState.CONNECTED) return true
            Thread.sleep(500)
        }
        return state == ConnectorState.CONNECTED
    }

    private fun applyComponent(position: Int, styleIndex: Int): Boolean = runCatching {
        connector.settingsFeature().setDialComponent(position, byteArrayOf(styleIndex.toByte()))
            .timeout(10, TimeUnit.SECONDS).blockingAwait()
        true
    }.getOrElse { Timber.w(it, "setDialComponent failed"); false }

    /** На годинниках формату GUI колір часу міняється командою, без повторного надсилання циферблата. */
    fun applyTimeStyleQuick(): Boolean {
        if (!prefs.guiFormat || prefs.pushBinFlag < 0 || state != ConnectorState.CONNECTED || pushing) return false
        val pos = prefs.dialPosition
        val style = prefs.timeStyle
        worker.execute {
            if (applyComponent(pos, style)) emit("toast", JSONObject().put("message", "Колір годинника змінено"))
        }
        return true
    }

    @Volatile var diag: String? = null
        private set

    private class Template(val bin: File, val style: Bitmap, val styleBaseOnWidth: Int, val styleIndex: Int, val styleCount: Int)

    private val BASE_STYLE_NAMES = listOf("White", "Black", "Yellow", "Green", "Gray")
    private val BASE_STYLE_RES = intArrayOf(
        com.topstep.fitcloud.sdk.R.drawable.fc_dial_custom_style_white,
        com.topstep.fitcloud.sdk.R.drawable.fc_dial_custom_style_black,
        com.topstep.fitcloud.sdk.R.drawable.fc_dial_custom_style_yellow,
        com.topstep.fitcloud.sdk.R.drawable.fc_dial_custom_style_green,
        com.topstep.fitcloud.sdk.R.drawable.fc_dial_custom_style_gray,
    )

    /** Шаблон власного циферблата з сервера FitCloud (той самий, що використовує FitCloudPro). */
    private fun template(lcd: Int, toolVersion: String, gui: Boolean, info: FcDeviceInfo, shape: FcShape): Template {
        val styleIdx = prefs.timeStyle.coerceIn(0, 4)
        if (!gui) {
            val arr = apiList("/public/dial/custom", mapOf("lcd" to lcd.toString(), "toolVersion" to toolVersion))
            if (arr.length() == 0) throw WatchException("Сервер не має шаблону власного циферблата для цього годинника")
            var pick: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("styleName").equals(BASE_STYLE_NAMES[styleIdx], true)) pick = o
            }
            val o = pick ?: arr.getJSONObject(0)
            val idx = BASE_STYLE_NAMES.indexOfFirst { it.equals(o.optString("styleName"), true) }.let { if (it < 0) 0 else it }
            val bin = download(o.getString("binUrl"), ".bin")
            val style = BitmapFactory.decodeResource(app.resources, BASE_STYLE_RES[idx])
            return Template(bin, style, DialDrawer.STYLE_BASE_ON_WIDTH, idx, arr.length())
        } else {
            val platform = when (info.getIcType()) {
                FcDeviceInfo.IcType.IC_568X -> "gui-5681"
                FcDeviceInfo.IcType.IC_8773 -> "gui-8773"
                else -> "gui"
            }
            val arr = apiList("/public/dial/customgui", mapOf("lcd" to lcd.toString(), "toolVersion" to toolVersion, "platform" to platform))
            if (arr.length() == 0) throw WatchException("Сервер не має шаблону власного циферблата для цього годинника")
            val o = arr.getJSONObject(0)
            val urls = o.optJSONArray("components")?.optJSONObject(0)?.optJSONArray("urls")
                ?: throw WatchException("Шаблон циферблата без стилів часу")
            if (urls.length() == 0) throw WatchException("Шаблон циферблата без стилів часу")
            val idx = styleIdx.coerceAtMost(urls.length() - 1)
            val bin = download(o.getString("binUrl"), ".bin")
            val styleFile = download(urls.getString(idx), ".png")
            val style = BitmapFactory.decodeFile(styleFile.absolutePath) ?: throw WatchException("Не вдалося відкрити стиль часу")
            return Template(bin, style, shape.width * 2, idx, urls.length())
        }
    }

    private const val API = "http://fitcloud.hetangsmart.com"

    private fun apiList(path: String, params: Map<String, String>): JSONArray {
        val body = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = URL(API + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw WatchException("Сервер циферблатів відповів ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            return root.optJSONArray("data") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, ext: String): File {
        val dir = File(app.filesDir, "templates").apply { mkdirs() }
        val name = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ext
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return file
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location") ?: throw WatchException("Помилка завантаження шаблону")
                    return@repeat
                }
                if (code != 200) throw WatchException("Помилка завантаження шаблону ($code)")
                val tmp = File(dir, "$name.part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(file)) throw WatchException("Не вдалося зберегти шаблон")
                return file
            } finally {
                conn.disconnect()
            }
        }
        throw WatchException("Забагато переадресацій при завантаженні шаблону")
    }

    class WatchException(message: String) : Exception(message)

    fun humanError(e: Throwable?): String {
        if (e == null) return "Невідома помилка"
        if (e is WatchException) return e.message ?: "Помилка"
        val n = e.javaClass.simpleName
        return when {
            n.contains("Auth", true) -> "Годинник прив'язаний до іншого застосунку. Натисніть «Прив'язати знову»."
            n.contains("Timeout", true) -> "Годинник не відповів вчасно"
            n.contains("BleScan", true) -> "Не вдалося почати пошук. Увімкніть Bluetooth і геолокацію"
            n.contains("BleDisconnected", true) || n.contains("Disconnected", true) -> "Зв'язок з годинником перервався"
            e is java.net.UnknownHostException -> "Немає інтернету"
            e is java.io.IOException -> "Помилка мережі: ${e.message ?: n}"
            else -> e.message?.let { "$n: $it" } ?: n
        }
    }
}
