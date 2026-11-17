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

    /** 拟人化随机抖动：在基准像素附近做小范围偏移。 */
    fun jitter(base: Float, maxDeltaPx: Int): Float {
        if (maxDeltaPx <= 0) return base
        return base + Random.nextInt(-maxDeltaPx, maxDeltaPx + 1)
    }
}
