package com.miaoxiang.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 后台定时轮询：遍历所有「已配对设备」，拉取各自电脑写来的通知，弹系统通知并标记已读。
 * 每台设备有独立的 lastSeen 游标，切换设备不会互相干扰；通知标题带设备名以区分来源。
 */
class PollingWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.notifyEnabled) return Result.success()
        val devices = prefs.devices
        if (devices.isEmpty()) return Result.success()

        for (device in devices) {
            val pair = device.code
            val res = runBlocking { CloudApi.fetchUnread(pair) }
            if (res.isFailure) continue
            val rows = res.getOrNull() ?: continue

            var lastId = prefs.getLastSeen(pair)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optLong("id", 0L)
                if (id <= lastId) continue

                val type = row.optString("msg_type", "")
                // 心跳 / 组清单 / 回执 不弹通知，只推进 seen
                if (type == CloudApi.MSG_HEARTBEAT || type == CloudApi.MSG_GROUP_LIST ||
                    type == CloudApi.MSG_ACK) {
                    lastId = id
                    CloudApi.markRead(id)
                    continue
                }
                val title = "[${device.name}] ${row.optString("title", "电脑通知")}"
                val content = row.optString("content", "")
                Notifier.show(applicationContext, title, content, id.toInt() + pair.hashCode())
                lastId = id
                CloudApi.markRead(id)
            }
            if (lastId > prefs.getLastSeen(pair)) prefs.setLastSeen(pair, lastId)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, minutes: Int) {
            val work = androidx.work.PeriodicWorkRequestBuilder<PollingWorker>(
                minutes.toLong(), java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            ).build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "miaoxiang_poll",
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    work
                )
        }
    }
}

/** 系统通知工具。 */
object Notifier {
    private const val CHANNEL_ID = "miaoxiang_task_notify"

    fun show(context: Context, title: String, content: String, requestCode: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "任务通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "电脑端任务完成/失败通知" }
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, requestCode, intent, piFlags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(requestCode, notification)
    }
}
