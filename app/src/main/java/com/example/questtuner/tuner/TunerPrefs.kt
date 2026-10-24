package com.example.questtuner.tuner

import android.content.Context

/** 用户设置（全部默认关闭 = 开关隔离）。SharedPreferences：BootReceiver/无障碍服务需同步读。 */
class TunerPrefs private constructor(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("tuner_prefs", Context.MODE_PRIVATE)

    var autoWifiAdb: Boolean
        get() = sp.getBoolean(K_AUTO_WIFI_ADB, false)
        set(v) = sp.edit().putBoolean(K_AUTO_WIFI_ADB, v).apply()
    var autoA11y: Boolean
        get() = sp.getBoolean(K_AUTO_A11Y, false)
        set(v) = sp.edit().putBoolean(K_AUTO_A11Y, v).apply()
    var autoApplyProfile: Boolean
        get() = sp.getBoolean(K_AUTO_APPLY, false)
        set(v) = sp.edit().putBoolean(K_AUTO_APPLY, v).apply()
    var restoreOnBoot: Boolean
        get() = sp.getBoolean(K_RESTORE_ON_BOOT, false)
        set(v) = sp.edit().putBoolean(K_RESTORE_ON_BOOT, v).apply()
    var firstRunDone: Boolean
        get() = sp.getBoolean(K_FIRST_RUN_DONE, false)
        set(v) = sp.edit().putBoolean(K_FIRST_RUN_DONE, v).apply()
    var metricsOverlay: Boolean
        get() = sp.getBoolean(K_METRICS_OVERLAY, false)
        set(v) = sp.edit().putBoolean(K_METRICS_OVERLAY, v).apply()
    var metricsGraph: Boolean
        get() = sp.getBoolean(K_METRICS_GRAPH, false)
        set(v) = sp.edit().putBoolean(K_METRICS_GRAPH, v).apply()
    var metricsStat: Boolean
        get() = sp.getBoolean(K_METRICS_STAT, false)
        set(v) = sp.edit().putBoolean(K_METRICS_STAT, v).apply()
    /** BUG-032：「无线调试由本应用开启」持久标记（进程被杀后启动对账用） */
    var wifiAdbByUs: Boolean
        get() = sp.getBoolean(K_WIFI_ADB_BY_US, false)
        set(v) = sp.edit().putBoolean(K_WIFI_ADB_BY_US, v).apply()

    companion object {
        private const val K_AUTO_WIFI_ADB = "auto_wifi_adb"
        private const val K_AUTO_A11Y = "auto_a11y"
        private const val K_AUTO_APPLY = "auto_apply_profile"
        private const val K_RESTORE_ON_BOOT = "restore_on_boot"
        private const val K_FIRST_RUN_DONE = "first_run_done"
        private const val K_METRICS_OVERLAY = "metrics_overlay"
        private const val K_METRICS_GRAPH = "metrics_graph"
        private const val K_METRICS_STAT = "metrics_stat"
        private const val K_WIFI_ADB_BY_US = "wifi_adb_by_us"
        fun get(context: Context): TunerPrefs = TunerPrefs(context)
    }
}
