package `in`.zeppay.zeppay.debugtools

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import `in`.zeppay.zeppay.BuildConfig
import `in`.zeppay.zeppay.ussd.UssdService

/**
 * DEBUG BUILDS ONLY (registered in src/debug/AndroidManifest.xml). A stand-in for the
 * carrier's *99# dialog so the stepwise engine can be exercised on an emulator with no SIM.
 * Frames are illustrative, not captured from a real carrier: real texts must come from the
 * carrier-menu check on a real phone. Nothing here touches the network or money.
 * Extra "script": ok (default) | ambiguous | fail | needs_account | slow.
 * "slow" closes the dialog for 6 s after the PIN step before the reply appears, the way some
 * real phones show nothing while the bank processes (the gap that 2.1.0 misread as "closed").
 */
class SimulatedCarrierActivity : Activity() {
    private lateinit var msg: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private var step = 0
    private var answers = mutableListOf<String>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!BuildConfig.DEBUG) { finish(); return }
        title = UssdService.SIM_WINDOW_TITLE
        val pad = (20 * resources.displayMetrics.density).toInt()
        msg = TextView(this).apply { textSize = 17f; setPadding(0, 0, 0, pad / 2) }
        input = EditText(this)
        send = Button(this).apply { text = "Send"; setOnClickListener { next(input.text.toString()) } }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        val row = LinearLayout(this).apply { gravity = Gravity.END; addView(cancel); addView(send) }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); addView(msg); addView(input); addView(row) })
        step = intent.getIntExtra("startStep", 0)
        intent.getStringArrayListExtra("answers")?.let { answers.addAll(it) }
        show()
    }

    private val code get() = intent.getStringExtra("code") ?: "*99#"
    private val script get() = intent.getStringExtra("script") ?: "ok"

    private fun frames(): List<Pair<String, Boolean>> {
        val vpa = answers.getOrElse(0) { "" }; val amt = answers.getOrElse(1) { "" }
        return when (code) {
            "*99#" -> listOf("Welcome to *99#\n1. Send Money\n2. Request Money\n3. Check Balance\n4. My Profile\n5. Pending Requests\n6. Transactions\n7. UPI PIN" to true)
            "*99*3#" -> listOf("Enter UPI PIN" to true, "Your A/c XX1234 available balance is Rs 1,234.50" to false)
            else -> buildList {
                if (script == "needs_account") add("Select account\n1. SBI XX1234\n2. HDFC XX9876" to true)
                add((if (code == "*99*1*1#") "Enter mobile number of the payee" else "Enter UPI ID of the payee") to true)
                add("Enter amount in Rs." to true)
                add("Enter remarks (1 to skip)" to true)
                add("Pay Rs $amt to $vpa\nEnter UPI PIN" to true)
                add(when (script) {
                    "fail" -> "Transaction declined by bank. Ref no 412345678901"
                    "ambiguous" -> "Your request has been processed. Ref no 412345678901"
                    else -> "Payment of Rs $amt to $vpa is successful. Ref no 412345678901"
                } to false)
            }
        }
    }

    private fun show() {
        val f = frames()
        if (step >= f.size) { finish(); return }
        val (text, hasInput) = f[step]
        msg.text = text
        input.setText("")
        input.visibility = if (hasInput) android.view.View.VISIBLE else android.view.View.GONE
        // The PIN box masks input like the real one; the value is discarded below, never stored.
        input.inputType = if (text.contains("PIN")) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        send.text = if (hasInput) "Send" else "OK"
    }

    private fun next(value: String) {
        val f = frames()
        if (step < f.size && !f[step].second) { finish(); return }
        val isPin = step < f.size && f[step].first.contains("PIN")
        val isMenu = script == "needs_account" && step == 0
        if (!isPin && !isMenu) answers += value
        step++
        if (isPin && script == "slow") {
            val again = android.content.Intent(applicationContext, SimulatedCarrierActivity::class.java)
                .putExtras(intent).putExtra("startStep", step).putStringArrayListExtra("answers", ArrayList(answers))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val app = applicationContext
            finish()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ app.startActivity(again) }, 6_000)
            return
        }
        show()
    }
}
