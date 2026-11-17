package com.updaterkit

import com.updaterkit.UpdaterKit.Config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 远端最新版本信息 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val pageUrl: String
)

/** 检查结果：区分「已是最新 / 有新版 / 失败」，手动检查时才能给出正确提示 */
sealed class CheckResult {
    data object Latest : CheckResult()
    data class Available(val info: UpdateInfo) : CheckResult()
    data class Failed(val reason: String) : CheckResult()
}

/** 下载结果 */
sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Failure(val reason: String) : DownloadResult()
}

/**
 * 更新能力的内核：查版本 / 下载 APK / 拉起系统安装器。
 *
 * 这里刻意不用任何业务层的 OkHttp 客户端，全部用本模块自己的客户端：
 * - 检查更新用「短超时」客户端，判断快、失败快，避免启动时网络未就绪把请求卡死（历史坑）
 * - 下载用「长超时」客户端，大文件弱网下才不会被 30s 读超时打断
 */
internal object UpdateCore {

    private const val API_HOST = "https://api.github.com/"
    private const val GITHUB_HOSTS = "github.com|githubusercontent.com|github-releases"

    /** 检查更新的镜像前缀（直连失败时按顺序兜底） */
    val CHECK_MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://gh.api.99988866.xyz/"
    )

    /** 下载 APK 的镜像前缀（release-assets.githubusercontent.com 在部分网络下被墙/限速） */
    val DOWNLOAD_MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
        "https://gh.api.99988866.xyz/"
    )

    private val checkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 下载进度：0f..1f，-1f 表示失败 */
    val progress = MutableStateFlow(0f)

    // ---------------- 版本 ----------------

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** 1.10 > 1.9；缺位按 0 处理 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun latestApiUrl(cfg: Config): String = "${API_HOST}repos/${cfg.owner}/${cfg.repo}/releases/latest"

    fun pageUrl(cfg: Config): String = "https://github.com/${cfg.owner}/${cfg.repo}/releases/latest"

    /** 生成检查候选地址：自定义清单优先，GitHub API 再挂镜像 */
    private fun checkUrls(cfg: Config): List<String> {
        val bases = if (cfg.extraCheckUrls.isNotEmpty()) cfg.extraCheckUrls else listOf(latestApiUrl(cfg))
        val out = ArrayList<String>()
        for (u in bases) {
            out.add(u)
            if (cfg.useMirrors && u.startsWith(API_HOST)) {
                for (m in CHECK_MIRRORS) out.add(m + u)
            }
        }
        return out.distinct()
    }

    suspend fun check(context: Context, cfg: Config, attempts: Int): CheckResult =
        withContext(Dispatchers.IO) {
            val current = currentVersionName(context)
            val urls = checkUrls(cfg)
            val n = attempts.coerceIn(1, urls.size * 2)
            var lastReason = "网络连接失败，请检查网络后重试"
            for (i in 0 until n) {
                try {
                    when (val r = fetch(urls[i % urls.size], cfg, current)) {
                        is CheckResult.Latest -> return@withContext CheckResult.Latest
                        is CheckResult.Available -> return@withContext r
                        is CheckResult.Failed -> lastReason = r.reason
                    }
                } catch (e: Exception) {
                    lastReason = mapError(e)
                    if (i < n - 1) delay(800L)
                }
            }
            CheckResult.Failed(lastReason)
        }

    /** 请求单个地址：返回 Latest / Available；抛异常表示这次请求不算数，换地址重试 */
    private fun fetch(url: String, cfg: Config, current: String): CheckResult {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "${cfg.userAgent}-Updater")
            .get()
            .build()
        checkClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            if (!body.trimStart().startsWith("{")) throw IOException("not json")
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val latest = tag.trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank()) throw IOException("no tag")
            if (compareVersion(latest, current) <= 0) return CheckResult.Latest

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (!name.endsWith(cfg.assetSuffix, ignoreCase = true)) continue
                    if (cfg.assetKeyword.isNotBlank() &&
                        !name.contains(cfg.assetKeyword, ignoreCase = true)
                    ) continue
                    val u = a.optString("browser_download_url")
                    if (u.isBlank()) continue
                    apkUrl = u
                    size = a.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl.isBlank()) {
                // 有新版但没找到安装包：当成「无更新」，别用错误弹窗骚扰用户
                return CheckResult.Latest
            }
            return CheckResult.Available(
                UpdateInfo(
                    version = latest,
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    sizeBytes = size,
                    pageUrl = json.optString("html_url").ifBlank { pageUrl(cfg) }
                )
            )
        }
    }

    private fun mapError(e: Throwable): String = when (e) {
        is UnknownHostException -> "无法连接网络，请检查网络或代理设置"
        is SocketTimeoutException -> "连接超时，GitHub 可能被拦截，可稍后重试"
        is IOException -> {
            val msg = e.message.orEmpty()
            when {
                msg.contains("HTTP 403") -> "GitHub 接口被限流，请稍后再试"
                msg.contains("HTTP 404") -> "仓库或 Release 不存在，请检查 owner/repo 配置"
                msg.contains("not json") -> "服务器返回的不是版本信息（可能被网络劫持）"
                msg.startsWith("HTTP") -> "检查失败（$msg）"
                else -> "网络请求失败，请稍后重试"
            }
        }
        else -> "检查失败，请稍后重试"
    }

    // ---------------- 下载 ----------------

    private fun downloadUrls(cfg: Config, original: String): List<String> {
        val t = original.trim()
        if (!cfg.useMirrors) return listOf(t)
        val out = ArrayList<String>()
        out.add(t)
        val lower = t.lowercase()
        if (GITHUB_HOSTS.split("|").any { lower.contains(it) }) {
            for (m in DOWNLOAD_MIRRORS) out.add(m + t)
        }
        return out.distinct()
    }

    suspend fun download(context: Context, cfg: Config, info: UpdateInfo): DownloadResult =
        withContext(Dispatchers.IO) {
            progress.value = 0f
            val dir = File(context.cacheDir, "updaterkit").apply { mkdirs() }
            val safeName = "${cfg.userAgent.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${info.version}${cfg.assetSuffix}"
            val target = File(dir, safeName)

            // 已有同版本、大小对得上的包，直接复用
            if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
                progress.value = 1f
                return@withContext DownloadResult.Success(target)
            }

            var lastReason = "下载失败，请检查网络后重试"
            for (url in downloadUrls(cfg, info.apkUrl)) {
                repeat(2) {
                    val r = tryOne(url, target, info.sizeBytes)
                    when (r) {
                        is DownloadResult.Success -> {
                            progress.value = 1f
                            return@withContext r
                        }
                        is DownloadResult.Failure -> lastReason = r.reason
                    }
                }
            }
            progress.value = -1f
            DownloadResult.Failure(lastReason)
        }

    private fun tryOne(url: String, target: File, expectedSize: Long): DownloadResult {
        val req = Request.Builder().url(url).get().build()
        val resp = try {
            downloadClient.newCall(req).execute()
        } catch (e: Exception) {
            return DownloadResult.Failure(mapError(e))
        }
        if (!resp.isSuccessful) {
            try { resp.body?.close() } catch (_: Exception) { }
            return DownloadResult.Failure("下载失败（HTTP ${resp.code}）")
        }
        val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: expectedSize
        var ok = false
        try {
            resp.body?.byteStream()?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                    out.flush()
                }
            }
            ok = target.length() >= 1024L && (expectedSize <= 0L || target.length() == expectedSize)
            if (!ok) return DownloadResult.Failure("安装包校验失败（大小不符），已删除，请重试")
        } catch (e: Exception) {
            return DownloadResult.Failure(mapError(e))
        } finally {
            if (!ok) try { target.delete() } catch (_: Exception) { }
        }
        return DownloadResult.Success(target)
    }

    // ---------------- 安装 ----------------

    fun authority(context: Context) = "${context.packageName}.updaterkit.fileprovider"

    /** Android 8+ 是否已允许「安装未知应用」 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** 拉起系统安装器；成功返回 null，失败返回中文原因 */
    fun install(context: Context, apk: File): String? = try {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        null
    } catch (e: Exception) {
        "无法打开安装程序（${e.javaClass.simpleName}），请到浏览器手动下载"
    }
}
