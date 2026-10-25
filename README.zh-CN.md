# QuestTuner

[English](README.md) | **简体中文**

Quest 3 头显端参数调节工具。APK 直接安装在头显内部，通过**纯 Kotlin 实现的 ADB 客户端**连接本机 ADB 服务（`127.0.0.1:5555`）执行 `setprop` 调节系统参数。核心调参**无需 root、无需电脑**；一条可选的 PC 一次性命令可解锁自动化功能（见「可选授权与自动化」）。

## 功能

| 功能 | 系统属性 | 说明 |
|---|---|---|
| 刷新率 72 / 90 / 120 Hz | `debug.oculus.refreshRate` | 全局生效 |
| 渲染纹理分辨率 | `debug.oculus.textureWidth / textureHeight` | 0.5x–2.0x 缩放 |
| CPU / GPU 性能等级 0–4 | `debug.oculus.cpuLevel / gpuLevel` | 省电 ↔ 性能 |
| 透视精度 | `debug.oculus.highPrecisionPassthrough` | 0 / 1 |
| 按应用 Profile | — | JSON 存储，一键应用 / 编辑 / 删除 |
| 恢复默认 | — | 清空全部调参属性 |
| 首次配置向导 | — | 首次启动引导：机制说明 / 授权命令复制 / 开关预选 |
| 按前台应用自动应用 | 无障碍服务（仅读包名） | 前台切换自动应用 / 退出回滚；纯本地 |
| 授权自动化 | WRITE_SECURE_SETTINGS | 自动开无线调试 / 自动启用无障碍（默认关） |
| 应用内自更新 | GitHub Releases | 四重校验（解析 / 包名 / 签名 / 防降级），手动触发 |
| Private DNS 开关 | Settings.Global | 可选，主机名校验 + 失败还原 |
| OVR Metrics 开关 | 系统广播 | 需已安装 OVR Metrics Tool |
| 开机恢复默认 | BOOT_COMPLETED | 可选开关，尽力而为 |

## 工作原理

应用内嵌了一个从零实现的 ADB 客户端（不依赖任何外部库）：

```
adb/    CNXN/AUTH 握手、RSA-2048 密钥管理、android_pubkey 公钥编码
        （524 字节 wire 格式、小端序）、shell v2 流、报文 CRC
tuner/  setprop 指令映射、Profile 存储（原子化 JSON 写入）、
        已装应用扫描（VR intent-filter 识别）
update/ 应用内自更新：GitHub Releases 检查 / 下载、APK 四重校验、
        PackageInstaller 安装会话
ui/     Jetpack Compose UI + ViewModel（MVVM）
```

全部操作都在头显上完成：应用作为普通 ADB 客户端连接 `127.0.0.1:5555` 的 `adbd`，用自己生成的 RSA 密钥授权（首次会弹出一次「是否允许 USB 调试」对话框），之后以 `shell` 用户身份执行 `setprop`——该用户对 `debug.*` 属性有写权限。

## 构建

环境要求：**JDK 17**、**Android SDK**（compileSdk 34）。

```bash
.\gradlew.bat :app:assembleDebug        # Windows（Linux/macOS 用 ./gradlew）
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库已入库 Gradle wrapper（8.7，SHA-256 已固定），首次运行会自动下载 Gradle——
需联网一次。完全离线环境可将 `gradle/wrapper/gradle-wrapper.properties` 中的
`distributionUrl` 指向本地 `file:///` 的 `gradle-8.7-bin.zip` 副本，或退回使用
预装的 Gradle 8.7（`gradle :app:assembleDebug`）。

## 安装到 Quest 3

1. 头显：**设置 → 系统 → 开发者选项 → 打开 USB / 无线调试**（需先开启开发者模式）
2. PC 通过 USB 连接头显：
   ```bash
   adb devices
   adb install -r app-debug.apk
   ```
3. 头显内：**应用库 → 筛选「未知来源」→ QuestTuner**
4. 首次点击「连接」时会弹出「是否允许 USB 调试」→ 勾选**始终允许** → **允许**
5. 之后所有操作无需电脑，头显独立完成

## 可选授权与自动化

核心调参全程无需电脑（首连授权框在头显上点「允许」即可）。一条可选的 PC 一次性命令可解锁额外自动化：

```bash
adb shell pm grant com.example.questtuner android.permission.WRITE_SECURE_SETTINGS
```

可解锁：连接时自动开启无线调试、自动启用无障碍服务、Private DNS 控制。

**无电脑替代路径**：系统设置 → 无障碍 → 手动开启 QuestTuner 服务——按前台应用自动应用同样可用。

所有自动化默认关闭，均为独立开关。

## 隐私

唯一的网络行为是你主动点击「检查更新」时访问 GitHub API（api.github.com）。Profile、调参、无障碍服务（只读前台应用包名，绝不读取屏幕内容）全部本地完成。无统计、无追踪、无数据上传。

## 重要说明

- `refreshRate` 属性在**较新固件（v74+）上可能已被 Meta 收紧**。若不生效，请反馈你的固件版本。
- 调节刷新率后建议用「刷新状态」验证 `debug.oculus.refreshRate` 的实际值。
- `textureWidth / textureHeight` 参考值：Quest 3 默认单眼 1680×1760，上限 3072×3208。
- **不做任何持久化**：`setprop` 值重启即失效，随时可用「恢复默认」或重启头显还原。
- 应用内「检查更新」需在系统设置允许「安装未知应用」（头显上完成，无需电脑）。
- 本应用不修改系统分区。

## 项目结构

```
QuestTuner/
├── app/src/main/java/com/example/questtuner/
│   ├── adb/          # 纯 Kotlin 实现的 ADB 客户端协议（CNXN/AUTH/shell）
│   ├── tuner/        # 调参指令映射、Profile 存储、应用扫描
│   ├── update/       # 应用内自更新（Releases 检查、APK 校验、安装）
│   ├── ui/           # Jetpack Compose UI + ViewModel
│   └── MainActivity.kt
└── docs/             # QA 文档：bug 报告 / 修复方案 / 验证报告
```

## 免责声明

本项目仅用于调节个人自己的设备。所有改动仅本次开机有效、完全可逆。与 Meta 无关，风险自负。

---

QuestTuner v0.2.2 · Kotlin 2.0 · Jetpack Compose (BOM 2024.06) · minSdk 32（Quest 3 / Android 12L）
