package com.miaoxiang.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动签到任务模型。
 * 坐标统一用 0-1000 的千分比保存，执行时再换算成当前屏幕像素，
 * 这样同一套任务在不同分辨率的手机上位置基本一致。
 */
data class AutoStep(
    // click_xy / long_xy / swipe / click_text / wait / global_action / open_app
    val type: String = "click_xy",
    val x: Int = 500,
    val y: Int = 500,
    val x2: Int = 500,
    val y2: Int = 500,
    val durationMs: Int = 400,       // 长按 / 滑动的持续时间
    // click_text=目标文字；global_action=动作键(home/back/...)；open_app=应用包名
    val text: String = "",
    val delayMs: Int = 800,          // 本步执行完后的间隔
    val appLabel: String = ""        // open_app 的应用名，仅用于界面展示
) {
    fun toJson() = JSONObject().apply {
        put("type", type)
        put("x", x)
        put("y", y)
        put("x2", x2)
        put("y2", y2)
        put("durationMs", durationMs)
        put("text", text)
        put("delayMs", delayMs)
        put("appLabel", appLabel)
    }

    companion object {
        fun fromJson(o: JSONObject) = AutoStep(
            type = o.optString("type", "click_xy"),
            x = o.optInt("x", 500),
            y = o.optInt("y", 500),
            x2 = o.optInt("x2", 500),
            y2 = o.optInt("y2", 500),
            durationMs = o.optInt("durationMs", 400),
            text = o.optString("text", ""),
            delayMs = o.optInt("delayMs", 800),
            appLabel = o.optString("appLabel", "")
        )
    }
}

data class AutoTask(
    val id: String,
    val name: String,
    val packageName: String = "",     // 执行前先打开的应用包名，留空则不打开
    val appLabel: String = "",
    val steps: List<AutoStep> = emptyList(),
    val enabled: Boolean = true,
    val dailyEnabled: Boolean = false,
    val dailyHour: Int = 8,
    val dailyMinute: Int = 0,
    val lastRunDate: String = ""      // yyyy-MM-dd，防止同一天重复执行
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("enabled", enabled)
        put("dailyEnabled", dailyEnabled)
        put("dailyHour", dailyHour)
        put("dailyMinute", dailyMinute)
        put("lastRunDate", lastRunDate)
        putArray("steps")
    }

    private fun JSONObject.putArray(tag: String) {
        val arr = JSONArray()
        for (s in steps) arr.put(s.toJson())
        put(tag, arr)
    }

    companion object {
        fun fromJson(o: JSONObject): AutoTask {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<AutoStep>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { steps.add(AutoStep.fromJson(it)) }
            }
            return AutoTask(
                id = o.optString("id", ""),
                name = o.optString("name", "未命名任务"),
                packageName = o.optString("packageName", ""),
                appLabel = o.optString("appLabel", ""),
                steps = steps,
                enabled = o.optBoolean("enabled", true),
                dailyEnabled = o.optBoolean("dailyEnabled", false),
                dailyHour = o.optInt("dailyHour", 8),
                dailyMinute = o.optInt("dailyMinute", 0),
                lastRunDate = o.optString("lastRunDate", "")
            )
        }
    }
}
