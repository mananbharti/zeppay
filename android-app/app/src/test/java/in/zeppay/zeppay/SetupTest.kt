package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.data.Setup
import `in`.zeppay.zeppay.data.SetupEnv
import `in`.zeppay.zeppay.data.SetupProgress
import `in`.zeppay.zeppay.data.SetupStep
import `in`.zeppay.zeppay.data.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val fresh = SetupEnv(sdk = 35, callGranted = false, helperOn = false, sideloaded = true, overlayGranted = false,
        overlayCardOn = false, notificationsGranted = false, installingUpdate = false, installGranted = false)
    private val none = SetupProgress()

    @Test fun walksStepsInOrderAndDetectsGrants() {
        assertEquals(SetupStep.PHONE, Setup.current(fresh, none))
        val phoneOk = fresh.copy(callGranted = true)
        assertEquals(SetupStep.HELPER, Setup.current(phoneOk, none))
        val helperOk = phoneOk.copy(helperOn = true)
        assertEquals(SetupStep.NOTIFICATIONS, Setup.current(helperOk, none))
        assertNull(Setup.current(helperOk.copy(notificationsGranted = true), none))
    }

    @Test fun skippingMovesOnAndIsRemembered() {
        val p = Setup.skip(SetupStep.PHONE, none)
        assertEquals(SetupStep.HELPER, Setup.current(fresh, p))
        assertEquals(StepStatus.SKIPPED, Setup.status(SetupStep.PHONE, fresh, p))
        // Round-trips through what the Store keeps.
        val back = Setup.decode(Setup.encode(p), p.helperAttempted)
        assertEquals(p, back)
    }

    @Test fun reopenOffersSkippedStepsAgainButKeepsDoneOnes() {
        val env = fresh.copy(helperOn = true)
        val p = Setup.skip(SetupStep.PHONE, none)
        val again = Setup.reopen(p)
        assertEquals(SetupStep.PHONE, Setup.current(env, again))
        assertEquals(StepStatus.DONE, Setup.status(SetupStep.HELPER, env, again))
    }

    @Test fun revokedPermissionComesBack() {
        val allDone = fresh.copy(callGranted = true, helperOn = true, notificationsGranted = true)
        assertNull(Setup.current(allDone, none))
        assertEquals(SetupStep.HELPER, Setup.current(allDone.copy(helperOn = false), none))
    }

    @Test fun restrictedStepOnlyAfterATriedHelperOnSideloadedAndroid13Plus() {
        val env = fresh.copy(callGranted = true)
        assertFalse(SetupStep.RESTRICTED in Setup.applicable(env, none))
        val tried = Setup.triedHelper(none)
        assertTrue(SetupStep.RESTRICTED in Setup.applicable(env, tried))
        assertEquals(SetupStep.RESTRICTED, Setup.current(env, tried))
        // Not on Android 12, not from a store, not when the helper step was skipped.
        assertFalse(SetupStep.RESTRICTED in Setup.applicable(env.copy(sdk = 32), tried))
        assertFalse(SetupStep.RESTRICTED in Setup.applicable(env.copy(sideloaded = false), tried))
        assertFalse(SetupStep.RESTRICTED in Setup.applicable(env, Setup.skip(SetupStep.HELPER, tried)))
        // Re-check: once the helper is on, the restricted step is done.
        assertEquals(StepStatus.DONE, Setup.status(SetupStep.RESTRICTED, env.copy(helperOn = true), tried))
    }

    @Test fun overlayOnlyWhenTheCardIsOn() {
        val env = fresh.copy(callGranted = true, helperOn = true, notificationsGranted = true)
        assertFalse(SetupStep.OVERLAY in Setup.applicable(env, none))
        assertEquals(SetupStep.OVERLAY, Setup.current(env.copy(overlayCardOn = true), none))
        assertNull(Setup.current(env.copy(overlayCardOn = true, overlayGranted = true), none))
    }

    @Test fun notificationsOnlyOnAndroid13Plus() {
        val env = fresh.copy(sdk = 32, callGranted = true, helperOn = true)
        assertFalse(SetupStep.NOTIFICATIONS in Setup.applicable(env, none))
        assertNull(Setup.current(env, none))
    }

    @Test fun installOnlyWhileInstallingAnUpdate() {
        val env = fresh.copy(callGranted = true, helperOn = true, notificationsGranted = true)
        assertFalse(SetupStep.INSTALL in Setup.applicable(env, none))
        assertEquals(SetupStep.INSTALL, Setup.single(SetupStep.INSTALL, env.copy(installingUpdate = true)))
        assertNull(Setup.single(SetupStep.INSTALL, env.copy(installingUpdate = true, installGranted = true)))
    }

    @Test fun positionCountsShownSteps() {
        val env = fresh.copy(callGranted = true)
        assertEquals(1 to 3, Setup.position(SetupStep.HELPER, env, none))
    }
}
