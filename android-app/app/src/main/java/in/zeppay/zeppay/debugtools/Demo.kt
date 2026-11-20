package `in`.zeppay.zeppay.debugtools

import `in`.zeppay.zeppay.BuildConfig
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.ussd.Engine

/**
 * Debug builds only: `adb shell am start -n in.zeppay.zeppay.debug/in.zeppay.zeppay.MainActivity --es demo sim:ok`
 * turns on the simulated carrier with a script (ok | ambiguous | fail | needs_account),
 * or `--es demo skip-setup` jumps past onboarding for screenshots.
 */
object Demo {
    fun apply(vm: ZepViewModel, cmd: String) {
        if (!BuildConfig.DEBUG) return
        when {
            cmd.startsWith("sim:") -> { Engine.simulate = true; Engine.simScript = cmd.removePrefix("sim:") }
            cmd == "skip-setup" -> vm.finishSetup()
        }
    }
}
