package com.example.questtuner.tuner

/**
 * Quest 3 调参指令映射（与 QGO / Quest助手 同源的系统属性）。
 *
 * 这些 setprop 通过 adbd shell 执行，adbd 是 shell 用户（UID 2000），
 * 对 hw.* / debug.* / persist. 前缀的属性有写权限（见 init property_contexts）。
 *
 * 注意：Quest 固件不同版本行为有差异，部分属性在 v69+ 需要额外条件，
 * 应用后用 dumpsys 验证实际生效值。
 */
object TuningCommands {

    /** 刷新率：72 / 90 / 120 Hz */
    fun setRefreshRate(hz: Int): List<String> = listOf(
        "setprop debug.oculus.refreshRate $hz",
    )

    /** 读取当前刷新率 */
    fun getRefreshRate(): String = "dumpsys display | grep -E 'refreshRate|RenderRate' | head -5"

    /**
     * 渲染分辨率缩放（纹理分辨率比例）。
     * QGO 用法：setprop debug.oculus.textureWidth / textureHeight 设定像素预算。
     */
    fun setResolution(width: Int, height: Int): List<String> = listOf(
        "setprop debug.oculus.textureWidth $width",
        "setprop debug.oculus.textureHeight $height",
    )

    fun getResolution(): String =
        "dumpsys vr | grep -iE 'texture|resolution' | head -8"

    /**
     * CPU/GPU 性能等级 (0-4)：
     *   debug.oculus.cpuLevel / gpuLevel
     * 实际生效需要 ANGLE/Powerprof 模块配合，部分固件仅对游戏进程生效。
     */
    fun setCpuGpuLevel(cpu: Int, gpu: Int): List<String> = listOf(
        "setprop debug.oculus.cpuLevel $cpu",
        "setprop debug.oculus.gpuLevel $gpu",
    )

    fun getCpuGpuLevel(): String = "getprop debug.oculus.cpuLevel; getprop debug.oculus.gpuLevel"

    /** 平滑透视 (Passthrough) 分辨率档位 */
    fun setPassthroughQuality(hi: Boolean): List<String> = listOf(
        "setprop debug.oculus.highPrecisionPassthrough ${if (hi) 1 else 0}",
    )

    /** 全局默认（清除所有调参属性恢复系统默认） */
    fun resetAll(): List<String> = listOf(
        "setprop debug.oculus.refreshRate \"\"",
        "setprop debug.oculus.textureWidth \"\"",
        "setprop debug.oculus.textureHeight \"\"",
        "setprop debug.oculus.textureHeight \"\"",
        "setprop debug.oculus.cpuLevel \"\"",
        "setprop debug.oculus.gpuLevel \"\"",
    )

    /** 验证当前状态（一条命令聚合读取） */
    fun statusCommand(): String = listOf(
        "getprop debug.oculus.refreshRate",
        "getprop debug.oculus.textureWidth",
        "getprop debug.oculus.textureHeight",
        "getprop debug.oculus.cpuLevel",
        "getprop debug.oculus.gpuLevel",
        "getprop debug.oculus.highPrecisionPassthrough",
    ).joinToString("; echo ---; ")
}
