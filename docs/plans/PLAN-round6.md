# QuestTuner Round 6 修复方案（PLAN-round6）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round6.md（David，BUG-039~043 + R6-O1~O5）+ task-24 描述（含 lead 三项决策）
- 基线：master @ 76462ea（tag v0.2.1）；修复在 `fix/round-6` 分支进行
- 行号基于 76462ea 核对；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行
- BUG-039 已由 David 撤销定级（注入被 optInt 链阻断），其分析存档于报告——本方案不重复追注入面，包名校验缺口并入 BUG-041

## 总览

| 编号 | 级别 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-039 | ~~P1~~ | 已撤销（David 推演：数值全经 Int 化、包名不进命令）；校验缺口并入 041 | — |
| BUG-040 | P2 | 修（reload 域校验：超域字段置 null + Log.w） | ProfileRepository.kt |
| BUG-041 | P2 | 修（profile 输入链一次修：包名正则 + 覆盖确认 + 编辑入口） | ProfileRepository.kt、TunerApp.kt、TunerViewModel.kt |
| BUG-042 | P2 | 修（README 双语更新到 v0.2.2：6 项新能力 + update/ 包 + 隐私节 + 版本尾行） | README.md、README.zh-CN.md |
| BUG-043 | P2 | 修（versionName 0.2.2 / versionCode 3 + tag 恒等式记录） | app/build.gradle.kts、docs/plans/TODO-round-next.md（新建） |
| R6-O1~O5 | 优化 | 4 修（O1 并入 041 / O2 并入 041 / O5 并入 042）/ 1 不做（O3）/ 1 可选（O4） | 见 §优化建议 |

**建议实施顺序**（David 优先级）：① 043（版本 + 恒等式记录，一行级先落位）→ ② 040（reload 域校验，纯 repo 层）→ ③ 041（输入链 UI + repo + VM，改动最大）→ ④ 042（README 双语，纯文档收尾）。每步独立可编译。

---

## BUG-043（P2）版本三元组不一致：tag v0.2.1 vs versionName 0.2.0 vs versionCode 2

**lead 决策**：versionName 升 **0.2.2** / versionCode **3**——tag v0.2.1 已对外但 versionName 未进发布物（内嵌 0.2.0），向前对齐不回头；本轮发布物将是 0.2.2(3)，下一个 tag = v0.2.2。tag 恒等式写进方案并落 build.gradle.kts 注释 + TODO 文件。

- 涉及文件：`app/build.gradle.kts`、`docs/plans/TODO-round-next.md`（新建）
- 具体改动点：

  **（a）build.gradle.kts:15-16【改】**：
  ```kotlin
        versionCode = 3
        versionName = "0.2.2"
  ```

  **（b）build.gradle.kts:15-16 处【增】恒等式注释（放 versionCode 上方）**：
  ```kotlin
        // 发布恒等式（BUG-043 教训：tag 与内嵌版本脱节 → 自更新同版本循环）：
        //   git tag 名 == "v" + versionName；打 tag 前必须先改本处两个值。
        //   例：versionName 0.2.2 → tag v0.2.2 → Release APK 内嵌 0.2.2(3)。
        //   SelfUpdater 防降级比较用 versionName（SelfUpdater.kt versionGreaterThan）。
        versionCode = 3
        versionName = "0.2.2"
  ```

  **（c）【增】`docs/plans/TODO-round-next.md`**（新建，发布检查单落点；lead 决策二选一，本方案选 TODO 文件——README 开发者节留给 042 的对外内容，内部流程纪律放 docs）：
  ```markdown
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
  ```

- 风险与注意事项：
  - 0.2.2(3) 的自更新行为核对：装着 0.2.1 tag 产物（内嵌 0.2.0）的用户 → 检查更新比较 `0.2.2 > 0.2.0` ✅ 正常更新一次，装后内嵌 0.2.2 → 再检查 `0.2.2 > 0.2.2` false ✅ 不再提示——循环消除。
  - versionCode 3 跳过与 tag 0.2.1 的对应关系：0.2.1 从未作为 APK versionCode 发布（内嵌一直是 2），3 无断档问题。
- 验证方法：`aapt2 dump badging` 显示 versionName=0.2.2 versionCode=3；发布后按检查单第 4 条跑一轮自更新（0.2.1 内嵌用户 → 0.2.2 一次成功、二次不提示）。

---

## BUG-040（P2）JSON 导入路径数值零上限：超域值直接下发 setprop

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt`
- 修复思路：reload 补与 UI 同域的校验（对等原则：两条写入路径同一套规则）；**超域处置 = 置 null（丢弃该字段）+ Log.w**，而非 coerce——理由：手编 JSON 的超域值（999999）coerce 到 3072 会静默产生用户没写过的值（「我写了 999999 为什么变 3072」），置 null 让 describeProfile 显示「未设该项」更诚实，且不会下发任何超域命令。域值取 UI 现行约束：refreshRate ∈ {72,90,120}（FilterChip 枚举同源）、texture ≤ 3072/3208（coerceAtMost 同源）、cpu/gpuLevel 0-4（Slider 同源）。
- 具体改动点：

  **（a）ProfileRepository companion object【增】域校验纯函数**：
  ```kotlin
      companion object {
          /** BUG-040：与 UI 同域的数值校验（FilterChip/Slider/coerceAtMost 同源规则）。
           *  纯函数可单测：入参 JSON 原始值，出参 null=超域丢弃。 */
          fun sanitizeRefreshRate(v: Int): Int? = v.takeIf { it in intArrayOf(72, 90, 120) }
          fun sanitizeTexture(v: Int, isHeight: Boolean): Int? =
              v.takeIf { it > 0 && it <= if (isHeight) 3208 else 3072 }
          fun sanitizeLevel(v: Int): Int? = v.takeIf { it in 0..4 }

          fun of(filesDir: File): ProfileRepository = ...
      }
  ```
  （注意 3208 是 README 与 TunerApp 既有语义的实际上限——TunerApp.kt:225 是 `coerceAtMost(3200)`，README 写 3208。**不一致的既有事实**：UI 路径 3200 / 文档 3208。本方案取 **3208** 为统一域（与 README「上限 3072×3208」一致，Quest 3 硬件上限语义），UI 的 3200 是缩放计算产物巧合（1760*2=3520>3200 才被 coerce），不冲突——Wang 不改 UI，仅 reload 用 3208。差异说明写入注释。）

  **（b）reload :47-51【改】**：
  ```kotlin
      refreshRate = sanitizeRefreshRate(o.optInt("refreshRate", -1)),
      textureWidth = sanitizeTexture(o.optInt("textureWidth", -1), isHeight = false),
      textureHeight = sanitizeTexture(o.optInt("textureHeight", -1), isHeight = true),
      cpuLevel = sanitizeLevel(o.optInt("cpuLevel", -1)),
      gpuLevel = sanitizeLevel(o.optInt("gpuLevel", -1)),
  ```
  （超域统一置 null；与原 takeIf 链差异：optInt 默认 -1 经 sanitize 自然返回 null——无额外分支。）

  **（c）超域观测【增】**：解析循环内（:42-54）每构造一个 p 后：
  ```kotlin
      if (p.refreshRate == null && o.has("refreshRate") ||
          p.textureWidth == null && o.has("textureWidth") ||
          p.textureHeight == null && o.has("textureHeight") ||
          p.cpuLevel == null && o.has("cpuLevel") ||
          p.gpuLevel == null && o.has("gpuLevel")) {
          android.util.Log.w("ProfileRepository", "out-of-range fields dropped for ${p.packageName}")
      }
  ```
  （一次性 Log.w，静默修正可诊断。）

- 风险与注意事项：
  - 老用户既有合法数据不受影响（72/90/120、≤上限、0-4 全通过）；曾有超域值的 profile 该字段被丢弃 → describeProfile 少显示一项，应用时少发一条命令——正确的降级方向。
  - persist 在下次 save 时只写非 null 字段（:90-94 既有的 `?.let`）——超域字段从存储中自然消失（自愈）。
- 验证方法：单测（纯 JVM，ProfileRepository 用临时目录）：写 JSON 含 textureWidth=999999/refreshRate=7/cpuLevel=99 → new ProfileRepository → profiles 断言三项为 null、合法项保留；Log.w 可捕获（Robolectric）或走查。实机：附录清单 C-15 场景（push 手编 JSON）→ 超域字段不再下发。

---

## BUG-041（P2）保存无覆盖确认、无编辑入口、包名格式零校验（三条 UX 缺口一次修）

**lead 决策**：040+041 同属 profile 输入链一次修。三项子修复 + R6-O1/O2 并入。

- 涉及文件：`tuner/ProfileRepository.kt`、`ui/TunerApp.kt`、`ui/TunerViewModel.kt`
- 修复思路：
  1. **包名正则**（R6-O1 并入）：Android 包名规范 `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)*$` + "global" 白名单 + 长度 ≤100；**双端校验**——UI 保存前终检（拦截 + 行内错误提示）、reload 入口同校验（存量脏数据自愈丢弃）。注意必须允许至少两段（`com.a` 合法、`abc` 单段不合法——Android 规范要求至少一个分隔点；但历史手输可能有单段——reload 侧对存量宽松放行还是丢弃？**决策：reload 也严校验**，单段包名本就是死 profile（永不匹配 autoApply/find），丢弃 + Log 是自愈）。
  2. **编辑入口**（R6-O2 并入，解决缺口 2 的正解）：ProfileRow 增「编辑」按钮 → NewProfileCard 进入编辑模式（预填现有值：pkg/hz 由 profile 值反推 scale/cpu/gpu、label 锚点）→ 保存即覆盖。**编辑模式下的保存不做覆盖确认**（用户意图明确就是要改这条）；新建模式下同包名已存在 → 覆盖确认对话框（AlertDialog 一次确认）。
  3. **覆盖确认**（缺口 1）：仅「新建 + 同包名已存在」时弹 AlertDialog（「已存在 xxx 的配置，覆盖？」确认/取消）——编辑模式与确认弹窗互斥使用（编辑=明确意图，新建+撞名=需确认），这就是 lead「选一」的选择与理由。
- 具体改动点：

  **（a）ProfileRepository companion【增】包名校验纯函数**：
  ```kotlin
      /** BUG-041/R6-O1：Android 包名格式 + "global" 白名单 + 长度 ≤100。纯函数可单测。 */
      private val PKG_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
      fun isValidPackageName(pkg: String): Boolean =
          pkg == "global" || (pkg.length <= 100 && PKG_NAME.matches(pkg))
  ```
  （至少两段：正则尾部 `+` 要求 ≥1 个 `.seg`；"global" 是应用内全局约定名。）

  **（b）reload :44-45【改】包名处**：
  ```kotlin
      val rawPkg = o.getString("packageName")
      if (!isValidPackageName(rawPkg)) {
          android.util.Log.w("ProfileRepository", "invalid packageName dropped: $rawPkg")
          return@runCatching   // 跳过该条（runCatching 内 forEach 的 lambda return）
      }
      val p = AppProfile(packageName = rawPkg, ...)
  ```
  （注意 :42 的 for 循环在 runCatching lambda 内——`return@runCatching` 会误伤整循环。正确写法：for 循环提为局部 fun 或用 `continue`——for 内不能带标签 continue 到 runCatching；**改为**：
  ```kotlin
      for (i in 0 until arr.length()) {
          val o = arr.getJSONObject(i)
          val rawPkg = o.getString("packageName")
          if (!isValidPackageName(rawPkg)) {
              android.util.Log.w("ProfileRepository", "invalid packageName dropped: $rawPkg")
              continue
          }
          ...
  ```
  （for 循环内普通 continue 即可，不影响 runCatching 的 ok 判定。）

  **（c）TunerViewModel【增】编辑状态与校验入口**：
  - UiState【增】：`val editingProfile: AppProfile? = null`（非 null = NewProfileCard 编辑模式）。
  - VM【增】：
  ```kotlin
      /** BUG-041/R6-O2：进入编辑模式（NewProfileCard 预填） */
      fun editProfile(p: AppProfile) {
          _state.value = _state.value.copy(editingProfile = p)
      }
      fun cancelEdit() {
          _state.value = _state.value.copy(editingProfile = null)
      }
      /** BUG-041：保存前置检查——包名非法返回错误文案；同包名存在返回待确认标记 */
      fun precheckSave(pkg: String): String? {
          if (!ProfileRepository.isValidPackageName(pkg)) {
              return "包名格式不正确（示例 com.example.app，或留空作为全局）"
          }
          return null
      }
      fun profileExists(pkg: String): Boolean = viewModelScope.let { runCatching { repo.find(pkg) != null }.getOrDefault(false) }
  ```
  （profileExists 简化——repo.find 是 @Synchronized 快速内存查表，主线程调用可接受（round-1 BUG-016 语境是磁盘 IO；LinkedHashMap.get 无 IO）——直接同步实现：
  ```kotlin
      fun profileExists(pkg: String): Boolean = repo.find(pkg) != null
  ```
  saveProfile 保存成功后【增】`_state.value = ...copy(editingProfile = null)`（编辑完成退出模式）。

  **（d）TunerApp.kt NewProfileCard【改】（编辑模式 + 校验 + 确认）**：

  - 函数签名【改】：`fun NewProfileCard(state: UiState, viewModel: TunerViewModel)`（签名不变，经 state.editingProfile 取编辑对象）。
  - 状态初始化 :156-164【改】——编辑模式预填：
  ```kotlin
      val edit = state.editingProfile
      // 编辑模式预填：refreshRate/levels 直取；scale 由 textureWidth 反推（编辑时改 scale 重算 W/H）
      var pkg by androidx.compose.runtime.remember(edit) {
          androidx.compose.runtime.mutableStateOf(edit?.packageName ?: "")
      }
      var hz by androidx.compose.runtime.remember(edit) {
          androidx.compose.runtime.mutableStateOf(edit?.refreshRate ?: 90)
      }
      var scale by androidx.compose.runtime.remember(edit) {
          androidx.compose.runtime.mutableStateOf(
              ((edit?.textureWidth ?: 1680) / 1680f).coerceIn(0.5f, 2.0f))
      }
      var cpu by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.cpuLevel ?: 2) }
      var gpu by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.gpuLevel ?: 2) }
      ...
      // pickedPkg/pickedLabel：编辑模式锚定为编辑对象（label 沿用原值）
      var pickedPkg by ... { androidx.compose.runtime.mutableStateOf(edit?.packageName) }
      var pickedLabel by ... { androidx.compose.runtime.mutableStateOf(edit?.label) }
  ```
  （remember(edit)——编辑对象变化时重置全部字段；scale 反推：原 1680→1.0x、3072→~1.83x，coerce 兜住超域历史值。）
  - 保存按钮 :222-240【改】——终检 + 覆盖确认：
  ```kotlin
      var confirmOverwrite by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
      var pkgError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

      Button(onClick = {
          val targetPkg = pkg.ifBlank { "global" }
          // BUG-041/R6-O1：保存前包名终检
          pkgError = viewModel.precheckSave(targetPkg)
          if (pkgError != null) return@Button
          // BUG-041：新建模式 + 同包名已存在 → 覆盖确认（编辑模式 = 明确意图，不确认）
          if (edit == null && viewModel.profileExists(targetPkg)) {
              confirmOverwrite = true
          } else {
              doSave(targetPkg)
          }
      }) { Text(if (edit != null) "保存修改" else "保存 Profile") }

      // 行内错误提示（包名校验）
      pkgError?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
  ```
  - 确认对话框与 doSave（Card 内【增】）：
  ```kotlin
      if (confirmOverwrite) {
          androidx.compose.material3.AlertDialog(
              onDismissRequest = { confirmOverwrite = false },
              title = { Text("覆盖确认") },
              text = { Text("已存在 ${pkg.ifBlank { "全局" }} 的配置，保存将覆盖旧值。") },
              confirmButton = {
                  androidx.compose.material3.TextButton(onClick = {
                      confirmOverwrite = false
                      doSave(pkg.ifBlank { "global" })
                  }) { Text("覆盖保存") }
              },
              dismissButton = {
                  androidx.compose.material3.TextButton(onClick = { confirmOverwrite = false }) { Text("取消") }
              },
          )
      }
  ```
  doSave 提为 Card 内局部函数（原 :223-238 保存逻辑抽出，label 终检逻辑不变）：
  ```kotlin
      fun doSave(targetPkg: String) {
          val w = (1680 * scale).toInt().coerceAtMost(3072)
          val h = (1760 * scale).toInt().coerceAtMost(3200)
          val label = (if (pkg == pickedPkg) pickedLabel else null) ?: pkg.ifBlank { "全局" }
          viewModel.saveProfile(
              AppProfile(packageName = targetPkg, label = label,
                  refreshRate = hz, textureWidth = w, textureHeight = h,
                  cpuLevel = cpu, gpuLevel = gpu)
          )
      }
  ```
  （Composable 内局部 fun 直接写（非闭包问题——doSave 引用的都是当前组合的状态变量，合法）。
  - 编辑模式标题与取消：Card 顶部【增】：
  ```kotlin
      Text(if (edit != null) "编辑 Profile：${edit.label ?: edit.packageName}" else "新建 Profile",
          style = MaterialTheme.typography.titleSmall)
      if (edit != null) {
          androidx.compose.material3.TextButton(onClick = viewModel::cancelEdit) { Text("取消编辑") }
      }
  ```
  - 外层「新建 Profile」标题 :55【改】：编辑模式时 LazyColumn 的该 item 标题已由 Card 内标题表达，外层标题可保持不变（「新建 Profile」item 与编辑共存不冲突——编辑时 Card 显示编辑标题，视觉可区分，简单不折腾）。

  **（e）ProfileRow 编辑入口 :125-140【改】**：
  - 签名【增】`onEdit: () -> Unit` 参数；TunerApp :48-53 调用处【增】`onEdit = { viewModel.editProfile(p) }`。
  - 按钮行 :136-139【增】：
  ```kotlin
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Button(onClick = onApply, enabled = connected) { Text("应用") }
          OutlinedButton(onClick = onEdit) { Text("编辑") }   //【增】R6-O2
          OutlinedButton(onClick = onDelete) { Text("删除") }
      }
  ```

- 风险与注意事项：
  - remember(edit) 键控重置：同一 profile 连续编辑两次（保存后 editingProfile=null → 再点编辑）会正确重置；编辑 A 过程中点编辑 B——editingProfile 变化触发重置 ✅。
  - 单段包名被 reload 丢弃的存量影响：曾用「global」以外单词建过的死 profile 消失——正是自愈目标；Log.w 有记录。
  - scale 反推的精度损失：原 2000 → 1.19x，保存时 1680*1.19≈1999——±1 像素级漂移可接受（Slider 本身连续值）。
  - profileExists 主线程同步查表：内存 LinkedHashMap.get，无 IO——不违背 round-1 BUG-016 原则（该原则针对磁盘）。
  - AlertDialog 与 AppPickerDialog 并存：都是独立 Dialog window，不冲突。
  - 编辑模式保存后 editingProfile 置 null → remember(null) 重置为新建默认值 ✅（自然回到新建模式）。
- 验证方法：
  1. 单测：`isValidPackageName`（合法 com.a / com.example.app / global；非法 abc 单段 / `; echo` / 空串 / 101 长度 / `com.` 尾点）；`sanitize*` 域函数（BUG-040 节已列）。
  2. UI 旅程：新建 com.a（90Hz）→ 再次新建 com.a（120Hz）→ 覆盖确认弹出 → 确认后旧值替换；编辑入口：点「编辑」→ Card 预填 120Hz 等 → 改 90 → 保存修改 → 行内更新。
  3. 手输 `abc` → 保存 → 行内红字「包名格式不正确…」不落盘。
  4. 手编 JSON 存量脏包名 → 重启 → 该条消失 + Log.w。

---

## BUG-042（P2）README 双语与 v0.2.2 实际功能严重脱节

**lead 决策**：README 双语更新到 v0.2.2 现状——6 项新能力写入、项目结构补 update/ 包、新增「隐私」节（明确唯一网络行为 = 检查更新连 GitHub API，profile/调参全本地）、版本尾行更新。R6-O5（隐私节文案与向导对齐）并入。

- 涉及文件：`README.md`、`README.zh-CN.md`（两份同构修改，保持逐节对齐——David 已核对现两份结构对齐，改后也要对齐）
- 具体改动点（双语对照要点，Wang 按此执行）：

  **（a）导语节【改】**：补一句授权分层说明（解决「No PC required」与现实矛盾的边界）：
  - EN: `**No root, no PC required** for core tuning. An optional one-time PC command unlocks automation features (see "Optional Grant & Automation").`
  - 中: 核心调参**无需 root、无需电脑**；一条可选的 PC 一次性命令可解锁自动化功能（见「可选授权与自动化」）。

  **（b）Features 功能表【增】6 行**（追加在现有表后）：
  | 功能 | 系统属性/机制 | 说明 |
  |---|---|---|
  | 首次配置向导 First-run wizard | — | 首次启动引导：机制说明/授权命令复制/开关预选 |
  | 按前台应用自动应用 Auto-apply per app | 无障碍服务（仅读包名） | 前台切换自动应用/退出回滚；纯本地 |
  | 授权自动化 Optional automation | WRITE_SECURE_SETTINGS | 自动开无线调试/自动启用无障碍（可关闭） |
  | 应用内自更新 In-app self-update | GitHub Releases | 四重校验（解析/包名/签名/防降级），手动触发 |
  | Private DNS 开关 | Settings.Global | 可选，主机名校验+失败还原 |
  | OVR Metrics 开关 | 系统广播 | 需已安装 OVR Metrics Tool |
  | 开机恢复默认 Restore on boot | BOOT_COMPLETED | 可选开关，尽力而为 |

  **（c）How It Works 结构图【增】update/ 行**：
  ```
  update/ Self-update: GitHub Releases check/download, 4-fold APK
         verification, PackageInstaller session
  ```
  Project Structure 树 :66-69【增】`│   ├── update/       # Self-update (Releases check, APK verify, install)`。

  **（d）【增】"Optional Grant & Automation / 可选授权与自动化"节**（Install 节后）：
  - 核心调参全程无需 PC（首连授权框在头显上点允许即可）；
  - 可选一次性 PC 命令 `adb shell pm grant com.example.questtuner android.permission.WRITE_SECURE_SETTINGS` 解锁：连接时自动开无线调试、自动启用无障碍、Private DNS；
  - **无 PC 替代路径**（附录推演的两个文案缺口之一）：系统设置 → 无障碍 → 手动开启 QuestTuner 服务 → autoApply 同样可用；
  - 所有自动化默认关闭、独立开关。

  **（e）【增】"Privacy / 隐私"节**（R6-O5 并入，放在 Important Notes 前）：
  - EN: `**Privacy.** The only network behavior is the GitHub Releases API request made when you tap "Check for update" (api.github.com). Profiles, tuning parameters, and the accessibility service (which reads only the foreground app's package name, never screen content) are fully local. No analytics, no tracking, no data upload.`
  - 中: **隐私**。唯一的网络行为是你主动点击「检查更新」时访问 GitHub API（api.github.com）。Profile、调参、无障碍服务（只读前台应用包名，绝不读取屏幕内容）全部本地完成。无统计、无追踪、无数据上传。

  **（f）Important Notes【增】1 行**（自更新说明，附录 E-25 关联）：
  - EN/中: 应用内「检查更新」需在系统设置允许「安装未知应用」（头显上完成，无需 PC）/ In-app updates require allowing "install unknown apps" in system settings (done on-headset, no PC).

  **（g）尾行 :79【改】**：`QuestTuner v0.2.2 · Kotlin 2.0 · Jetpack Compose (BOM 2024.06) · minSdk 32（Quest 3 / Android 12L）`（EN 版同英文化）。

  **（h）AutomationCard 无 PC 安抚句（附录推演文案缺口之二，顺带）**：AutomationCard.kt :37-40 未授权提示文案【改】追加一句：
  ```kotlin
  "未授予 WRITE_SECURE_SETTINGS（一次性，PC 执行后自动功能可用）。无 PC？核心调参不受影响，可直接跳过；自动应用可在 系统设置 → 无障碍 手动开启本服务后使用。"
  ```

- 风险与注意事项：
  - 双语逐节对齐纪律（David 核对的既有优点要保持）：改 EN 必改 zh-CN，节顺序/表格行一一对应。
  - 文案事实核对：所有描述与代码实态一致（无障碍只读包名 ✅、更新四重校验 ✅、自动化默认关 ✅）——README 是对外契约，不写未实现的能力。
  - 「无 PC 替代路径」的手动无障碍路径：技术成立（round-4 门禁 a11yRunning 修正后），文案首次披露——建议 David 增量验证时实机走一遍该路径（附录清单补一条）。
- 验证方法：grep 复现（向导/自动应用/更新/Private DNS/metrics/无障碍/隐私 各 ≥1 命中）；两份 diff 节结构对齐走查；v0.2.2 尾行。

---

## 优化建议评估（R6-O1~O5）

| # | 结论 | 理由 / 改动点 |
|---|---|---|
| R6-O1 包名失焦校验+保存终检 | **本轮修**（并入 BUG-041） | 见 041 (c)(d)：precheckSave 终检 + 行内错误。失焦校验（onValueChange 外的 isError 态）可选加：OutlinedTextField `isError = pkgError != null`——Wang 顺手，不强制 |
| R6-O2 编辑模式入口 | **本轮修**（并入 BUG-041） | 见 041 (d)(e)：ProfileRow 编辑按钮 + NewProfileCard 编辑模式预填（remember(edit) 键控重置 + scale 反推）。比确认弹窗更完整——两者都做了（编辑=正解，确认=新建撞名兜底） |
| R6-O3 persist 超 200 条 Log.w | **不做** | 量级无碍（David 自评）；加观测点 = 为不存在的问题加代码。50+ profile 已是极端使用，JSON 全量写 <1ms 级。若未来真有需求，届时连 SQLite 迁移一起评估 |
| R6-O4 搜索 placeholder 提示包名前缀 | **可选（Wang 顺手）** | placeholder「搜索名称或包名（支持 com.meta. 前缀）」一行文案，零风险零逻辑。不强求 |
| R6-O5 README 隐私节 | **本轮修**（并入 BUG-042） | 见 042 (e)：隐私节 + 唯一网络行为声明，与向导文案口径一致 |

---

## 附录处置（task-24 ④）

实机回归总清单（33 条 + 无 PC 推演）**保留在 BUG-REPORT-round6.md 内作为正式交付物**，不另建文件。本方案不复制清单内容（避免双源漂移）；round-6 验收引用该附录 + 新增以下 round-6 专项点：
1. 043：aapt2 badging 版本核对 + 自更新循环消除（0.2.1 内嵌用户 → 0.2.2 一次成功）
2. 040：手编 JSON 超域字段（textureWidth=999999/refreshRate=7/cpuLevel=99）→ 载入后丢弃 + Log.w + 不再下发
3. 041：覆盖确认/编辑预填/包名正则三旅程（上文验证方法 2-4）
4. 042：README 双语 grep + 「无 PC 手动无障碍」路径实机走查（新披露路径）
5. 041(h)：AutomationCard 无 PC 安抚句显示

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-6`（master @ 76462ea 为基）。
2. **顺序**：① 043 → ② 040 → ③ 041 → ④ 042（+041(h) 文案）。041 改动最大（repo+VM+UI 三层），040 的 sanitize 纯函数先落（041 的 reload 包名校验同函数区域，减少冲突）。
3. **单测**（本轮新增纯函数集中，单测性价比高）：`isValidPackageName`（≥8 用例）、`sanitizeRefreshRate/sanitizeTexture/sanitizeLevel`（合法/边界/超域各覆盖）——建议新建 `ProfileValidationTest`（app/src/test，纯 JVM）；既有 26 用例保持绿。
4. **不改动**：adb/ 包、TuningExecutor、ForegroundProfileService、SelfUpdater（本轮全不碰）；AutomationCard 仅 (h) 文案一行。
5. **README 纪律**：双语逐节对齐；只写已实现能力；版本尾行与 build.gradle 同步（043/042 联动，Wang 最后统一核对一次）。
