# QuestTuner Round 8 测试质量专项方案（PLAN-round8）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round7.md（David，BUG-044~046 + 测试盲区分析第④线）+ task-28 描述
- 基线：master @ 79b3d98；修复在 `fix/round-8` 分支进行
- 行号基于 79b3d98 核对；标注【改】【删】【增】同前几轮惯例
- 本轮性质：测试质量专项（两个针对性测试）+ 一个文档顺带修；无生产代码逻辑改动

## 总览

| 项 | 来源 | 结论 | 涉及文件 |
|---|---|---|---|
| ① 编辑三旅程 Compose UI 测试 | 盲区 1（最高危） | 实施（Robolectric + createComposeRule，VM 直构无需接口抽象） | 新增 app/src/test/.../ui/EditJourneyUiTest.kt、build.gradle.kts |
| ② ShadowSettings a11y 往返 Robolectric | 盲区 3（BUG-025 回归防线） | 实施（enable→幂等→disable 闭环 + 第三方服务保留红线） | 新增 app/src/test/.../tuner/A11yRoundTripTest.kt |
| ③ README Privacy 节标题正文重复 | BUG-045 遗留观察（未修，已核实 :76-78 双语均在） | 修（删正文开头重复词） | README.md、README.zh-CN.md |
| ④ R7-O3/O4 | 任务④ | O3 **已落地零改动**（核对 TODO-round-next.md:15-19 五项处置全在）；O4 即本方案①② | — |

**建议实施顺序**：① build.gradle.kts 依赖与 testOptions（两项测试的共同前置）→ ② A11yRoundTripTest（纯 Robolectric 无 Compose 依赖，先验证 Robolectric 环境跑通）→ ③ EditJourneyUiTest（Compose 环境叠加）→ ④ README 双语两行。

---

## 依赖增量与构建兼容性评估（先行结论，决定①②可行性）

**新增依赖**（app/build.gradle.kts dependencies 块【增】）：

```kotlin
    // ---- Round8 测试质量专项 ----
    testImplementation("org.robolectric:robolectric:4.12.2")     // 盲区3 框架交互测试
    debugImplementation("androidx.compose.ui:ui-test-manifest")  // Compose 测试需要（BOM 管版本）
    testImplementation("androidx.compose.ui:ui-test-junit4")     // 盲区1 Compose UI 测试（BOM 管版本）
```

**android 块【增】**（Robolectric 访问资源必需）：

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

兼容性核对（逐项）：
1. **版本对齐**：`ui-test-junit4`/`ui-test-manifest` 走既有 compose-bom 2024.06.00（与 ui/material3 同源，无版本漂移）；Robolectric 4.12.2 支持 SDK 34（compileSdk 34 ✓）、支持 natural SDK 选择（@Config(sdk=[34]) 显式钉住更稳，见测试文件）。
2. **R8/产物影响**：`testImplementation`/`debugImplementation` 不进 release classpath——release APK 体积与混淆零变化（David round-5/7 的产物级验证结论不被破坏）。`ui-test-manifest` 仅 debug 变体注入 test-only Activity，release 无感。
3. **既有 31 用例**：纯 JVM 测试不受 Robolectric 引入影响（runner 不同类，各跑各的）；`testDebugUnitTest` 总任务合并执行。
4. **Robolectric 与 org.json:json 测试依赖**：SelfUpdater 既有测试用真实 org.json——Robolectric 自带 Android org.json 实现，类路径并存无冲突（不同测试类各自 @RunWith）。
5. **已知边界**：Robolectric 下 Compose 测试的 Main dispatcher = Robolectric main looper（非真主线程）——VM 协程断言需 idle looper，方案 ①(e) 给出具体写法；这是标准做法非风险。

---

## ① 编辑三旅程 Compose UI 测试（盲区 1，最高危）

### VM 依赖注入评估（任务点名）

`TunerViewModel(app: Application)` 是 AndroidViewModel，依赖 repo/prefs（构造于 filesDir/SP）+ 五个 object 单例（SecureAutomation/TuningExecutor/SelfUpdater/OvrMetrics/PrivateDns）。**评估结论：不需要接口抽象**——理由：
1. `createComposeRule()`（非 Activity 版）+ Robolectric Application 环境下可直接 `TunerViewModel(application)` 实例化——repo/prefs 自动落到 Robolectric 沙箱 filesDir（每次测试临时目录，天然隔离）；
2. VM init 的副作用在 Robolectric 下全部安全降级：`refreshCapabilities()` → `hasWriteSecureSettings`（Shadow PackageManager 默认 denied → grant=false，不触发 a11y 对账分支）、`isA11yRunning`（Shadow AccessibilityManager 空列表 → false）、`reconcileWifiAdbOnStartup`（wifiAdbByUs 默认 false → no-op）；`SelfUpdater.installResult`/`TuningExecutor.connectionState` collect 初始 null/false 不发射——**init 无网络、无系统写、无崩溃路径**；
3. 编辑三旅程不触碰 ADB/网络（saveProfile 只写本地 repo）——object 单例的连接态与本测试无交集。
接口抽象（repo/prefs 抽 interface + VM 构造注入）需改生产代码 ~6 处签名，为测试改生产结构违背本轮「无生产逻辑改动」定位——**落选，记录**。

### 测试文件

【增】`app/src/test/java/com/example/questtuner/ui/EditJourneyUiTest.kt`（放 test 而非 androidTest：Quest 项目无仪器环境/模拟器 CI，Robolectric 本地跑最务实）：

```kotlin
package com.example.questtuner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.questtuner.tuner.AppProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-041 编辑三旅程 UI 状态机回归（round-7 盲区 1）：
 * 旅程1 新建撞名 → 覆盖确认弹窗（确认/取消）
 * 旅程2 编辑预填 + 取消编辑（remember(edit) 键控重置）
 * 旅程3 保存修改 → 退出编辑态回新建默认
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditJourneyUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val existing = AppProfile(
        packageName = "com.game.a", label = "Game A",
        refreshRate = 90, textureWidth = 1680, textureHeight = 1760, cpuLevel = 2, gpuLevel = 2,
    )

    private fun boot(vm: TunerViewModel) {
        compose.setContent { TunerApp(viewModel = vm) }
        compose.waitForIdle()
    }

    // ---- 旅程 1：新建撞名 → 覆盖确认 ----

    @Test
    fun newSave_existingPkg_showsOverwriteDialog_confirmSaves() {
        val vm = TunerViewModel(compose.activityScenarioRule.scenario.result.resultData.application)
        // 简化：直接用 Robolectric application（Wang 按 (e) 节的 Application 获取方式落地）
        vm.saveProfileBlocking(existing)          // 见 (e) 辅助：预置已存在 profile
        boot(vm)

        compose.onNodeWithText("包名").assertExists()          // NewProfileCard 在树上
        compose.onNodeWithText("com.example.app（留空=全局）")  // placeholder 定位输入框
            .performTextInput("com.game.a")
        compose.onNodeWithText("保存 Profile").performClick()

        compose.onNodeWithText("覆盖确认").assertIsDisplayed()   // 弹窗出现
        compose.onNodeWithText("覆盖保存").performClick()

        compose.onNodeWithText("覆盖确认").assertDoesNotExist()  // 弹窗关闭
        // 断言保存生效（列表行出现，值已替换——refreshRate 仍 90 默认）
        compose.onNodeWithText("Game A", substring = true).assertIsDisplayed()
    }

    @Test
    fun newSave_existingPkg_dialogCancel_doesNotSave() {
        val vm = ...同上预置...
        boot(vm)
        ...输入 com.game.a、点保存...
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("覆盖确认").assertDoesNotExist()
        // 取消后列表仍只有一行（未新增第二条）
    }

    // ---- 旅程 2：编辑预填 + 取消 ----

    @Test
    fun editButton_prefillsForm_cancelRestoresNewMode() {
        val vm = ...预置 existing（refreshRate=90）...
        boot(vm)
        compose.onNodeWithText("编辑").performClick()           // ProfileRow 编辑入口

        // 预填断言：编辑标题 + 包名输入框含现有包名
        compose.onNodeWithText("编辑 Profile：Game A").assertIsDisplayed()
        compose.onNodeWithText("保存修改").assertIsDisplayed()   // 编辑模式按钮文案

        compose.onNodeWithText("取消编辑").performClick()
        compose.onNodeWithText("新建 Profile").assertIsDisplayed()      // 回新建标题
        compose.onNodeWithText("保存 Profile").assertIsDisplayed()      // 回新建按钮
    }

    // ---- 旅程 3：保存修改 → 退出编辑态（remember(null) 键控重置）----

    @Test
    fun editSave_exitsEditMode_backToNewDefaults() {
        val vm = ...预置 existing...
        boot(vm)
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("120").performClick()            // 改刷新率 chip
        compose.onNodeWithText("保存修改").performClick()

        // 保存完成 → editingProfile=null → remember(null) 重置 → 回新建模式
        compose.onNodeWithText("新建 Profile").assertIsDisplayed()
        compose.onNodeWithText("保存 Profile").assertIsDisplayed()
        // 修改已落库：ProfileRow 副文案含 120Hz
        compose.onNodeWithText("120Hz", substring = true).assertIsDisplayed()
    }

    // ---- 附带：包名非法行内错误（R6-O1 的 isError 联动，同文件顺带）----

    @Test
    fun invalidPkg_showsInlineError() {
        val vm = ...
        boot(vm)
        compose.onNodeWithText("com.example.app（留空=全局）")
            .performTextInput("abc")                            // 单段非法
        compose.onNodeWithText("保存 Profile").performClick()
        compose.onNodeWithText("包名格式不正确", substring = true).assertIsDisplayed()
    }
}
```

### 实现要点（Wang 必读）

**（a）Application 获取**：`createComposeRule()` 无 Activity——用 Robolectric：
```kotlin
private val app = org.robolectric.Robolectric.buildApplication(android.app.Application::class.java).get()
```
或 `ApplicationProvider.getApplicationContext()`（androidx.test:core，Robolectric 传递依赖自带）。VM 构造 `TunerViewModel(app)`。上面代码块中 `compose.activityScenarioRule...` 写法作废，以此为准。

**（b）saveProfileBlocking 辅助**：saveProfile 是异步协程（viewModelScope.launch + IO）——测试预置需同步等待：
```kotlin
private fun TunerViewModel.saveProfileBlocking(p: AppProfile) {
    saveProfile(p)
    compose.waitForIdle()   // idle Compose + Main looper
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    // IO 协程：Robolectric 下 Dispatchers.IO 是真线程池——轮询 state 直到 profiles 更新（上限 2s）
    val deadline = System.currentTimeMillis() + 2_000
    while (state.value.profiles.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
}
```
（同步 repo 直写替代方案更简单：测试直接 `ProfileRepository.of(app.filesDir).save(p)` 预置 + VM init 读到——**推荐**，绕开协程等待；VM profiles 来自 init 协程读 repo，boot() 的 waitForIdle + 上面 idle 已覆盖。Wang 择一，推荐直写 repo。）

**（c）断言锚点**：全部用文案定位（中文单语产品，测试与 UI 文案耦合是可接受的取舍——文案变更时测试同步改，恰恰是文案回归的意外防线）。`substring = true` 用于带动态值的断言（"120Hz " 副文案、"编辑 Profile：Game A"）。

**（d）FilterChip "120" 定位歧义**：QuickTuneCard 也有 "120"（`120Hz` 按钮文案是 "120Hz" 非 "120"）——FilterChip label 是纯 "120"，无碰撞；若仍歧义用 `onAllNodesWithText("120")[index]` 或 hasClickAction() 组合——Wang 调试时处理，不阻塞。

**（e）VM 协程与测试时序总原则**：同步路径（editProfile/cancelEdit/precheckSave/profileExists/StateFlow 更新）断言只需 `waitForIdle()`；异步路径（saveProfile）用 (b) 的直写 repo 预置规避 + 保存后轮询 state。**不引入 IdlingResources**（为此要改生产 VM，违背本轮定位）。

### 风险与注意事项

- Robolectric + Compose 组合首次跑通可能遇到的已知坑：a) `ui-test-manifest` 缺失 → NoSuchAlgorithm/Activity 未注册——依赖清单已含；b) `Config(sdk=[34])` 需 Robolectric 4.12+（已选）；c) Windows 文件锁（Robolectric 缓存）——David 构建环境已跑过 Robolectric 类测试则无新问题（本轮首次引入，若遇 `Could not lock ... robolectric` 清 `~/.m2/repository/org/robolectric` 缓存重试）。
- 测试文件放 `src/test`（Robolectric）而非 `src/androidTest`：无仪器环境；若未来建 CI 模拟器可平移（改动仅 @RunWith 与目录）。
- 旅程覆盖边界：不测「编辑中换目标」（点编辑 B）——remember(edit) 键控已由旅程 2/3 间接覆盖（进编辑=键变化触发重置的同机制）；如 Wang 有余量可加一条，非必须。

### 验证方法

`gradle :app:testDebugUnitTest` 全绿（31 旧 + 本文件 5 新 + ②文件 4 新 = 40）；故意破坏验证（可选）：临时注释 TunerApp.kt:290 的 `edit == null` 条件（编辑模式也弹确认）→ 旅程 3 测试应红——证明测试真的看住这条路径。

---

## ② ShadowSettings a11y 往返 Robolectric 测试（盲区 3，BUG-025 回归防线）

### 测试目标

`SecureAutomation.enableA11yService/disableA11yService`（SecureAutomation.kt:101-144）的 Settings.Secure 真实读写闭环——补上 BUG-025 修复（putString 返回值检查 + disable 回读）的自动化回归防线；同时覆盖 mergeA11yList 的序列化边界（追加/幂等/第三方保留——D-16 红线的测试版）。

### 前置条件处理（关键）

`enableA11yService` 第一行 `hasWriteSecureSettings` 门禁——Robolectric ShadowPackageManager 默认 **denied**，必须显式授 权：
```kotlin
shadowOf(app.packageManager).grantPermission(
    app.packageName, android.Manifest.permission.WRITE_SECURE_SETTINGS)
```
（`org.robolectric.Shadows.shadowOf`；ShadowSettings 的 Settings.Secure putString/getString 在 Robolectric 默认可用。）

### 测试文件

【增】`app/src/test/java/com/example/questtuner/tuner/A11yRoundTripTest.kt`：

```kotlin
package com.example.questtuner.tuner

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BUG-025 回归防线（round-7 盲区 3）：SecureAutomation a11y 两键的 Settings 往返闭环。
 * 覆盖：追加式写入 / 幂等重入 / 回读探测 / 第三方服务保留红线 / accessibility_enabled 联动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A11yRoundTripTest {

    private lateinit var ctx: Context
    private val flat: String by lazy { SecureAutomation.a11yComponentName(ctx).flattenToString() }

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(ctx.packageManager).grantPermission(
            ctx.packageName, android.Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", "")
    }

    private fun enabledList(): String =
        Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: ""

    @Test
    fun enableWritesAndReadsBack() {
        val r = SecureAutomation.enableA11yService(ctx)
        assertTrue("enable 应 Ok（实际 $r）", r is SecureAutomation.CapProbe.Ok)
        assertTrue(enabledList().split(':').contains(flat))          // 往返：写入可读回
        assertEquals("1", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))           // 总开关联动
    }

    @Test
    fun enableIsIdempotent_noDuplicate() {
        SecureAutomation.enableA11yService(ctx)
        SecureAutomation.enableA11yService(ctx)                       // 二次 enable
        assertEquals(1, enabledList().split(':').count { it == flat }) // 不重复追加
    }

    @Test
    fun disableRemovesOurs_keepsThirdParty() {
        // 预置第三方服务（红线：我们开/关不动它）
        Settings.Secure.putString(ctx.contentResolver,
            "enabled_accessibility_services", "com.other.svc/.OtherService")
        SecureAutomation.enableA11yService(ctx)
        assertTrue(enabledList().contains("com.other.svc/.OtherService"))   // 追加式保留

        val r = SecureAutomation.disableA11yService(ctx)
        assertTrue(r is SecureAutomation.CapProbe.Ok)
        val list = enabledList().split(':')
        assertFalse(list.contains(flat))                              // 我们已移除
        assertTrue(list.contains("com.other.svc/.OtherService"))      // 第三方保留
        // 第三方仍在 → accessibility_enabled 不应为 0（disable 的 rest 非空分支）
        assertEquals("1", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))
    }

    @Test
    fun disableLastService_clearsMasterSwitch() {
        SecureAutomation.enableA11yService(ctx)
        SecureAutomation.disableA11yService(ctx)
        assertEquals("0", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))            // 移除至空 → 总开关关
        assertEquals("", enabledList())
    }
}
```

（`androidx.test:core` 的 `ApplicationProvider` 经 Robolectric 传递可用；若类路径缺失则改 `Robolectric.buildApplication(Application::class.java).get()`——Wang 按编译结果择一。）

### 风险与注意事项

- ShadowSettings 对 `Settings.Secure.putString` 返回值语义：Robolectric 实现为真实 Map 写入（返回 true）——BUG-025 的「返回 false 拒绝」分支在 Shadow 下不可达（无法注入失败），该分支防线仍靠代码走查（记录：Shadow 限制，非方案缺陷）。
- `flattenToString()` 形如 `com.example.questtuner/tuner.ForegroundProfileService`——含 `/` 不含 `:`，与列表分隔符无冲突（序列化边界结论在测试中实证）。
- ForegroundProfileService 类引用（a11yComponentName 内）在 Robolectric 类加载无障碍服务实例化——只构造 ComponentName 字符串，不触发服务生命周期。
- 四用例覆盖 mergeA11yList 的边界（空串起步/幂等/含第三方）既有 MergeA11yListTest 的纯函数层互补——纯函数测合并逻辑，本文件测「合并逻辑接上真 Settings 读写」的整环。

### 验证方法

`testDebugUnitTest` 全绿；破坏性验证（可选）：临时注释 SecureAutomation.kt:141-142 的 disable 回读 → disableRemovesOurs 仍绿（回读是探测非行为）但若注释 :130-131 的过滤逻辑 → 第三方保留断言红——证明测试看住过滤行为本身。

---

## ③ README「Privacy 节标题正文重复」双语顺带修（BUG-045 遗留观察）

已核实未修：README.md:76-78（`## Privacy` 标题 + 正文 `**Privacy.** The only network...`——标题词在正文句首重复）；README.zh-CN.md:76-78（`## 隐私` + `**隐私**。唯一的网络行为...`）同构重复。round-6 方案的文案模板照搬所致（Li 的方案瑕疵，本轮清账）。

- 涉及文件：`README.md`、`README.zh-CN.md`
- 具体改动点：
  - README.md:78【改】：`**Privacy.** The only network behavior is...` → `The only network behavior is the GitHub Releases API request made when you tap "Check for update" (api.github.com). Profiles, tuning parameters, and the accessibility service (which reads only the foreground app's package name, never screen content) are fully local. No analytics, no tracking, no data upload.`（仅删句首 `**Privacy.** ` 重复词，其余原文不动）
  - README.zh-CN.md:78【改】：`**隐私**。唯一的网络行为是...` → `唯一的网络行为是你主动点击「检查更新」时访问 GitHub API（api.github.com）。Profile、调参、无障碍服务（只读前台应用包名，绝不读取屏幕内容）全部本地完成。无统计、无追踪、无数据上传。`（同法删 `**隐私**。`）
- 风险：无（纯文案；Privacy 节其余内容与 Release notes 的一致性 David round-7 已核对，不动）。
- 验证方法：grep `Privacy\.\*\*|\*\*隐私\*\*` 双语零命中；`## Privacy` / `## 隐私` 标题保留各 1 处。

---

## ④ R7-O3 / R7-O4 评估

| # | 结论 | 说明 |
|---|---|---|
| R7-O3（TODO 五项处置更新） | **已落地，本轮零改动** | 已核对 docs/plans/TODO-round-next.md:15-19：R5-O2 关闭（round-7 审计标注）、A7 转实机任务、keystore 转 lead 决策项、R4-O7 维持+触发条件、R5-O1 半步建议——五项处置全部在案。无重复劳动 |
| R7-O4（下轮补两个针对性测试） | **即本方案①②** | 本轮执行体。其余盲区（协程时序/集成链）维持「纯函数+实机清单」现状——David 自评投入产出比合理，本方案不扩大 |

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-8`（master @ 79b3d98 为基）。
2. **顺序**：① 依赖+testOptions → ② A11yRoundTripTest（Robolectric 环境先跑通）→ ③ EditJourneyUiTest（Compose 叠加）→ ④ README 两行。若 ③ 遇 Robolectric+Compose 环境问题（①节的已知坑），允许只交付 ②+④ 并把 ③ 的阻塞细节回报 lead（不静默降级）。
3. **生产代码零改动纪律**：本轮不碰 app/src/main 任何文件（README 是文档；测试与构建配置除外）。若 ① 的 VM 实例化确需生产代码让步（评估结论是不需要），先回报 lead 再动。
4. **测试数量预期**：31 旧 + ② 4 + ① 5 = 40 用例；`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` 三件套全绿（release 体积不变可顺带核对依赖不渗漏）。
5. **交付后自验**（增量）：
   1. 测试报告 40/40；破坏性抽查一条（①节验证方法）
   2. release APK 体积与 round-7 取证基线一致（依赖不渗漏的实证）
   3. README 双语 Privacy 节 grep 零重复
