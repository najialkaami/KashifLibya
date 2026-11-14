package ly.kashif

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** نافذة تعريف المتصل التي تظهر فوق شاشة المكالمة. تحتاج صلاحية «العرض فوق التطبيقات». */
object CallerOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var manager: WindowManager? = null
    private val autoClose = Runnable { dismiss() }
    private const val SHOW_MS = 25_000L

    fun canShow(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun show(c: Context, number: String, e: NumberEntry?) {
        val app = c.applicationContext
        if (!canShow(app)) return
        main.post {
            dismiss()
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = build(app, number, e)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP
                lp.y = dp(app, 48)
                wm.addView(v, lp)
                view = v
                manager = wm
                main.postDelayed(autoClose, SHOW_MS)
            } catch (_: Exception) {
            }
        }
    }

    fun dismiss() {
        main.removeCallbacks(autoClose)
        try {
            view?.let { manager?.removeView(it) }
        } catch (_: Exception) {
        }
        view = null
        manager = null
    }

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private fun label(c: Context, text: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        return t
    }

    private fun build(c: Context, number: String, e: NumberEntry?): View {
        val spam = e?.spam == true
        val name = e?.name.orEmpty()
        val known = name.isNotBlank()
        val reports = e?.reports ?: 0
        val accent = when {
            spam -> Color.parseColor("#FF6B5E")
            known -> Color.parseColor("#3FBF8F")
            else -> Color.parseColor("#8DA398")
        }
        val title = if (known) name else "رقم غير معروف"
        val note = when {
            spam -> "مزعج · بلّغ عنه $reports شخصاً"
            known && reports > 1 -> "سجّله $reports مستخدمين بهذا الاسم"
            known -> "موجود في قاعدة البيانات"
            else -> "لا توجد معلومات عن هذا الرقم"
        }

        val root = FrameLayout(c)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        root.clipToPadding = false

        val card = LinearLayout(c)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#14231C"))
        bg.cornerRadius = dp(c, 20).toFloat()
        bg.setStroke(dp(c, 2), accent)
        card.background = bg
        card.elevation = dp(c, 8).toFloat()

        val avatar = TextView(c)
        avatar.text = if (known) name.take(1) else "؟"
        avatar.gravity = Gravity.CENTER
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        avatar.setTextColor(Color.parseColor("#14231C"))
        avatar.setTypeface(avatar.typeface, Typeface.BOLD)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(accent)
        avatar.background = circle
        card.addView(avatar, LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(c, title, 18f, Color.WHITE, true))
        col.addView(label(c, NumberUtil.pretty(number), 14f, Color.parseColor("#C9D8D0")))
        col.addView(label(c, note, 12f, accent))
        val colLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        colLp.marginStart = dp(c, 12)
        colLp.marginEnd = dp(c, 8)
        card.addView(col, colLp)

        val close = TextView(c)
        close.text = "✕"
        close.gravity = Gravity.CENTER
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        close.setTextColor(Color.parseColor("#C9D8D0"))
        close.setOnClickListener { dismiss() }
        card.addView(close, LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)))

        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return root
    }
}
