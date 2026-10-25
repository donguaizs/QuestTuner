# QuestTuner Round 9 验证报告（VERIFY-round9）

- 验证人：David（QA）
- 日期：2026-10-25
- 对象：`fix/round-9` @ **4f98fd5**（9 commit：c1c9d74 报告 → 1afd1b9 方案 → b3172cd/63f9480/6d85ee3/fe535ee/0048156/5823813/4f98fd5 实现）
- 依据：docs/plans/PLAN-round9.md（逐项对照）+ lead 验证指令四要点
- 方法：最终状态代码走查（file:line 引用）+ 独立复跑单测 + wrapper 离线全链路实验 + 偏差复核

## 总判定

| 项 | 内容 | 判定 |
|---|---|---|
| ① | BUG-047 .gitignore 兜底 | **PASS** |
| ② | BUG-049 selectApkAsset + checkLatest 接线 + 3 用例 | **PASS** |
| ③-a | BUG-050 BootReceiver 条件断开 | **PASS** |
| ③-b | BUG-051 ensureConnected 一把锁 | **PASS** |
| ④ | BUG-052/053 编辑态回填 | **PASS**（返工 b8c15bd 复验改判；初验 FAIL 归档见文末复验节） |
| ⑤ | BUG-048 测试权限清理（选项 A） | **PASS** |
| ⑥ | BUG-054 wrapper 入库 | **PASS**（离线级；网络下载待补实机） |
| ⑦ | R9-O4 TODO 三处同步 | **PASS** |
| — | 独立复跑单测 | **38/38 全绿** |
| — | Wang 偏差 ×3 | 全部属实、修正正确（见偏差节） |

**结论（复验后终判）：7 项 7 PASS / 0 FAIL，可合入 master。** 初验曾判 ④ FAIL（BUG-052 语义反转），lead 组织返工 b8c15bd 后复验改判，全程归档见文末【复验节】；初验 FAIL 详情与修复方向见专节（保留作 QA 痕迹）。

---

## 逐项证据

### ① .gitignore（PASS）

- `.gitignore:18-22` 追加段与 PLAN/报告**逐字一致**，4 条均带前导 `/` 锚定仓库根（不误伤子目录同名）：
  ```
  # Robolectric/jar 解包残渣（BUG-047：曾泄漏到仓库根，2026-10-24）
  /META-INF/  /android/  /org/  /sqlite4java/
  ```
- 既有条目零改动（对照 b3750c7 版本 diff）；`git status --short` **零输出**（残渣已清 + 新条目无噪音）✅

### ② selectApkAsset（PASS）

- 纯函数 `SelfUpdater.kt:49-51` 与 PLAN 逐字一致：优先 `endsWith("-release.apk")` → fallback `.apk` 且排除 `sources`/`mapping`；`-release-notes.txt` 类名因 `.apk` 后缀要求天然不误伤 ✅
- **checkLatest 接线走查（lead 点名重点）**：`SelfUpdater.kt:83-88` —— 先 map 出**完整 assets 的 name 列表** → `selectApkAsset(names)` 对全列表做一次选择 → `objs.firstOrNull { it.optString("name") == picked }` 回查对应 JSONObject。**确认是"全列表选择后回查"，不是逐资产单独判断**（逐资产判断会使偏好失效——每个 debug 资产单独喂给 selectApkAsset 也会被选中）。Wang 声称属实 ✅
- 数组缺失/无可选 apk → null → `?: return@runCatching null`（按无资产处理）语义保持 ✅
- 新增 3 用例 `UpdateSafetyTest.kt:52-72`（prefersRelease 用 v0.2.2 实测顺序数据 / fallsBackToAnyApk / skipsSourcesAndMapping_andNull 含双 null 分支），`assertNull` import 已补 ✅
- 不动项核对：checkHost 白名单、四重校验、MAX_DOWNLOAD_BYTES 均零变化（git diff 6d85ee3 核实仅 :44-51 与 :80-88 两处）✅

### ③-a BootReceiver 条件断开（PASS）

- `BootReceiver.kt:43-46`：`if (!TuningExecutor.adbSessionActive) { disconnect(); disableWifiAdbIfWeEnabled(...) }`——守卫位置精确（while 循环之后、log 之前），注释与 PLAN 逐字一致 ✅
- **会话标志调用面核实**：`markSessionActive()` 全仓唯一调用点 = TunerViewModel.kt:151（用户 connect 成功路径）；markSessionInactive 在 :183/:191（取消/断开）。BootReceiver 保持不 markSessionActive（:28 注释原样）→ 守卫语义精确匹配"用户显式连接过" ✅
- while 轮询循环本体零改动 ✅

### ③-b ensureConnected 一把锁（PASS）

- `TuningExecutor.kt:61-99` 采用 PLAN **推荐的简化版**（一把锁）：快路径锁外读 `@Volatile connection`（:62，读竞态无害）→ keyStore 初始化在锁外（无害，@Synchronized loadOrGenerate）→ `adbMutex.withLock` 内**检查+connect+赋值原子化**（:75-87）：锁内重检 `connection != null` → 输家 `conn.close()` 关自己多建的实例、返回 true（连接确实存在）；赢家 `withTimeout(35s)` connect 后赋值+置状态 ✅
- 泄漏路径闭合：并发双首连第二条不再覆盖第一条（原 BUG 场景的赋值竞争点已入锁）；disconnect 竞态窗口下无"旧实例覆盖"路径 ✅
- `withTimeout` 只包 connect（35s 语义不变，等锁时间不计入）；失败路径 conn.close()（:96）；`interpretShellResult` 的 `connection === conn` 引用比对与 disconnect() fire-and-forget 语义未动（PLAN 注意事项遵守）✅

### ④ 编辑态回填（**FAIL**——BUG-052 语义反转；BUG-053 部分正确）

- **053 部分正确**：`TunerViewModel.kt:414` delete 回填 `editingProfile = _state.value.editingProfile?.takeIf { it?.packageName != pkg }`——以 packageName 为键对齐删除目标，删编辑中对象 → 退出编辑态；删其他对象 → 保留。时序表：删编辑中 X → 回新建 ✅；删 Y（编辑 X 中）→ X 编辑保留 ✅
- **052 FAIL**：详见下节【④ FAIL 专节】。`TunerViewModel.kt:366+376` 的 `savedRef = p` + `takeIf { it != savedRef }` 用 data class **值相等**做"是否退出自己编辑态"的判定键，而编辑保存路径（TunerApp.doSave）构造的 `p` 是表单新值实例——用户**改动过的保存**（旅程 3 的正常形态）必然 `editingProfile(旧值) != savedRef(新值)` → 编辑态不退出 = round-6 BUG-041 旅程 3"保存后退出编辑态"回归。

### ⑤ A11yRoundTripTest 清理（PASS）

- diff（63f9480）三点与 PLAN 逐字一致：删 `grantPermissions` 行 ✅；删 `else if (i == granted.size) {}` 恒假分支 ✅；注释整段重写为"PackageInfo 手术承重 + checkPermission 路径原因 + 删改前先跑探针"警告 ✅
- **只动 setup**：4 个 @Test 方法零改动（diff 核实 + 当前文件 @Test 计数 = 4）✅；`shadowOf` import 保留（installPackage 仍用）✅；build.gradle.kts / testOptions 零改动 ✅
- 复跑 4/4 绿（见单测节）——清理后承重链（PackageInfo 手术）完好，与 round-9 探针实验结论自洽 ✅

### ⑥ wrapper 入库（PASS，离线级）

- **入库物核查**：5823813 恰好 6 文件——README×2 + gradlew.bat(2918B) + gradlew(8692B) + gradle-wrapper.jar(43453B) + gradle-wrapper.properties(337B)。**无 zip/dist 入库**；全仓跟踪文件按体积排序最大是 PLAN-round2.md(89KB)，wrapper jar 42.4KB 第 4——无任何大文件误入库 ✅（lead 指令第 4 条复核通过）
- **properties 逐字核对**：`gradle-wrapper.properties:1-8` 与 PLAN 期望逐字一致，`distributionUrl=https://services.gradle.org/distributions/gradle-8.7-bin.zip`（官方 https）+ `distributionSha256Sum=544c35d6…b961d` ✅
- **sha256 独立实证**：本机实算 `F:\workspace\toolchain\gradle-8.7-bin.zip` SHA-256 = `544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d`——与 pin 值**完全一致**（64 位 hex）✅（这同时证明 Li 的"实算值"无误）
- **离线全链路实验**（独立 GRADLE_USER_HOME=F:\workspace\tmp\gw-verify\home，wrapper jar 直调 GradleWrapperMain，distributionUrl 临时指本地 file:// zip，pin 值不动）：下载 → **sha256 校验通过** → 解包 → 输出 "Welcome to Gradle 8.7!" 全链路成功 ✅。按 lead 对齐的口径：官方 URL 的真实网络下载验证记**待补实机项**（Wang 本地 file:// 链路已走通，与本实验互证）
- `--version` 补充说明：直跑 `java -cp gradle-wrapper.jar GradleWrapperMain --version` 首次在默认 `C:\Users\86177\.gradle` 处建锁目录失败（ACL 问题，环境备忘记载的既有坑，非 wrapper 缺陷）——改用重定向 GRADLE_USER_HOME 后成功，此即上面全链路实验
- README.md:41-54 与 README.zh-CN.md:41-53 Build 节均按 PLAN 改写（主命令 gradlew.bat、需联网一次说明、file:// 离线改法、预装兜底），双语同构 ✅
- `git status` 零输出（.gitignore 不误伤 wrapper jar）✅

### ⑦ TODO 同步（PASS）

- TODO-round-next.md 三处全部落地：:17 编辑三旅程条目补"保存中点编辑"注（BUG-052 UI 级回归挂靠）✅；:18 R9-O1 延后池新条目（含"先实测对 4.12.2 无副作用"前置）✅；:25 实机清单执行备注（BUG-050 条件断开真机项，未重排 33 条）✅；:31-32 环境备忘两条 wrapper 条目 ✅
- 8 轮痕迹引用（docs/bugs、docs/plans、docs/verification）未改动；发布检查单 5 条原样 ✅

---

## 独立复跑单测（PASS：38/38）

命令（toolchain 入口，未触发 gradlew 网络下载）：
`gradle.bat -p F:\workspace\QuestTuner :app:testDebugUnitTest`（JAVA_HOME/GRADLE_USER_HOME/TEMP/TMP 按环境备忘）→ **BUILD SUCCESSFUL**，逐类：

| 测试类 | tests | failures | errors |
|---|---|---|---|
| tuner.A11yRoundTripTest | 4 | 0 | 0 |
| tuner.IntentFoldTest | 3 | 0 | 0 |
| tuner.LeaveSnapshotTest | 3 | 0 | 0 |
| tuner.MergeA11yListTest | 4 | 0 | 0 |
| tuner.ProfileValidationTest | 5 | 0 | 0 |
| tuner.ValuesMatchTest | 5 | 0 | 0 |
| update.CleanStaleApksTest | 2 | 0 | 0 |
| update.UpdateSafetyTest | **7**（35→38 的 +3 即此） | 0 | 0 |
| update.VersionCompareTest | 5 | 0 | 0 |
| **合计** | **38** | **0** | **0** |

与 PLAN 预期 35+3=38 一致 ✅。注：本轮复跑命中 UP-TO-DATE（Wang 已跑过同输入）；判定依据含 XML 结果文件逐类零失败。

## 已核对无问题（附加通过项）

- 工作区干净（git status 零输出）；round-9 探针临时文件无残留；app/src/test 无多余文件 ✅
- ②③④ 生产代码改动面 = PLAN 清单四文件（SelfUpdater/BootReceiver/TuningExecutor/TunerViewModel），无越界改动；无新依赖、无构建配置变化 ✅
- commit 序列与 PLAN 建议分组一致（每项一 commit，中文 conventional）✅

---

## Wang 偏差复核（3 条，全部属实且修正正确）

1. **⑦ 环境备忘措辞**：PLAN 原稿写"GRADLE_USER_HOME 已有完整 dists 缓存，勿删"；Wang 改为"目前**无** dists 缓存，勿指望直接命中"+ file:// 离线法。实证：`Test-Path F:\workspace\toolchain\gradle-home\wrapper` = **False**（dists 缓存确实不存在——历史构建全走 toolchain 解包版 gradle，从未下载过 wrapper dist）。**Wang 修正与事实一致，PLAN 原稿错误**，同意采纳 ✅
2. **⑥ zip 入 hash 目录**：已核实无任何 zip/发行包入库（见 ⑥ 节入库物核查）✅
3. **⑥ 验证降级为 file:// 链路**：PLAN 全局注意事项 6 明文允许（网络卡下载时以 --version 冒烟 + 完整校验记 VERIFY 补做）；Wang 本地 file:// 实测 + 本报告独立复现（sha256 校验+解包+版本横幅全链路），官方 https 真实网络下载按 lead 口径记待补实机项 ✅

## 已知限制的独立结论（lead 指令第 5 条）

| 项 | PLAN 裁定 | David 独立结论 |
|---|---|---|
| BUG-050 | 代码评审级 | **同意**。双触发源时序（开机恢复 × 用户连接）在单测中需 Mock 单例 + 协程调度注入，成本远超一行守卫的风险量级；已挂实机清单执行备注兜底 |
| BUG-051 | 代码评审级 | **同意**。并发竞态需 AdbConnection 假实现注入（现为直接 new 的具体类），为测试改生产结构不值当（PLAN 评估正确）；一把锁形态使竞态面显著缩小，评审可覆盖 |
| BUG-052 | 代码评审级 | **不同意（本轮已被证伪）**。④ FAIL 恰是反例：编辑态状态机改动**纯值语义、无框架依赖**，一条 VM 级断言（保存后 editingProfile == null）即可拦住——评审没拦住是因为 PLAN 方案本身的语义论证就错了（见下节），评审级验证对"方案错误"无免疫力。且 round-7 盲区 1 / round-8 降级的 EditJourneyUiTest 旅程 3（断言"保存修改→回新建标题"）正是这条回归的既定防线——延后成本现已兑现。**建议：本轮返工 052 时顺手把旅程 3 单测提级（或最低限度在延后池标注"052 修复必测"）** |
| BUG-053 | 代码评审级 | **部分同意**。删除-编辑交叉同样可用 VM 级断言低成本覆盖，但单一比较键正确性已走查证实、风险低于 052；接受评审级，随 052 返工一并补测试更佳 |

---

## ④ FAIL 专节：BUG-052 修复语义反转（旅程 3 回归）

- 位置：TunerViewModel.kt:366（`val savedRef = p`）+ :376（`editingProfile = _state.value.editingProfile?.takeIf { it != savedRef }`）
- 根因：**判定键选错**。"退出自己这次保存的编辑态"的正确判据是"当前编辑引用 == 保存**进入时**的编辑引用"（引用/会话语义），实现却用 `savedRef`（表单**新值**实例）与当前编辑对象做 data class **值相等**比较。编辑保存路径（TunerApp.kt doSave）总是从表单状态构造新 AppProfile——用户改动过的保存（编辑的正常用途）必然新旧值不等：
  1. 用户点 A 行"编辑" → editingProfile = A_old(90Hz)
  2. 表单改 90→120 → 点"保存修改" → doSave 构造 p(120Hz)，savedRef=p
  3. IO 完成回填：editingProfile(A_old, 90Hz) `!=` savedRef(p, 120Hz) → **takeIf 保留 → 编辑态不退出**
  4. UI 停留"编辑 Profile：A"——round-6 BUG-041 旅程 3"保存修改 → 回新建默认"回归
  对照：唯一能正常退出的路径是**不改任何字段**直接保存（此时 p 与 A_old 内容相同、data class 判等成立）——语义完全反了：改动越大越不退出。
- PLAN 责任界定：PLAN-round9 ④ 要点 1 的论证把场景想反了（担心的"保存后点编辑同内容 A' 被误清"是小概率无害边界，漏掉的恰是主路径"改内容保存不退出"）——**方案缺陷，Wang 忠实实现，Wang 无责**。
- 后果评估：用户可见行为回归（P2）；无数据损坏（profiles.json 保存正确，仅 UI 编辑态残留）；用户可点"取消编辑"脱困；再点"保存修改"会重复保存同样内容（幂等，无害但困惑）。
- **修复方向（一行级）**：saveProfile 入口捕获保存前的编辑引用，回填改**引用比较**：
  ```kotlin
  val priorEdit = _state.value.editingProfile   // 替换 val savedRef = p
  ...
  editingProfile = _state.value.editingProfile?.takeIf { it !== priorEdit }
  ```
  引用比较天然区分"同一编辑会话未动"（=== → 清，退出编辑态 ✅）与"保存 IO 中用户点了另一个编辑"（!== → 保留 ✅），不依赖 data class 值相等——同时覆盖 PLAN 想要的两个场景且无反转。053 的 packageName 键可保持不动（已验证正确）。
- 防回归建议：EditJourneyUiTest 旅程 3 用例（PLAN-round8 已有完整设计，断言"保存修改 → 新建 Profile 标题回归"）是本回归的直接防线——建议随 052 返工提级实施，或最低限度在 TODO 延后池将该用例标注为"052 修复的验收条件"。

## 修复优先级建议

1. **④ BUG-052 返工**（一行级 + 建议补旅程 3 断言；返工后本项复验只需走查 3 行 + 定向测试）
2. 其余 6 项维持 PASS，可随 052 返工 commit 一并合入
3. 待补实机项累积两条：wrapper 官方 https 真实下载验证（⑥）、BUG-050 条件断开真机项（已入 TODO 实机备注）

---

## 复验节（14:26，返工 b8c15bd 后改判 ④ PASS）

- 返工对象：`fix/round-9` @ **b8c15bd**（"fix: BUG-052 返工——编辑态回填改引用比较（!== priorEdit）"，仅 TunerViewModel.kt +8/-4；053 与测试未动——lead 一线复核 + 本复验 diff 走查双重确认）
- **diff 走查**：`val savedRef = p` → `val priorEdit = _state.value.editingProfile`（saveProfile.kt:366）；回填 `takeIf { it != savedRef }` → `takeIf { it !== priorEdit }`（:381）。与 VERIFY 初验给出的修复方向逐字吻合；注释含完整教训记录（值比较反转成因 + "David 验证 FAIL 的教训"）✅
- **三时序断言（代码级推演，锚点=引用）**：
  1. 旅程 3 正常保存→退出：editingProfile=R，priorEdit=R，改字段（90→120）不影响（表单新构实例 p 不参与比较）→ 完成时 `R !== R` 为假 → takeIf 清空 → **回新建模式 ✓**（BUG-041 旅程 3 恢复，且改不改字段行为一致）
  2. 保存 IO 期间换编辑→保留：priorEdit=R，期间 editProfile(B) 置 editingProfile=S（S 与 R 不同引用）→ `S !== R` 为真 → **S 保留 ✓**（BUG-052 原始诉求维持）
  3. null 编辑态短路无副作用：新建模式保存（editingProfile=null）→ priorEdit=null → `null?.takeIf` 短路为 null；期间用户点编辑 B → `B !== null` → **保留 ✓**
- **定向复跑**（`--rerun-tasks` 强制执行，26 tasks executed 无缓存嫌疑）：A11yRoundTripTest **4/4** 绿（time 16.6s）、UpdateSafetyTest **7/7** 绿；BUILD SUCCESSFUL。全量 38/38 由 Wang 实跑在案（lead 指令豁免重复）
- **053 与其余项**：b8c15bd 未触碰，初验 PASS 结论维持
- **改判**：④ **FAIL → PASS**。总判定 **7 PASS / 0 FAIL**；VERIFY 初验对 052 无单测覆盖的"不同意"结论随之部分兑现（返工 diff 注释已沉淀教训，EditJourneyUiTest 旅程 3 提级建议保留于 TODO 挂靠，随 round-10 带走）
- 复验人：David；复验耗时 ~8 分钟（走查 + 定向复跑 + 本节归档）
