package com.miaoxiang.link

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 喵想联动 - 主界面
 * 与电脑端通过云端信箱双向通信：
 *  - 电脑任务完成 → 这里展示 + 系统通知
 *  - 这里点任务组 → 电脑收到后执行
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var polling = false

    private lateinit var pairEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var taskContainer: LinearLayout
    private lateinit var notifyContainer: LinearLayout
    private lateinit var notifySwitch: Switch

    private var knownGroups = listOf<Group>()

    data class Group(val type: String, val name: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        askNotificationPermission()
        buildUi()
        applyPairToUi()
        startForegroundPolling()
        PollingWorker.schedule(this, prefs.pollMinutes)
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 标题
        TextView(this).apply {
            text = "喵想联动"
            textSize = 22f
            setTextColor(Color.parseColor("#0078D4"))
            gravity = Gravity.CENTER
            wrap.addView(this, lpWrap())
        }
        TextView(this).apply {
            text = "电脑任务通知 · 手机远程下发"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            wrap.addView(this, lpWrap())
        }

        // 配对码卡片
        val cardPair = card()
        addSectionTitle(cardPair, "① 配对设置")
        pairEdit = EditText(this).apply {
            hint = "输入电脑端显示的配对码"
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.AllCaps())
            textSize = 16f
            setTextColor(Color.parseColor("#222222"))
        }
        cardPair.addView(pairEdit, lpWrap())
        val rowPair = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "保存配对码"
            setOnClickListener { savePair() }
            rowPair.addView(this, lpWeight())
        }
        Button(this).apply {
            text = "测试连接"
            setOnClickListener { testConnection() }
            rowPair.addView(this, lpWeight())
        }
        cardPair.addView(rowPair, lpWrap())
        statusText = TextView(this).apply {
            text = "状态：未连接"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
        }
        cardPair.addView(statusText, lpWrap())
        notifySwitch = Switch(this).apply {
            text = "后台接收任务通知"
            isChecked = prefs.notifyEnabled
            setOnCheckedChangeListener { _, on ->
                prefs.notifyEnabled = on
                if (on) PollingWorker.schedule(this@MainActivity, prefs.pollMinutes)
            }
        }
        cardPair.addView(notifySwitch, lpWrap())
        wrap.addView(cardPair, lpWrap())

        // 任务组卡片
        val cardTask = card()
        addSectionTitle(cardTask, "② 远程执行任务组（点一下，电脑就执行）")
        taskContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardTask.addView(taskContainer, lpWrap())
        Button(this).apply {
            text = "刷新任务组"
            setOnClickListener { loadGroups(showToast = true) }
            cardTask.addView(this, lpWrap())
        }
        wrap.addView(cardTask, lpWrap())

        // 通知记录卡片
        val cardNotify = card()
        addSectionTitle(cardNotify, "③ 最近任务通知")
        notifyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardNotify.addView(notifyContainer, lpWrap())
        wrap.addView(cardNotify, lpWrap())

        TextView(this).apply {
            text = "提示：电脑端需开启「手机联动」并使用相同配对码。\n本 App 在前台时实时刷新；退到后台约每 ${prefs.pollMinutes} 分钟检查一次通知。"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            wrap.addView(this, lpWrap())
        }

        setContentView(root)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        val p = (14 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
        layoutParams = lp
        elevation = 2f * resources.displayMetrics.density
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, lpWrap())
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (8 * resources.displayMetrics.density).toInt()
    }

    private fun lpWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = (6 * resources.displayMetrics.density).toInt()
    }

    // ------------------------------------------------------------------ 配对
    private fun applyPairToUi() {
        pairEdit.setText(prefs.pairCode)
    }

    private fun savePair() {
        val code = pairEdit.text.toString().trim().uppercase()
        if (code.length < 4) {
            toast("配对码至少 4 位")
            return
        }
        prefs.pairCode = code
        prefs.lastSeenId = 0L
        PollingWorker.schedule(this, prefs.pollMinutes)
        toast("配对码已保存")
        refreshAll()
    }

    private fun testConnection() {
        val code = pairEdit.text.toString().trim().uppercase()
        if (code.isEmpty()) { toast("请先填配对码"); return }
        toast("正在测试…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CloudApi.ping(code).isSuccess }
            toast(if (ok) "连接成功，配对码可用" else "连接失败，请检查网络或配对码")
        }
    }

    // ------------------------------------------------------------------ 数据加载
    private fun startForegroundPolling() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (prefs.pairCode.isNotEmpty()) refreshAll()
                handler.postDelayed(this, 4000)
            }
        })
    }

    private fun refreshAll() {
        scope.launch {
            loadGroupsInternal(false)
            loadNotificationsInternal()
            loadStatusInternal()
        }
    }

    private fun loadGroups(showToast: Boolean) {
        scope.launch {
            val ok = loadGroupsInternal(showToast)
            if (showToast) toast(if (ok) "任务组已刷新" else "刷新失败，请检查连接")
        }
    }

    private suspend fun loadGroupsInternal(showToast: Boolean): Boolean {
        val pair = prefs.pairCode.trim()
        if (pair.isEmpty()) return false
        val res = withContext(Dispatchers.IO) { CloudApi.fetchLatest(pair, CloudApi.MSG_GROUP_LIST, 1) }
        val arr = res.getOrElse { return false }
        if (arr.length() == 0) return true
        val payload = arr.getJSONObject(0).optString("payload", "{}")
        var groups = JSONArray()
        try { groups = JSONObject(payload).optJSONArray("groups") ?: JSONArray() } catch (_: Exception) {}
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
        return true
    }

    private fun renderGroups(groups: List<Group>) {
        taskContainer.removeAllViews()
        if (groups.isEmpty()) {
            taskContainer.addView(TextView(this).apply {
                text = "暂无任务组。请先在电脑端开启手机联动，或点「刷新任务组」。"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
            return
        }
        val order = mapOf("screenshot_group" to 0, "group" to 1, "mixed_group" to 2, "script" to 3)
        val sorted = groups.sortedBy { order[it.type] ?: 9 }
        for (g in sorted) {
            val btn = Button(this).apply {
                text = "▸  ${g.label}：${g.name}"
                isAllCaps = false
                setTextColor(Color.parseColor("#0078D4"))
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { confirmRun(g) }
            }
            taskContainer.addView(btn, lpWrap())
        }
    }

    private fun confirmRun(g: Group) {
        AlertDialog.Builder(this)
            .setTitle("远程执行")
            .setMessage("让电脑立即执行：\n${g.label}「${g.name}」？\n\n（电脑正在跑其他任务时会忽略本次指令）")
            .setPositiveButton("执行") { _, _ -> sendCommand(g) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendCommand(g: Group) {
        val pair = prefs.pairCode.trim()
        if (pair.isEmpty()) { toast("请先设置配对码"); return }
        toast("指令已发送")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CloudApi.sendCommand(pair, g.type, g.name).isSuccess
            }
            if (!ok) toast("发送失败，请检查网络")
        }
    }

    private suspend fun loadNotificationsInternal() {
        val pair = prefs.pairCode.trim()
        if (pair.isEmpty()) return
        val res = withContext(Dispatchers.IO) { CloudApi.fetchLatest(pair, CloudApi.MSG_TASK_DONE, 6) }
        val done = res.getOrElse { JSONArray() }
        val res2 = withContext(Dispatchers.IO) { CloudApi.fetchLatest(pair, CloudApi.MSG_TASK_FAILED, 6) }
        val failed = res2.getOrElse { JSONArray() }
        val combined = mutableListOf<JSONObject>()
        for (i in 0 until done.length()) done.optJSONObject(i)?.let { combined.add(it) }
        for (i in 0 until failed.length()) failed.optJSONObject(i)?.let { combined.add(it) }
        combined.sortByDescending { it.optString("created_at", "") }

        notifyContainer.removeAllViews()
        if (combined.isEmpty()) {
            notifyContainer.addView(TextView(this).apply {
                text = "暂无通知记录"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }, lpWrap())
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (row in combined.take(8)) {
            val type = row.optString("msg_type", "")
            val icon = if (type == CloudApi.MSG_TASK_FAILED) "✗" else "✓"
            val color = if (type == CloudApi.MSG_TASK_FAILED) "#DC3545" else "#28A745"
            var time = ""
            try {
                val raw = row.optString("created_at", "")
                val parsed = java.time.OffsetDateTime.parse(raw)
                time = sdf.format(Date.from(parsed.toInstant()))
            } catch (_: Exception) {}
            val line = TextView(this).apply {
                text = "$icon  ${row.optString("title", "")}  ·  $time\n${row.optString("content", "")}"
                textSize = 12f
                setTextColor(Color.parseColor("#444444"))
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0,
                    (6 * resources.displayMetrics.density).toInt())
            }
            notifyContainer.addView(line, lpWrap())
        }
    }

    private suspend fun loadStatusInternal() {
        val pair = prefs.pairCode.trim()
        if (pair.isEmpty()) {
            statusText.text = "状态：未设置配对码"
            return
        }
        val res = withContext(Dispatchers.IO) { CloudApi.fetchLatest(pair, CloudApi.MSG_HEARTBEAT, 1) }
        val arr = res.getOrElse { null }
        if (arr == null || arr.length() == 0) {
            statusText.text = "状态：未发现电脑（请开启电脑端联动）"
            statusText.setTextColor(Color.parseColor("#DC3545"))
            return
        }
        val created = arr.getJSONObject(0).optString("created_at", "")
        val minutesAgo = try {
            val t = java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli()
            ((System.currentTimeMillis() - t) / 60000).toInt()
        } catch (_: Exception) { 999 }
        if (minutesAgo <= 5) {
            statusText.text = "状态：● 电脑在线（心跳 $minutesAgo 分钟前）"
            statusText.setTextColor(Color.parseColor("#28A745"))
        } else {
            statusText.text = "状态：○ 电脑可能离线（心跳 $minutesAgo 分钟前）"
            statusText.setTextColor(Color.parseColor("#999999"))
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
