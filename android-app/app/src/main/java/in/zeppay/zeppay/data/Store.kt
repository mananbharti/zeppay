package `in`.zeppay.zeppay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Tx.status values. "success"/"failed"/"unknown" are the v1 values and stay readable.
const val STATUS_SUCCESS = "success"   // Paid: confirmed by the payment flow's own reply, or by the user
const val STATUS_FAILED = "failed"
const val STATUS_UNKNOWN = "unknown"   // Not confirmed: check bank SMS
const val STATUS_PENDING = "pending"   // Started, no result recorded yet
const val STATUS_STOPPED = "stopped"   // Ended before the PIN step: no money moved
const val ROUTE_USSD = "ussd"
const val ROUTE_IVR = "ivr"
const val ROUTE_MANUAL = "manual"

/** Who decided the status: the bank's *99# reply as read by ZepPay, or the user. */
const val BY_BANK_REPLY = "bank_reply"
const val BY_USER = "user"

data class Tx(
    val id: String,
    val payee: Payee,
    val amount: Double,
    val note: String,
    val status: String,
    val at: Long,
    val route: String,
    val raw: String = "",       // the carrier's text behind the status (never contains the PIN)
    val decidedBy: String = "",
    val splitRef: String = "",  // "groupId:personId" when paying a split share
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("vpa", payee.vpa).put("mobile", payee.mobile).put("account", payee.account)
        .put("ifsc", payee.ifsc).put("name", payee.name).put("amount", amount).put("note", note)
        .put("status", status).put("at", at).put("route", route).put("raw", raw)
        .put("decidedBy", decidedBy).put("splitRef", splitRef)

    companion object {
        fun fromJson(o: JSONObject): Tx = Tx(
            id = o.optString("id"),
            payee = Payee(o.optString("vpa"), o.optString("mobile"), o.optString("account"), o.optString("ifsc"), o.optString("name")),
            amount = o.optDouble("amount"),
            note = o.optString("note"),
            status = o.optString("status", STATUS_UNKNOWN),
            at = o.optLong("at"),
            route = o.optString("route", ROUTE_USSD),
            raw = o.optString("raw"),
            decidedBy = o.optString("decidedBy", BY_USER),
            splitRef = o.optString("splitRef"),
        )
    }
}

/** A carrier menu captured on this phone by the read-only menu check. */
data class MenuScan(val carrier: String, val at: Long, val raw: String, val items: List<Pair<String, String>>) {
    fun toJson(): JSONObject = JSONObject().put("carrier", carrier).put("at", at).put("raw", raw)
        .put("items", JSONArray().apply { items.forEach { put(JSONArray().put(it.first).put(it.second)) } })

    /** Whether the menu lists something matching [word] (e.g. "balance"). null = never checked. */
    fun offers(word: Regex): Boolean = items.any { word.containsMatchIn(it.second) }

    companion object {
        fun fromJson(o: JSONObject?): MenuScan? = o?.let {
            val arr = it.optJSONArray("items") ?: JSONArray()
            MenuScan(it.optString("carrier"), it.optLong("at"), it.optString("raw"),
                (0 until arr.length()).map { i -> arr.getJSONArray(i).let { p -> p.getString(0) to p.getString(1) } })
        }
    }
}

/**
 * On-device store (SharedPreferences "zeppay", same file and keys as v1 so updates keep
 * history and settings). Plain JSON on purpose: no secrets live here, there is no PIN to
 * protect, and staying dependency-free keeps v1 data readable. allowBackup is off.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("zeppay", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun str(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun put(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()

    var onboarded: Boolean get() = bool("onboarded", false); set(v) = put("onboarded", v)
    /** 2 = has seen the v2 setup (accessibility helper, SIM choice, updates). */
    var setupVersion: Int get() = prefs.getInt("setupVersion", 0); set(v) = prefs.edit().putInt("setupVersion", v).apply()
    var theme: String get() = str("theme", "auto"); set(v) = put("theme", v)
    var carrierOverride: String get() = str("carrier", "auto"); set(v) = put("carrier", v)
    var simSubId: Int get() = prefs.getInt("simSubId", -1); set(v) = prefs.edit().putInt("simSubId", v).apply()
    var dtmfAssist: Boolean get() = bool("dtmfAssist", true); set(v) = put("dtmfAssist", v)
    var ivrLang: String get() = str("ivrLang", "1"); set(v) = put("ivrLang", v)
    var dtmfAfterConnect: Int get() = prefs.getInt("dtmfAfterConnect", 6); set(v) = prefs.edit().putInt("dtmfAfterConnect", v).apply()
    var dtmfBetweenMenu: Int get() = prefs.getInt("dtmfBetweenMenu", 4); set(v) = prefs.edit().putInt("dtmfBetweenMenu", v).apply()
    var dtmfBeforeConfirm: Int get() = prefs.getInt("dtmfBeforeConfirm", 4); set(v) = prefs.edit().putInt("dtmfBeforeConfirm", v).apply()
    var autoUpdateCheck: Boolean get() = bool("autoUpdateCheck", true); set(v) = put("autoUpdateCheck", v)
    var lastUpdateCheck: Long get() = prefs.getLong("lastUpdateCheck", 0); set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()
    /** Debug builds only: a manifest URL for testing updates against a local server. */
    var updateUrlOverride: String get() = str("updateUrlOverride", ""); set(v) = put("updateUrlOverride", v)

    /**
     * The optional progress card over the bank's screen. Unset (upgrades from 2.0.0) means on
     * only if "display over other apps" was already granted, so nobody is asked again.
     */
    var overlayCard: Boolean
        get() = if (prefs.contains("overlayCard")) bool("overlayCard", false) else android.provider.Settings.canDrawOverlays(context)
        set(v) = put("overlayCard", v)
    /** Setup walkthrough progress: steps the user chose to skip, by id. */
    var setupSkipped: Set<String>
        get() = prefs.getStringSet("setupSkipped", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("setupSkipped", v).apply()
    /** The user went to Accessibility from the walkthrough at least once (shows the restricted-settings step if it stayed off). */
    var helperAttempted: Boolean get() = bool("helperAttempted", false); set(v) = put("helperAttempted", v)
    /** Runtime permissions already asked once, by name (so a second ask goes to App info instead). */
    var askedPermissions: Set<String>
        get() = prefs.getStringSet("askedPermissions", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("askedPermissions", v).apply()

    var menuScan: MenuScan?
        get() = try { MenuScan.fromJson(prefs.getString("menuScan", null)?.let { JSONObject(it) }) } catch (_: Exception) { null }
        set(v) = put("menuScan", v?.toJson()?.toString() ?: "")

    var history: List<Tx>
        get() = try {
            val arr = JSONArray(str("history", "[]"))
            (0 until arr.length()).map { Tx.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        set(list) = put("history", JSONArray().apply { list.take(500).forEach { put(it.toJson()) } }.toString())

    var groups: List<Group>
        get() = try {
            val arr = JSONArray(str("groups", "[]"))
            (0 until arr.length()).map { Group.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        set(list) = put("groups", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    fun record(tx: Tx) { history = listOf(tx) + history.filter { it.id != tx.id } }
}
