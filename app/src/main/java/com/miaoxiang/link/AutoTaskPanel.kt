package com.miaoxiang.link

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.UUID

/**
 * 「自动签到」页签：无障碍授权、拟人化设置、任务列表与步骤编辑。
 * 由 MainActivity 作为第 4 个页签挂载，所有交互在本类内闭环。
 */
class AutoTaskPanel(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onRunTask: (AutoTask, (Boolean, String) -> Unit) -> Boolean
) {
    private val ctx: Context = activity
    private var toastHost: View = View(ctx)

    /** 供 MainActivity 拿到一个 Toast 用的锚点（传根布局即可）。 */
    fun setToastHost(v: View) { toastHost = v }

    fun build(): View {
        val scroll = ScrollView(ctx).apply { setBackgroundColor(Color.parseColor("#F5F6F8")) }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // ---- 权限授权卡 ----
        val cardAuth = card()
        addTitle(cardAuth, "① 开启模拟操作权限（两项都要开）")
        val authStatus = TextView(ctx).apply { textSize = 12f }
        cardAuth.addView(authStatus, lpWrap(6))
        val btnAccessibility = Button(ctx).apply {
            text = "1. 开启无障碍（模拟点击/长按/滑动）"
            isAllCaps = false
            setOnClickListener { openAccessibilitySettings() }
        }
        cardAuth.addView(btnAccessibility, lpWrap(8))
        val btnOverlay = Button(ctx).apply {
            text = "2. 开启悬浮窗（屏幕取点用）"
            isAllCaps = false
            setOnClickListener { openOverlaySettings() }
        }
        cardAuth.addView(btnOverlay, lpWrap(4))
        cardAuth.addView(TextView(ctx).apply {
            text = "支持：点击 / 长按 / 滑动翻页 / 点击文字 / 回主页面等系统手势 / 打开应用。\n" +
                   "权限：在无障碍列表里找「喵想联动 · 自动签到」，点开后打开开关。\n" +
                   "悬浮窗：允许「显示在其他应用上层」，取点时准星才能盖在目标 App 上。\n" +
                   "两项都是手机系统出于安全考虑要求手动授权的，本 App 不会读取你的屏幕内容。"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
        }, lpWrap(4))
        wrap.addView(cardAuth, lpWrap(0))

        // ---- 拟人化设置卡 ----
        val cardHuman = card()
        addTitle(cardHuman, "② 拟人化设置（降低被识别为脚本的概率）")
        val swHuman = Switch(ctx).apply {
            text = "开启随机抖动"
            isChecked = prefs.jitterEnabled
        }
        swHuman.setOnCheckedChangeListener { _, on -> prefs.jitterEnabled = on }
        cardHuman.addView(swHuman, lpWrap(6))
        val rangeRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun addSeek(label: String, min: Int, max: Int, cur: Int, unit: String, onChange: (Int) -> Unit) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val tv = TextView(ctx).apply {
                text = "$label：$cur$unit"
                textSize = 12f
                setTextColor(Color.parseColor("#444444"))
            }
            val seek = SeekBar(ctx).apply {
                this.max = max - min
                progress = cur - min
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label：$v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            row.addView(tv, lpWrap(0))
            row.addView(seek, lpWrap(2))
            rangeRows.addView(row, lpWrap(0))
        }
        addSeek("坐标抖动", 0, 30, prefs.jitterPx, " px", { prefs.jitterPx = it })
        addSeek("间隔抖动", 0, 1000, prefs.jitterDelayMs, " ms", { prefs.jitterDelayMs = it })
        addSeek("按压抖动", 0, 150, prefs.jitterPressMs, " ms", { prefs.jitterPressMs = it })
        addSeek("滑动分段", 1, 8, prefs.jitterMoveSteps, " 段", { prefs.jitterMoveSteps = it })
        cardHuman.addView(rangeRows, lpWrap(2))
        cardHuman.addView(TextView(ctx).apply {
            text = "抖动只改变动作的表现形式，不保证绕过任何应用的检测。请自行评估风险。"
            textSize = 11f
            setTextColor(Color.parseColor("#C00000"))
        }, lpWrap(6))
        wrap.addView(cardHuman, lpWrap(0))

        // ---- 任务列表卡 ----
        val cardTasks = card()
        addTitle(cardTasks, "③ 我的签到任务")
        val tasksContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardTasks.addView(tasksContainer, lpWrap(6))
        cardTasks.addView(Button(ctx).apply {
            text = "＋ 新建签到任务"
            isAllCaps = false
            setTextColor(Color.parseColor("#0078D4"))
            setOnClickListener { editTask(null) { renderTasks(tasksContainer) } }
        }, lpWrap(8))
        wrap.addView(cardTasks, lpWrap(0))

        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        host.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 状态与刷新
        fun refresh() {
            val accOn = GestureService.isAccessibilityEnabled(ctx)
            val overlayOn = PointPicker.canDraw(ctx)
            authStatus.text = "无障碍：" + (if (accOn) "● 已开启" else "○ 未开启") +
                              "    悬浮窗：" + (if (overlayOn) "● 已开启" else "○ 未开启")
            authStatus.setTextColor(Color.parseColor(
                if (accOn && overlayOn) "#28A745" else "#DC3545"))
            btnAccessibility.text = if (accOn) "1. 无障碍已开启（点此可重新进入）"
                                    else "1. 开启无障碍（模拟点击/长按/滑动）"
            btnOverlay.text = if (overlayOn) "2. 悬浮窗已开启（点此可重新进入）"
                              else "2. 开启悬浮窗（屏幕取点用）"
            renderTasks(tasksContainer)
        }
        scroll.setTag(R.id.autotask_refresh, object : Runnable { override fun run() = refresh() })
        refresh()
        scroll.addView(host, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    /** 供外部在 onResume 时刷新授权状态。 */
    fun refresh(view: View) {
        val tag = view.getTag(R.id.autotask_refresh) as? Runnable ?: return
        tag.run()
    }

    /**
     * 直接跳到「喵想联动」无障碍服务的详情开关页。
     * 用 fragment_args_key 让系统滚动定位到本服务；部分 ROM 不支持该 extra，
     * 会退回到无障碍总列表——此时用户按提示找「喵想联动 · 自动签到」即可。
     */
    private fun openAccessibilitySettings() {
        val cn = ComponentName(ctx, GestureService::class.java)
        val key = cn.flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", key)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", key)
            })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            toast("打不开设置，请到 系统设置 → 无障碍 → 已下载的服务 里开启")
        }
    }

    /** 跳到悬浮窗（显示在其他应用上层）授权页，直接定位到本 App。 */
    private fun openOverlaySettings() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {
                toast("打不开设置，请到 系统设置 → 应用 → 喵想联动 → 显示在其他应用上层 里开启")
            }
        } else {
            toast("当前系统版本默认允许悬浮窗，可直接使用取点器")
        }
    }

    private fun renderTasks(container: LinearLayout) {
        container.removeAllViews()
        val tasks = prefs.autoTasks
        if (tasks.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "还没有任务。新建后可用「屏幕取点」拖准星添加点击/滑动，或加「回主页面→翻页→点应用」这套流程。"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap(2))
            return
        }
        for (t in tasks) {
            val c = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                val p = dp(12)
                setPadding(p, p, p, p)
                elevation = dp(2).toFloat()
            }
            val line1 = TextView(ctx).apply {
                text = (if (t.enabled) "● " else "○ ") + t.name +
                       (if (t.appLabel.isNotBlank()) "  → ${t.appLabel}" else "")
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
            }
            c.addView(line1, lpWrap(0))
            val sched = if (t.dailyEnabled)
                "每日 ${String.format("%02d", t.dailyHour)}:${String.format("%02d", t.dailyMinute)} 自动执行"
            else "未设置定时"
            c.addView(TextView(ctx).apply {
                text = "${t.steps.size} 个步骤 · $sched"
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap(2))

            val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(Button(ctx).apply {
                text = "▶ 执行"
                isAllCaps = false
                setOnClickListener {
                    if (TaskRunner.isRunning) { toast("有任务正在执行"); return@setOnClickListener }
                    val started = onRunTask(t) { ok, msg -> toast(if (ok) "成功：$msg" else "失败：$msg") }
                    if (!started) toast("无法启动，请检查无障碍权限")
                }
            }, lpWeight())
            row1.addView(Button(ctx).apply {
                text = if (t.dailyEnabled) "定时：开" else "定时：关"
                isAllCaps = false
                setOnClickListener { toggleSchedule(t) { renderTasks(container) } }
            }, lpWeight())
            row1.addView(Button(ctx).apply {
                text = if (t.enabled) "已启用" else "已停用"
                isAllCaps = false
                setOnClickListener {
                    prefs.saveAutoTask(t.copy(enabled = !t.enabled))
                    if (!t.enabled) ScheduleReceiver.scheduleNext(ctx, t.copy(enabled = true))
                    else ScheduleReceiver.cancel(ctx, t.id)
                    renderTasks(container)
                }
            }, lpWeight())
            c.addView(row1, lpWrap(8))

            val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(Button(ctx).apply {
                text = "编辑步骤"
                isAllCaps = false
                setOnClickListener { editTask(t) { renderTasks(container) } }
            }, lpWeight())
            row2.addView(Button(ctx).apply {
                text = "删除"
                isAllCaps = false
                setTextColor(Color.parseColor("#C00000"))
                setOnClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("删除任务")
                        .setMessage("确定删除「${t.name}」？")
                        .setPositiveButton("删除") { _, _ ->
                            ScheduleReceiver.cancel(ctx, t.id)
                            prefs.deleteAutoTask(t.id)
                            renderTasks(container)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }, lpWeight())
            c.addView(row2, lpWrap(4))
            container.addView(c, lpWrap(8))
        }
    }

    private fun toggleSchedule(task: AutoTask, after: () -> Unit) {
        if (task.dailyEnabled) {
            prefs.saveAutoTask(task.copy(dailyEnabled = false))
            ScheduleReceiver.cancel(ctx, task.id)
            toast("已关闭「${task.name}」的定时")
            after()
            return
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val tp = TimePicker(ctx).apply { setIs24HourView(true) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            tp.hour = task.dailyHour
            tp.minute = task.dailyMinute
        } else {
            @Suppress("DEPRECATION")
            tp.currentHour = task.dailyHour
            @Suppress("DEPRECATION")
            tp.currentMinute = task.dailyMinute
        }
        view.addView(tp, lpWrap(0))
        view.addView(TextView(ctx).apply {
            text = "到点会自动执行本任务。请确保手机已解锁、无障碍权限开启。"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        }, lpWrap())
        AlertDialog.Builder(ctx)
            .setTitle("每日定时 - ${task.name}")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val h: Int
                val m: Int
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    h = tp.hour
                    m = tp.minute
                } else {
                    @Suppress("DEPRECATION")
                    h = tp.currentHour
                    @Suppress("DEPRECATION")
                    m = tp.currentMinute
                }
                val updated = task.copy(dailyEnabled = true, dailyHour = h, dailyMinute = m, lastRunDate = "")
                prefs.saveAutoTask(updated)
                ScheduleReceiver.scheduleNext(ctx, updated)
                toast("已设置每日 ${String.format("%02d", h)}:${String.format("%02d", m)}")
                after()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 任务编辑对话框：名称 + 目标App + 步骤列表。 */
    private fun editTask(existing: AutoTask?, after: () -> Unit) {
        var pkg = existing?.packageName ?: ""
        var label = existing?.appLabel ?: ""
        val steps = (existing?.steps ?: emptyList()).toMutableList()
        val taskId = existing?.id ?: UUID.randomUUID().toString()
        lateinit var dlg: AlertDialog

        val view = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        view.addView(box)

        val nameEdit = EditText(ctx).apply {
            hint = "任务名称（如：早起签到）"
            setText(existing?.name ?: "")
            textSize = 16f
        }
        box.addView(nameEdit, lpWrap(0))

        val appBtn = Button(ctx).apply {
            text = "目标应用：" + (label.ifBlank { "（点击选择，可不选）" })
            isAllCaps = false
        }
        appBtn.setOnClickListener { pickApp { p, l -> pkg = p; label = l; appBtn.text = "目标应用：$l" } }
        box.addView(appBtn, lpWrap(8))

        box.addView(TextView(ctx).apply {
            text = "推荐用下方「🎯 屏幕取点」：拖准星到目标位置点一下即可，不用手输坐标。"
            textSize = 11f
            setTextColor(Color.parseColor("#0078D4"))
        }, lpWrap(6))

        val stepsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(stepsBox, lpWrap(4))

        fun renderSteps() {
            stepsBox.removeAllViews()
            if (steps.isEmpty()) {
                stepsBox.addView(TextView(ctx).apply {
                    text = "还没有步骤。推荐点下面「🎯 屏幕取点」拖准星添加。"
                    textSize = 12f
                    setTextColor(Color.parseColor("#999999"))
                }, lpWrap(0))
            }
            for (i in steps.indices) {
                val s = steps[i]
                val sc = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F0F2F5"))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                sc.addView(TextView(ctx).apply {
                    text = "第 ${i + 1} 步"
                    textSize = 12f
                    setTextColor(Color.parseColor("#0078D4"))
                    setTypeface(null, Typeface.BOLD)
                }, lpWrap(0))
                sc.addView(TextView(ctx).apply {
                    text = stepSummary(s)
                    textSize = 12f
                    setTextColor(Color.parseColor("#444444"))
                }, lpWrap(2))
                val ops = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                ops.addView(Button(ctx).apply {
                    text = "编辑"; isAllCaps = false
                    setOnClickListener { editStep(s) { renderSteps() } }
                }, lpWeight())
                if (i > 0) ops.addView(Button(ctx).apply {
                    text = "↑"; isAllCaps = false
                    setOnClickListener { java.util.Collections.swap(steps, i, i - 1); renderSteps() }
                }, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
                if (i < steps.size - 1) ops.addView(Button(ctx).apply {
                    text = "↓"; isAllCaps = false
                    setOnClickListener { java.util.Collections.swap(steps, i, i + 1); renderSteps() }
                }, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
                ops.addView(Button(ctx).apply {
                    text = "删除"; isAllCaps = false
                    setTextColor(Color.parseColor("#C00000"))
                    setOnClickListener { steps.removeAt(i); renderSteps() }
                }, lpWeight())
                sc.addView(ops, lpWrap(4))
                stepsBox.addView(sc, lpWrap(6))
            }
        }
        renderSteps()

        box.addView(Button(ctx).apply {
            text = "🎯 屏幕取点（推荐：拖准星点一下，不用输坐标）"
            isAllCaps = false
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#0078D4"))
            setOnClickListener {
                if (!PointPicker.canDraw(ctx)) {
                    toast("请先开启悬浮窗权限，才能用屏幕取点")
                    PointPicker.requestPermission(ctx)
                    return@setOnClickListener
                }
                // 先把当前编辑框里还没落盘的内容暂存成 task，取点完再带着它们重开
                val draft = AutoTask(
                    id = taskId,
                    name = nameEdit.text.toString().trim(),
                    packageName = pkg,
                    appLabel = label,
                    steps = steps.toList(),
                    enabled = existing?.enabled ?: true,
                    dailyEnabled = existing?.dailyEnabled ?: false,
                    dailyHour = existing?.dailyHour ?: 8,
                    dailyMinute = existing?.dailyMinute ?: 0,
                    lastRunDate = existing?.lastRunDate ?: ""
                )
                dlg.dismiss()
                PointPicker.start(activity) { picked ->
                    if (picked.isNotEmpty()) {
                        editTask(draft.copy(steps = draft.steps + picked), after)
                        toast("已取 ${picked.size} 个动作，检查后点保存")
                    } else {
                        editTask(draft, after)
                    }
                }
            }
        }, lpWrap(8))

        box.addView(Button(ctx).apply {
            text = "＋ 手动添加步骤（输坐标/找文字）"
            isAllCaps = false
            setTextColor(Color.parseColor("#0078D4"))
            setOnClickListener { editStep(null) { steps.add(it); renderSteps() } }
        }, lpWrap(4))

        dlg = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "新建签到任务" else "编辑任务")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("请填任务名称"); return@setOnClickListener }
                if (steps.isEmpty()) { toast("请至少添加一个步骤"); return@setOnClickListener }
                val task = AutoTask(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    packageName = pkg,
                    appLabel = label,
                    steps = steps.toList(),
                    enabled = existing?.enabled ?: true,
                    dailyEnabled = existing?.dailyEnabled ?: false,
                    dailyHour = existing?.dailyHour ?: 8,
                    dailyMinute = existing?.dailyMinute ?: 0,
                    lastRunDate = existing?.lastRunDate ?: ""
                )
                prefs.saveAutoTask(task)
                if (task.dailyEnabled) ScheduleReceiver.scheduleNext(ctx, task)
                toast("已保存：${task.name}")
                dlg.dismiss()
                after()
            }
        }
        dlg.show()
    }

    /** 单个步骤编辑：类型 + 对应参数。新建时回调传出，编辑时原地修改。 */
    private fun editStep(existing: AutoStep?, done: (AutoStep) -> Unit) {
        val types = arrayOf(
            "点击坐标", "长按坐标", "滑动/翻页", "点击文字", "等待",
            "系统动作（回主页/返回等）", "打开应用（按包名）"
        )
        val typeKeys = arrayOf(
            "click_xy", "long_xy", "swipe", "click_text", "wait",
            "global_action", "open_app"
        )
        val startIdx = typeKeys.indexOf(existing?.type ?: "click_xy").coerceAtLeast(0)
        // 新类型的参数存在这里，确定按钮再取（避免视图 tag 类型混乱）
        var pickedAction = existing?.text?.takeIf { GestureService.GA_KEYS.contains(it) } ?: GestureService.GA_HOME
        var pickedPkg = existing?.takeIf { it.type == "open_app" }?.text ?: ""
        var pickedAppLabel = existing?.takeIf { it.type == "open_app" }?.appLabel ?: ""

        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val spType = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, types).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(startIdx)
        }
        view.addView(TextView(ctx).apply {
            text = "步骤类型"; textSize = 12f; setTextColor(Color.parseColor("#666666"))
        }, lpWrap(0))
        view.addView(spType, lpWrap(2))

        val fields = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        view.addView(fields, lpWrap(8))

        fun eEdit(hint: String, value: String): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 15f
        }
        fun tEdit(hint: String, value: String): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
        }

        fun renderFields(selected: Int) {
            fields.removeAllViews()
            when (typeKeys[selected]) {
                "click_xy" -> {
                    val x = eEdit("X（0-1000）", "${existing?.x ?: 500}")
                    val y = eEdit("Y（0-1000）", "${existing?.y ?: 500}")
                    fields.addView(x, lpWrap(0)); fields.addView(y, lpWrap(4))
                    fields.setTag(R.id.step_fields, arrayOf(x, y))
                }
                "long_xy" -> {
                    val x = eEdit("X（0-1000）", "${existing?.x ?: 500}")
                    val y = eEdit("Y（0-1000）", "${existing?.y ?: 500}")
                    val dur = eEdit("长按时长(ms)", "${existing?.durationMs ?: 800}")
                    fields.addView(x, lpWrap(0)); fields.addView(y, lpWrap(4)); fields.addView(dur, lpWrap(4))
                    fields.setTag(R.id.step_fields, arrayOf(x, y, dur))
                }
                "swipe" -> {
                    val x1 = eEdit("起点 X（0-1000）", "${existing?.x ?: 500}")
                    val y1 = eEdit("起点 Y（0-1000）", "${existing?.y ?: 800}")
                    val x2 = eEdit("终点 X（0-1000）", "${existing?.x2 ?: 500}")
                    val y2 = eEdit("终点 Y（0-1000）", "${existing?.y2 ?: 200}")
                    val dur = eEdit("滑动时长(ms)", "${existing?.durationMs ?: 500}")
                    fields.addView(x1, lpWrap(0)); fields.addView(y1, lpWrap(4))
                    fields.addView(x2, lpWrap(4)); fields.addView(y2, lpWrap(4)); fields.addView(dur, lpWrap(4))
                    fields.setTag(R.id.step_fields, arrayOf(x1, y1, x2, y2, dur))
                }
                "click_text" -> {
                    val txt = tEdit("要点击的文字（如：签到）", existing?.text ?: "")
                    fields.addView(txt, lpWrap(0))
                    fields.setTag(R.id.step_fields, arrayOf(txt))
                }
                "wait" -> {
                    val txt = TextView(ctx).apply {
                        text = "此步骤仅等待，下方“本步后等待”即为等待时长。"
                        textSize = 12f; setTextColor(Color.parseColor("#999999"))
                    }
                    fields.addView(txt, lpWrap(0))
                    fields.setTag(R.id.step_fields, emptyArray<EditText>())
                }
                "global_action" -> {
                    val gaSpinner = Spinner(ctx).apply {
                        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, GestureService.GA_LABELS).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        setSelection(GestureService.GA_KEYS.indexOf(pickedAction).coerceAtLeast(0))
                    }
                    gaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                            pickedAction = GestureService.GA_KEYS[pos]
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                    fields.addView(TextView(ctx).apply {
                        text = "选择系统动作（由系统执行，无需坐标）"
                        textSize = 12f; setTextColor(Color.parseColor("#666666"))
                    }, lpWrap(0))
                    fields.addView(gaSpinner, lpWrap(2))
                    fields.addView(TextView(ctx).apply {
                        text = "「回主页面」相当于从底部上滑回桌面；之后可用「滑动/翻页」找应用，再点图标。"
                        textSize = 11f; setTextColor(Color.parseColor("#999999"))
                    }, lpWrap(2))
                    fields.setTag(R.id.step_fields, emptyArray<EditText>())
                }
                "open_app" -> {
                    val appBtn = Button(ctx).apply {
                        text = "选择应用：" + (pickedAppLabel.ifBlank { "（点击选择）" })
                        isAllCaps = false
                    }
                    appBtn.setOnClickListener {
                        pickApp { p, l -> pickedPkg = p; pickedAppLabel = l; appBtn.text = "选择应用：$l" }
                    }
                    fields.addView(appBtn, lpWrap(0))
                    fields.addView(TextView(ctx).apply {
                        text = "按包名直接打开，比翻页找图标更稳。可在「回主页面」之后插入这一步。"
                        textSize = 11f; setTextColor(Color.parseColor("#999999"))
                    }, lpWrap(2))
                    fields.setTag(R.id.step_fields, emptyArray<EditText>())
                }
            }
            val delay = eEdit("本步后等待(ms)", "${existing?.delayMs ?: 800}")
            fields.addView(delay, lpWrap(8))
            fields.setTag(R.id.step_delay, delay)
        }
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = renderFields(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        renderFields(startIdx)

        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "添加步骤" else "编辑步骤")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val type = typeKeys[spType.selectedItemPosition]
                val arr = fields.getTag(R.id.step_fields) as? Array<EditText> ?: emptyArray()
                val num = { e: EditText -> e.text.toString().trim().toIntOrNull() ?: 0 }
                val delayEdit = fields.getTag(R.id.step_delay) as? EditText
                val delay = delayEdit?.text.toString().trim().toIntOrNull() ?: 800
                val step = when (type) {
                    "click_xy" -> AutoStep(type, x = num(arr[0]), y = num(arr[1]), delayMs = delay)
                    "long_xy" -> AutoStep(type, x = num(arr[0]), y = num(arr[1]), durationMs = num(arr[2]), delayMs = delay)
                    "swipe" -> AutoStep(type, x = num(arr[0]), y = num(arr[1]), x2 = num(arr[2]), y2 = num(arr[3]), durationMs = num(arr[4]), delayMs = delay)
                    "click_text" -> AutoStep(type, text = arr[0].text.toString().trim(), delayMs = delay)
                    "wait" -> AutoStep(type, delayMs = delay)
                    "global_action" -> AutoStep(type, text = pickedAction, delayMs = if (delay == 800) 1200 else delay)
                    "open_app" -> {
                        if (pickedPkg.isBlank()) { toast("请先选择要打开的应用"); return@setPositiveButton }
                        AutoStep(type, text = pickedPkg, appLabel = pickedAppLabel, delayMs = if (delay == 800) 1600 else delay)
                    }
                    else -> AutoStep("wait", delayMs = delay)
                }
                done(step)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择本机已安装且可启动的 App。 */
    private fun pickApp(onPick: (String, String) -> Unit) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo to it.loadLabel(pm).toString() }
            .filter { it.first.enabled }
            .distinctBy { it.first.packageName }
            .sortedBy { it.second }
        if (list.isEmpty()) { toast("没有找到可打开的 App"); return }
        val names = list.map { it.second }
        AlertDialog.Builder(ctx)
            .setTitle("选择要打开的应用")
            .setItems(names.toTypedArray()) { _, i ->
                onPick(list[i].first.packageName, names[i])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stepSummary(s: AutoStep): String = when (s.type) {
        "click_xy" -> "点击坐标 (${s.x}, ${s.y})"
        "long_xy" -> "长按 (${s.x}, ${s.y}) ${s.durationMs}ms"
        "swipe" -> "滑动 (${s.x},${s.y}) → (${s.x2},${s.y2}) ${s.durationMs}ms"
        "click_text" -> "点击文字「${s.text}」"
        "global_action" -> "系统动作：${GestureService.gaLabel(s.text)}"
        "open_app" -> "打开应用「${s.appLabel.ifBlank { s.text }}」"
        "wait" -> "等待 ${s.delayMs}ms"
        else -> s.type
    }

    // ------------------------------------------------------------------ UI 工具
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        val p = dp(14)
        setPadding(p, p, p, p)
        elevation = dp(2).toFloat()
    }

    private fun addTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
        }, lpWrap(0))
    }

    private fun lpWrap(top: Int = 8) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun lpWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
