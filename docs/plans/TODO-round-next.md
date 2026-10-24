# TODO（round-next）

## 发布检查单（每次打 tag 前执行，源自 BUG-043 教训）
1. `app/build.gradle.kts`：versionCode +1、versionName 按语义化递增
2. 恒等式核对：即将打的 tag 名 == "v" + versionName（自更新比较用 versionName）
3. `assembleRelease` 后 `aapt2 dump badging app-release.apk | grep versionName` 确认内嵌值
4. tag → push → GitHub Release：资产 APK 必须来自该 tag 的构建（tag 名进 APK 内嵌版本的对齐）
5. 换 release keystore 前重读 PLAN-round2 P3 的 TODO(lead)（换签 = 已装用户自更新校验失败，需迁移策略）

## 遗留技术项（延后池）
- R4-O7 adbStatus 覆盖链去重（UI 状态层重构时）
- R5-O1 选择器图标通道 + InstalledApp 稳定化（选择器虚拟化时）
- R5-O2 双源标记（内存/持久）收窄评估（SecureAutomation 重构时）
- OVR Metrics 三常量实机校准（A7，PC 脚本见 PLAN-round2）
