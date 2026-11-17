package com.updaterkit

import android.content.Context

/** 「跳过此版本」的本地记录：只记版本号，新版本发布后自然失效 */
internal object UpdateStore {

    private const val PREFS = "updaterkit_prefs"
    private const val KEY_SKIPPED = "skipped_version"

    fun skippedVersion(context: Context): String = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED, "").orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun setSkippedVersion(context: Context, version: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SKIPPED, version).apply()
        } catch (_: Exception) {
        }
    }

    fun clearSkippedVersion(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_SKIPPED).apply()
        } catch (_: Exception) {
        }
    }
}
