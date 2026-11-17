package com.miaoxiang.link

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地设置：多设备（配对码 + 名称）、当前激活设备、通知开关、按设备的已读游标。
 *
 * v1.2 起由「单配对码」升级为「设备列表」：每台已配对过的电脑是一条 Device，
 * 切换设备即切换当前配对码，所有通知收发 / 远程执行都走当前设备。
 * 旧版只存了一个 pair_code 的数据会自动迁移成一台名为「我的电脑」的设备。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("miaoxiang_link", Context.MODE_PRIVATE)

    data class Device(val code: String, val name: String)

    companion object {
        private const val K_DEVICES = "devices_json"
        private const val K_CURRENT = "current_code"
        private const val K_SEEN = "last_seen_map"
        private const val K_PAIR_OLD = "pair_code"       // 旧版单配对码
        private const val K_SEEN_OLD = "last_seen_id"    // 旧版单游标
        private const val K_AUTO_TASKS = "auto_tasks_json"
    }

    // ------------------------------------------------------------ 自动签到任务
    var autoTasks: List<AutoTask>
        get() {
            val raw = sp.getString(K_AUTO_TASKS, "") ?: ""
            if (raw.isBlank()) return emptyList()
            val list = mutableListOf<AutoTask>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(AutoTask.fromJson(it)) }
                }
            } catch (_: Exception) {}
            return list
        }
        set(value) {
            val arr = JSONArray()
            for (t in value) arr.put(t.toJson())
            sp.edit().putString(K_AUTO_TASKS, arr.toString()).apply()
        }

    fun findAutoTask(id: String): AutoTask? = autoTasks.firstOrNull { it.id == id }

    fun findAutoTaskByName(name: String): AutoTask? =
        autoTasks.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun saveAutoTask(task: AutoTask) {
        val list = autoTasks.filter { it.id != task.id }.toMutableList()
        list.add(task)
        autoTasks = list
    }

    fun deleteAutoTask(id: String) {
        autoTasks = autoTasks.filter { it.id != id }
    }

    // ------------------------------------------------------------ 设备列表
    var devices: List<Device>
        get() {
            val raw = sp.getString(K_DEVICES, "") ?: ""
            val list = mutableListOf<Device>()
            if (raw.isNotBlank()) {
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val code = o.optString("code", "").trim().uppercase()
                        if (code.isEmpty()) continue
                        list.add(Device(code, o.optString("name", "").ifBlank { "未命名设备" }))
                    }
                } catch (_: Exception) {}
            }
            // 兼容迁移：旧版单配对码 → 设备列表
            if (list.isEmpty()) {
                val old = (sp.getString(K_PAIR_OLD, "") ?: "").trim().uppercase()
                if (old.length >= 4) {
                    val migrated = listOf(Device(old, "我的电脑"))
                    devices = migrated
                    if ((sp.getString(K_CURRENT, "") ?: "").isBlank()) currentCode = old
                    val oldSeen = sp.getLong(K_SEEN_OLD, 0L)
                    if (oldSeen > 0) setLastSeen(old, oldSeen)
                    return migrated
                }
            }
            return list
        }
        set(value) {
            val arr = JSONArray()
            for (d in value) {
                arr.put(JSONObject().apply {
                    put("code", d.code.trim().uppercase())
                    put("name", d.name)
                })
            }
            sp.edit().putString(K_DEVICES, arr.toString()).apply()
        }

    /** 当前激活设备的配对码（所有收发都用它）。 */
    var currentCode: String
        get() {
            val saved = (sp.getString(K_CURRENT, "") ?: "").trim().uppercase()
            if (saved.isNotBlank()) return saved
            // 兜底：没设当前设备时取设备列表第一个
            return devices.firstOrNull()?.code ?: ""
        }
        set(value) = sp.edit().putString(K_CURRENT, value.trim().uppercase()).apply()

    val currentDevice: Device?
        get() {
            val code = currentCode
            return devices.firstOrNull { it.code == code }
        }

    val currentDeviceName: String
        get() = currentDevice?.name ?: "未选择设备"

    /** 新增或更新一台设备（按配对码去重），并设为当前设备。返回是否成功。 */
    fun addDevice(codeRaw: String, nameRaw: String): Boolean {
        val code = codeRaw.trim().uppercase()
        if (code.length < 4) return false
        val name = nameRaw.trim().ifBlank { "电脑 ${devices.size + 1}" }
        val list = devices.filter { it.code != code }.toMutableList()
        list.add(Device(code, name))
        devices = list
        currentCode = code
        return true
    }

    fun removeDevice(code: String) {
        val target = code.trim().uppercase()
        val list = devices.filter { it.code != target }
        devices = list
        if (currentCode == target) currentCode = list.firstOrNull()?.code ?: ""
        // 删除设备时清掉它的已读游标
        try {
            val map = JSONObject(sp.getString(K_SEEN, "{}") ?: "{}")
            map.remove(target)
            sp.edit().putString(K_SEEN, map.toString()).apply()
        } catch (_: Exception) {}
    }

    fun renameDevice(code: String, newName: String) {
        val target = code.trim().uppercase()
        devices = devices.map {
            if (it.code == target) Device(it.code, newName.trim().ifBlank { it.name }) else it
        }
    }

    fun switchDevice(code: String) {
        val target = code.trim().uppercase()
        if (devices.any { it.code == target }) currentCode = target
    }

    /** 判断某配对码是否已在设备列表里（防止重复添加）。 */
    fun hasDevice(code: String): Boolean {
        val target = code.trim().uppercase()
        return devices.any { it.code == target }
    }

    // ------------------------------------------------------------ 已读游标（按设备隔离）
    fun getLastSeen(code: String): Long {
        val key = code.trim().uppercase()
        try {
            val map = JSONObject(sp.getString(K_SEEN, "{}") ?: "{}")
            return map.optLong(key, 0L)
        } catch (_: Exception) { return 0L }
    }

    fun setLastSeen(code: String, id: Long) {
        val key = code.trim().uppercase()
        try {
            val map = JSONObject(sp.getString(K_SEEN, "{}") ?: "{}")
            map.put(key, id)
            sp.edit().putString(K_SEEN, map.toString()).apply()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 全局设置
    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify_enabled", true)
        set(value) = sp.edit().putBoolean("notify_enabled", value).apply()

    var pollMinutes: Int
        get() = sp.getInt("poll_minutes", 15)
        set(value) = sp.edit().putInt("poll_minutes", value).apply()

    // ------------------------------------------------------------ 拟人化输入设置
    var jitterEnabled: Boolean
        get() = sp.getBoolean("jitter_enabled", true)
        set(value) = sp.edit().putBoolean("jitter_enabled", value).apply()

    /** 坐标随机抖动幅度（像素），0 表示不抖动。 */
    var jitterPx: Int
        get() = if (jitterEnabled) sp.getInt("jitter_px", 6) else 0
        set(value) = sp.edit().putInt("jitter_px", value).apply()

    /** 步间等待的随机抖动（毫秒）。 */
    var jitterDelayMs: Int
        get() = if (jitterEnabled) sp.getInt("jitter_delay_ms", 250) else 0
        set(value) = sp.edit().putInt("jitter_delay_ms", value).apply()

    /** 按压时长的随机抖动（毫秒）。 */
    var jitterPressMs: Int
        get() = if (jitterEnabled) sp.getInt("jitter_press_ms", 40) else 0
        set(value) = sp.edit().putInt("jitter_press_ms", value).apply()

    /** 滑动拆成几段模拟连续移动，1 表示不分段。 */
    var jitterMoveSteps: Int
        get() = if (jitterEnabled) sp.getInt("jitter_move_steps", 4) else 1
        set(value) = sp.edit().putInt("jitter_move_steps", value).apply()

    // 旧接口保留，供未改完的调用方平滑过渡（语义=当前设备）
    @Deprecated("用 currentCode", ReplaceWith("currentCode"))
    var pairCode: String
        get() = currentCode
        set(value) {
            if (value.isNotBlank() && devices.isEmpty()) addDevice(value, "我的电脑")
            else currentCode = value
        }

    @Deprecated("用 getLastSeen(code)", ReplaceWith("getLastSeen(currentCode)"))
    var lastSeenId: Long
        get() = getLastSeen(currentCode)
        set(value) = setLastSeen(currentCode, value)
}
