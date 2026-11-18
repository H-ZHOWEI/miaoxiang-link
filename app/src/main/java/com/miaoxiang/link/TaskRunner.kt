package com.miaoxiang.link

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 自动签到任务执行引擎。
 * 三种触发方式（手机手动 / 电脑下发 / 每日定时）都汇入 [run]，保证同一时间只跑一个任务。
 */
object TaskRunner {
    private const val TAG = "TaskRunner"

    /** 触发来源，仅用于日志与通知文案。 */
    const val SOURCE_MANUAL = "手动"
    const val SOURCE_PHONE = "手机"
    const val SOURCE_PC = "电脑"
    const val SOURCE_SCHEDULE = "定时"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var runningTaskName: String = ""
        private set

    /** 状态变化回调（UI 线程调用），参数：是否运行中、当前状态描述。 */
    var onStateChanged: ((Boolean, String) -> Unit)? = null

    /**
     * 启动一个任务。若已有任务在跑，返回 false 不打断。
     * 完成后回调 (成功, 说明)。
     */
    fun run(
        context: Context,
        task: AutoTask,
        source: String,
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (isRunning) {
            onDone?.invoke(false, "已有任务正在执行，请稍候")
            return false
        }
        if (GestureService.instance == null) {
            onDone?.invoke(false, "无障碍权限未开启，请到「自动签到」页授权")
            return false
        }
        val appContext = context.applicationContext
        currentJob = scope.launch {
            val result = executeOnce(appContext, task, source)
            currentJob = null
            withContext(Dispatchers.Main) {
                onDone?.invoke(result.first, result.second)
            }
        }
        return true
    }

    /**
     * 一次性执行任务并返回结果，供定时广播等无 UI 场景在后台协程里直接调用。
     * 内部同样受 isRunning 保护，避免与手动任务撞车。
     */
    suspend fun executeOnce(context: Context, task: AutoTask, source: String = SOURCE_SCHEDULE): Pair<Boolean, String> {
        if (isRunning) return Pair(false, "已有任务正在执行，本次跳过")
        val svc = GestureService.instance
            ?: return Pair(false, "无障碍权限未开启")
        isRunning = true
        runningTaskName = task.name
        notifyState(true, "正在执行：${task.name}（$source）")
        return try {
            execute(context.applicationContext, svc, task)
        } catch (e: Exception) {
            Pair(false, "执行异常：${e.message ?: "未知错误"}")
        } finally {
            isRunning = false
            runningTaskName = ""
            withContext(Dispatchers.Main) {
                notifyState(false, "执行结束")
            }
        }
    }

    /** 停止当前任务（手机端点“停止”或电脑下发 stop 时调用）。 */
    fun stop() {
        val job = currentJob ?: return
        isRunning = false
        runningTaskName = ""
        job.cancel()
        notifyState(false, "任务已停止")
    }

    private fun notifyState(running: Boolean, text: String) {
        try {
            onStateChanged?.invoke(running, text)
        } catch (_: Exception) {}
    }

    private suspend fun execute(
        context: Context,
        svc: GestureService,
        task: AutoTask
    ): Pair<Boolean, String> {
        val prefs = Prefs(context)
        val jitterPx = prefs.jitterPx
        val jitterDelay = prefs.jitterDelayMs
        val jitterPress = prefs.jitterPressMs
        val moveSteps = prefs.jitterMoveSteps

        // 1) 先打开目标 App
        if (task.packageName.isNotBlank()) {
            val opened = withContext(Dispatchers.Main) { svc.openApp(task.packageName) }
            if (!opened) {
                return Pair(false, "打不开应用 ${task.appLabel.ifBlank { task.packageName }}，请确认已安装")
            }
            // 等 App 起来，时间带抖动
            delay((1800 + randomNeg(jitterDelay * 2)).coerceAtLeast(400).toLong())
        }

        // 2) 顺序执行每一步
        val (w, h) = svc.screenSize()
        for ((index, step) in task.steps.withIndex()) {
            if (!currentCoroutineContext().isActive) return Pair(false, "任务已停止（第 ${index + 1} 步中断）")

            val stepOk = when (step.type) {
                "click_xy" -> {
                    val x = svc.jitter(step.x * w / 1000f, jitterPx)
                    val y = svc.jitter(step.y * h / 1000f, jitterPx)
                    val press = (step.durationMs + randomNeg(jitterPress)).coerceAtLeast(30)
                    dispatchClick(svc, x, y, press.toLong())
                }
                "long_xy" -> {
                    val x = svc.jitter(step.x * w / 1000f, jitterPx)
                    val y = svc.jitter(step.y * h / 1000f, jitterPx)
                    val press = (step.durationMs + randomNeg(jitterPress / 2)).coerceAtLeast(150)
                    dispatchClick(svc, x, y, press.toLong())
                }
                "swipe" -> {
                    val x1 = svc.jitter(step.x * w / 1000f, jitterPx)
                    val y1 = svc.jitter(step.y * h / 1000f, jitterPx)
                    val x2 = svc.jitter(step.x2 * w / 1000f, jitterPx)
                    val y2 = svc.jitter(step.y2 * h / 1000f, jitterPx)
                    dispatchSwipe(svc, x1, y1, x2, y2, step.durationMs.toLong(), moveSteps)
                }
                "click_text" -> withContext(Dispatchers.Main) { svc.clickByText(step.text) }
                // 系统级动作（回主页面/返回/最近任务等）：无需坐标，直接调官方 global action。
                // 抖动对系统动作无意义，这里不施加。
                "global_action" -> withContext(Dispatchers.Main) { svc.globalAction(step.text) }
                // 按包名打开应用：比"翻页找图标再点"更稳，可在流程任意位置插入。
                "open_app" -> {
                    val okOpen = withContext(Dispatchers.Main) { svc.openApp(step.text) }
                    if (okOpen) delay((1600 + randomNeg(jitterDelay)).coerceAtLeast(500).toLong())
                    okOpen
                }
                "wait" -> true
                else -> false
            }

            if (!stepOk) {
                return Pair(false, "第 ${index + 1} 步失败（${stepLabel(step)}），手势未被系统接受")
            }

            // 步间等待：基础间隔 + 抖动
            val wait = (step.delayMs + randomNeg(jitterDelay)).coerceAtLeast(80)
            delay(wait.toLong())
        }

        return Pair(true, "已完成：${task.name}（${task.steps.size} 步）")
    }

    /** 点击手势派发到主线程执行，并等待系统回调。 */
    private suspend fun dispatchClick(svc: GestureService, x: Float, y: Float, pressMs: Long): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                svc.click(x, y, pressMs).await()
            } catch (e: Exception) {
                Log.w(TAG, "点击派发异常", e)
                false
            }
        }
    }

    /**
     * 滑动手势。若开启分段，把一条直线拆成多段小滑动，段间随机停顿，
     * 更接近真人手指的连续移动；否则一次派发。
     */
    private suspend fun dispatchSwipe(
        svc: GestureService,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        moveSteps: Int
    ): Boolean {
        val segments = moveSteps.coerceIn(1, 8)
        if (segments == 1) {
            return withContext(Dispatchers.Main) {
                try { svc.swipe(x1, y1, x2, y2, durationMs).await() } catch (e: Exception) { false }
            }
        }
        val per = (durationMs / segments).coerceAtLeast(30)
        var sx = x1
        var sy = y1
        for (i in 1..segments) {
            if (!currentCoroutineContext().isActive) return false
            val ratio = i.toFloat() / segments
            val ex = x1 + (x2 - x1) * ratio
            val ey = y1 + (y2 - y1) * ratio
            val ok = withContext(Dispatchers.Main) {
                try { svc.swipe(sx, sy, ex, ey, per).await() } catch (e: Exception) { false }
            }
            if (!ok) return false
            sx = ex
            sy = ey
            if (i < segments) delay(Random.nextLong(16, 60))
        }
        return true
    }

    private fun randomNeg(range: Int): Int =
        if (range <= 0) 0 else Random.nextInt(-range, range + 1)

    private fun stepLabel(step: AutoStep): String = when (step.type) {
        "click_xy" -> "点击(${step.x},${step.y})"
        "long_xy" -> "长按(${step.x},${step.y})"
        "swipe" -> "滑动(${step.x},${step.y})→(${step.x2},${step.y2})"
        "click_text" -> "点击文字「${step.text}」"
        "global_action" -> "系统动作「${GestureService.gaLabel(step.text)}」"
        "open_app" -> "打开应用「${step.appLabel.ifBlank { step.text }}」"
        "wait" -> "等待"
        else -> step.type
    }
}
