package ly.kashif

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** إعدادات Supabase. انسخ العنوان من صفحة Project Overview بزر Copy ولا تكتبه يدوياً. */
object Cloud {
    private const val URL_RAW = "https://eutobimccyaslmsqqppq.supabase.co"
    private const val KEY = "sb_publishable_q5H4xqeyBc7qYn4adMZYYA_rpcN90yp"

    val base: String get() = URL_RAW.trim().trimEnd('/')
    val configured: Boolean get() = !base.contains("YOUR-") && !KEY.contains("YOUR-")
    /** عناوين Supabase القياسية: 20 حرفاً ثم .supabase.co */
    val looksValid: Boolean get() = Regex("https://[a-z0-9]{20}\\.supabase\\.co").matches(base)

    private fun conn(path: String, prefer: String?): HttpURLConnection {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("apikey", KEY)
        if (KEY.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $KEY")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        return c
    }

    /** يعيد null عند النجاح أو نص الخطأ. */
    fun insertNames(batch: List<Pair<String, String>>): String? {
        val arr = JSONArray()
        batch.forEach { arr.put(JSONObject().put("number", it.first).put("name", it.second)) }
        val c = conn("/rest/v1/contact_names?on_conflict=number,name", "resolution=ignore-duplicates,return=minimal")
        return try {
            c.outputStream.use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code in 200..299) null else "HTTP $code " + c.errorStream?.bufferedReader()?.readText().orEmpty().take(150)
        } finally { c.disconnect() }
    }

    /** يبحث عن الاسم الأكثر تكراراً لرقم، أو null. */
    fun lookup(number: String): NumberEntry? {
        if (!configured) return null
        val c = conn("/rest/v1/rpc/lookup_name", null)
        return try {
            c.outputStream.use { it.write(JSONObject().put("n", number).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return null
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            if (arr.length() == 0) null
            else arr.getJSONObject(0).let { NumberEntry(number, it.getString("name"), reports = it.optInt("reports", 0)) }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }
}
