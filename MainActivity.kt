import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkTermsAndConditions()
    }

    private fun checkTermsAndConditions() {
        val prefs = getSharedPreferences("KashifPrefs", Context.MODE_PRIVATE)
        val isAgreed = prefs.getBoolean("has_agreed_terms", false)

        if (!isAgreed) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("شروط الاستخدام وسياسة الخصوصية")
            builder.setMessage(
                "أهلاً بك في كاشف ليبيا.\n\n" +
                "يقوم التطبيق بالوصول إلى جهات الاتصال الخاصة بك ومشاركتها مع خوادمنا بهدف تحسين خدمة كشف الأرقام والتعرف على هوية المتصلين.\n\n" +
                "بالمتابعة والموافقة، فإنك تمنحنا الإذن بالوصول ومزامنة هذه البيانات بأمان وفقاً لسياسة الخصوصية."
            )
            builder.setCancelable(false)

            builder.setPositiveButton("موافق ومتابعة") { dialog, _ ->
                prefs.edit().putBoolean("has_agreed_terms", true).apply()
                dialog.dismiss()
                startContactSync()
            }

            builder.setNegativeButton("رفض وخروج") { _, _ ->
                finish()
            }

            val dialog = builder.create()
            dialog.show()
        } else {
            startContactSync()
        }
    }

    private fun startContactSync() {
        // ضع هنا كود مزامنة جهات الاتصال
    }
}
