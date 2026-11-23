package com.filmcam

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 필름카메라 데이트백 느낌의 주황색 7세그먼트 타임스탬프. */
object DateStamp {
    const val MODE_OFF = 0
    const val MODE_DATE = 1
    const val MODE_DATE_TIME = 2

    private val dateFmt = SimpleDateFormat("''yy MM dd", Locale.US)
    private val dateTimeFmt = SimpleDateFormat("''yy MM dd  HH:mm", Locale.US)

    @Synchronized
    fun text(mode: Int, millis: Long): String? = when (mode) {
        MODE_DATE -> dateFmt.format(Date(millis))
        MODE_DATE_TIME -> dateTimeFmt.format(Date(millis))
        else -> null
    }

    // a=위 b=오른쪽위 c=오른쪽아래 d=아래 e=왼쪽아래 f=왼쪽위 g=가운데
    private val SEGMENTS = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
        '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
    )

    fun render(text: String, h: Float): Bitmap {
        val digitW = h * 0.56f
        val gap = h * 0.24f
        val t = h * 0.14f
        val slant = 0.12f

        fun advance(c: Char) = when (c) {
            ' ' -> h * 0.45f
            '\'' -> h * 0.22f + gap
            ':' -> h * 0.22f + gap
            else -> digitW + gap
        }

        val pad = h * 0.45f
        var total = 0f
        for (c in text) total += advance(c)
        val w = (total + pad * 2 + h * slant).toInt() + 1
        val bh = (h + pad * 2).toInt() + 1
        val bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFF6A00.toInt()
            maskFilter = BlurMaskFilter(t * 1.1f, BlurMaskFilter.Blur.NORMAL)
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A1F.toInt() }

        fun drawSeg(x0: Float, y0: Float, seg: Char) {
            val g = t * 0.12f
            val cy = y0 + h / 2f
            val r = when (seg) {
                'a' -> RectF(x0 + t * 0.5f + g, y0, x0 + digitW - t * 0.5f - g, y0 + t)
                'g' -> RectF(x0 + t * 0.5f + g, cy - t / 2, x0 + digitW - t * 0.5f - g, cy + t / 2)
                'd' -> RectF(x0 + t * 0.5f + g, y0 + h - t, x0 + digitW - t * 0.5f - g, y0 + h)
                'f' -> RectF(x0, y0 + t * 0.5f + g, x0 + t, cy - g)
                'b' -> RectF(x0 + digitW - t, y0 + t * 0.5f + g, x0 + digitW, cy - g)
                'e' -> RectF(x0, cy + g, x0 + t, y0 + h - t * 0.5f - g)
                else -> RectF(x0 + digitW - t, cy + g, x0 + digitW, y0 + h - t * 0.5f - g) // c
            }
            canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, glow)
            canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, core)
        }

        canvas.translate(pad + h * slant, pad)
        canvas.skew(-slant, 0f)
        var x = 0f
        for (c in text) {
            when (c) {
                in SEGMENTS -> SEGMENTS.getValue(c).forEach { drawSeg(x, 0f, it) }
                '\'' -> {
                    val r = RectF(x, 0f, x + t, h * 0.28f)
                    canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, glow)
                    canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, core)
                }
                ':' -> {
                    for (cy in floatArrayOf(h * 0.30f, h * 0.70f)) {
                        val r = RectF(x, cy - t / 2, x + t, cy + t / 2)
                        canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, glow)
                        canvas.drawRoundRect(r, t * 0.3f, t * 0.3f, core)
                    }
                }
            }
            x += advance(c)
        }
        return bmp
    }
}
