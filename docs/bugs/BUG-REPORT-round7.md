# QuestTuner Round 7 Bug 报告（发布运营与延后项审计）

- 审查人：David（QA）
- 日期：2026-10-24
- 基线：master @ c971f71（== tag v0.2.2，已核对 tag 指向）
- 本轮视角：发布资产一致性 / TODO 延后池再评估 / 三文本交叉一致性 / 测试结构性盲区。不重复 round-1~6 已核对项。
- 编号自 BUG-044 起

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-044 | P1 | Release v0.2.2 资产/TODO 检查单 | 检查单第 4 条"资产必须来自该 tag 构建"**不可执行且本次已被违反**：release APK 整体 SHA-256 与 tag 构建不一致（debug 一致），检查单无哈希步骤；结构级取证证实内容等同（非实质缺陷，见分析） |
| BUG-045 | P2 | Release v0.2.2 body / TODO 检查单 | Release notes 数据错误：**"43 defects found, 41 fixed"**——实际 43 reported / **42 fixed**（唯一下降路径 BUG-017 不修 + round-3 一度 FAIL 已修复）；且 notes 全文无 **0.2.0(2) → 0.2.2(3) 升级路径说明**（BUG-043 修复的直接受益者=老版本用户，其最需要知道"现在更新不会再循环"） |
| BUG-046 | P2 | 三文本交叉 | Release notes 与 README 双语的**授权表述精度矛盾**：notes 写 grant 解锁"auto-enable wireless ADB, auto-enable accessibility service, **first-run setup wizard**"——向导不需要授权（无 PC 也能用，README"无电脑替代路径"明确）；notes 把三个能力并列在 grant 之下，向导混入造成层级错误 |

优化建议（R7-O1~O4）单列；**TODO 池处置建议表**（①②③线产出）在专门章节。

---

## P1

### BUG-044 检查单第 4 条不可执行，且 v0.2.2 Release 资产哈希与 tag 构建不一致

- 级别：P1（发布流程唯一硬校验点失效；**取证结论为内容等同**，无供应链实质风险——但流程若不改，下次可能放过真不一致）
- 位置：docs/plans/TODO-round-next.md:7（检查单第 4 条）；Release v0.2.2 两资产
- 描述与取证（三级验证）：
  1. **整体哈希**：本地 clean 重建 tag v0.2.2（HEAD == tag c971f71 已核）——
     - debug：本地 `cb927059…83882c` == 远端 digest `cb927059…83882c`（20307702B）**完全一致** ✅
     - release：本地 `f4d03be7…e5929` ≠ 远端 `46716f1b…3394a`（**同为 1768014B**）❌
  2. **远端资产完整性**：下载远端 release APK → SHA-256 == API digest（传输无损，差异是构建侧真实存在）✅
  3. **结构级取证**（定位差异层面）：解包对比——**63 个 zip 条目完全相同**（0 only-remote / 0 only-local / 0 尺寸差异）、**classes.dex SHA-256 相同**（1C9C06DB…，1721708B）、resources.arsc 相同、badging 相同（0.2.2/3）、**apksigner 双侧 Verifies 且证书 SHA-256 一致**（9746fefd…，v2 scheme）。
  **结论**：代码/资源/签名内容**零差异**——整体哈希差异只能源于 zip 容器层（AGP 的 APK 打包时间戳/压缩条目顺序/签名块排版在两次构建间的非确定性，v2 签名块含时间戳字段是已知因素）。即：**远端资产内容 == tag 构建**（好事），但"哈希比对"这一检查手段**无法证明它**——这正是检查单第 4 条的可执行性缺陷：条文只说"资产必须来自该 tag 构建"，无任何可操作的验证步骤，而最直觉的"整体哈希比对"对 release APK 天然不成立（重建不可复现）。发布者无法自查，审计只能靠本次这样的深度取证。
- 证据：如上三级哈希/结构数据（命令与输出已在验证过程留档）。
- 建议验证方法：任选一台机器重建 tag 比对 release 哈希即可复现不一致。修复方向（R7-O1 详）：检查单第 4 条改为**"上传后下载回 Compare：资产字节数 == 本地构建 && 远端 API digest == 上传文件哈希 && badging 版本一致"**（字节级自洽可执行），并注明"跨机器整体哈希重建比对不适用（构建非确定性），内容等同性以 dex/arsc/证书三指纹为准"。

## P2

### BUG-045 Release notes 数据错误（41→42）且缺老用户升级路径说明

- 级别：P2（对外文案的事实错误；Release body 是 immutable 语义弱（GitHub 可编辑），可修正）
- 位置：Release v0.2.2 body 第一段；对照 docs/bugs/BUG-REPORT-round1/3/4/5/6.md
- 描述：
  1. **计数错误**："**43 defects found, 41 fixed**"。逐轮核对：round-1 报 18（修 17，BUG-017 不修）；round-3 报 10（修 10，含 round3b 补修 BUG-020）；round-4 报 5（修 5）；round-5 报 5（修 5）；round-6 报 5（有效 4：BUG-039 当轮撤销——报告自撤销存档，若按"reported 43"口径则 039 计入分子需扣回）。正确口径：**found 42**（18+10+5+5+5−1 撤销）**/ fixed 42**（17+10+5+5+5），或含 039 的 43 found / 42 fixed——无论哪种口径都不是 "41 fixed"。差的 1 个最可能是把 BUG-017（不修）与 BUG-020（一度 FAIL 后已修复）记混。
  2. **缺升级路径说明**：v0.2.2 的核心修复之一是 BUG-043（版本三元组脱节→自更新循环风险）。0.2.0(2) 内嵌版老用户是最直接受益者，notes 的 Install 节只写新装流程，无一行"从 0.2.0 升级：应用内检查更新即可，升级后版本显示 0.2.2 且不再重复提示"。
- 证据：Release body 原文（API 获取）vs 六轮 BUG-REPORT 编号连续性（001-043，其中 017 不修、039 撤销、020 round3b 修复）。
- 建议验证方法：按上述口径复算。修复：编辑 Release body 改 "42 found / 42 fixed"（或 lead 认可的其他明确口径+脚注），Install 节补一行升级说明。

### BUG-046 三文本交叉矛盾：notes 把 setup wizard 归入 grant 解锁能力

- 级别：P2（层级错误会误导无 PC 用户以为向导/基础引导需要授权）
- 位置：Release body Highlights 首条 vs README.md/README.zh-CN.md「Optional Grant & Automation/可选授权与自动化」节
- 描述：notes 原文："**One-time PC grant unlocks automation**: … → auto-enable wireless ADB, auto-enable accessibility service, **first-run setup wizard**"。三处文本对同一事实的表述：
  - README（双语，round-6 修）：grant 解锁 = 自动开无线调试 / 自动启用无障碍 / Private DNS 控制——**三项**，向导不在列（向导无条件首启出现）✅
  - notes：三项 + **first-run setup wizard**（第四项混入）❌
  矛盾实质：向导是所有用户首启必现的基础引导（向导第一步文案本身就在教"不授权也能用"），把它列在 "PC grant unlocks" 之下既与 README 矛盾、也与产品分层（核心功能无 PC）矛盾。无 PC 用户读到 notes 可能误判"基础引导也需要 PC 命令"。次要出入（记录不单列）：notes "R8 release build: 22.2 MB → 1.8 MB" 用 debug 21.6MB 的近似口径，与 README 无对应表述、无冲突；notes 的 Privacy 节与 README 隐私节表述一致 ✅。
- 证据：三文本原文并排（API body + 两 README）。
- 建议验证方法：并排复读。修复：notes 该条去掉 "first-run setup wizard" 或改为独立 bullet（"first-run wizard for everyone"）。

---

## TODO-round-next.md 延后项池再评估（第②线产出）

迭代 6 轮后代码形态已变，逐项重评：

| 项 | 现状核对 | 处置建议 | 理由 |
|---|---|---|---|
| R4-O7 adbStatus 覆盖链去重 | 仍为纯观感（两条"连接已断开"先后覆盖）；round-5 拆流后高频源已消除，覆盖冲突窗口收窄到低频终态 | **继续延后**（维持原决策） | 收益进一步下降：034 修复后 adbStatus 发射源已从"高频+终态混合"变纯低频终态；为低收益重构状态层不值。触发条件不变（UI 状态层重构时顺带） |
| R5-O1 选择器图标通道 + InstalledApp 稳定化 | AppPickerRow 仍逐行 toBitmap(64)（remember(app.icon) 组合内）；Dialog 一次性全量组合（无虚拟化外的缓存） | **下轮做（低成本半步）**：只做 LruCache（packageName→ImageBitmap，容量 ~100），虚拟化继续延后 | 实机回归清单多次跑选择器场景；数百 app 头显上滚动图标转换是可感知卡顿的最可能来源；LruCache 半步改动 ~30 行不触碰虚拟化。注：BUG-034 已把 apps 移出高频重组路径，但滚动时 AppPickerRow 首次组图的转换成本仍在 |
| R5-O2 双源标记收窄 | wifiAdbEnabledByUs（内存）+ wifiAdbByUs（持久）仍在 enable/disable 双写；round-4 对账上线后两源语义已收敛（内存=本会话 connect 持有、持久=归属声明），实际双写点仅 2 处且都在 SecureAutomation 内聚 | **关闭** | 收窄的原始动机（两源漂移风险）已被 round-4 的 reconcile 结构性解决：内存源只在会话内、持久源只在归属判断，两者读写路径分离且各自单一职责；再收窄是零收益重构。关闭理由=原风险已消解 |
| OVR Metrics 三常量校准（A7） | 常量仍为假设值 + TODO 注释；**校准依赖实机 PC 脚本（dumpsys/am broadcast），非代码迭代可推进** | **继续延后**（转为实机任务） | 与代码轮次解耦：任何代码轮都无法替代头实测；建议从"代码延后池"移到"实机任务清单"（合并进 round-6 附录 A 组清单执行时顺带），TODO 文本改挂靠实机清单编号 |
| 换 release keystore 迁移（检查单第 5 条） | 仍用 debug keystore 签 release（round-2 临时决策 + TODO）；当前分发量 0（download_count 0/0），换签窗口成本最低就是现在 | **决策项交 lead**（非延后技术项）：若计划公开分发则下轮前完成（含迁移策略：先发一个 debug 签的"过渡版"提示用户开启自更新，再切新签——或接受一次性手动重装）；若仅自用则可关闭 | 技术上无障碍（gradle 一行 + 策略文档）；每多一个真实用户，换签成本+1。这是产品决策不是代码任务，建议 lead 明确意向后从 TODO 技术池摘出归入发布决策 |

**建议 TODO 文件更新**：R5-O2 标注"已关闭（round-4 对账结构性解决）"；A7 移入实机清单；keystore 项改"lead 决策项"；R4-O7/R5-O1 保持+补充上述触发条件。

## 测试结构性盲区观察（第④线）

31/31 单测的覆盖性质分析——**现有测试全部是纯函数单测**（值域/正则/折叠/合并/版本比较/文件清理），以下四类回归**必然漏网**：

1. **UI 状态机**（最高风险）：editingProfile 三旅程（round-6 新增）只有人工走查——编辑态保存/取消/换目标的 remember 键控重置逻辑，任何字段漏键控就是静默状态残留，纯函数测试无法触达。同理 wizardVisible/connecting/editing 的组合态。
2. **协程时序**：LeaveSnapshotTest 用模式复现代替真执行（round-3 已知边界）；ForegroundProfileService 的 worker 生命周期（onServiceConnected 重回调的 scope cancel）无测试——round-3 的 BUG-019/028 修复的回归只能靠实机。
3. **Android 框架交互**：SecureAutomation 全部 Settings 读写（BUG-025 的 putString 返回值检查、mergeA11yList 的实际写入效果）、PackageInstaller 会话、BroadcastReceiver——零覆盖（需 Robolectric/仪器测试）。
4. **集成链**：SelfUpdater 的 check→download→verify→install 四环只有各环纯函数（checkHost/isTagSafe/versionGreaterThan）有测，环间衔接（如 verifyApk 读到 download 半截文件）无测试。

**下轮建议**（按性价比）：① Compose UI 测试 1 个——NewProfileCard 编辑三旅程（createAndroidComposeRule，覆盖最大盲区 1）；② Robolectric 引入 1 个——SecureAutomation.enable/disableA11yService 的 ShadowSettings 往返（覆盖盲区 3 的最高危路径，也补上 BUG-025 修复的回归防线）；其余保持"纯函数单测+实机清单"现状（投入产出比合理）。

## 优化建议（R7-OX）

| # | 位置 | 建议 |
|---|---|---|
| R7-O1 | TODO 检查单第 4 条 | 按 BUG-044 修复方向改写为可执行步骤（上传后下载回比对字节+digest+badging 三要素），并注明整体哈希跨机器重建比对不适用及原因 |
| R7-O2 | Release body | 修正 42/42 口径 + 补 0.2.0→0.2.2 升级一行 + wizard 归位（BUG-045/046 一并） |
| R7-O3 | TODO 文件 | 按上表更新五项处置（R5-O2 关闭/A7 转实机/keystore 转决策项） |
| R7-O4 | 测试 | 下轮补两个针对性测试（编辑三旅程 UI 测试 + ShadowSettings a11y 往返），其余盲区接受现状并在实机清单执行时留意 |

## 已核对无问题（本轮通过项）

- **tag 卫生**：v0.2.2 == master HEAD（c971f71），轻量 tag 指向正确；v0.2.1 历史保留未改 ✅
- **debug 资产**：与 tag 构建逐字节一致（20307702B/SHA-256 全同）——debug 签名链路（同一 keystore、非 R8 路径）可复现 ✅
- **远端资产传输完整性**：下载哈希 == API digest ✅；两资产 content_type 均为 android package archive ✅
- **release 资产内容等同性**（本轮核心取证）：63 条目/dex/arsc/badging/签名证书五维全同——**供应链无实质风险** ✅
- **notes 其余内容**：Highlights 各条与六轮交付物吻合（grant 三项[除 046 的 wizard 混入]/autoApply 隐私表述/self-update 四重校验/app picker/Private DNS+OVR+boot/R8 体积/README 双语+隐私节）；Install 三步与 README 安装节一致；Privacy 节与 README 隐私节技术事实一致（round-6 已核对表述）✅
- **版本号三处一致**：notes 标题 v0.2.2 == tag == badging 0.2.2(3) == README 尾行 v0.2.2 ✅（BUG-043 修复的对外闭环成立）
- **TODO 检查单第 1-3/5 条**：内容与 round-6 验证一致，无可执行性问题（唯第 4 条见 BUG-044）✅

## 修复优先级建议

1. **BUG-044**（R7-O1：检查单改可执行——下一次发布就会用到，当前版本发布者无自查手段）
2. **BUG-045 + BUG-046**（R7-O2：同一处 Release body 编辑一次改完；数据错误对外是事实问题）
3. R7-O3（TODO 处置更新）+ R7-O4（下轮测试方向）随下轮计划带走。
