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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ضع هنا بيانات مشروعك من Supabase (Project Settings > API)
// استخدم مفتاح anon (العام) فقط، ولا تضع service_role أبداً داخل التطبيق.
private const val SUPABASE_URL = "https://eutobimccyaslmsqqppq.supabase.co"
private const val SUPABASE_ANON_KEY = "sb_publishable_q5H4xqeyBc7qYn4adMZYYA_rpcN90yp"

private const val PREFS = "kashif"
private const val KEY_CONSENT = "contacts_consent"
private const val KEY_LAST_SYNC = "contacts_last_sync"

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

    /** يرسل الأسماء مرة كل 24 ساعة كحد أقصى، وفقط إذا وافق المستخدم. */
    fun syncIfDue(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONSENT, false)) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0) < DAY_MS) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val all = readContacts(c)
                var ok = true
                all.chunked(BATCH).forEach { if (!upload(it)) ok = false }
                if (ok) prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            } catch (_: Exception) { /* سيُعاد المحاولة في الفتح القادم */ }
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

    private fun upload(batch: List<Pair<String, String>>): Boolean {
        val arr = JSONArray()
        batch.forEach { arr.put(JSONObject().put("number", it.first).put("name", it.second)) }
        val conn = URL("$SUPABASE_URL/rest/v1/contact_names?on_conflict=number,name").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            // المفاتيح القديمة (anon) تبدأ بـ eyJ وتحتاج هذا السطر، أما المفتاح الجديد sb_publishable_ فيكفيه apikey
            if (SUPABASE_ANON_KEY.startsWith("eyJ")) conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            conn.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal")
            conn.outputStream.use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}
