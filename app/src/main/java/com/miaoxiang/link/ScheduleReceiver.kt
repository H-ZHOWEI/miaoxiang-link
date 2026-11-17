package com.miaoxiang.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日定时触发：由 AlarmManager 到点唤醒，执行到期的签到任务，然后安排第二天的闹钟。
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleReceiver"
        const val ACTION_DAILY = "com.miaoxiang.link.DAILY_TASK"
        const val EXTRA_TASK_ID = "task_id"

        /** 为单个任务安排下一次（明天同一时刻）闹钟。 */
        fun scheduleNext(context: Context, task: AutoTask) {
            if (!task.enabled || !task.dailyEnabled) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, task.dailyHour)
                set(Calendar.MINUTE, task.dailyMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // 若今天的时刻已过，顺延到明天
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            val pi = pendingIntent(context, task.id)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
                Log.i(TAG, "任务「${task.name}」下次执行：${cal.time}")
            } catch (e: Exception) {
                Log.w(TAG, "安排闹钟失败", e)
            }
        }

        /** 取消某任务的闹钟。 */
        fun cancel(context: Context, taskId: String) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try { am.cancel(pendingIntent(context, taskId)) } catch (_: Exception) {}
        }

        /** App 启动 / 开机时，为所有启用定时的任务补注册闹钟。 */
        fun rescheduleAll(context: Context) {
            val prefs = Prefs(context)
            for (task in prefs.autoTasks) {
                cancel(context, task.id)
                scheduleNext(context, task)
            }
        }

        private fun pendingIntent(context: Context, taskId: String): android.app.PendingIntent {
            val intent = Intent(context, ScheduleReceiver::class.java).apply {
                action = ACTION_DAILY
                putExtra(EXTRA_TASK_ID, taskId)
            }
            val flag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            return android.app.PendingIntent.getBroadcast(
                context, taskId.hashCode(), intent, flag
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val prefs = Prefs(context)
        val task = prefs.findAutoTask(taskId) ?: return
        if (!task.enabled || !task.dailyEnabled) return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (task.lastRunDate == today) {
            Log.i(TAG, "任务「${task.name}」今天已执行过，跳过")
            scheduleNext(context, task)
            return
        }

        // 标记今天已执行（先标记，避免执行中重复闹钟导致跑两次）
        prefs.saveAutoTask(task.copy(lastRunDate = today))

        if (!GestureService.isEnabled()) {
            Notifier.show(context, "[自动签到] ${task.name}", "无障碍权限未开启，未执行", taskId.hashCode())
            scheduleNext(context, task.copy(lastRunDate = today))
            return
        }

        // 广播回调用 goAsync 在后台协程执行，避免阻塞主线程导致手势无法派发
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = TaskRunner.executeOnce(context.applicationContext, task, TaskRunner.SOURCE_SCHEDULE)
                Notifier.show(
                    context,
                    "[自动签到] ${task.name}",
                    if (result.first) "定时执行成功：${result.second}" else "定时执行失败：${result.second}",
                    taskId.hashCode()
                )
            } finally {
                scheduleNext(context, task.copy(lastRunDate = today))
                pending.finish()
            }
        }
    }
}
