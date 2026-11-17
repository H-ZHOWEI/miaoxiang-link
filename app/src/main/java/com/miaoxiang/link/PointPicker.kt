package com.miaoxiang.link

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 屏幕可视化取点器（对标主流自动点击 App 的「添加点击点」）。
 *
 * 在目标 App 上方浮一个可拖动的准星 + 一个工具条：
 *  - 拖准星到想要的位置
 *  - 选「点击 / 长按 / 滑动」，点「记录此点」即可生成步骤，不用手输坐标
 *  - 悬浮窗用 FLAG_NOT_TOUCH_MODAL，按在别处会透传给下层 App，可正常翻页找位置
 *  - 坐标用 getLocationOnScreen 取准星中心的真实屏幕像素，再换算成 0-1000 千分比，
 *    与 [GestureService] 派发手势用的坐标系一致
 *
 * 权限：需要 SYSTEM_ALERT_WINDOW（悬浮窗）。无障碍权限仍由 [GestureService] 负责。
 */
object PointPicker {

    private var wm: WindowManager? = null
    private var ctx: Context? = null
    private var cross: CrossView? = null
    private var bar: View? = null
    private var onDone: ((List<AutoStep>) -> Unit)? = null
    private val collected = mutableListOf<AutoStep>()
    private var swipeStart: Pair<Int, Int>? = null
    private var overlayType = 0

    var isRunning = false
        private set

    /** 是否已获得悬浮窗权限。 */
    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /** 跳到悬浮窗授权页，直接定位本 App。 */
    fun requestPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 启动取点器。用户点「完成」后回调收集到的步骤（可能为空）。 */
    fun start(ctx: Context, done: (List<AutoStep>) -> Unit) {
        if (isRunning) return
        this.ctx = ctx.applicationContext
        this.onDone = done
        collected.clear()
        swipeStart = null
        wm = this.ctx!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        addCrosshair()
        addBar()
        isRunning = true
    }

    /** 结束取点、清理悬浮窗并回调结果。 */
    fun stop() {
        try { cross?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { bar?.let { wm?.removeView(it) } } catch (_: Exception) {}
        cross = null
        bar = null
        isRunning = false
        val cb = onDone
        onDone = null
        val result = collected.toList()
        collected.clear()
        cb?.invoke(result)
    }

    private fun flags() =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun addCrosshair() {
        val c = ctx!!
        val size = dp(c, 62)
        val view = CrossView(c)
        val lp = WindowManager.LayoutParams(
            size, size, overlayType, flags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (c.resources.displayMetrics.widthPixels - size) / 2
            y = (c.resources.displayMetrics.heightPixels - size) / 2
        }
        var lastX = 0f
        var lastY = 0f
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += (e.rawX - lastX).toInt()
                    lp.y += (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }
        cross = view
        wm?.addView(view, lp)
    }

    /** 准星中心在屏幕上的真实像素坐标（与手势坐标系一致）。 */
    private fun centerPx(): Pair<Int, Int> {
        val v = cross ?: return 500 to 500
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] + v.width / 2) to (loc[1] + v.height / 2)
    }

    /** 换成 0-1000 千分比。 */
    private fun perMille(): Pair<Int, Int> {
        val (px, py) = centerPx()
        val dm = ctx!!.resources.displayMetrics
        val x = ((px * 1000f / dm.widthPixels).roundToInt()).coerceIn(0, 1000)
        val y = ((py * 1000f / dm.heightPixels).roundToInt()).coerceIn(0, 1000)
        return x to y
    }

    private fun addBar() {
        val c = ctx!!
        val barView = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6222833"))
            setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8))
        }

        val spinner = Spinner(c).apply {
            adapter = ArrayAdapter(
                c, android.R.layout.simple_spinner_item, arrayOf("点击", "长按", "滑动")
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        barView.addView(spinner, wrap())

        val recordBtn = Button(c).apply { text = "记录此点"; isAllCaps = false; textSize = 13f }
        val undoBtn = Button(c).apply { text = "撤销"; isAllCaps = false; textSize = 13f }
        val btnRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(recordBtn, weight())
        btnRow.addView(undoBtn, weight())
        barView.addView(btnRow, wrap())

        val doneBtn = Button(c).apply { text = "完成(0)"; isAllCaps = false; textSize = 13f }
        barView.addView(doneBtn, wrap())

        val tip = TextView(c).apply {
            text = "拖准星到目标位置。滑动先「设起点」再「设终点」。按屏幕别处可正常翻页。"
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        barView.addView(tip, wrap(4))
        val summary = TextView(c).apply {
            text = "已添加 0 个动作"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        barView.addView(summary, wrap(4))

        fun refreshSummary() {
            summary.text = "已添加 ${collected.size} 个动作" +
                if (collected.isEmpty()) "" else "\n" +
                    collected.mapIndexed { i, s -> "${i + 1}. ${desc(s)}" }.joinToString("\n")
            doneBtn.text = "完成(${collected.size})"
        }

        fun selected() = arrayOf("click_xy", "long_xy", "swipe")[spinner.selectedItemPosition]

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                swipeStart = null
                recordBtn.text = if (pos == 2) "设起点" else "记录此点"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        recordBtn.setOnClickListener {
            val (x, y) = perMille()
            when (selected()) {
                "swipe" -> {
                    val st = swipeStart
                    if (st == null) {
                        swipeStart = x to y
                        recordBtn.text = "设终点"
                        tip.text = "起点已记 ($x,$y)。拖准星到终点，再点「设终点」。"
                    } else {
                        collected.add(
                            AutoStep(
                                type = "swipe", x = st.first, y = st.second,
                                x2 = x, y2 = y, durationMs = 500, delayMs = 800
                            )
                        )
                        swipeStart = null
                        recordBtn.text = "设起点"
                        tip.text = "已添加滑动。可继续添加或点「完成」。"
                        refreshSummary()
                    }
                }
                "long_xy" -> {
                    collected.add(AutoStep(type = "long_xy", x = x, y = y, durationMs = 800, delayMs = 800))
                    refreshSummary()
                }
                else -> {
                    collected.add(AutoStep(type = "click_xy", x = x, y = y, delayMs = 800))
                    refreshSummary()
                }
            }
        }
        undoBtn.setOnClickListener {
            if (swipeStart != null) {
                swipeStart = null
                recordBtn.text = if (spinner.selectedItemPosition == 2) "设起点" else "记录此点"
            } else if (collected.isNotEmpty()) {
                collected.removeAt(collected.lastIndex)
            }
            refreshSummary()
        }
        doneBtn.setOnClickListener { stop() }

        val lp = WindowManager.LayoutParams(
            dp(c, 228), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = c.resources.displayMetrics.widthPixels - dp(c, 236)
            y = dp(c, 56)
        }
        bar = barView
        wm?.addView(barView, lp)
        refreshSummary()
    }

    private fun desc(s: AutoStep) = when (s.type) {
        "click_xy" -> "点击(${s.x},${s.y})"
        "long_xy" -> "长按(${s.x},${s.y})"
        "swipe" -> "滑动(${s.x},${s.y})→(${s.x2},${s.y2})"
        else -> s.type
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun wrap(top: Int = 6) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx!!, top) }
    private fun weight() = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
    ).apply { rightMargin = dp(ctx!!, 4) }

    /** 准星：十字 + 中心圆点，中心即记录坐标。 */
    class CrossView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val cx = width / 2f
            val cy = height / 2f
            val d = context.resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = Color.parseColor("#FFE04040")
            c.drawCircle(cx, cy, 18 * d, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f * d
            c.drawLine(cx - 27 * d, cy, cx + 27 * d, cy, paint)
            c.drawLine(cx, cy - 27 * d, cx, cy + 27 * d, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF00D0FF")
            c.drawCircle(cx, cy, 4 * d, paint)
        }
    }
}
