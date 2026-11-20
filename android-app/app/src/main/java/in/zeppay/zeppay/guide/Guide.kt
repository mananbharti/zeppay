package `in`.zeppay.zeppay.guide

import android.app.Activity
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import `in`.zeppay.zeppay.R
import `in`.zeppay.zeppay.data.SetupStep
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/**
 * Short offline guide clips, one per setup step, shipped in res/raw (no network). To replace a
 * placeholder with a real recording, overwrite the same file name; no code change is needed.
 */
object Guide {
    fun clip(step: SetupStep): Int = when (step) {
        SetupStep.PHONE -> R.raw.guide_phone
        SetupStep.HELPER -> R.raw.guide_helper
        SetupStep.RESTRICTED -> R.raw.guide_restricted
        SetupStep.OVERLAY -> R.raw.guide_overlay
        SetupStep.NOTIFICATIONS -> R.raw.guide_notifications
        SetupStep.INSTALL -> R.raw.guide_install
    }

    fun uri(c: Context, res: Int): Uri = Uri.parse("android.resource://${c.packageName}/$res")

    /**
     * Picture-in-picture is usable: the device has the feature (low-RAM phones don't) and the
     * user hasn't switched "Picture-in-picture" off for ZepPay.
     */
    fun pipAvailable(c: Context): Boolean {
        if (!c.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return try {
            val ops = c.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), c.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { true }
    }

    /** Set when entering PiP was refused at run time; the walkthrough then plays the clip inline. */
    val pipFailed = MutableStateFlow(false)

    /**
     * Plays [step]'s clip floating in picture-in-picture, then opens [then] (the step's system
     * screen) underneath it. Returns false when PiP isn't available, so the caller plays it inline.
     */
    fun watch(c: Context, step: SetupStep, then: Intent?): Boolean {
        if (!pipAvailable(c)) return false
        pipFailed.value = false
        c.startActivity(Intent(c, GuideVideoActivity::class.java)
            .putExtra(GuideVideoActivity.EXTRA_CLIP, clip(step))
            .putExtra(GuideVideoActivity.EXTRA_THEN, then)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return true
    }

    /** Closes the floating clip (the step got done, or the user is back in ZepPay). */
    fun close() { GuideVideoActivity.current?.get()?.finish() }
}

/**
 * The floating guide player. Its own task (taskAffinity in the manifest) so the PiP window
 * stays up while the user is in system Settings. Muted, looping, offline.
 */
class GuideVideoActivity : Activity() {
    companion object {
        const val EXTRA_CLIP = "clip"
        const val EXTRA_THEN = "then"
        @Volatile var current: WeakReference<GuideVideoActivity>? = null
    }

    private lateinit var video: VideoView
    private lateinit var chrome: LinearLayout
    private var prepared = false
    private var resumed = false
    private var triedPip = false
    private var ratio = Rational(9, 20)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        current = WeakReference(this)
        video = VideoView(this)
        val close = Button(this).apply { text = getString(R.string.guide_close); setOnClickListener { finish() } }
        val caption = TextView(this).apply {
            text = getString(R.string.guide_caption); setTextColor(Color.WHITE); textSize = 15f
            setPadding(24, 16, 24, 16)
        }
        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(caption); addView(close)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(chrome, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        play(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); triedPip = false; prepared = false; play(i) }

    private fun play(i: Intent) {
        val res = i.getIntExtra(EXTRA_CLIP, 0)
        if (res == 0) { finish(); return }
        video.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = true; mp.setVolume(0f, 0f)
            if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = clampRatio(Rational(mp.videoWidth, mp.videoHeight))
            prepared = true; video.start(); maybePip()
        }
        video.setOnErrorListener { _, _, _ -> Guide.pipFailed.value = true; finish(); true }
        video.setVideoURI(Guide.uri(this, res))
    }

    override fun onResume() { super.onResume(); resumed = true; maybePip() }
    override fun onPause() { resumed = false; super.onPause() }

    private fun maybePip() {
        if (!prepared || !resumed || triedPip || isInPictureInPictureMode) return
        triedPip = true
        val ok = try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) } catch (_: Exception) { false }
        if (!ok) { Guide.pipFailed.value = true; finish(); return }
        @Suppress("DEPRECATION")
        val then = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_THEN, Intent::class.java) else intent.getParcelableExtra(EXTRA_THEN)
        then?.let { try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        chrome.visibility = if (inPip) android.view.View.GONE else android.view.View.VISIBLE
    }

    // Swiping the PiP window away stops the activity; don't leave it running hidden.
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    /** Android accepts PiP aspect ratios between 1:2.39 and 2.39:1. */
    private fun clampRatio(r: Rational): Rational {
        val v = r.toFloat()
        return when { v < 1 / 2.39f -> Rational(100, 239); v > 2.39f -> Rational(239, 100); else -> r }
    }
}
