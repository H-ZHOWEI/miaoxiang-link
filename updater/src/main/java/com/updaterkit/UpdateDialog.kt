package com.updaterkit

import com.updaterkit.UpdaterKit.Config

import android.app.Activity
import android.app.AlertDialog
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 更新弹窗：标题 / 版号 / 更新说明 / 进度条 / 状态行 + 立即更新·以后再说·跳过此版本。
 * 用系统 AlertDialog（不依赖 AppCompat / Material 主题），所以任何主题的 App 都能弹得出来。
 */
internal object UpdateDialog {

    fun show(activity: Activity, info: UpdateInfo, cfg: Config) {
        if (activity.isFinishing) return
        val owner = activity as? ComponentActivity ?: return

        val view: View = LayoutInflater.from(activity)
            .inflate(R.layout.updaterkit_dialog_update, null, false)
        val tvVersion = view.findViewById<TextView>(R.id.uk_version)
        val tvNotes = view.findViewById<TextView>(R.id.uk_notes)
        val pb = view.findViewById<ProgressBar>(R.id.uk_progress)
        val tvStatus = view.findViewById<TextView>(R.id.uk_status)

        tvVersion.text = "新版本 v${info.version}（当前 v${UpdateCore.currentVersionName(activity)}）"
        tvNotes.text = info.notes.ifBlank { "修复已知问题，优化使用体验。" }
        if (info.notes.length > 320) {
            tvNotes.movementMethod = ScrollingMovementMethod.getInstance()
        }

        var downloading = false
        var failed = false

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("以后再说", null)
            .setNeutralButton("跳过此版本", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            val btnUpdate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val btnNeutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            btnNeutral.setOnClickListener {
                if (failed) {
                    // 失败态：这个按钮变「重试」
                    failed = false
                    btnUpdate.text = "立即更新"
                    btnNeutral.text = "跳过此版本"
                    pb.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                    btnUpdate.performClick()
                    return@setOnClickListener
                }
                UpdateStore.setSkippedVersion(activity, info.version)
                UpdaterKit.toast(activity, "已跳过 v${info.version}，下个版本发布再提醒")
                dialog.dismiss()
            }

            btnUpdate.setOnClickListener {
                if (downloading) return@setOnClickListener
                if (failed) {
                    // 失败态：这个按钮变「浏览器下载」
                    UpdaterKit.openDownloadPage(activity)
                    return@setOnClickListener
                }
                // Android 8+ 必须用户单独允许「安装未知应用」
                if (!UpdateCore.canInstall(activity)) {
                    UpdaterKit.toast(activity, "请在本页打开「允许安装未知应用」，返回后再点一次「立即更新」")
                    UpdateCore.openInstallPermission(activity)
                    return@setOnClickListener
                }
                downloading = true
                failed = false
                btnUpdate.isEnabled = false
                btnNeutral.isEnabled = false
                pb.visibility = View.VISIBLE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "正在下载…"
                btnUpdate.text = "下载中"

                owner.lifecycleScope.launch {
                    val progressJob = launch {
                        UpdateCore.progress.collect { p ->
                            if (p >= 0f) {
                                pb.progress = (p * 100).toInt()
                                tvStatus.text = "正在下载 ${(p * 100).toInt()}%"
                            }
                        }
                    }
                    val result = UpdateCore.download(activity, cfg, info)
                    progressJob.cancel()
                    when (result) {
                        is DownloadResult.Failure -> {
                            downloading = false
                            failed = true
                            btnUpdate.isEnabled = true
                            btnNeutral.isEnabled = true
                            btnUpdate.text = "浏览器下载"
                            btnNeutral.text = "重试"
                            pb.visibility = View.GONE
                            tvStatus.text = result.reason
                        }

                        is DownloadResult.Success -> {
                            tvStatus.text = "下载完成，正在打开安装程序…"
                            val err = UpdateCore.install(activity, result.file)
                            if (err != null) {
                                downloading = false
                                failed = true
                                btnUpdate.isEnabled = true
                                btnNeutral.isEnabled = true
                                btnUpdate.text = "浏览器下载"
                                btnNeutral.text = "重试"
                                pb.visibility = View.GONE
                                tvStatus.text = err
                            } else {
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener { UpdaterKit.onDialogDismissed() }
        try {
            dialog.show()
        } catch (_: Exception) {
            // 窗口已失效（极端情况）：别让自动检查把 App 弄崩，下次回前台再试
            UpdaterKit.onDialogDismissed()
        }
    }
}
