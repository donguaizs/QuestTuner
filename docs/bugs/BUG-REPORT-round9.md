# QuestTuner Round 9 Bug 报告（仓库卫生 / round-8 测试质量 / 连接生命周期 / UI 状态并发）

- 审查人：David（QA）
- 日期：2026-10-25
- 基线：master @ b3750c7（TODO-round-next 收尾提交，== v0.2.2 发布后状态）；本轮侦查在 fix/round-9 分支完成
- 本轮视角：仓库卫生取证 / round-8 新增测试自身质量 / 连接生命周期与并发 / UI 状态机并发角落数据一致性。不重复 round-1~8 已核对项（BUG-001~046 均已查阅，BUG-017/020/025/026/027/028/032/034/036/041 相关路径已比对不重复报告）。
- 编号自 BUG-047 起
- 方法论说明：本轮两个核心结论（BUG-047 成因、BUG-048 死分支）均由**可复现实验取证**（测试复跑 + 权限探针），非纯走查。

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-047 | P2 | 仓库根 4 个未跟踪目录 / .gitignore | Robolectric 单测曾在旧 CWD 下运行，把 jar 解包残渣写进仓库根（字节级取证证实来源）；现无 .gitignore 条目兜底，重发风险在 |
| BUG-048 | P3 | A11yRoundTripTest.setup | 权限授予双机制冗余（PackageInfo 手术承重 + grantPermissions 似无效）且含恒假死分支，注释把"承重层"归属写错，未来简化会让 4 用例全红且难定位 |
| BUG-049 | P2 | SelfUpdater.checkLatest:70-73 | Release 多 APK 时按数组顺序盲选首个 .apk——当前 v0.2.2 实测 debug 在前，指向 20MB debug 包（体积差 11.5 倍），依赖资产命名/上传顺序 |
| BUG-050 | P2 | BootReceiver.onReceive:39-40 | 开机恢复结束后无条件 disconnect+回滚无线调试，会杀掉同时段用户/向导建立的显式连接（共享单例 + 会话标志未校验） |
| BUG-051 | P2 | TuningExecutor.ensureConnected:62 | 双重检查在 adbMutex 之外，两个并发首连都能通过 `connection == null` 门，第二条连接绕锁connect 后覆盖第一条 → 首条泄漏（无限期挂起） |
| BUG-052 | P3 | TunerViewModel.saveProfile:370-375 | 完成回填无条件清 editingProfile，可吞掉保存完成前发起的新编辑态（时序窗口窄，数据不丢仅状态残留） |
| BUG-053 | P3 | TunerViewModel.deleteProfile:402-411 | 删除正在编辑的 profile 不清 editingProfile，NewProfileCard 停留在编辑态；此时点保存修改会复活已删除的 profile（键控 remember 依赖 edit 引用，引用不变字段不重置） |
| BUG-054 | P3 | 仓库根缺 gradle wrapper | gradlew/gradle wrapper 从未入库，README 只写 `gradle :app:assembleDebug`；无 wrapper 的 Android 项目任何机器上不可直接构建（简报/新人的 `.\gradlew.bat` 直接失败，本轮实测） |

优化建议 R9-O1~O4 单列末节。

---

## P2

### BUG-047 Robolectric 解包残渣泄漏进仓库根：字节级取证 + 复现实验排除"每次必写"，兜底缺失

- 级别：P2（仓库卫生：一次已发生、机制上可再发、无兜底防线；不污染构建产物，但污染 git status 与误提交面）
- 位置：仓库根 `META-INF/`、`android/`、`org/`、`sqlite4java/`（未跟踪）；`.gitignore`
- 内容与取证（三级）：
  1. **内容**：`META-INF/MANIFEST.MF`(25B) + `META-INF/services/org.robolectric.internal.ShadowProvider`(24B) + `org/robolectric/plugins/DefaultSdkProvider.class`(4617B) + `android/webkit/RoboCookieManager.class`(9087B) + `$Cookie.class` + `sqlite4java/{win32-x64,win32-x86,osx,linux-amd64,linux-i386}` 原生库。文件 mtime 全部为 **2024-05-18 05:16-05:17**（jar 内构建时间戳，解包保留），目录创建时间 **2026-10-24 21:33:32/35 与 23:22:07 两波**。
  2. **字节级来源比对**：残渣文件 SHA-256 与 gradle-home 缓存中 Robolectric 4.12.2 系 jar 的对应条目逐一吻合——`DefaultSdkProvider.class` == robolectric-4.12.2.jar、`RoboCookieManager(.class/$Cookie)` == shadows-framework-4.12.2.jar、`ShadowProvider` 服务文件 == shadows-framework-4.12.2.jar、MANIFEST.MF 为 4.12.2 家族公共清单（25B 版）。来源=Robolectric 依赖 jar 解包，非手放、非其他工具。
  3. **复现实验（排除"每次必写"）**：2026-10-25 12:47 在 fresh Gradle daemon、进程 CWD=仓库根条件下复跑 `A11yRoundTripTest`（BUILD SUCCESSFUL，4/4 用例执行）——残渣 4 目录创建时间无任何变化（无新增文件、无新目录）。结论：**当前环境下 Robolectric 运行不再向 CWD 写出**；仓库根残渣是 2026-10-24 特定历史条件的一次性产物。
- 成因推断（证据边界内的最强结论）：时间窗（21:33 与 23:22 两波）与 round-8 当晚调试 Robolectric 环境的时段重合；PLAN-round8 风险节记载过 android-all-instrumented 误入 testImplementation、缓存锁排查等动作。该错误配置下（android-all-instrumented 在 classpath 且 Robolectric 以非标准方式引导）Robolectric 的 jar 解包/服务加载路径曾把 META-INF、shadows-framework 类、sqlite natives 以**相对路径**写到了当时的进程工作目录（= 仓库根）。确切注入点是 Robolectric 内部行为 + 当时异常 classpath 的组合，本轮不再深挖（一次性事件，修复方向不在 Robolectric 源）。
- 影响：`git status` 常态脏；`git add -A` / `git add .` 会把 ~3.9MB 二进制残渣（含 4 平台 so/dll）误提交进历史。
- 处置建议（双管齐下）：
  1. 删除 4 个残渣目录（工作区清理，不入库任何内容）；
  2. `.gitignore` 增补兜底条目（防再发）：
     ```
     # Robolectric/jar 解包残渣（BUG-047：曾泄漏到仓库根，2026-10-24）
     /META-INF/
     /android/
     /org/
     /sqlite4java/
     ```
  3. （可选，R9-O1）测试 JVM 工作目录固定化：`testOptions.unitTests.all { it.workingDir(project.layout.buildDirectory.dir("tmp/robolectric-cwd").get().asFile) }`——把"写出 CWD"的可能后果锁进 build/（天然被 ignore）。本轮未实测该配置对 Robolectric 4.12.2 的副作用，故列为建议而非必修。

### BUG-049 自更新资产选择靠数组顺序：v0.2.2 实测 debug 在前，可能下载 20MB debug 包

- 级别：P2（对外下载链路的选择健壮性；当前未触发、下次上传顺序变化即触发）
- 位置：`app/src/main/java/com/example/questtuner/update/SelfUpdater.kt:70-73`
- 代码：`o.optJSONArray("assets")?.let { arr -> (0 until arr.length()).map { ... }.firstOrNull { it.optString("name").endsWith(".apk") } }`
- 描述：`firstOrNull { name.endsWith(".apk") }` 对 assets **数组顺序**敏感。实测 Release v0.2.2（GitHub API `releases/latest`，2026-10-25 拉取）：`assets[0] = QuestTuner-v0.2.2-debug.apk`（20,307,702B，created 13:15:18），`assets[1] = QuestTuner-v0.2.2-release.apk`（1,768,014B，created 13:14:58）——**上传更晚的 debug 反而排在前面**（GitHub 按 asset id/上传完成序返回，created_at 与顺序无保证）。当前幸运点：debug 与 release 同签（debug keystore 复用，TODO 遗留决策），四重校验会全过 → 用户自更新装的是 20MB 的 debug 变体（R8 非优化构建，功能等价）。但：①11.5 倍体积差直接违背 R8 体积工程成果与 release notes 表述；②一旦 keystore 决策落地（release 换签），**签名校验将拒绝 debug 资产 → 自更新全体失败**，且报错文案（"签名证书不一致"）不会指向真正的根因（选错资产）。
- 证据：API 响应原文（assets 顺序、size、created_at、digest）已在取证时留档；`UpdateSafetyTest` 只测 checkHost/isTagSafe 纯函数，资产选择逻辑零测试。
- 建议验证方法：对 v0.2.2 release 调 checkLatest 逻辑（或临时打印 ReleaseInfo.url）→ 观察 browser_download_url 是否为 debug.apk。
- 处置建议：资产选择改为**偏好 release 变体**：先 `firstOrNull { name.endsWith("-release.apk") }`，fallback 才是任意 `.apk`；同时跳过明显非目标资产（如 `*sources*`/`*mapping*`）。一行级改动，下轮顺手修。

### BUG-050 BootReceiver 恢复结束无条件 disconnect：可杀掉用户同时段的显式连接

- 级别：P2（数据/会话破坏性竞态；触发窗口=开机后 30s 内用户打开 app 并连接，restoreOnBoot 默认关、实际概率低，但后果是"连上了却被莫名踢断"）
- 位置：`app/src/main/java/com/example/questtuner/tuner/BootReceiver.kt:33-41`
- 描述：开机恢复轮询循环（30s 窗口）结束后执行：
  ```kotlin
  TuningExecutor.disconnect()
  SecureAutomation.disableWifiAdbIfWeEnabled(appContext)
  ```
  两个问题：
  1. **无条件 disconnect**：`TuningExecutor.connection` 是全局单例。开机恢复成功（或超时失败）后断开是本意；但如果用户恰在这 30s 窗口内打开 app 点了"连接"（成功），BootReceiver 的 `disconnect()` 会把**用户刚建立的连接**关掉。UI 侧 `connectionState.collect` 会把状态拉回"连接已断开"，用户视角="刚连上就自己断了"。
  2. **disableWifiAdbIfWeEnabled 会关掉用户依赖的无线调试**：BootReceiver 不 markSessionActive（设计如此，注释明确），它自己一般没开过无线调试（ensureConnected 只连接不开启）——但若自动连接流程（`ensureWifiAdbThenConnect`）在窗口内由用户 connect 触发并开了无线调试（wifiAdbEnabledByUs=true），BootReceiver 收尾同样会把它关掉，连接随之断开。
  触发链：重启（restoreOnBoot=ON）→ BootReceiver 轮询（adbd 未就绪时最长 30s）→ 用户 10~20s 内进 app 点连接 → 连接成功 → BootReceiver 循环超时/成功退出 → disconnect 杀掉用户连接。失败路径同样中招：每轮 `ensureConnected` 失败都会 `conn.close()`，若用户连接恰好插在两次轮询之间，轮询的失败自清理只关自己的实例（安全），但**收尾 disconnect 是全局的**（不安全）。
- 证据：BootReceiver.kt:34-41 与 TuningExecutor.kt:20（单例 connection）/136-140（disconnect 无条件关）对照；BUG-027 会话语义（`adbSessionActive`）已存在却未被 BootReceiver 收尾检查。
- 建议验证方法：真机复现困难（双触发源）；代码走查可证：disconnect 前无任何 `adbSessionActive`/调用方身份检查。
- 处置建议：收尾改为条件断开——`if (!TuningExecutor.adbSessionActive) { TuningExecutor.disconnect(); SecureAutomation.disableWifiAdbIfWeEnabled(appContext) }`。语义：用户显式连接过（session active）→ 开机恢复不得收尾；非会话态 → 维持现状清理。一行改动 + 注释。

### BUG-051 ensureConnected 并发双重检查失效：双首连竞态泄漏第一条连接

- 级别：P2（资源泄漏 + 竞态；触发条件=两个并发调用方在"无连接"窗口同时进入。现有调用方：UI connect 流程、ForegroundProfileService 自愈、BootReceiver 轮询——服务自愈与用户连接并发是设计内场景，窗口虽窄但长期存在）
- 位置：`app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt:61-91`
- 描述：
  ```kotlin
  suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
      if (connection != null) return@withContext true   // ← 在 adbMutex 之外
      ...
      adbMutex.withLock { withTimeout(35_000L) { conn.connect(keyStore).getOrThrow() } }
      if (result.isSuccess) { connection = conn; ... }   // ← 无条件覆盖
  ```
  时序：协程 A 与 B 同时通过锁外的 `connection == null` 检查 → 各自 new AdbConnection → A 先拿锁 connect 成功，`connection = A` → B 拿锁，**未重检**，在自己新建的实例上 connect 成功（第二次 TCP 连接、可能再走一遍 AUTH），`connection = B` 覆盖 A → **实例 A 无 close、无引用**：socket 保持打开、adbd 侧会话悬挂，直到进程退出或连接自然死亡。期间所有命令都走 B，A 的 adbd 资源持续占用（对头显端 adbd 连接数是真实消耗）。
  与 `disconnect()` 竞态时更糟：disconnect 恰在 B 赋值前清场 → B 的连接存活但 `connectionState` 已广播 false，UI 显示断开实际连着。
  注：adbMutex 只保护了"connect 动作串行化"，没保护"检查+赋值"的复合原子性——双重检查锁定的标准形态要求两处检查都在锁内。
- 证据：代码走查（上述行号）；`AdbConnection.connect` 的 BUG-036 防御只挡"同实例重连"，不挡"实例被替换后旧实例悬挂"。
- 处置建议：把首次检查移入锁内并赋值前重检：
  ```kotlin
  adbMutex.withLock {
      if (connection != null) return@withContext true
      ... conn.connect ...
      if (result.isSuccess) { connection = conn } else { ... }
  }
  ```
  （或在赋值前 `if (connection != null) { conn.close(); return true }`——最小改动二选一。）

## P3

### BUG-048 A11yRoundTripTest 权限授予：双机制冗余 + 恒假死分支 + 注释归属写错（承重层一旦被"简化"全红）

- 级别：P3（测试可维护性；当前 4 用例全绿，风险在未来维护动作）
- 位置：`app/src/test/java/com/example/questtuner/tuner/A11yRoundTripTest.kt:28-54`
- 描述（本轮探针实验取证）：
  1. setup 里有**两套**授权机制并存：①PackageInfo 手术（:37-51，把 WRITE_SECURE_SETTINGS 塞进 requestedPermissions + requestedPermissionsFlags 置位 + `shadowPm.installPackage(pi)`）；②`shadowOf(app).grantPermissions(...)`（:52）。
  2. **探针实验**（临时 A11yPermProbeTest，取证后已删）：仅做②不做① → `enableA11yService` 返回 **NoPermission**（探针红）；完全不授权 → 同样 NoPermission（门禁真实生效，测试可失败性成立）。结论：**承重的是①（或①②组合），②单独无效**——`grantPermissions` 对 `checkPermission` 路径（SecureAutomation.hasWriteSecureSettings 走的正是它）不生效或不足以生效。
  3. 但 :32-35 注释把机制描述为"grantPermissions 对 ShadowContextImpl 生效但 checkPermission 走 ShadowPackageManager 路径"——暗示②有效、①是补丁；实测恰好说明**注释与承重事实相反/含混**，后人按注释理解"①是绕路补丁"顺手简化掉① → 4 用例全红，且错误信息（CapProbe.NoPermission）不指向权限授予层，排查成本高。
  4. :46-48 存在恒假死分支：
     ```kotlin
     for (i in perms.indices) {
         if (i < granted.size) granted[i] = granted[i] or 2
         else if (i == granted.size) {}   // ← 恒假（i 恒 < perms.size == granted.size），空体
     }
     ```
     语义上想处理"flags 数组比权限表短"的扩容，但循环边界决定 `i == granted.size` 永不成立，空 {} 也无操作——纯噪音。
  5. 次要：`installPackage(pi)` 与手动置 flags 后再 install 的组合属于"未文档化的 Shadow 行为依赖"（Robolectric 4.12 无 grantPermission(String,String) 是事实，但最小充分组合未固化在注释里）。
- 证据：探针测试运行记录（test-results XML：probe_grantPermissionsOnly_sufficient FAIL 于 NoPermission；probe_noGrant_gateReturnsNoPermission PASS）；探针文件取证后已从工作区删除（不留测试垃圾）。
- 处置建议：三选一并落注释：A) 删②（grantPermissions 行）——实验证明单独无效，留着误导；B) 删①留②并验证（若某 Shadow 路径下②可生效需实测，当前实验否决）；C) 保守：保持现状 + 重写注释明确"①承重、②冗余保底、删除任一层前先跑探针"。推荐 A+死分支清理（最小、诚实）。

### BUG-052 saveProfile 完成回填无条件清 editingProfile：可吞掉并发发起的新编辑态

- 级别：P3（状态机并发角落；窗口=saveProfile 的 IO 协程期间用户点了另一个"编辑"，概率低但模式上存在）
- 位置：`app/src/main/java/com/example/questtuner/tuner/TunerViewModel.kt:365-378`
- 描述：`saveProfile` 在 IO 完成后回填 `_state.value = _state.value.copy(profiles = ps, editingProfile = null)`。时序：用户在编辑 A → 点"保存修改"（协程启动）→ 立即点 ProfileRow B 的"编辑"（`editProfile(B)` 同步置 editingProfile=B）→ IO 完成 → 回填把 editingProfile 强制清 null → **用户正在编辑 B 的界面瞬间退回新建模式**（remember(edit) 键控把 B 的预填全丢）。若用户此时没注意已填了半截表单，点保存会按新建逻辑走（可能触发覆盖确认）。反向时序（先点编辑后保存完成 vs 保存完成后再点编辑）行为正常，问题仅在"保存进行中点编辑"。
  同构问题也存在于 deleteProfile（见 BUG-053，但那里是"该清不清"，本条是"不该清的清了"——同一回填机制的两个对称缺陷）。
- 处置建议：回填改成条件清——`editingProfile = _state.value.editingProfile?.takeIf { it != savedRef }`（只清"自己这个编辑对象"），或保存期间禁用编辑入口。前者一行级。

### BUG-053 删除正在编辑的 profile 不清编辑态：可"复活"已删除的 profile

- 级别：P3（数据一致性角落；无直接数据损坏——文件里已删，靠 UI 状态残留复活）
- 位置：`app/src/main/java/com/example/questtuner/tuner/TunerViewModel.kt:402-411`（deleteProfile 不清 editingProfile）+ `ui/TunerApp.kt:158-175`（remember(edit) 键控依赖 edit 引用变化）
- 描述：时序：用户点 ProfileRow X 的"编辑"（editingProfile=X，NewProfileCard 进入编辑态预填）→ 转到列表点 X 的"删除"（repo 删除成功、列表刷新，X 行消失）→ **编辑态仍在**（editingProfile 引用未清）→ NewProfileCard 标题仍是"编辑 Profile：X"→ 用户点"保存修改"→ `doSave` 走 `edit != null` 分支（无覆盖确认）→ `repo.save(X)` → **X 复活**（列表重新出现）。用户视角："我明明删了它，又回来了"。若用户只改了参数没动包名，复活的是旧包名+新参数的混合体。
  另一半角落在 Compose 层：`remember(edit)` 键控意味着只有 edit **引用变化**才重置字段——editingProfile 悬挂引用时表单内容与已删除数据保持一致，放大了"合理感"，用户更难察觉。
- 处置建议：`deleteProfile` 回填时对齐 BUG-052 的条件清逻辑（删的是当前编辑对象 → editingProfile = null）。与 BUG-052 同一处回填代码，建议一并修。

### BUG-054 仓库无 gradle wrapper：可复现构建缺最后一环，`.\gradlew.bat` 在仓库根直接失败

- 级别：P3（可复现性/入职体验；不影响已配好 toolchain 的现有环境）
- 位置：仓库根（gradlew/gradlew.bat/gradle/wrapper/* 全缺）；README.md:41-46（Build 节）
- 描述：
  1. `git log --all --diff-filter=A -- 'gradlew*' 'gradle/*'` 零命中——wrapper **从未入库**；
  2. README Build 节只写 `gradle :app:assembleDebug`（依赖系统 PATH 上的 Gradle 8.7+，README 已如实写要求）；
  3. 实测：在仓库根跑 `.\gradlew.bat` → CommandNotFoundException（文件不存在）——迭代简报与交接文档中"在仓库下运行 .\gradlew.bat"的说法对该仓库当前状态不可执行；
  4. 全团队构建都走 `F:\workspace\toolchain\gradle-extract\gradle-8.7\bin\gradle.bat -p F:\workspace\QuestTuner`（VERIFY-round1~6 留档命令），**Gradle 8.7 版本只存在于 toolchain 惯例，仓库内无任何机器可读的固定**——新环境 Gradle 版本漂移是 AGP 项目经典翻车点。
- 处置建议：`gradle wrapper --gradle-version 8.7` 生成并提交 gradlew.bat + gradle/wrapper/*（distributionSha256Sum 建议同时固定）；README Build 节改写为 `.\gradlew.bat :app:assembleDebug`。注意 distribution zip 下载需网络——离线环境下首次 ./gradlew 会失败，可配 distributionUrl 指向本地 file:// 或保留 toolchain 说明作为离线 fallback（lead 决策）。

---

## 优化建议（R9-OX）

| # | 位置 | 建议 |
|---|---|---|
| R9-O1 | app/build.gradle.kts testOptions | 测试 JVM workingDir 固定到 build/tmp/robolectric-cwd，把 Robolectric 类加载期任何 CWD 写出锁进 build/（BUG-047 兜底的根治半步；需先验证无副作用） |
| R9-O2 | SelfUpdater.checkLatest | BUG-049 修复：`-release.apk` 偏好选择 + fallback 任意 .apk（一行级） |
| R9-O3 | BootReceiver / TuningExecutor | BUG-050 条件断开（`!adbSessionActive` 才收尾）+ BUG-051 双重检查入锁——两个都是小改动，建议同轮打包 |
| R9-O4 | TODO-round-next.md | 若 lead 采纳 BUG-054，把 "gradle wrapper 入库" 记入发布检查单或环境备忘节（新环境 one-liner：先 gradle wrapper 再构建） |

## 已核对无问题（本轮通过项）

- **今日复跑实验**：A11yRoundTripTest 4/4 绿（Robolectric 4.12.2 / SDK 34，fresh daemon，CWD=仓库根）——round-8 交付质量本身成立，残渣非现行测试运行产生 ✅
- **A11yRoundTripTest 可失败性**：无授权时门禁真实拦截（探针 2 返回 NoPermission）——测试不是恒真 ✅
- **测试套件总量**：35 用例（9 个测试类）结构完整，与 round-8 交付记录一致 ✅
- **foregroundService / a11y 日志循环截断**：a11y.log 64KB 粗截断、boot.log 16KB 截断逻辑成立（appendText 前检查）✅
- **shell 输出上限链**：AdbConnection MAX_SHELL_OUTPUT 8MB 累计闸 + readPacket 单包 1MB 闸双层成立（BUG-035 修复完好）✅
- **AdbKeyStore 自愈链**：损坏/旧格式 pubkey → 删除重建路径完整（legacy n0inv 校验 + getOrDefault(false) 兜底）✅
- **AppScanner 无稳定性回归**：label/icon 均 runCatching 包裹（round-3 教训保持）✅
- **manifest 权限面**：仅 INTERNET/REQUEST_INSTALL_PACKAGES/RECEIVE_BOOT_COMPLETED 三项，无多余授权（BUG-049 不涉及权限变化）✅
- **checkLatest 404 语义**：无 Release 如实报错不假装最新（:62），版本比较前置于下载（:75）✅

## 修复优先级建议

1. **BUG-047**（.gitignore 兜底 + 残渣清理——10 分钟，下次 git add 前必须做，防 3.9MB 二进制误入库）
2. **BUG-050 + BUG-051**（R9-O3 打包修：连接生命周期两处，都是小 diff 但都是"用户连接被莫名杀掉/资源悬挂"级后果）
3. **BUG-049**（R9-O2 一行级；keystore 换签决策落地前修掉，避免换签后自更新全军覆没）
4. **BUG-052 + BUG-053**（同一处回填逻辑的两个对称缺陷，建议一起修）
5. **BUG-048**（测试注释/死分支清理，随任意轮顺手）
6. **BUG-054**（lead 决策：wrapper 入库 + distributionUrl 策略）
