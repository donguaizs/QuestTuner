package com.example.questtuner.tuner

import android.content.Context
import android.provider.Settings

/**
 * Private DNS 控制（QGO 同源：Settings.Global 两键）。
 * 假设A8：Quest 遵循 AOSP private_dns_mode / private_dns_specifier 行为。
 * 探测：写入后回读两键。
 * 失败：还原写入前旧值，返回错误信息。
 */
object PrivateDns {
    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"

    sealed class DnsResult {
        object Ok : DnsResult()
        object NoPermission : DnsResult()
        data class Rejected(val detail: String) : DnsResult()
    }

    fun currentMode(context: Context): String? =
        Settings.Global.getString(context.contentResolver, MODE)

    fun currentSpecifier(context: Context): String? =
        Settings.Global.getString(context.contentResolver, SPECIFIER)

    fun setHostname(context: Context, host: String): DnsResult {
        if (!SecureAutomation.hasWriteSecureSettings(context)) return DnsResult.NoPermission
        val cr = context.contentResolver
        val oldMode = currentMode(context)
        val oldSpec = currentSpecifier(context)
        return try {
            Settings.Global.putString(cr, MODE, "hostname")
            Settings.Global.putString(cr, SPECIFIER, host)
            // 回读探测
            val m = Settings.Global.getString(cr, MODE)
            val s = Settings.Global.getString(cr, SPECIFIER)
            if (m == "hostname" && s == host) DnsResult.Ok
            else {
                // 失败自动还原，不留半套状态
                Settings.Global.putString(cr, MODE, oldMode ?: "off")
                Settings.Global.putString(cr, SPECIFIER, oldSpec ?: "")
                DnsResult.Rejected("回读不一致（mode=$m）")
            }
        } catch (e: Exception) {
            runCatching {
                Settings.Global.putString(cr, MODE, oldMode ?: "off")
                Settings.Global.putString(cr, SPECIFIER, oldSpec ?: "")
            }
            DnsResult.Rejected(e.message ?: "unknown error")
        }
    }

    fun off(context: Context): DnsResult {
        if (!SecureAutomation.hasWriteSecureSettings(context)) return DnsResult.NoPermission
        return try {
            Settings.Global.putString(context.contentResolver, MODE, "off")
            if (Settings.Global.getString(context.contentResolver, MODE) == "off") DnsResult.Ok
            else DnsResult.Rejected("回读不一致")
        } catch (e: Exception) {
            DnsResult.Rejected(e.message ?: "unknown error")
        }
    }
}
