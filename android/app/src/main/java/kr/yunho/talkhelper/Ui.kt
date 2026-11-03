package kr.yunho.talkhelper

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 어르신용 큰 글씨 화면을 코드로 만드는 도우미 */
object Ui {
    val YELLOW = Color.parseColor("#FEE500")
    val BROWN = Color.parseColor("#3B1E1E")
    val BG = Color.parseColor("#F7F4EE")
    val CARD = Color.WHITE
    val MUTED = Color.parseColor("#6B625A")
    val LINE = Color.parseColor("#E2D9CC")
    val MINE = Color.parseColor("#FDE68A")
    val THEM = Color.parseColor("#F1ECE4")
    val RED = Color.parseColor("#B4441F")
    val GREEN = Color.parseColor("#2F7A4B")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun scale(ctx: Context) = Prefs(ctx).textScale

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(ctx, 2), stroke)
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 18f, bold: Boolean = false, color: Int = BROWN) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale(ctx))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    fun button(ctx: Context, s: CharSequence, bg: Int = YELLOW, fg: Int = BROWN, sp: Float = 20f, onClick: (View) -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale(ctx))
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(ctx, bg, 18, if (bg == CARD) LINE else null)
        minHeight = dp(ctx, 64)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        stateListAnimator = null
        setOnClickListener(onClick)
    }

    fun input(ctx: Context, hint: String, password: Boolean = false, multiline: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setTextColor(BROWN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale(ctx))
        background = rounded(ctx, CARD, 12, LINE)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
    }

    fun vbox(ctx: Context, padDp: Int = 0, bg: Int? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
        if (bg != null) background = rounded(ctx, bg, 18)
    }

    fun hbox(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 세로 목록에 위아래 간격을 두고 넣기 */
    fun LinearLayout.add(v: View, topDp: Int = 10, weight: Float = 0f): View {
        val horizontal = orientation == LinearLayout.HORIZONTAL
        val lp = if (horizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (weight == 0f) 1f else weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (horizontal) lp.marginStart = if (childCount == 0) 0 else dp(context, 8) else lp.topMargin = dp(context, topDp)
        addView(v, lp)
        return v
    }

    /** 말풍선 */
    fun bubble(ctx: Context, who: String, msg: String, mine: Boolean): View = vbox(ctx).apply {
        val name = text(ctx, who, 14f, color = MUTED)
        val body = text(ctx, msg, 19f).apply {
            background = rounded(ctx, if (mine) MINE else THEM, 16)
            val p = dp(ctx, 12)
            setPadding(p, dp(ctx, 8), p, dp(ctx, 8))
        }
        gravity = if (mine) Gravity.END else Gravity.START
        addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
