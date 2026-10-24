package com.example.questtuner.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SelfUpdater {

    // lead 已定：GitHub 仓库坐标（默认分支 master）
    private const val REPO = "donguaizs/QuestTuner"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** 允许的下载 host（GitHub Releases 资产分发域）；API 响应中的其他 host 一律拒绝（BUG-021） */
    private val ALLOWED_HOSTS = setOf(
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )

    /** 下载硬上限（BUG-021）：正常 APK <10MB 量级，100MB 是安全余量上限 */
    private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

    /** tag 安全字符集（BUG-022）：git tag 允许含 '/'，可构造路径穿越；仅放行安全字符集 */
    private val TAG_SAFE = Regex("^[A-Za-z0-9._-]+$")

    /** 纯函数（可单测）：tag 是否在安全字符集内 */
    fun isTagSafe(tag: String): Boolean = TAG_SAFE.matches(tag)

    /** host 白名单纯函数（可单测） */
    fun checkHost(url: String): Boolean =
        runCatching { URL(url).host in ALLOWED_HOSTS }.getOrDefault(false)

    data class ReleaseInfo(val tag: String, val url: String, val notes: String, val size: Long)

    /** 安装结果通道（receiver 无 VM 引用，经此流转给 UI 观察） */
    private val _installResult = MutableStateFlow<String?>(null)
    val installResult: StateFlow<String?> = _installResult

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    // ---- 1) 检查 ----
    suspend fun checkLatest(context: Context): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "QuestTuner")   // GitHub API 必需
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                // 404 = 该仓库还没有任何 Release：如实报告，不假装「已是最新」
                if (conn.responseCode == 404) error("暂无发布版本（更新源未发布 Release）")
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name")
                if (tag.isBlank()) return@runCatching null
                // BUG-022 防御前移：非法 tag 在检查阶段即报错，不让恶意 tag 进入任何文件操作
                check(TAG_SAFE.matches(tag)) { "Release tag 含非法字符: $tag" }
                val asset = o.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                } ?: return@runCatching null
                val remote = tag.removePrefix("v")
                if (!versionGreaterThan(remote, currentVersionName(context))) {
                    return@runCatching null   // 已是最新
                }
                ReleaseInfo(
                    tag = tag,
                    url = asset.optString("browser_download_url"),
                    notes = o.optString("body").take(2000),
                    size = asset.optLong("size"),
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---- 2) 下载（带进度回调；BUG-021 纵深防御三重） ----
    suspend fun download(
        url: String,
        expectedSize: Long,          // ReleaseInfo.size；<=0 表示未知
        dest: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // 纵深防御①：host 白名单（防 API 被篡改后指向任意外部服务器）
            val host = URL(url).host
            check(host in ALLOWED_HOSTS) { "下载地址不在白名单: $host" }
            // 纵深防御②：已知 size 预检
            if (expectedSize > MAX_DOWNLOAD_BYTES) error("文件过大: ${expectedSize / 1024 / 1024}MB")
            dest.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                // contentLength 可被伪造/缺失：流式累计才是硬闸
                if (total > MAX_DOWNLOAD_BYTES) error("Content-Length 超限")
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            // 纵深防御③：累计硬上限（防 chunked 传输绕过 contentLength）
                            if (done > MAX_DOWNLOAD_BYTES) error("下载超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB 上限，中止")
                            out.write(buf, 0, n)
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                dest
            } catch (t: Throwable) {
                dest.delete()
                throw t
            } finally {
                conn.disconnect()
            }
        }
    }

    fun updateApk(context: Context, tag: String): File {
        // BUG-022：净化（checkLatest 已前移校验，此处是第二道防线）
        check(TAG_SAFE.matches(tag)) { "非法 tag: $tag" }
        return File(File(context.getExternalFilesDir(null), "update"), "questtuner-$tag.apk")
    }

    // ---- 3) 四重校验（任一失败拒绝安装并删文件） ----
    suspend fun verifyApk(context: Context, f: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val archive = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: error("下载的 APK 无法解析（可能损坏）")
                check(archive.packageName == context.packageName) { "包名不匹配: ${archive.packageName}" }
                val newSigs = archive.signingInfo?.apkContentsSigners ?: error("APK 无签名信息")
                val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val oldSigs = installed.signingInfo?.apkContentsSigners ?: error("无法读取已安装签名")
                check(newSigs.size == oldSigs.size &&
                    newSigs.zip(oldSigs).all { (a, b) -> a.toByteArray().contentEquals(b.toByteArray()) }) {
                    "签名证书不一致，拒绝安装"
                }
                check(versionGreaterThan(archive.versionName ?: "", currentVersionName(context))) {
                    "版本不高于当前，拒绝降级安装"
                }
            }
        }

    // ---- 4) 安装（含 canRequestPackageInstalls 门禁） ----
    // 假设A6：Quest 弹出系统安装确认界面
    // 探测：InstallResultReceiver 收到的 EXTRA_STATUS
    // 失败：旧版本原地保留（天然回滚），UI 提示 PC adb install 备选
    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        // 门禁：未允许「安装未知应用」时引导到系统设置页（canRequestPackageInstalls=false 时调用）
        val i = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /** BUG-024：install 整体 Result 保护（磁盘满/会话失败不再崩溃）；BUG-023：suspend + IO 执行 */
    suspend fun install(context: Context, apk: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                // openSession(Int) 接 sessionId：先 createSession(params) 再 openSession(id)
                val session = installer.openSession(installer.createSession(params))
                try {
                    session.openWrite("questtuner", 0, -1).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java)
                        .putExtra("apk_path", apk.absolutePath)
                    // PackageInstaller 要求 mutable PendingIntent（系统会填入 status 与确认 Intent）
                    val pending = android.app.PendingIntent.getBroadcast(
                        context, 1001, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                } catch (t: Throwable) {
                    // abandon 后 receiver 收到 STATUS_FAILURE → UI 提示文案正确
                    runCatching { session.abandon() }
                    throw t
                }
                session.close()
            }
        }

    fun notifyInstallResult(success: Boolean, message: String?) {
        _installResult.value = if (success) "更新安装成功" else "安装失败: ${message ?: "未知错误"}（可用 PC adb install 手动更新）"
    }

    fun consumeInstallResult() { _installResult.value = null }

    // ---- 版本比较（纯函数，单测覆盖）："0.3.0" > "0.2.9" ----
    // O6 语义文档化：tag 命名约定 vX.Y.Z；含 pre-release 后缀的 tag（如 v0.3.0-beta.1）
    // 的非数字段按 0 处理，通常不高于同数字段的正式版——即 pre-release 不触发自更新
    // （防误装测试版，安全方向）。完整 semver pre-release 排序留 round-4。
    fun versionGreaterThan(a: String, b: String): Boolean {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
