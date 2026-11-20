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
    const val MSG_COMMAND = "command"
    const val MSG_ACK = "ack"
    const val MSG_GROUP_LIST = "group_list"
    const val MSG_HEARTBEAT = "heartbeat"

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

    /**
     * v1.8：一次 GET 拉取前台需要的三类低频消息（任务组 / 完成 / 失败），
     * 用 PostgREST 的 `msg_type=in.(...)` 合并，替代原来 3 次独立请求。
     * 心跳单独低频拉取并缓存（心跳每分钟都有，混在一起会挤掉通知）。
     */
    fun fetchDashboard(pairCode: String, limit: Int = 30): Result<JSONArray> {
        val types = "$MSG_GROUP_LIST,$MSG_TASK_DONE,$MSG_TASK_FAILED"
        val q = "pair_code=eq.${enc(pairCode.trim().uppercase())}" +
                "&direction=eq.$DIR_PC2PHONE" +
                "&msg_type=in.($types)" +
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

    /** 测试配对码是否有效（能连通云端即可）。 */
    fun ping(pairCode: String): Result<Boolean> {
        return fetchUnread(pairCode, 1).map { true }
    }
}
