package com.miaoxiang.link

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 云端信箱客户端：与电脑端共用同一张 link_messages 表，纯 HTTP REST 读写。
 */
object CloudApi {
    private const val REST = "https://wbapp-gp4ndbcf8opv7nyrqcfllg.app.workbuddy.host/.cloud/database/rest/v1"
    private const val KEY = "wbpk_gp4ndbCF8Opv7NYrQcfLlG_2zil329GWxaMLCznWPjTPICBb0JFzsLU"
    private const val TABLE = "link_messages"

    const val DIR_PC2PHONE = "pc2phone"
    const val DIR_PHONE2PC = "phone2pc"

    const val MSG_TASK_DONE = "task_done"
    const val MSG_TASK_FAILED = "task_failed"
    const val MSG_TASK_START = "task_start"
    const val MSG_TASK_STOP = "task_stop"
    const val MSG_COMMAND = "command"
    const val MSG_ACK = "ack"
    const val MSG_GROUP_LIST = "group_list"
    const val MSG_HEARTBEAT = "heartbeat"
    // 电脑 → 手机：下发「执行某个自动签到任务」的指令（pc2phone 方向）
    const val MSG_RUN_AUTO_TASK = "run_auto_task"
    // 手机 → 电脑：手机本地签到任务清单同步（pc2phone 方向，供电脑端下拉选择）
    const val MSG_AUTO_TASK_LIST = "auto_task_list"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, path: String, body: String? = null): Result<String> {
        return try {
            val builder = Request.Builder()
                .url(REST + path)
                .header("x-wb-webapp-access-key", KEY)
                .header("Accept", "application/json")
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, body.toRequestBody(jsonMedia))
            } else {
                builder.method(method, null)
            }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) Result.success(text)
                else Result.failure(Exception("HTTP ${resp.code}: ${text.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 手机下发一条指令（direction=phone2pc）。 */
    fun sendCommand(pairCode: String, targetType: String, targetName: String,
                    extra: JSONObject? = null): Result<Boolean> {
        val payload = JSONObject().apply {
            put("target_type", targetType)
            put("target_name", targetName)
            extra?.let {
                for (key in it.keys()) put(key, it.get(key))
            }
        }
        return postCommand(pairCode, "执行任务", targetName, payload)
    }

    /**
     * 手机下发一条控制指令（暂停 / 继续 / 停止）。
     * action 取值见电脑端 CMD_ACTION_PAUSE / RESUME / STOP：pause / resume / stop。
     * 电脑端 _on_command 解析 action 后绕过「忙时拒绝」，在 UI 线程执行。
     */
    fun sendAction(pairCode: String, action: String): Result<Boolean> {
        val title = when (action) {
            "pause" -> "暂停任务"
            "resume" -> "继续任务"
            "stop" -> "停止任务"
            else -> "控制指令"
        }
        val payload = JSONObject().apply { put("action", action) }
        return postCommand(pairCode, title, title, payload)
    }

    private fun postCommand(pairCode: String, title: String, content: String,
                            payload: JSONObject): Result<Boolean> {
        val row = JSONObject().apply {
            put("pair_code", pairCode.trim().uppercase())
            put("direction", DIR_PHONE2PC)
            put("msg_type", MSG_COMMAND)
            put("title", title)
            put("content", content)
            put("payload", payload.toString())
            put("is_read", false)
        }
        return request("POST", "/$TABLE", row.toString()).map { true }
    }

    /** 取电脑发给手机、且未读的消息（升序）。 */
    fun fetchUnread(pairCode: String, limit: Int = 50): Result<JSONArray> {
        val q = "pair_code=eq.${enc(pairCode.trim().uppercase())}" +
                "&direction=eq.$DIR_PC2PHONE" +
                "&is_read=eq.false" +
                "&select=*" +
                "&order=created_at.asc" +
                "&limit=$limit"
        return request("GET", "/$TABLE?$q").map { text ->
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    /** 取电脑发给手机的最新一类消息（不限已读，倒序）。 */
    fun fetchLatest(pairCode: String, msgType: String, limit: Int = 5): Result<JSONArray> {
        val q = "pair_code=eq.${enc(pairCode.trim().uppercase())}" +
                "&direction=eq.$DIR_PC2PHONE" +
                "&msg_type=eq.$msgType" +
                "&select=*" +
                "&order=created_at.desc" +
                "&limit=$limit"
        return request("GET", "/$TABLE?$q").map { text ->
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    /** 标记消息已读。 */
    fun markRead(id: Long): Result<Boolean> {
        return request("PATCH", "/$TABLE?id=eq.$id", "{\"is_read\":true}").map { true }
    }

    /**
     * 向电脑写一条「手机本地签到任务」的消息（direction=pc2phone）。
     * 任务清单和执行回执都走这个方向——电脑端 get_status 会用 get_latest 读 pc2phone，
     * 且手机端自己的 fetchUnread 会按 msg_type 跳过这些类型，不会重复弹通知。
     */
    private fun postToPc(pairCode: String, msgType: String, title: String,
                         content: String, payload: JSONObject): Result<Boolean> {
        val row = JSONObject().apply {
            put("pair_code", pairCode.trim().uppercase())
            put("direction", DIR_PC2PHONE)
            put("msg_type", msgType)
            put("title", title)
            put("content", content)
            put("payload", payload.toString())
            put("is_read", false)
        }
        return request("POST", "/$TABLE", row.toString()).map { true }
    }

    /** 手机签到任务执行完成/失败后回执给电脑（电脑端按 task_done 显示在任务通知里）。 */
    fun reportAutoTaskResult(pairCode: String, taskName: String, success: Boolean,
                             detail: String): Result<Boolean> {
        val type = if (success) MSG_TASK_DONE else MSG_TASK_FAILED
        val payload = JSONObject().apply {
            put("status", if (success) "done" else "failed")
            put("label", "手机签到·$taskName")
            put("source", "phone_auto")
            put("task_name", taskName)
        }
        return postToPc(pairCode, type, "手机签到${if (success) "完成" else "失败"}",
            "$taskName\n$detail", payload)
    }

    /** 把手机本地的签到任务清单同步到云端，供电脑端下拉选择下发。 */
    fun syncAutoTaskList(pairCode: String, tasks: JSONArray): Result<Boolean> {
        val payload = JSONObject().apply { put("tasks", tasks) }
        return postToPc(pairCode, MSG_AUTO_TASK_LIST, "手机签到任务清单", "", payload)
    }

    /** 拉取电脑下发给手机的签到指令（pc2phone 方向，msg_type=run_auto_task）。 */
    fun fetchAutoCommands(pairCode: String, limit: Int = 30): Result<JSONArray> {
        val q = "pair_code=eq.${enc(pairCode.trim().uppercase())}" +
                "&direction=eq.$DIR_PC2PHONE" +
                "&msg_type=eq.$MSG_RUN_AUTO_TASK" +
                "&is_read=eq.false" +
                "&select=*" +
                "&order=created_at.asc" +
                "&limit=$limit"
        return request("GET", "/$TABLE?$q").map { text ->
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    /** 测试配对码是否有效（能连通云端即可）。 */
    fun ping(pairCode: String): Result<Boolean> {
        return fetchUnread(pairCode, 1).map { true }
    }
}
