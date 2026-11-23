package com.filmcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

const val AMBER = 0xFFFFB347.toInt()
const val BG_DARK = 0xFF0E0E0E.toInt()
const val CHIP_BG = 0xFF222222.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.pill(color: Int, radiusDp: Int = 18): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

/**
 * 세로/가로 화면을 하나의 레이아웃으로 처리한다. 화면을 돌려도 뷰를 다시 만들거나 옮기지 않고
 * 위치와 크기만 바꾸므로 카메라 미리보기(TextureView)가 끊기지 않는다.
 * 세로: 상단바 / 3:4 미리보기 / 필름 / 모드 / 조작.  가로: 왼쪽 4:3 미리보기 + 오른쪽 조작판.
 */
class CameraLayout(
    ctx: Context,
    private val frame: View,
    private val top: View,
    private val strip: View,
    private val modes: View,
    private val controls: View,
) : ViewGroup(ctx) {
    private val rects = HashMap<View, IntArray>()

    init {
        listOf(frame, top, strip, modes, controls).forEach { addView(it) }
    }

    private fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        rects[v] = intArrayOf(x, y, x + w, y + h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val topH = context.dp(52)
        val stripH = context.dp(62)
        val modesH = context.dp(36)
        if (w > h) {
            var pw = h * 4 / 3
            var ph = h
            val minPanel = context.dp(300)
            if (pw > w - minPanel) {
                pw = w - minPanel
                ph = pw * 3 / 4
            }
            place(frame, 0, (h - ph) / 2, pw, ph)
            val px = pw
            val panelW = w - pw
            place(top, px, 0, panelW, topH)
            place(strip, px, topH, panelW, stripH)
            place(modes, px, topH + stripH, panelW, modesH)
            val used = topH + stripH + modesH
            place(controls, px, used, panelW, max(h - used, 0))
        } else {
            val ph = w * 4 / 3
            place(top, 0, 0, w, topH)
            place(frame, 0, topH, w, ph)
            val y1 = topH + ph
            place(strip, 0, y1, w, stripH)
            place(modes, 0, y1 + stripH, w, modesH)
            val used = y1 + stripH + modesH
            place(controls, 0, used, w, max(h - used, 0))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((v, rc) in rects) v.layout(rc[0], rc[1], rc[2], rc[3])
    }
}

/** 셔터 버튼. 영상 모드에서는 빨간색, 녹화 중에는 진행 링이 돈다. */
class ShutterView(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    var progress = 0f
        set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(4).toFloat()
        color = Color.WHITE
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(5).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF4A3D.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(5)
        canvas.drawCircle(cx, cy, r, ring)
        fill.color = if (video) 0xFFFF4A3D.toInt() else Color.WHITE
        val inner = if (recording) r * 0.45f else r - context.dp(9)
        canvas.drawCircle(cx, cy, inner, fill)
        if (recording) {
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progress, false, arc)
        }
    }
}

/** 필름 선택 가로 스트립. */
class FilterStrip(ctx: Context, private val onSelect: (FilmFilter) -> Unit) : HorizontalScrollView(ctx) {
    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
    }
    private val chips = ArrayList<TextView>()
    var selected = Filters.DEFAULT_INDEX
        private set

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        Filters.ALL.forEachIndexed { i, f ->
            val chip = TextView(ctx).apply {
                text = f.name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
                setOnClickListener { select(i, true) }
            }
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(38))
            lp.marginEnd = ctx.dp(8)
            row.addView(chip, lp)
            chips.add(chip)
        }
        paint()
    }

    fun select(i: Int, notify: Boolean) {
        selected = i
        paint()
        val c = chips[i]
        smoothScrollTo(c.left - width / 2 + c.width / 2, 0)
        if (notify) onSelect(Filters.ALL[i])
    }

    private fun paint() {
        chips.forEachIndexed { i, c ->
            val on = i == selected
            c.background = context.pill(if (on) AMBER else CHIP_BG, 19)
            c.setTextColor(if (on) Color.BLACK else 0xFFDDDDDD.toInt())
        }
    }
}

fun Context.textButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 14f
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = pill(CHIP_BG, 18)
    setOnClickListener { onClick() }
}

/** 3분할 격자선. */
class GridView(ctx: Context) : View(ctx) {
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30000000
        strokeWidth = ctx.dp(3).toFloat()
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        strokeWidth = max(1f, ctx.dp(1).toFloat() * 0.8f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, shade)
            canvas.drawLine(0f, y, w, y, shade)
            canvas.drawLine(x, 0f, x, h, line)
            canvas.drawLine(0f, y, w, y, line)
        }
    }
}
