# TODO（round-next）

## 发布检查单（每次打 tag 前执行，源自 BUG-043/BUG-044 教训）
1. `app/build.gradle.kts`：versionCode +1、versionName 按语义化递增
2. 恒等式核对：即将打的 tag 名 == "v" + versionName（自更新比较用 versionName）
3. `assembleRelease` 后 `aapt2 dump badging app-release.apk | grep versionName` 确认内嵌值
4. **资产一致性验证（BUG-044 修订版，可执行）**：Gradle 构建非字节级可复现（zip 容器层差异），
   **禁止用整体 SHA-256 比对**。正确做法（R7-O1）：
   a. 上传后从 Release 页**下载回**资产，与本地构建产物比对 SHA-256（防上传截断/替换）
   b. 或用 GitHub API 的 asset digest 字段核对
   c. 语义等价核验：`aapt2 dump badging` 两侧一致 + `apksigner verify --print-certs` 证书一致
5. 换 release keystore 前重读 PLAN-round2 P3 的 TODO(lead)（换签 = 已装用户自更新校验失败，需迁移策略）

## 遗留技术项（延后池）
- R4-O7 adbStatus 覆盖链去重（UI 状态层重构时；round-5 034 修复后收益进一步降低）
- R5-O1 选择器图标通道：**round-8 建议先做半步**——图标 LruCache（~30 行，滚动卡顿最可能源）；虚拟化继续延后
- ~~R5-O2 双源标记收窄~~（**round-7 审计关闭**：round-4 对账已结构性解决双源漂移动机，再收窄零收益）
- OVR Metrics 三常量实机校准（A7：转实机任务，已并入 BUG-REPORT-round6 附录实机总清单）
- keystore 迁移：转 lead 产品决策项（当前 download_count 低，换签窗口成本最低；决定后按第 5 条执行）

## round-8 建议清单（源自 round-7 测试盲区分析）
- BUG-044/045/046 修复：检查单修订已落地本文档；Release notes 计数修正（42/42）+ 补 0.2.0→0.2.2 升级路径 + 向导移出 "PC grant unlocks" 层级（lead 直接执行）
- 补两个针对性测试：编辑三旅程 Compose UI 测试（UI 状态机盲区，最高危）+ ShadowSettings a11y 往返 Robolectric（框架交互盲区）
