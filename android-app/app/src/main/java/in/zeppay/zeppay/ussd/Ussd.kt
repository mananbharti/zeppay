package `in`.zeppay.zeppay.ussd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import `in`.zeppay.zeppay.data.Carrier

/** Small platform helpers shared by the stepwise engine and the manual fallbacks. */
object Ussd {
    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Carrier of the default SIM from its operator name. null = could not tell. */
    fun detectCarrier(context: Context): Carrier? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        listOfNotNull(
            tm.simOperatorName,
            if (Build.VERSION.SDK_INT >= 29) tm.simCarrierIdName?.toString() else null,
            tm.networkOperatorName,
        ).firstNotNullOfOrNull { Carrier.detect(it) }
    } catch (_: Exception) { null }

    /**
     * Manual route: opens the phone's dialer with a *99# menu code typed in, and the user
     * presses Call and answers every prompt. Used when the accessibility helper is off.
     */
    fun openDialer(context: Context, code: String) {
        val uri = Uri.parse("tel:" + code.replace("#", Uri.encode("#")))
        context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Places a voice call (UPI 123PAY IVR). Commas in [dial] are DTMF pauses. */
    fun callNumber(context: Context, dial: String) {
        val uri = Uri.parse("tel:" + Uri.encode(dial, ","))
        val action = if (hasCallPermission(context)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
