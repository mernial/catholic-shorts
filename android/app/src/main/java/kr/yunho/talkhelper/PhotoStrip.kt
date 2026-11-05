package kr.yunho.talkhelper

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

/** 찾은 사진을 작은 그림 줄로 보여 주고, 누르면 크게 */
object PhotoStrip {
    fun decode(f: File, maxPx: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    /** [files] 를 가로로 늘어놓은 줄 */
    fun view(a: Activity, files: List<File>): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val size = Ui.dp(a, 104)
        for (f in files) {
            val iv = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(a, Ui.LINE, 12)
                clipToOutline = true
                contentDescription = "찾은 사진. 누르면 크게 봅니다"
                setOnClickListener { show(a, f) }
            }
            row.addView(iv, LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(a, 8) })
            Bg.run({ decode(f, size) }) { r -> r.getOrNull()?.let { iv.setImageBitmap(it) } }
        }
        return HorizontalScrollView(a).apply { addView(row); isHorizontalScrollBarEnabled = false }
    }

    /** 화면 가득 크게 보기 (누르면 닫힘) */
    fun show(a: Activity, f: File) {
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { d.dismiss() }
        }
        d.setContentView(iv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val px = maxOf(a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
        Bg.run({ decode(f, px) }) { r -> r.getOrNull()?.let { iv.setImageBitmap(it) } }
        d.show()
    }
}
