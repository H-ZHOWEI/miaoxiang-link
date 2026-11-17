package com.miaoxiang.link

import android.content.Context
import android.content.SharedPreferences

/** 本地设置：配对码、通知开关、上次已读消息时间等。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("miaoxiang_link", Context.MODE_PRIVATE)

    var pairCode: String
        get() = sp.getString("pair_code", "") ?: ""
        set(value) = sp.edit().putString("pair_code", value.trim().uppercase()).apply()

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify_enabled", true)
        set(value) = sp.edit().putBoolean("notify_enabled", value).apply()

    var pollMinutes: Int
        get() = sp.getInt("poll_minutes", 15)
        set(value) = sp.edit().putInt("poll_minutes", value).apply()

    var lastSeenId: Long
        get() = sp.getLong("last_seen_id", 0L)
        set(value) = sp.edit().putLong("last_seen_id", value).apply()
}
