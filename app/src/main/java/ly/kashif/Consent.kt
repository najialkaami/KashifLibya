package ly.kashif

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

const val PREFS = "kashif"
private const val KEY_CONSENT = "contacts_consent"
private const val KEY_LAST_SYNC = "contacts_last_sync"
private const val KEY_LAST_HASH = "contacts_last_hash"
const val KEY_STATUS = "sync_status"

private const val CONSENT_TEXT = """نود إعلامك بأن تطبيقنا يعتمد على بناء قاعدة بيانات للأسماء وأرقام الهواتف بهدف تحسين خدمة كشف هوية المتصل والتعرف على الأرقام.

عند الموافقة، سيطلب التطبيق صلاحية الوصول إلى جهات الاتصال، وستُعالج البيانات التي لديك صلاحية قانونية لمشاركتها وفق سياسة الخصوصية، وقد تُرسل إلى خوادمنا وقاعدة بياناتنا المركزية (Supabase).

باستخدام هذه البيانات، نسعى إلى تطوير قاعدة بيانات تساعد جميع مستخدمي التطبيق على معرفة هوية المتصلين.

ملاحظة: الموافقة على هذه العملية شرط للاستفادة من خدمات التطبيق.

هل توافق على ذلك؟"""

/** يعرض نافذة الموافقة عند أول فتح، ثم يطلب صلاحية جهات الاتصال ويبدأ المزامنة في الخلفية دون أي عنصر في الشاشة الرئيسية. */
@Composable
fun ConsentGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var agreed by remember { mutableStateOf(prefs.getBoolean(KEY_CONSENT, false)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ContactsSync.start(ctx.applicationContext)
    }

    LaunchedEffect(agreed) {
        if (agreed) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                ContactsSync.start(ctx.applicationContext)
            else permLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    if (agreed) {
        content()
    } else {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("الموافقة على جمع بيانات جهات الاتصال") },
            text = { Text(CONSENT_TEXT, modifier = Modifier.verticalScroll(rememberScrollState())) },
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

/** عامل خلفية يعمل مرة يومياً عند توفر الإنترنت، حتى لو لم يفتح المستخدم التطبيق. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (ContactsSync.syncNow(applicationContext)) Result.success() else Result.retry()
}

object ContactsSync {
    private const val BATCH = 500
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun report(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.US).format(java.util.Date())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, "$t · $msg").apply()
    }

    /** يجدول المزامنة اليومية في الخلفية ويجري مزامنة فورية الآن إن لزم. */
    fun start(c: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("contacts-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        CoroutineScope(Dispatchers.IO).launch { syncNow(c) }
    }

    /** يعيد true عند النجاح أو عدم الحاجة للإرسال، وfalse عند فشل الشبكة ليُعاد المحاولة لاحقاً. */
    suspend fun syncNow(c: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONSENT, false)) return@withContext true
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            report(c, "صلاحية جهات الاتصال غير ممنوحة"); return@withContext true
        }
        if (!Cloud.configured) { report(c, "إعدادات الخادم غير مكتملة"); return@withContext true }
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0) < DAY_MS) return@withContext true
        try {
            val all = readContacts(c)
            if (all.isEmpty()) { report(c, "لا توجد أرقام ليبية في جهات الاتصال"); return@withContext true }
            val hash = all.hashCode()
            if (hash == prefs.getInt(KEY_LAST_HASH, 0) && prefs.contains(KEY_LAST_HASH)) {
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                report(c, "لا تغييرات جديدة (${all.size} اسماً)"); return@withContext true
            }
            var error: String? = null
            all.chunked(BATCH).forEach { val r = Cloud.insertNames(it); if (r != null) error = r }
            if (error == null) {
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).putInt(KEY_LAST_HASH, hash).apply()
                report(c, "تمت مزامنة ${all.size} اسماً")
                true
            } else {
                report(c, "فشل الإرسال: $error")
                false
            }
        } catch (e: Exception) {
            report(c, "خطأ: ${e.javaClass.simpleName}")
            false
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
