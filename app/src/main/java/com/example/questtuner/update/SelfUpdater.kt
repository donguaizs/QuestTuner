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

    // ---- 2) 下载（带进度回调） ----
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
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

    fun updateApk(context: Context, tag: String): File =
        File(File(context.getExternalFilesDir(null), "update"), "questtuner-$tag.apk")

    // ---- 3) 四重校验（任一失败拒绝安装并删文件） ----
    fun verifyApk(context: Context, f: File): Result<Unit> = runCatching {
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

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // openSession(Int) 接 sessionId：先 createSession(params) 再 openSession(id)
        val session = installer.openSession(installer.createSession(params))
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
        session.close()
    }

    fun notifyInstallResult(success: Boolean, message: String?) {
        _installResult.value = if (success) "更新安装成功" else "安装失败: ${message ?: "未知错误"}（可用 PC adb install 手动更新）"
    }

    fun consumeInstallResult() { _installResult.value = null }

    // ---- 版本比较（纯函数，单测覆盖）："0.3.0" > "0.2.9" ----
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
