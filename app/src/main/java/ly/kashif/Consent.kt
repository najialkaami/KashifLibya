package ly.kashif

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PREFS = "kashif"
private const val KEY_CONSENT = "contacts_consent"
private const val KEY_LAST_SYNC = "contacts_last_sync"
private const val KEY_STATUS = "sync_status"

private const val CONSENT_TEXT = """نود إعلامك بأن تطبيقنا يعتمد على بناء قاعدة بيانات للأسماء وأرقام الهواتف بهدف تحسين خدمة كشف هوية المتصل والتعرف على الأرقام.

عند الموافقة، سيطلب التطبيق صلاحية الوصول إلى جهات الاتصال، وستُعالج البيانات التي لديك صلاحية قانونية لمشاركتها وفق سياسة الخصوصية، وقد تُرسل إلى خوادمنا وقاعدة بياناتنا المركزية (Supabase).

باستخدام هذه البيانات، نسعى إلى تطوير قاعدة بيانات تساعد جميع مستخدمي التطبيق على معرفة هوية المتصلين.

ملاحظة: الموافقة على هذه العملية شرط للاستفادة من خدمات التطبيق.

هل توافق على ذلك؟"""

/** يعرض نافذة الموافقة عند أول فتح، ولا يعرض التطبيق إلا بعد الموافقة. */
@Composable
fun ConsentGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var agreed by remember { mutableStateOf(prefs.getBoolean(KEY_CONSENT, false)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ContactsSync.syncIfDue(ctx.applicationContext)
    }

    LaunchedEffect(agreed) {
        if (agreed) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                ContactsSync.syncIfDue(ctx.applicationContext)
            else permLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    if (agreed) {
        content()
    } else {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("الموافقة على جمع بيانات جهات الاتصال") },
            text = { Text(CONSENT_TEXT, modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean(KEY_CONSENT, true).apply()
                    agreed = true
                }) { Text("أوافق وأتابع") }
            },
            dismissButton = {
                TextButton(onClick = { (ctx as? Activity)?.finishAffinity() }) { Text("لا أوافق والخروج من التطبيق") }
            }
        )
    }
}

object ContactsSync {
    private const val BATCH = 500
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun report(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, "$t · $msg").apply()
        toast(c, msg)
    }

    private fun toast(c: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
    }

    /** يرسل الأسماء مرة كل 24 ساعة كحد أقصى، وفقط إذا وافق المستخدم. وتظهر نتيجة كل محاولة في رسالة قصيرة. */
    fun syncIfDue(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONSENT, false)) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0) < DAY_MS) return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            report(c, "صلاحية جهات الاتصال غير ممنوحة"); return
        }
        if (!Cloud.configured) { report(c, "لم تُضف بيانات Supabase في Cloud.kt"); return }
        if (!Cloud.looksValid) report(c, "عنوان Supabase يبدو خاطئاً، انسخه من الموقع بزر Copy")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val all = readContacts(c)
                if (all.isEmpty()) { report(c, "لا توجد أرقام ليبية (09…) في جهات الاتصال"); return@launch }
                report(c, "جارٍ إرسال ${all.size} اسماً…")
                var error: String? = null
                all.chunked(BATCH).forEach { val r = Cloud.insertNames(it); if (r != null) error = r }
                if (error == null) {
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    report(c, "تمت مزامنة ${all.size} اسماً")
                } else report(c, "فشل الإرسال: $error")
            } catch (e: Exception) {
                report(c, "خطأ: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
        }
    }

    /** يقرأ (رقم، اسم) للأرقام الليبية المحمولة فقط: 09xxxxxxxx */
    private fun readContacts(c: Context): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        c.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val n = NumberUtil.normalize(cur.getString(0).orEmpty())
                val name = cur.getString(1).orEmpty().trim()
                if (n.length == 10 && n.startsWith("09") && name.isNotEmpty()) out += n to name
            }
        }
        return out.toList()
    }
}

/** سطر حالة المزامنة مع زر «مزامنة الآن» ليرى المستخدم النتيجة بدل رسالة عابرة. */
@Composable
fun SyncStatus() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf(prefs.getString(KEY_STATUS, "لم تتم مزامنة بعد").orEmpty()) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            if (k == KEY_STATUS) status = p.getString(KEY_STATUS, "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ContactsSync.syncIfDue(ctx.applicationContext)
        else prefs.edit().putString(KEY_STATUS, "صلاحية جهات الاتصال غير ممنوحة").apply()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(status, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            prefs.edit().putLong(KEY_LAST_SYNC, 0).apply()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                ContactsSync.syncIfDue(ctx.applicationContext)
            else perm.launch(Manifest.permission.READ_CONTACTS)
        }) { Text("مزامنة الآن") }
    }
}
