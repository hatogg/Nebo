package ua.nebo.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity(), Watch.Listener {

    private lateinit var web: WebView
    private var ready = false
    private val serviceListener: (JSONObject) -> Unit = { data -> runOnUiThread { send("alerts", data) } }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF0B0E17.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
        if (BuildConfig.DEBUG && intent.getBooleanExtra("renderTest", false)) renderTest()
        requestBasePermissions()
    }

    /** Лише для налагоджувальної збірки: малює зразки циферблатів у файли (перевірка в CI). */
    private fun renderTest() {
        Thread {
            val dir = java.io.File(getExternalFilesDir(null), "faces").apply { mkdirs() }
            val r = FaceRenderer(this)
            val now = System.currentTimeMillis()
            val east = setOf("Харківська область", "Сумська область", "Донецька область", "Луганська область",
                "Запорізька область", "Дніпропетровська область", "Херсонська область", "Автономна Республіка Крим")
            val cases = listOf(
                Triple("kyiv_alert", east + setOf("Київська область", "м. Київ"), "м. Київ"),
                Triple("lviv_calm", east, "Львівська область"),
                Triple("all_clear", emptySet(), "Одеська область"),
            )
            for ((name, active, home) in cases) {
                val st = AlertState(active, active.associateWith { now - 23 * 60_000L }, now)
                for (style in listOf(Prefs.STYLE_MAP, Prefs.STYLE_HOME)) {
                    val bmp = r.render(410, 502, st, home, style)
                    java.io.File(dir, "${name}_$style.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            val live = runCatching { Alerts.fetch(this) }
            java.io.File(dir, "live.txt").writeText(
                live.fold({ "OK active=" + it.active.sorted().joinToString(", ") + " since=" + it.since }, { "FAIL " + it })
            )
            live.getOrNull()?.let { st ->
                val bmp = r.render(410, 502, st, "м. Київ", Prefs.STYLE_MAP)
                java.io.File(dir, "live_map.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            java.io.File(dir, "done").writeText("ok")
        }.start()
    }

    override fun onStart() {
        super.onStart()
        Watch.addListener(this)
        NeboService.listeners += serviceListener
        if (ready) send("resume", JSONObject())
    }

    override fun onStop() {
        Watch.removeListener(this)
        NeboService.listeners -= serviceListener
        super.onStop()
    }

    override fun onWatchEvent(type: String, data: JSONObject) = send(type, data)

    private fun send(type: String, data: JSONObject) {
        if (!ready) return
        val js = "window.Nebo&&Nebo.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ")"
        web.evaluateJavascript(js, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.Nebo?Nebo.back():false") { r ->
            if (r != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------- дозволи ----------

    private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestBasePermissions() {
        val need = ArrayList<String>()
        blePermissions().filterNotTo(need) { has(it) }
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) onPermissionsReady() else requestPermissions(need.toTypedArray(), REQ_BASE)
    }

    private fun onPermissionsReady() {
        if (blePermissions().all { has(it) }) runCatching { NeboService.start(this) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BASE -> { onPermissionsReady(); send("perm", permJson()) }
            REQ_CALLS -> {
                if (has(Manifest.permission.READ_PHONE_STATE)) Watch.connector.telephonyControlPhoneStatePermission()
                send("perm", permJson())
            }
            REQ_SCAN -> if (blePermissions().all { has(it) }) { onPermissionsReady(); Watch.startScan() } else
                send("scan", JSONObject().put("devices", JSONArray()).put("done", true).put("error", "Без дозволу на Bluetooth годинник не знайти"))
        }
    }

    private fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NotifyListener::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }

    private fun batteryFree(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun permJson(): JSONObject = JSONObject()
        .put("ble", blePermissions().all { has(it) })
        .put("notifications", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
        .put("notificationAccess", notificationAccess())
        .put("battery", batteryFree())
        .put("calls", has(Manifest.permission.READ_PHONE_STATE))

    // ---------- міст для інтерфейсу ----------

    inner class Bridge {
        @JavascriptInterface
        fun hello(): Boolean = true

        @JavascriptInterface
        fun getState(): String {
            val p = Watch.prefs
            val regions = JSONArray()
            MapData.get(this@MainActivity).regions.sortedBy { it.short }.forEach {
                regions.put(JSONObject().put("name", it.name).put("short", it.short))
            }
            return JSONObject()
                .put("watch", Watch.statusJson())
                .put("alerts", NeboService.stateJson(this@MainActivity))
                .put("prefs", JSONObject()
                    .put("home", p.home)
                    .put("faceStyle", p.faceStyle)
                    .put("timeStyle", p.timeStyle)
                    .put("pushPolicy", p.pushPolicy)
                    .put("autoPush", p.autoPush)
                    .put("alertNotify", p.alertNotify)
                    .put("forward", p.forwardNotifications))
                .put("perm", permJson())
                .put("regions", regions)
                .put("version", BuildConfig.VERSION_NAME)
                .toString()
        }

        @JavascriptInterface
        fun getPreview(): String {
            val p = Watch.prefs
            val bmp = FaceRenderer(this@MainActivity).render(410, 502, NeboService.current, p.home, p.faceStyle)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun setPref(key: String, value: String) {
            val p = Watch.prefs
            var repush = false
            when (key) {
                "home" -> { p.home = value; repush = true }
                "faceStyle" -> { p.faceStyle = value; repush = true }
                "timeStyle" -> { p.timeStyle = value.toIntOrNull() ?: 0; repush = true }
                "pushPolicy" -> p.pushPolicy = value
                "autoPush" -> p.autoPush = value == "true"
                "alertNotify" -> p.alertNotify = value == "true"
                "forward" -> {
                    p.forwardNotifications = value == "true"
                    runOnUiThread { Watch.onForwardChanged() }
                }
            }
            if (repush && p.autoPush && p.deviceAddress != null && NeboService.current.fetchedAt > 0) {
                runOnUiThread { Watch.pushFace(NeboService.current, "settings") }
            }
        }

        @JavascriptInterface
        fun scan(start: Boolean) = runOnUiThread {
            if (!start) { Watch.stopScan(); return@runOnUiThread }
            val need = blePermissions().filterNot { has(it) }
            if (need.isEmpty()) Watch.startScan() else requestPermissions(need.toTypedArray(), REQ_SCAN)
        }

        @JavascriptInterface
        fun bind(address: String, name: String) = runOnUiThread {
            Watch.bind(address, name)
            NeboService.start(this@MainActivity)
        }

        @JavascriptInterface
        fun unbind() = runOnUiThread { Watch.unbind() }

        @JavascriptInterface
        fun pushNow() = runOnUiThread {
            if (NeboService.current.fetchedAt == 0L) NeboService.start(this@MainActivity, refresh = true)
            Watch.pushFace(NeboService.current, "manual")
        }

        @JavascriptInterface
        fun refresh() = runOnUiThread { NeboService.start(this@MainActivity, refresh = true) }

        @JavascriptInterface
        fun openNotificationAccess() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestBattery() = runOnUiThread {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        @JavascriptInterface
        fun requestCalls() = runOnUiThread {
            val list = arrayListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 26) list += Manifest.permission.ANSWER_PHONE_CALLS
            requestPermissions(list.toTypedArray(), REQ_CALLS)
        }

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    companion object {
        private const val REQ_BASE = 1
        private const val REQ_CALLS = 2
        private const val REQ_SCAN = 3
    }
}
