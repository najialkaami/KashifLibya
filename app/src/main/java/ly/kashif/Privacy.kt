package ly.kashif

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ضع هنا بريدك لاستقبال طلبات حذف البيانات. إن تُرك فارغاً يختفي الزر. */
private const val CONTACT_EMAIL = ""

private val SECTIONS = listOf(
    "ما الذي يفعله التطبيق" to "هوية المتصل يعرض اسم المتصل ورقمه عند ورود مكالمة، ويتيح لك البحث عن رقم وحظر الأرقام المزعجة.",
    "البيانات التي نجمعها" to "عند موافقتك نقرأ أسماء جهات الاتصال وأرقامها، ونرسل منها الأرقام الليبية المحمولة فقط (التي تبدأ بـ 09) مع الاسم المحفوظ لها إلى قاعدة بياناتنا المركزية (Supabase). لا نرسل البريد ولا الصور ولا أي حقل آخر من جهات الاتصال. ولا نطلب منك حساباً ولا نخزّن رقمك أنت.",
    "ما يبقى على هاتفك" to "سجل المكالمات يُقرأ داخل الهاتف فقط لعرض الأسماء بجانب الأرقام، ولا يُرسل إلى أي جهة. وقائمة الأرقام المحظورة وبلاغاتك محفوظة على هاتفك.",
    "ما يُرسل عند البحث" to "عند ورود مكالمة أو بحثك عن رقم، يُرسل ذلك الرقم وحده إلى الخادم ليعيد الاسم الأكثر تسجيلاً له.",
    "كيف تُستخدم البيانات" to "لبناء قاعدة تساعد مستخدمي التطبيق على معرفة هوية المتصلين. الاسم الذي يظهر لرقم هو الاسم الذي سجّله أكبر عدد من المستخدمين، وقد يختلف عن اسم صاحب الرقم الحقيقي.",
    "الصلاحيات" to "جهات الاتصال: للمزامنة. سجل المكالمات: لعرض سجلك. فحص المكالمات: لمعرفة المتصل وحظر المزعجين. العرض فوق التطبيقات: لإظهار اسم المتصل فوق شاشة المكالمة. الإشعارات: لتنبيهك إن لم تكن النافذة مفعّلة.",
    "حذف بياناتك" to "البيانات المخزّنة مجهولة ولا ترتبط بحساب، لذلك نحذفها بطلب منك إن حدّدت الرقم أو الاسم المراد حذفه. اطلب ذلك عبر الزر أدناه. ويمكنك أيضاً سحب صلاحية جهات الاتصال من إعدادات الهاتف لإيقاف أي مزامنة جديدة.",
    "الأمان" to "لا نبيع البيانات. تُنقل عبر اتصال مشفّر (HTTPS)، ولا يستطيع التطبيق قراءة القاعدة كاملة، بل يسأل عن رقم واحد في كل مرة."
)

@Composable
fun PrivacyScreen() {
    val ctx = LocalContext.current
    val status = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, "لم تتم مزامنة بعد").orEmpty() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("سياسة الخصوصية", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        SECTIONS.forEach { (title, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(body, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        Text("آخر مزامنة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(status, fontSize = 13.sp)
        if (CONTACT_EMAIL.isNotBlank()) {
            Button(onClick = {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
                i.putExtra(Intent.EXTRA_SUBJECT, "طلب حذف بيانات - هوية المتصل")
                ctx.startActivity(i)
            }, modifier = Modifier.fillMaxWidth()) { Text("طلب حذف بياناتي") }
        }
        Text(" ", Modifier.padding(bottom = 24.dp))
    }
}
