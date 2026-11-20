package com.miaoxiang.link

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.updaterkit.UpdaterKit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 喵想联动 - 主界面（v1.3）
 *  - 三页签：远程执行 / 任务通知 / 设备，通知不再被长脚本列表挤到底部
 *  - 多台已配对设备可切换，所有收发都走「当前设备」的配对码
 *  - v1.3 新增远程控制：暂停 / 继续 / 停止当前设备正在执行的任务
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var polling = false

    // 顶部设备条
    private lateinit var deviceNameText: TextView
    private lateinit var deviceStatusText: TextView

    // 页面容器
    private lateinit var pageTask: View
    private lateinit var pageNotify: View
    private lateinit var pageDevice: View
    private lateinit var tabTask: TextView
    private lateinit var tabNotify: TextView
    private lateinit var tabDevice: TextView

    // 内容
    private lateinit var taskContainer: LinearLayout
    private lateinit var notifyContainer: LinearLayout
    private lateinit var deviceContainer: LinearLayout
    private lateinit var notifySwitch: Switch
    private lateinit var onDemandSwitch: Switch
    private lateinit var refreshHintText: TextView

    private var knownGroups = listOf<Group>()
    private var currentTab = 0

    // v1.8：一次拉取的原始数据做内存缓存，切页签/重复渲染不再联网
    private var cachedRows = JSONArray()
    private var lastRefreshAt = 0L
    private var lastHeartbeatFetch = 0L   // 心跳变化慢，最多每 30 秒拉一次

    data class Group(val type: String, val name: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        askNotificationPermission()
        buildUi()
        switchTab(0)
        updateRefreshHint()
        if (prefs.onDemandMode) {
            // 按需模式：不主动联网，页面显示提示，等用户点「刷新数据」
            renderEmptyStates()
        } else {
            refreshAll()
            startForegroundPolling()
        }
        PollingWorker.schedule(this, prefs.pollMinutes)
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        // 顶栏：标题 + 当前设备 + 状态
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        header.addView(TextView(this).apply {
            text = "喵想联动"
            textSize = 20f
            setTextColor(Color.parseColor("#0078D4"))
            setTypeface(null, Typeface.BOLD)
        }, lpWrap(0))
        deviceNameText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            text = "当前设备：${prefs.currentDeviceName}"
        }
        header.addView(deviceNameText, lpWrap(2))
        // 状态行 + 刷新按钮（v1.8 按需模式的核心入口：不按按钮就不联网）
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        deviceStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            text = "状态：检测中…"
        }
        statusRow.addView(deviceStatusText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(Button(this).apply {
            text = "↻ 刷新数据"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { manualRefresh() }
        }, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(statusRow, lpWrap(0))
        refreshHintText = TextView(this).apply {
            textSize = 11f
        }
        header.addView(refreshHintText, lpWrap(2))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 页签栏
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        tabTask = tab("① 远程执行")
        tabNotify = tab("② 任务通知")
        tabDevice = tab("③ 设备")
        tabTask.setOnClickListener { switchTab(0) }
        tabNotify.setOnClickListener { switchTab(1) }
        tabDevice.setOnClickListener { switchTab(2) }
        tabBar.addView(tabTask, lpWeight())
        tabBar.addView(tabNotify, lpWeight())
        tabBar.addView(tabDevice, lpWeight())
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 内容区（占满剩余高度，各页独立滚动，互不挤压）
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        // ---- 页1：远程执行 ----
        pageTask = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val taskWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cardTask = card()
        addSectionTitle(cardTask, "点一下任务组，当前电脑立即执行")
        taskContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardTask.addView(taskContainer, lpWrap())
        cardTask.addView(Button(this).apply {
            text = "刷新任务组"
            setOnClickListener { loadGroups(showToast = true) }
        }, lpWrap())
        // 远程控制：暂停 / 继续 / 停止当前设备正在执行的任务
        addSectionTitle(cardTask, "远程控制（暂停 / 继续 / 停止）")
        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlRow.addView(Button(this).apply {
            text = "⏸ 暂停"
            isAllCaps = false
            setOnClickListener { sendActionCmd("pause", "暂停") }
        }, lpWeight())
        ctrlRow.addView(Button(this).apply {
            text = "▶ 继续"
            isAllCaps = false
            setOnClickListener { sendActionCmd("resume", "继续") }
        }, lpWeight())
        ctrlRow.addView(Button(this).apply {
            text = "■ 停止"
            isAllCaps = false
            setTextColor(Color.parseColor("#C00000"))
            setOnClickListener { sendActionCmd("stop", "停止") }
        }, lpWeight())
        cardTask.addView(ctrlRow, lpWrap())
        cardTask.addView(TextView(this).apply {
            text = "（执行任务：电脑正忙时会忽略；暂停/停止：忙时也立即生效）"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        }, lpWrap())
        taskWrap.addView(cardTask, lpWrap())
        (pageTask as ScrollView).addView(taskWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 页2：任务通知 ----
        pageNotify = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val notifyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cardNotify = card()
        addSectionTitle(cardNotify, "最近任务通知（当前设备）")
        notifyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardNotify.addView(notifyContainer, lpWrap())
        notifyWrap.addView(cardNotify, lpWrap())
        (pageNotify as ScrollView).addView(notifyWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 页3：设备 ----
        pageDevice = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val deviceWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val cardDevice = card()
        addSectionTitle(cardDevice, "已配对的电脑（点卡片切换）")
        deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardDevice.addView(deviceContainer, lpWrap())
        cardDevice.addView(Button(this).apply {
            text = "＋ 添加新设备（输入配对码）"
            setOnClickListener { showAddDeviceDialog() }
        }, lpWrap())
        deviceWrap.addView(cardDevice, lpWrap())

        val cardSetting = card()
        addSectionTitle(cardSetting, "设置")
        notifySwitch = Switch(this).apply {
            text = "后台接收任务通知"
            isChecked = prefs.notifyEnabled
            setOnCheckedChangeListener { _, on ->
                prefs.notifyEnabled = on
                if (on) PollingWorker.schedule(this@MainActivity, prefs.pollMinutes)
            }
        }
        cardSetting.addView(notifySwitch, lpWrap())
        // v1.8：按需刷新开关（默认开）。开启后前台不自动联网，只有点顶部「刷新数据」才消耗资源点
        onDemandSwitch = Switch(this).apply {
            text = "按需刷新（省资源点）"
            isChecked = prefs.onDemandMode
            setOnCheckedChangeListener { _, on ->
                prefs.onDemandMode = on
                updateRefreshHint()
                if (on) {
                    stopForegroundPolling()
                    toast("已开启按需刷新：只有点顶部「刷新数据」才会联网")
                } else {
                    startForegroundPolling()
                    toast("已关闭按需刷新：每 ${prefs.autoRefreshSeconds} 秒自动刷新")
                }
            }
        }
        cardSetting.addView(onDemandSwitch, lpWrap())
        cardSetting.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { UpdaterKit.checkManually(this@MainActivity) }
        }, lpWrap())
        cardSetting.addView(TextView(this).apply {
            text = "提示：在每台电脑上各自开启「手机联动」并生成配对码，再逐个添加到这里。\n" +
                   "在本页点击设备卡片即可切换当前控制的电脑；只有已配对过的设备能切换。\n" +
                   "按需刷新默认开启：App 不自动联网、不消耗资源点，点顶部「↻ 刷新数据」按钮才拉取一次。\n" +
                   "关闭后可改为自动刷新（每 ${prefs.autoRefreshSeconds} 秒）；后台通知仍按每 ${prefs.pollMinutes} 分钟检查。"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        }, lpWrap())
        deviceWrap.addView(cardSetting, lpWrap())
        (pageDevice as ScrollView).addView(deviceWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(pageTask, matchParent())
        content.addView(pageNotify, matchParent())
        content.addView(pageDevice, matchParent())
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tab(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        setBackgroundColor(Color.parseColor("#EEF0F3"))
        setTextColor(Color.parseColor("#666666"))
    }

    private fun switchTab(index: Int) {
        currentTab = index
        pageTask.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageNotify.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageDevice.visibility = if (index == 2) View.VISIBLE else View.GONE
        styleTab(tabTask, index == 0)
        styleTab(tabNotify, index == 1)
        styleTab(tabDevice, index == 2)
        if (index == 1) refreshNotificationsOnly()
        if (index == 2) renderDevices()
    }

    private fun styleTab(t: TextView, active: Boolean) {
        t.setBackgroundColor(Color.parseColor(if (active) "#0078D4" else "#EEF0F3"))
        t.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#666666"))
        t.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        val p = dp(14)
        setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
        elevation = 2f * resources.displayMetrics.density
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
        }, lpWrap())
    }

    private fun lpWrap(top: Int = 8) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun lpWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = dp(4)
        leftMargin = dp(4)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    // ------------------------------------------------------------------ 设备
    private fun renderDevices() {
        deviceNameText.text = "当前设备：${prefs.currentDeviceName}"
        deviceContainer.removeAllViews()
        val list = prefs.devices
        if (list.isEmpty()) {
            deviceContainer.addView(TextView(this).apply {
                text = "还没有已配对的设备。点下方「添加新设备」，输入电脑端显示的配对码。"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
            return
        }
        for (d in list) {
            val active = d.code == prefs.currentCode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor(if (active) "#E8F2FE" else "#FAFAFA"))
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = (if (active) "● " else "○ ") + d.name + (if (active) "  （当前）" else "")
                textSize = 14f
                setTextColor(Color.parseColor(if (active) "#0078D4" else "#333333"))
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }, lpWrap(0))
            info.addView(TextView(this).apply {
                text = "配对码：${d.code}"
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap(0))
            row.addView(info, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = if (active) "已选中" else "切换"
                isEnabled = !active
                textSize = 12f
                setOnClickListener {
                    prefs.switchDevice(d.code)
                    onDeviceChanged()
                }
            }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))

            row.addView(Button(this).apply {
                text = "改名"
                textSize = 12f
                setOnClickListener { showRenameDialog(d) }
            }, LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))

            row.addView(Button(this).apply {
                text = "删除"
                textSize = 12f
                setOnClickListener { confirmRemove(d) }
            }, LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))

            deviceContainer.addView(row, lpWrap(6))
        }
    }

    private fun onDeviceChanged() {
        knownGroups = emptyList()
        deviceNameText.text = "当前设备：${prefs.currentDeviceName}"
        toast("已切换到：${prefs.currentDeviceName}")
        refreshAll()
        renderDevices()
    }

    private fun showAddDeviceDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val nameEdit = EditText(this).apply {
            hint = "设备名称（如：家里电脑）"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
        }
        val codeEdit = EditText(this).apply {
            hint = "电脑端显示的配对码"
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.AllCaps())
            textSize = 16f
        }
        view.addView(nameEdit, lpWrap())
        view.addView(codeEdit, lpWrap())
        view.addView(TextView(this).apply {
            text = "在电脑端点「手机联动 → 生成新配对码」后可看到配对码。"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        }, lpWrap())

        AlertDialog.Builder(this)
            .setTitle("添加设备")
            .setView(view)
            .setPositiveButton("添加并切换") { _, _ ->
                val code = codeEdit.text.toString().trim()
                if (code.length < 4) { toast("配对码至少 4 位"); return@setPositiveButton }
                if (prefs.hasDevice(code)) {
                    prefs.switchDevice(code)
                    toast("该设备已存在，已切换到它")
                    onDeviceChanged()
                    return@setPositiveButton
                }
                prefs.addDevice(code, nameEdit.text.toString())
                toast("已添加：${prefs.currentDeviceName}")
                onDeviceChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(d: Prefs.Device) {
        val edit = EditText(this).apply {
            setText(d.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("重命名设备")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                prefs.renameDevice(d.code, edit.text.toString())
                renderDevices()
                deviceNameText.text = "当前设备：${prefs.currentDeviceName}"
                toast("已重命名")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemove(d: Prefs.Device) {
        AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("删除「${d.name}」？删除后需重新输入配对码才能再次切换回来。")
            .setPositiveButton("删除") { _, _ ->
                val wasActive = d.code == prefs.currentCode
                prefs.removeDevice(d.code)
                if (wasActive) {
                    knownGroups = emptyList()
                    refreshAll()
                }
                renderDevices()
                deviceNameText.text = "当前设备：${prefs.currentDeviceName}"
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 数据加载
    /** 自动轮询仅在「按需模式」关闭时启用，间隔可调（默认 15 秒，远低于原来的 4 秒）。 */
    private fun startForegroundPolling() {
        if (polling || prefs.onDemandMode) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!prefs.onDemandMode && prefs.currentCode.isNotEmpty()) {
                    refreshAll()
                }
                handler.postDelayed(this, prefs.autoRefreshSeconds.coerceIn(5, 300) * 1000L)
            }
        })
    }

    private fun stopForegroundPolling() {
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    /** 顶部状态栏提示文案：告诉用户当前是否会自动联网。 */
    private fun updateRefreshHint() {
        refreshHintText.text = if (prefs.onDemandMode) {
            "按需模式：不自动联网 · 点右上「↻ 刷新数据」才会消耗资源点"
        } else {
            "自动模式：每 ${prefs.autoRefreshSeconds} 秒刷新（会持续消耗资源点）"
        }
        refreshHintText.setTextColor(Color.parseColor(
            if (prefs.onDemandMode) "#28A745" else "#C00000"))
    }

    /** 用户点「刷新数据」：主动拉取一次云端并刷新全部页面。 */
    private fun manualRefresh() {
        if (prefs.currentCode.isEmpty()) {
            toast("请先在「③ 设备」添加已配对的电脑")
            return
        }
        toast("正在刷新…")
        refreshAll(showToast = true)
    }

    /**
     * v1.8 核心：一次合并请求拉全部数据（替代原来 4 次 GET），结果存入内存缓存，
     * 各页面从缓存渲染。切页签、重复进入都不再联网。
     */
    private fun refreshAll(showToast: Boolean = false) {
        scope.launch {
            val pair = prefs.currentCode
            if (pair.isEmpty()) return@launch
            // 一次合并请求：任务组 + 完成 + 失败
            val res = withContext(Dispatchers.IO) { CloudApi.fetchDashboard(pair) }
            val ok = res.isSuccess
            if (ok) {
                val rows = res.getOrNull() ?: JSONArray()
                // 心跳单独拉，最多每 30 秒一次，合并进同一缓存供状态显示
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatFetch > 30_000L) {
                    val hb = withContext(Dispatchers.IO) {
                        CloudApi.fetchLatest(pair, CloudApi.MSG_HEARTBEAT, 1)
                    }
                    if (hb.isSuccess) {
                        hb.getOrNull()?.let { rows.put(it) }
                        lastHeartbeatFetch = now
                    }
                } else {
                    // 复用上次心跳行
                    for (i in 0 until cachedRows.length()) {
                        val row = cachedRows.optJSONObject(i) ?: continue
                        if (row.optString("msg_type", "") == CloudApi.MSG_HEARTBEAT) rows.put(row)
                    }
                }
                cachedRows = rows
                lastRefreshAt = now
                applyCachedRows()
            }
            if (showToast) {
                toast(if (ok) "已刷新（${prefs.currentDeviceName}）" else "刷新失败，请检查连接")
            }
        }
    }

    /** 用内存缓存渲染各页面（不联网）。 */
    private fun applyCachedRows() {
        renderGroupsFromCache()
        renderNotificationsFromCache()
        renderStatusFromCache()
        if (currentTab == 2) renderDevices()
    }

    /** 按需模式首次进入 / 无数据时，显示空状态，不联网。 */
    private fun renderEmptyStates() {
        if (knownGroups.isEmpty()) renderGroups(emptyList())
        if (notifyContainer.childCount == 0) {
            notifyContainer.removeAllViews()
            notifyContainer.addView(TextView(this).apply {
                text = "按需模式：点顶部「↻ 刷新数据」后显示通知"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
        }
        deviceStatusText.text = "状态：点「↻ 刷新数据」检测在线状态"
        deviceStatusText.setTextColor(Color.parseColor("#999999"))
    }

    /** 仅从缓存渲染通知（切到通知页用，不联网）。 */
    private fun refreshNotificationsOnly() {
        renderNotificationsFromCache()
    }

    private fun loadGroups(showToast: Boolean) {
        // 任务组按钮是显式操作，走一次完整刷新
        refreshAll(showToast = showToast)
    }

    /** 从缓存的合并结果里筛出某一 msg_type 的行。 */
    private fun rowsOfType(type: String, limit: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until cachedRows.length()) {
            val row = cachedRows.optJSONObject(i) ?: continue
            if (row.optString("msg_type", "") == type) {
                out.add(row)
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun renderGroupsFromCache() {
        val groupRows = rowsOfType(CloudApi.MSG_GROUP_LIST, 1)
        var groups = JSONArray()
        if (groupRows.isNotEmpty()) {
            val payload = groupRows[0].optString("payload", "{}")
            try { groups = JSONObject(payload).optJSONArray("groups") ?: JSONArray() } catch (_: Exception) {}
        }
        val list = mutableListOf<Group>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            list.add(Group(
                g.optString("type", ""),
                g.optString("name", ""),
                g.optString("label", "任务")
            ))
        }
        if (list != knownGroups || taskContainer.childCount == 0) {
            knownGroups = list
            renderGroups(list)
        }
    }

    private fun renderGroups(groups: List<Group>) {
        taskContainer.removeAllViews()
        if (groups.isEmpty()) {
            taskContainer.addView(TextView(this).apply {
                text = "当前设备暂无任务组。请确认该电脑已开启手机联动，或点「刷新任务组」。"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
            return
        }
        val order = mapOf("screenshot_group" to 0, "group" to 1, "mixed_group" to 2, "script" to 3)
        val sorted = groups.sortedBy { order[it.type] ?: 9 }
        for (g in sorted) {
            taskContainer.addView(Button(this).apply {
                text = "▸  ${g.label}：${g.name}"
                isAllCaps = false
                setTextColor(Color.parseColor("#0078D4"))
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { confirmRun(g) }
            }, lpWrap(4))
        }
    }

    private fun confirmRun(g: Group) {
        AlertDialog.Builder(this)
            .setTitle("远程执行 · ${prefs.currentDeviceName}")
            .setMessage("让「${prefs.currentDeviceName}」立即执行：\n${g.label}「${g.name}」？\n\n（电脑正在跑其他任务时会忽略本次指令）")
            .setPositiveButton("执行") { _, _ -> sendCommand(g) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendCommand(g: Group) {
        val pair = prefs.currentCode
        if (pair.isEmpty()) { toast("请先添加设备"); return }
        toast("指令已发送到：${prefs.currentDeviceName}")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CloudApi.sendCommand(pair, g.type, g.name).isSuccess
            }
            if (!ok) toast("发送失败，请检查网络")
        }
    }

    /** 下发暂停/继续/停止控制指令到当前设备。 */
    private fun sendActionCmd(action: String, label: String) {
        val pair = prefs.currentCode
        if (pair.isEmpty()) { toast("请先添加设备"); return }
        val device = prefs.currentDeviceName
        toast("已发送「${label}」到：${device}")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CloudApi.sendAction(pair, action).isSuccess
            }
            if (!ok) toast("「${label}」发送失败，请检查网络")
        }
    }

    private fun renderNotificationsFromCache() {
        val combined = mutableListOf<JSONObject>()
        combined.addAll(rowsOfType(CloudApi.MSG_TASK_DONE, 6))
        combined.addAll(rowsOfType(CloudApi.MSG_TASK_FAILED, 6))
        combined.sortByDescending { it.optString("created_at", "") }

        notifyContainer.removeAllViews()
        if (combined.isEmpty()) {
            notifyContainer.addView(TextView(this).apply {
                text = if (cachedRows.length() == 0 && prefs.onDemandMode)
                    "按需模式：点顶部「↻ 刷新数据」后显示通知"
                else "「${prefs.currentDeviceName}」暂无通知记录"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (row in combined.take(12)) {
            val type = row.optString("msg_type", "")
            val icon = if (type == CloudApi.MSG_TASK_FAILED) "✗" else "✓"
            val color = if (type == CloudApi.MSG_TASK_FAILED) "#DC3545" else "#28A745"
            var time = ""
            try {
                val raw = row.optString("created_at", "")
                val parsed = java.time.OffsetDateTime.parse(raw)
                time = sdf.format(Date.from(parsed.toInstant()))
            } catch (_: Exception) {}
            val fullText = "$icon  ${row.optString("title", "")}  ·  $time\n${row.optString("content", "")}"
            val spanned = android.text.SpannableString(fullText).apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(color)),
                    0, icon.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val line = TextView(this).apply {
                text = spanned
                textSize = 12f
                setTextColor(Color.parseColor("#444444"))
                setPadding(0, dp(6), 0, dp(6))
            }
            notifyContainer.addView(line, lpWrap())
        }
    }

    private fun renderStatusFromCache() {
        val pair = prefs.currentCode
        if (pair.isEmpty()) {
            deviceStatusText.text = "状态：请先添加设备"
            deviceStatusText.setTextColor(Color.parseColor("#DC3545"))
            return
        }
        val hb = rowsOfType(CloudApi.MSG_HEARTBEAT, 1)
        if (hb.isEmpty()) {
            deviceStatusText.text = if (cachedRows.length() == 0 && prefs.onDemandMode)
                "状态：点「↻ 刷新数据」检测在线状态"
            else "状态：未发现「${prefs.currentDeviceName}」（请开启该电脑的联动）"
            deviceStatusText.setTextColor(Color.parseColor("#DC3545"))
            return
        }
        val created = hb[0].optString("created_at", "")
        val minutesAgo = try {
            val t = java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli()
            ((System.currentTimeMillis() - t) / 60000).toInt()
        } catch (_: Exception) { 999 }
        if (minutesAgo <= 5) {
            deviceStatusText.text = "状态：● ${prefs.currentDeviceName} 在线（心跳 $minutesAgo 分钟前）"
            deviceStatusText.setTextColor(Color.parseColor("#28A745"))
        } else {
            deviceStatusText.text = "状态：○ ${prefs.currentDeviceName} 可能离线（心跳 $minutesAgo 分钟前）"
            deviceStatusText.setTextColor(Color.parseColor("#999999"))
        }
    }

    // ------------------------------------------------------------------ 工具
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        UpdaterKit.autoCheck(this)
        deviceNameText.text = "当前设备：${prefs.currentDeviceName}"
        if (currentTab == 2) renderDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
