package com.miaoxiang.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlin.random.Random

/**
 * 无障碍手势引擎：模拟点击 / 长按 / 滑动翻页，以及按文字定位点击。
 *
 * 这是手机端能模拟真人操作的核心。系统要求 App 在设置里手动授予无障碍权限后，
 * 服务才会被绑定。坐标由调用方换算成像素后传入，随机抖动在 [TaskRunner] 里统一处理。
 */
class GestureService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureService"
        var instance: GestureService? = null
            private set

        // ---- 系统级动作键（全面屏手势无法用注入滑动触发，改用官方 global action）----
        const val GA_HOME = "home"                    // 回主页面（相当于底部上滑）
        const val GA_BACK = "back"                    // 返回（相当于侧边滑回）
        const val GA_RECENTS = "recents"              // 最近任务
        const val GA_NOTIFICATIONS = "notifications"  // 下拉通知栏
        const val GA_QUICK = "quick_settings"         // 快捷设置
        const val GA_POWER = "power_dialog"           // 电源菜单
        const val GA_LOCK = "lock_screen"             // 锁屏（Android 9+）
        const val GA_SHOT = "screenshot"              // 截图（Android 14+）

        /** 供编辑界面下拉用：键与中文名一一对应。 */
        val GA_KEYS = arrayOf(
            GA_HOME, GA_BACK, GA_RECENTS, GA_NOTIFICATIONS,
            GA_QUICK, GA_POWER, GA_LOCK, GA_SHOT
        )
        val GA_LABELS = arrayOf(
            "回主页面（底部上滑）", "返回（侧边滑回）", "最近任务", "下拉通知栏",
            "快捷设置", "电源菜单", "锁屏", "系统截图"
        )

        fun gaLabel(key: String): String {
            val i = GA_KEYS.indexOf(key)
            return if (i >= 0) GA_LABELS[i] else key
        }

        /** 判断无障碍服务是否已被系统绑定（权限是否已开启）。 */
        fun isEnabled(): Boolean = instance != null

        /** 供外部通过系统设置判断“用户是否已尝试开启”，用于授权页文案。 */
        fun isAccessibilityEnabled(context: android.content.Context): Boolean {
            val expected = "${context.packageName}/${GestureService::class.java.name}"
            val flat = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
            } catch (_: Exception) { "" }
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(flat) }
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍手势服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务不依赖事件驱动，手势由指令主动发起，这里留空即可。
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍手势服务被中断")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 当前屏幕像素尺寸，供坐标换算使用。 */
    fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    /**
     * 发一条点击手势。duration<=150 视为点击，更长则相当于长按。
     * 返回值通过 CompletableDeferred 异步回调，true 表示手势被系统成功派发。
     */
    fun click(x: Float, y: Float, durationMs: Long): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!done.isCompleted) done.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (!done.isCompleted) done.complete(false)
            }
        }, null)
        return done
    }

    /**
     * 发一条滑动手势（翻页就是起点到终点的滑动）。
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!done.isCompleted) done.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (!done.isCompleted) done.complete(false)
            }
        }, null)
        return done
    }

    /**
     * 按屏幕上的文字定位并点击。比坐标更稳：只要界面上有对应文字就能点到，
     * 不受分辨率影响。先在可见节点里精确/包含匹配，向上找可点击的父节点再点。
     */
    fun clickByText(target: String): Boolean {
        if (target.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(target)
        for (node in nodes) {
            var cur: AccessibilityNodeInfo? = node
            var hops = 0
            while (cur != null && hops < 6) {
                if (cur.isEnabled && cur.isClickable) {
                    val ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) return true
                }
                cur = cur.parent
                hops++
            }
        }
        return false
    }

    /** 启动指定包名的 App。 */
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "打开应用失败: $packageName, ${e.message}")
            false
        }
    }

    /**
     * 系统级动作：回主页面、返回、最近任务、下拉通知等。
     *
     * 这些是 Android 的「全面屏手势」——由系统导航栏/手势区域处理，
     * 通过 dispatchGesture 注入的普通滑动到不了那一层，所以必须用官方的
     * performGlobalAction。效果与用户在屏幕上做手势完全一致，且跨机型稳定。
     *
     * 返回 false 表示该动作在当前系统版本不可用或执行失败。
     */
    fun globalAction(key: String): Boolean {
        val action: Int = when (key) {
            GA_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GA_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GA_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GA_NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GA_QUICK -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GA_POWER -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            GA_LOCK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            GA_SHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return try {
            performGlobalAction(action)
        } catch (e: Exception) {
            Log.w(TAG, "系统动作执行失败: $key, ${e.message}")
            false
        }
    }

    /** 拟人化随机抖动：在基准像素附近做小范围偏移。 */
    fun jitter(base: Float, maxDeltaPx: Int): Float {
        if (maxDeltaPx <= 0) return base
        return base + Random.nextInt(-maxDeltaPx, maxDeltaPx + 1)
    }
}
