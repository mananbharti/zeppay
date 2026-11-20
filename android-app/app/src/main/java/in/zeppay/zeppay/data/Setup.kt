package `in`.zeppay.zeppay.data

/**
 * The guided setup walkthrough as a pure state machine (no Android types, unit-tested).
 * One step per screen. What's done is never stored: it is re-detected from the system every
 * time ZepPay resumes ([SetupEnv]), so a permission switched off later shows up again. Only
 * the user's choices are stored: which steps they skipped, and whether they tried the helper.
 */
enum class SetupStep(val id: String, val optional: Boolean) {
    /** CALL_PHONE (+ READ_PHONE_STATE for picking the SIM). Without it ZepPay opens the dialer instead. */
    PHONE("phone", optional = true),
    /** The accessibility helper. Without it the user types each *99# step (manual route). */
    HELPER("helper", optional = true),
    /** Android 13+, installed from a file: "Allow restricted settings" before the helper switch works. */
    RESTRICTED("restricted", optional = true),
    /** Display over other apps. Only when the user turned on the progress card. */
    OVERLAY("overlay", optional = true),
    /** POST_NOTIFICATIONS on Android 13+, for the payment status notification. */
    NOTIFICATIONS("notifications", optional = true),
    /** Install unknown apps. Only when the user is actually installing an update. */
    INSTALL("install", optional = true);

    companion object { fun byId(id: String) = entries.firstOrNull { it.id == id } }
}

/** What the system says right now. Built on every resume. */
data class SetupEnv(
    val sdk: Int,
    val callGranted: Boolean,
    val helperOn: Boolean,
    /** Installed from an APK file (not a store), so Android 13+ may block the helper switch. */
    val sideloaded: Boolean,
    val overlayGranted: Boolean,
    /** The user wants the optional progress card. */
    val overlayCardOn: Boolean,
    val notificationsGranted: Boolean,
    /** The user is installing an update right now. */
    val installingUpdate: Boolean,
    val installGranted: Boolean,
)

/** What the user chose. Persisted. */
data class SetupProgress(val skipped: Set<SetupStep> = emptySet(), val helperAttempted: Boolean = false)

enum class StepStatus { DONE, TODO, SKIPPED }

object Setup {
    /** Steps that apply on this phone right now, in walkthrough order. */
    fun applicable(env: SetupEnv, p: SetupProgress): List<SetupStep> = buildList {
        add(SetupStep.PHONE)
        add(SetupStep.HELPER)
        // Only after the user tried the switch and it's still off: no point warning everyone.
        if (env.sdk >= 33 && env.sideloaded && p.helperAttempted && SetupStep.HELPER !in p.skipped) add(SetupStep.RESTRICTED)
        if (env.overlayCardOn) add(SetupStep.OVERLAY)
        if (env.sdk >= 33) add(SetupStep.NOTIFICATIONS)
        if (env.installingUpdate) add(SetupStep.INSTALL)
    }

    fun done(step: SetupStep, env: SetupEnv): Boolean = when (step) {
        SetupStep.PHONE -> env.callGranted
        SetupStep.HELPER, SetupStep.RESTRICTED -> env.helperOn
        SetupStep.OVERLAY -> env.overlayGranted
        SetupStep.NOTIFICATIONS -> env.sdk < 33 || env.notificationsGranted
        SetupStep.INSTALL -> env.installGranted
    }

    fun status(step: SetupStep, env: SetupEnv, p: SetupProgress): StepStatus = when {
        done(step, env) -> StepStatus.DONE
        step in p.skipped -> StepStatus.SKIPPED
        else -> StepStatus.TODO
    }

    /**
     * The step to show: the first one still to do, or null when the walkthrough is finished.
     * Once the restricted-settings step applies, it replaces the helper step: the helper's
     * switch is what Android is blocking, so that is the thing to fix first.
     */
    fun current(env: SetupEnv, p: SetupProgress): SetupStep? =
        shown(env, p).firstOrNull { status(it, env, p) == StepStatus.TODO }

    /** The steps the walkthrough shows, in order. */
    fun shown(env: SetupEnv, p: SetupProgress): List<SetupStep> =
        applicable(env, p).let { l -> if (SetupStep.RESTRICTED in l) l - SetupStep.HELPER else l }

    fun skip(step: SetupStep, p: SetupProgress): SetupProgress {
        require(step.optional) { "${step.id} can't be skipped" }
        return p.copy(skipped = p.skipped + step)
    }

    /** The user opened Accessibility from the helper step. */
    fun triedHelper(p: SetupProgress) = p.copy(helperAttempted = true)

    /** Re-opened from Settings: skipped steps are offered again; done steps stay done. */
    fun reopen(p: SetupProgress) = p.copy(skipped = emptySet())

    /** One step on its own (just in time: overlay when the card is turned on, install for an update). */
    fun single(step: SetupStep, env: SetupEnv): SetupStep? = if (done(step, env)) null else step

    /** For the progress dots: position of [step] among the applicable steps. */
    fun position(step: SetupStep, env: SetupEnv, p: SetupProgress): Pair<Int, Int> {
        val list = shown(env, p)
        return list.indexOf(step).coerceAtLeast(0) to list.size
    }

    fun encode(p: SetupProgress): Set<String> = p.skipped.map { it.id }.toSet()
    fun decode(skipped: Set<String>, helperAttempted: Boolean) =
        SetupProgress(skipped.mapNotNull { SetupStep.byId(it) }.toSet(), helperAttempted)
}
