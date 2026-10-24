package ua.nebo.watch

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** Налаштування застосунку. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("nebo", Context.MODE_PRIVATE)

    var home: String
        get() = sp.getString("home", "м. Київ")!!
        set(v) = sp.edit().putString("home", v).apply()

    /** "map" — карта України, "home" — моя область і міні-карта. */
    var faceStyle: String
        get() = sp.getString("faceStyle", STYLE_MAP)!!
        set(v) = sp.edit().putString("faceStyle", v).apply()

    /** Стиль цифр годинника: 0 білий, 1 чорний, 2 жовтий, 3 зелений, 4 сірий. */
    var timeStyle: Int
        get() = sp.getInt("timeStyle", 0)
        set(v) = sp.edit().putInt("timeStyle", v).apply()

    /** "all" — оновлювати при будь-якій зміні на карті, "home" — лише при зміні в моїй області. */
    var pushPolicy: String
        get() = sp.getString("pushPolicy", POLICY_ALL)!!
        set(v) = sp.edit().putString("pushPolicy", v).apply()

    var autoPush: Boolean
        get() = sp.getBoolean("autoPush", true)
        set(v) = sp.edit().putBoolean("autoPush", v).apply()

    var alertNotify: Boolean
        get() = sp.getBoolean("alertNotify", true)
        set(v) = sp.edit().putBoolean("alertNotify", v).apply()

    /** Сповіщати про жовтий рівень (дронова загроза) у моєму районі/області. */
    var yellowNotify: Boolean
        get() = sp.getBoolean("yellowNotify", true)
        set(v) = sp.edit().putBoolean("yellowNotify", v).apply()

    /** Показувати на карті райони, де тривога закінчилась менше 20 хв тому. */
    var showRecent: Boolean
        get() = sp.getBoolean("showRecent", true)
        set(v) = sp.edit().putBoolean("showRecent", v).apply()

    /** Як часто можна перезаписувати циферблат при змінах в інших районах, секунд. */
    var pushIntervalSec: Int
        get() = sp.getInt("pushInterval", 30)
        set(v) = sp.edit().putInt("pushInterval", v).apply()

    var forwardNotifications: Boolean
        get() = sp.getBoolean("forward", false)
        set(v) = sp.edit().putBoolean("forward", v).apply()

    var deviceAddress: String?
        get() = sp.getString("devAddr", null)
        set(v) = sp.edit().putString("devAddr", v).apply()

    var deviceName: String?
        get() = sp.getString("devName", null)
        set(v) = sp.edit().putString("devName", v).apply()

    /** true після першої успішної прив'язки — далі підключаємось у режимі входу. */
    var bound: Boolean
        get() = sp.getBoolean("bound", false)
        set(v) = sp.edit().putBoolean("bound", v).apply()

    val userId: String
        get() {
            var id = sp.getString("userId", null)
            if (id == null) {
                id = (100000000L + Random.nextLong(899999999L)).toString()
                sp.edit().putString("userId", id).apply()
            }
            return id
        }

    var lastPushSignature: String?
        get() = sp.getString("lastSig", null)
        set(v) = sp.edit().putString("lastSig", v).apply()

    var lastPushTime: Long
        get() = sp.getLong("lastPush", 0L)
        set(v) = sp.edit().putLong("lastPush", v).apply()

    /** Слот циферблата на годиннику, у який ми пишемо (щоб не затирати інші). */
    var pushBinFlag: Int
        get() = sp.getInt("binFlag", -1)
        set(v) = sp.edit().putInt("binFlag", v).apply()

    /** Чи годинник використовує формат GUI (стиль часу перемикається командою). */
    var guiFormat: Boolean
        get() = sp.getBoolean("gui", false)
        set(v) = sp.edit().putBoolean("gui", v).apply()

    /** Позиція нашого циферблата в списку слотів годинника. */
    var dialPosition: Int
        get() = sp.getInt("dialPos", 0)
        set(v) = sp.edit().putInt("dialPos", v).apply()

    /** Стиль часу, який ще треба застосувати після перепідключення (-1 — нічого). */
    var pendingComponent: Int
        get() = sp.getInt("pendingComp", -1)
        set(v) = sp.edit().putInt("pendingComp", v).apply()

    /** Коли почалась тривога в регіоні (якщо джерело не дає час), name -> millis. */
    fun alertSince(region: String): Long = sp.getLong("since_$region", 0L)
    fun setAlertSince(region: String, t: Long) {
        if (t == 0L) sp.edit().remove("since_$region").apply() else sp.edit().putLong("since_$region", t).apply()
    }

    companion object {
        const val STYLE_MAP = "map"
        const val STYLE_HOME = "home"
        const val POLICY_ALL = "all"
        const val POLICY_HOME = "home"
    }
}
