package `in`.zeppay.zeppay.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import `in`.zeppay.zeppay.BuildConfig
import `in`.zeppay.zeppay.MainActivity
import `in`.zeppay.zeppay.R
import `in`.zeppay.zeppay.data.Store
import `in`.zeppay.zeppay.ussd.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Release source: a small JSON manifest attached to the latest GitHub Release of
 * mananbharti/zeppay, next to the APK. Why: HTTPS, no server to run, the
 * "latest/download" URL is stable, and release notes live next to the file.
 *
 * update.json: {"versionCode":6,"versionName":"2.0.1","apkUrl":"https://...apk",
 *               "sha256":"<hex>","size":1234,"notes":["..."]}
 *
 * Install path: PackageInstaller session, requesting USER_ACTION_NOT_REQUIRED where the
 * documented conditions might hold, and always handling STATUS_PENDING_USER_ACTION by
 * showing Android's own confirmation. Silent install is NOT promised.
 */
data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val size: Long, val notes: List<String>)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val r: Release) : UpdateState
    data class Downloading(val r: Release, val done: Long, val total: Long) : UpdateState
    data class Verifying(val r: Release) : UpdateState
    data class Installing(val r: Release) : UpdateState
    data class WaitingForYou(val r: Release) : UpdateState   // Android's confirmation is showing / needed
    data class NeedsInstallPermission(val r: Release) : UpdateState
    data class Blocked(val r: Release, val why: String) : UpdateState  // e.g. a payment is running
    data class Failed(val why: String, val r: Release? = null) : UpdateState
}

object Updater {
    const val DEFAULT_URL = "https://github.com/mananbharti/zeppay/releases/latest/download/update.json"
    private const val CHANNEL = "updates"
    private const val ACTION_STATUS = "in.zeppay.zeppay.INSTALL_STATUS"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Set by MainActivity: true while ZepPay is on screen (decides prompt vs notification). */
    @Volatile var foreground = false
    /** Set by the app while a payment or call-assisted flow is open. */
    @Volatile var paymentOpen = false

    fun manifestUrl(store: Store): String =
        if (BuildConfig.DEBUG && store.updateUrlOverride.isNotBlank()) store.updateUrlOverride else DEFAULT_URL

    fun parse(json: String): Release {
        val o = JSONObject(json)
        val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val r = Release(o.getLong("versionCode"), o.optString("versionName"), o.getString("apkUrl"), o.getString("sha256").lowercase(), o.optLong("size"), notes)
        require(r.apkUrl.startsWith("https://") || BuildConfig.DEBUG) { "APK URL must be HTTPS" }
        require(Regex("^[0-9a-f]{64}$").matches(r.sha256)) { "Bad checksum in manifest" }
        return r
    }

    fun installedVersionCode(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }

    suspend fun check(c: Context, store: Store, quiet: Boolean = false): UpdateState = withContext(Dispatchers.IO) {
        if (!quiet) _state.value = UpdateState.Checking
        val s = try {
            val r = parse(httpGet(manifestUrl(store)))
            store.lastUpdateCheck = System.currentTimeMillis()
            if (r.versionCode > installedVersionCode(c)) UpdateState.Available(r) else UpdateState.UpToDate(store.lastUpdateCheck)
        } catch (e: Exception) {
            UpdateState.Failed("Couldn't check for updates. This needs internet. (${e.message ?: "error"})")
        }
        if (!quiet || s is UpdateState.Available) _state.value = s
        s
    }

    /** Download → verify → install. Refuses while a payment is open. */
    suspend fun downloadAndInstall(c: Context, r: Release) {
        if (paymentOpen || Engine.busy()) { _state.value = UpdateState.Blocked(r, "Finish or close the payment first. ZepPay never installs during a payment or a call."); return }
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) { _state.value = UpdateState.NeedsInstallPermission(r); return }
        val file = File(c.cacheDir, "update/zeppay-${r.versionCode}.apk").apply { parentFile?.mkdirs() }
        try {
            withContext(Dispatchers.IO) { download(r, file) }
            _state.value = UpdateState.Verifying(r)
            withContext(Dispatchers.IO) { verify(c, r, file) }
            if (paymentOpen || Engine.busy()) { _state.value = UpdateState.Blocked(r, "A payment started. Install again when it's done."); return }
            _state.value = UpdateState.Installing(r)
            withContext(Dispatchers.IO) { install(c, file) }
        } catch (e: Exception) {
            file.delete()
            _state.value = UpdateState.Failed(e.message ?: "Update failed", r)
        }
    }

    fun openInstallPermission(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000; conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun download(r: Release, out: File) {
        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) error("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L; var lastEmit = 0L
            conn.inputStream.use { inp -> out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    os.write(buf, 0, n); done += n
                    if (done - lastEmit > 128 * 1024 || done == total) { lastEmit = done; _state.value = UpdateState.Downloading(r, done, total) }
                }
            } }
        } finally { conn.disconnect() }
    }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Never install an unverified file: checksum, same package, higher versionCode, same signer. */
    fun verify(c: Context, r: Release, f: File) {
        if (sha256(f) != r.sha256) error("The download's checksum doesn't match. Not installing.")
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: error("The download isn't a valid app file.")
        if (archive.packageName != c.packageName) error("The download is a different app. Not installing.")
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (newCode <= installedVersionCode(c)) error("The download isn't newer than this version.")
        val mine = signerDigests(pm.getPackageInfo(c.packageName, flags))
        val theirs = signerDigests(archive)
        if (mine.isEmpty() || mine != theirs) error("The download isn't signed with ZepPay's key. Not installing.")
    }

    private fun signerDigests(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(f.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { inp -> s.openWrite("base.apk", 0, f.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
            val cb = PendingIntent.getBroadcast(c, id, Intent(c, InstallStatusReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(cb.intentSender)
        }
    }

    internal fun onStatus(c: Context, intent: Intent) {
        val r = (_state.value as? UpdateState.Installing)?.r ?: (_state.value as? UpdateState.WaitingForYou)?.r
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (r != null) _state.value = UpdateState.WaitingForYou(r)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (foreground) c.startActivity(confirm) else notify(c, "Tap to finish updating ZepPay", "Android needs you to confirm the update.", confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // the app restarts on the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                _state.value = UpdateState.Failed(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled." else "Android didn't install the update ($msg).", r)
            }
        }
    }

    fun notify(c: Context, title: String, text: String, target: Intent? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = target ?: Intent(c, MainActivity::class.java).putExtra("open", "update").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, 7, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            nm.notify(202, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(title)
                .setContentText(text).setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    /** Background check every 12 h on any network (platform JobScheduler). It only notifies; installing always takes a tap. */
    fun schedule(c: Context, on: Boolean) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (!on) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        // A failed schedule must never stop the app from opening: payments don't depend on it.
        try {
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, CheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(12 * 60 * 60 * 1000L).setPersisted(false).build())
        } catch (_: Exception) {}
    }
    private const val JOB_ID = 4201
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = Updater.onStatus(c, intent)
}

class CheckJob : JobService() {
    private val scope = kotlinx.coroutines.MainScope()
    override fun onStartJob(p: JobParameters): Boolean {
        scope.launch {
            val s = Updater.check(applicationContext, Store(applicationContext), quiet = true)
            if (s is UpdateState.Available) Updater.notify(applicationContext, "ZepPay ${s.r.versionName} is ready", "Tap to see what's new and update.")
            jobFinished(p, false)
        }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}
