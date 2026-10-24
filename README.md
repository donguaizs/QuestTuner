# QuestTuner — Quest 3 参数调节工具

类似 QGO / Quest助手 的头显端调参应用。APK 直接安装在 Quest 3 头显内部，通过
**本机 ADB（127.0.0.1:5555）** 执行 `setprop` 调节系统参数，无需 root、无需电脑常驻。

## 功能

| 功能 | 系统属性 | 说明 |
|---|---|---|
| 刷新率 72/90/120Hz | `debug.oculus.refreshRate` | 全局或按应用 |
| 渲染纹理分辨率 | `debug.oculus.textureWidth/Height` | 0.5x–2.0x 缩放 |
| CPU/GPU 性能等级 0–4 | `debug.oculus.cpuLevel / gpuLevel` | 省电 ↔ 性能 |
| 按应用 Profile | — | JSON 存储，一键应用/删除 |
| 恢复默认 | — | 清空全部调参属性 |

## 构建方法（本机 Windows）

工具链已下载到工作区，无需安装 Android Studio：

```powershell
$env:JAVA_HOME = "F:\workspace\toolchain\jdk-extract\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "F:\workspace\toolchain\gradle-home"
F:\workspace\toolchain\gradle-extract\gradle-8.7\bin\gradle.bat `
  -p F:\workspace\QuestTuner :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 安装到 Quest 3

1. 头显：设置 → 系统 → 开发者选项 → 打开 **USB / 无线调试**（需先在手机 App 配对过头显或已开启开发者模式）
2. PC 用 USB 连接 Quest 3：
   ```powershell
   & "F:\Program Files\Android\Android_SDK\platform-tools\adb.exe" devices
   & "F:\Program Files\Android\Android_SDK\platform-tools\adb.exe" install -r app-debug.apk
   ```
3. 头显内戴上头显 → 应用库 → 右上角筛选「未知来源」→ 打开 **QuestTuner**
4. 首次点击「连接」时，头显里会弹出「是否允许调试」对话框 → 勾选“始终允许” → 允许
5. 之后所有调节无需电脑

## 重要说明

- **刷新率属性在部分固件版本（v74+）可能被 Meta 收紧**，若不生效请反馈固件版本。
- 调节刷新率后建议用「刷新状态」验证 `debug.oculus.refreshRate` 实际值。
- textureWidth/Height 数值参考 Quest 3 默认每眼 1680×1760，上限 3072×3208。
- 本应用不修改系统分区、不持久化任何系统属性（`setprop` 重启失效），可随时用「恢复默认」或重启头显还原。

## 项目结构

```
QuestTuner/
├── app/src/main/java/com/example/questtuner/
│   ├── adb/          # 纯 Kotlin 实现的 ADB 客户端协议（CNXN/AUTH/shell）
│   ├── tuner/        # 调参指令映射、Profile 存储、应用扫描
│   ├── ui/           # Jetpack Compose UI + ViewModel
│   └── MainActivity.kt
├── local.properties  # SDK 路径
└── build.gradle.kts  # AGP 8.5.2 + Kotlin 2.0 + Compose BOM 2024.06
```
