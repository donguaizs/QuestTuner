package com.example.questtuner.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apkPath = intent.getStringExtra("apk_path")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 假设A6：拉起系统安装确认页；用户取消 → 旧版本保留
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apkPath?.let { File(it).delete() }   // 清理下载文件
                SelfUpdater.notifyInstallResult(true, null)
            }
            else -> {
                apkPath?.let { File(it).delete() }
                SelfUpdater.notifyInstallResult(
                    false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}
