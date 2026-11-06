package ly.kashif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build()); return
        }
        val n = NumberUtil.normalize(details.handle?.schemeSpecificPart.orEmpty())
        val e = runBlocking(Dispatchers.IO) { AppDatabase.get(applicationContext).dao().find(n) }
        val block = e?.blocked == true
        respondToCall(details, CallResponse.Builder().setDisallowCall(block).setRejectCall(block).build())
        Notifier.show(this, n, e, block)
    }
}

object Notifier {
    fun show(c: Context, n: String, e: NumberEntry?, blocked: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("caller", "كاشف المتصل", NotificationManager.IMPORTANCE_HIGH))
        val title = e?.name?.ifBlank { null } ?: "رقم غير معروف"
        val text = when {
            blocked -> "تم حظر هذا الرقم تلقائياً"
            e?.spam == true -> "مزعج · بلّغ عنه ${e.reports} شخصاً"
            else -> NumberUtil.pretty(n) + (e?.city?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
        nm.notify(n.hashCode(), NotificationCompat.Builder(c, "caller")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
    }
}
