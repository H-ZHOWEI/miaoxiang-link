package com.updaterkit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用内更新（Android）—— 一行接入版。
 *
 * 设计约束全部来自实战踩坑，改动前请先读 README 的「为什么这么写」：
 * 1. 每次打开 App（onResume）都检查，发现新版直接弹窗；**禁止加 12 小时冷却**，检查失败也绝不写冷却时间；
 * 2. 检查用独立短超时客户端，直连失败自动换镜像重试，避免启动瞬间网络没就绪导致静默失败；
 * 3. 「已是最新」自动检查完全静默；手动检查必须给反馈，且失败要说清是失败而不是「已是最新」；
 * 4. 「跳过此版本」只对当前版本生效，新版本发布后自动重新提醒；
 * 5. 下载多镜像轮换 + 每地址重试 + 大小校验，安装走 FileProvider。
 *
 * 用法：
 * ```kotlin
 * // Application.onCreate()
 * UpdaterKit.init(UpdaterKit.Config(owner = "H-ZHOWEI", repo = "BiliListen", appName = "BiliListen"))
 *
 * // 首页 Activity
 * override fun onResume() { super.onResume(); UpdaterKit.autoCheck(this) }
 *
 * // 「关于」页的检查更新按钮
 * UpdaterKit.checkManually(this)
 * ```
 */
object UpdaterKit {

    /**
     * @param owner         GitHub 用户名 / 组织名
     * @param repo          GitHub 仓库名（Release 就发在这个仓库）
     * @param appName       展示用名称，也用作 UA 与安装包文件名前缀
     * @param assetSuffix   Release 里安装包的扩展名，默认 `.apk`
     * @param assetKeyword  Release 里有多个资产时用来筛选的关键词（如 `arm64`），空=取第一个 APK
     * @param useMirrors    是否启用国内镜像兜底（默认开）
     * @param extraCheckUrls 自定义版本清单地址，非空时替代 GitHub API（需返回 GitHub Release 同结构 JSON）
     * @param autoCheckOnResume autoCheck() 是否真的发请求（想临时关掉自动检查时置 false）
     * @param respectSkipVersion 是否尊重用户点过的「跳过此版本」
     */
    data class Config(
        val owner: String,
        val repo: String,
        val appName: String = repo,
        val assetSuffix: String = ".apk",
        val assetKeyword: String = "",
        val useMirrors: Boolean = true,
        val extraCheckUrls: List<String> = emptyList(),
        val autoCheckOnResume: Boolean = true,
        val respectSkipVersion: Boolean = true
    ) {
        internal val userAgent: String
            get() = appName.ifBlank { repo }.replace(" ", "")
    }

    @Volatile
    private var config: Config? = null

    /** 本次进程已弹过提示的版本：切后台回来不反复弹，杀掉重开（每次运行）会重新提醒一次 */
    private var promptedVersion: String = ""

    /** 正在检查，避免 onResume 连点/连续回调并发请求 */
    private var checking = false

    /** 弹窗是否正在显示 */
    private var dialogShowing = false

    private val _progress = MutableStateFlow(0f)

    /** 下载进度 0f..1f，-1f 失败；接入方做自定义 UI 时可以订阅 */
    val progress: StateFlow<Float> = _progress

    fun init(config: Config) {
        this.config = config
        promptedVersion = ""
        checking = false
        dialogShowing = false
    }

    fun isInitialized(): Boolean = config != null

    /** 当前安装包 versionName */
    fun currentVersion(context: Context): String = UpdateCore.currentVersionName(context)

    /** Release 页面地址（浏览器下载兜底用） */
    fun downloadPage(context: Context): String {
        val cfg = config ?: return ""
        return UpdateCore.pageUrl(cfg)
    }

    fun openDownloadPage(context: Context) {
        val url = downloadPage(context)
        if (url.isBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast(context, "无法打开浏览器：$url")
        }
    }

    fun versionCompare(a: String, b: String): Int = UpdateCore.compareVersion(a, b)

    // ---------------- 检查 ----------------

    /**
     * 每次 onResume 调用即可。内部做了：
     * 窗口就绪后才发请求（否则弹窗会被吞）、STARTED 才弹、同版本本次进程只弹一次、
     * 检查中不重复发、跳过版本不打扰、失败静默（下次回前台再试）。
     */
    fun autoCheck(activity: Activity) {
        val cfg = config ?: return
        if (!cfg.autoCheckOnResume) return
        if (checking || dialogShowing) return
        if (activity.isFinishing) return
        activity.window?.decorView?.post {
            if (!activity.isFinishing) startCheck(activity, cfg, manual = false)
        }
    }

    /** 手动「检查更新」：无论有无新版都给反馈，失败/已最新要说得清 */
    fun checkManually(activity: Activity) {
        val cfg = config ?: run {
            toast(activity, "更新模块未初始化，请先调用 UpdaterKit.init()")
            return
        }
        if (checking) {
            toast(activity, "正在检查更新…")
            return
        }
        startCheck(activity, cfg, manual = true)
    }

    private fun startCheck(activity: Activity, cfg: Config, manual: Boolean) {
        val owner = activity as? ComponentActivity ?: return
        if (owner.isFinishing || owner.isDestroyed) return
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (checking) return
        if (dialogShowing && !manual) return
        checking = true
        owner.lifecycleScope.launch {
            try {
                val result = UpdateCore.check(owner, cfg, attempts = if (manual) 1 else 3)
                when (result) {
                    is CheckResult.Latest -> {
                        if (manual) toast(owner, "已是最新版本 v${UpdateCore.currentVersionName(owner)}")
                    }

                    is CheckResult.Failed -> {
                        // 自动检查失败完全静默，也绝不写冷却时间戳——下次打开继续试
                        if (manual) toast(owner, "检查更新失败：${result.reason}")
                    }

                    is CheckResult.Available -> {
                        val info = result.info
                        if (!manual) {
                            if (cfg.respectSkipVersion &&
                                UpdateStore.skippedVersion(owner) == info.version
                            ) return@launch
                            if (promptedVersion == info.version) return@launch
                        }
                        if (dialogShowing) return@launch
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                        promptedVersion = info.version
                        dialogShowing = true
                        try {
                            UpdateDialog.show(owner, info, cfg)
                        } catch (e: Exception) {
                            dialogShowing = false
                        }
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    internal fun onDialogDismissed() {
        dialogShowing = false
    }

    internal fun toast(context: Context, msg: String) {
        try {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
