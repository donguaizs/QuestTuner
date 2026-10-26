# QuestTuner Round 10 优化方案（PLAN-round10）

- 制定：Li（技术方案）
- 依据：docs/bugs/OPT-REPORT-round10.md（David，fe9ce00）+ lead 决策（2026-10-26，四项全做）
- 基线：master @ bca3415；本轮在 `fix/round-10` 分支进行
- 行号基于 fe9ce00 核对；【改】【删】【增】同前几轮惯例
- 本轮性质：性能/UX 优化专项（无缺陷修复、无依赖变化、无构建配置变化）。否决项（R9-O1 workingDir、release 体积）不进方案——David 已量化否决。
- lead 两点提醒已吸收：② 等价读法读 `ri.activityInfo.applicationInfo?.metaData`（**不是** `ri.activityInfo.metaData`）；③ 只做非空态刷新入口，不引入新缓存结构/广播监听/TTL。

## 总览（建议实施顺序即编号顺序）

| # | 来源 | 结论 | 涉及文件 | 量级 |
|---|---|---|---|---|
| ① | OPT ② N+1 IPC | 实施（GET_META_DATA + 等价读法 + Robolectric 双样本回归） | AppScanner.kt、新增 AppScannerVrClassifyTest.kt | 重点 |
| ② | OPT ① 图标内存 | 实施（方案 A 扫描期降采样 64px Bitmap） | AppScanner.kt、AppPickerDialog.kt | 重点 |
| ③ | OPT ③ 刷新入口 | 实施（非空态刷新按钮，一行级） | AppPickerDialog.kt | 一行 |
| ④ | OPT ④ Profile 搜索 | 实施（搜索框 + 纯函数 + 3 单测） | TunerApp.kt、新增 ProfileFilterTest.kt | ~25 行 |
| ⑤ | 顺带 | 实施（TODO-round-next R5-O1 条目清账） | docs/plans/TODO-round-next.md | 2min |

①② 同在 AppScanner.kt，建议一次改完一次 commit；③② 同在 AppPickerDialog.kt 同理。测试数预期 **38 旧 + ① 3 + ④ 3 = 44 全绿**（lead 预估 42~44 上沿）。

---

## ① N+1 IPC 消除（重点）

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/tuner/AppScanner.kt`【改】
  - 【增】`app/src/test/java/com/example/questtuner/tuner/AppScannerVrClassifyTest.kt`
- 具体设计：

（1）AppScanner.kt:33【改】：

```kotlin
        // OPT-round10 ①：GET_META_DATA 一次带全（component 级 + application 级 meta 均填充，
        // 语义等价论证见 OPT-REPORT-round10 ②节）——消除每应用一次 getActivityInfo 的 N+1 IPC
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
```

（2）hasVrCategory【改】：签名去掉 `pm` 参数（不再需要任何 IPC），调用点 :38 同步改 `hasVrCategory(ri)`。步骤 2（:64-76）替换为纯字段读取：

```kotlin
        // 2) 兜底：application 级 meta-data（queryIntentActivities 已带 GET_META_DATA，零额外 IPC）
        // ★读 applicationInfo.metaData（application 级）；activityInfo.metaData 是 component 级，多数应用为空
        val appMeta = ri.activityInfo.applicationInfo?.metaData
        val metaVr = appMeta?.getBoolean(VR_CATEGORY_META, false)
            ?: appMeta?.getBoolean(VR_CATEGORY_META2, false)
            ?: false
        if (metaVr) return true
        return packageFallback(ri.activityInfo.packageName)
```

（3）【删】原 :65-70 的 `runCatching { pm.getActivityInfo(...) }` 整段（含内联 ComponentName 构造）；filter categories 优先段（:57-63）与 packageFallback（:79-82）**一字不动**。

（4）回归测试【增】（Robolectric，复用 round-8 基建：不新增依赖、不动 user.home/maven.repo.local、严禁 android-all-instrumented 进 testImplementation）：

```kotlin
package com.example.questtuner.tuner

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * OPT-round10 ①回归防线：queryIntentActivities 一次带全 GET_META_DATA 后，
 * isVr 分类语义不丢。双样本覆盖两条判定路径：filter category（标准 VR 声明）、
 * application meta（兜底声明）；加 2D 反例与 packageFallback 前缀兜底。
 * 注：Shadow 对注入 ResolveInfo 原样返回（不模拟 flags 差异）——本测试防线是
 * 「读法 + 分类语义」；flags 是否带 GET_META_DATA 由代码走查 + grep 确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppScannerVrClassifyTest {

    private lateinit var ctx: android.content.Context
    private lateinit var pm: PackageManager

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        pm = ctx.packageManager
    }

    private fun ri(pkg: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    private fun install(name: String, vrFilter: Boolean, appMetaVr: Boolean) {
        val r = ri(name)
        r.filter = IntentFilter().apply {
            addAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_LAUNCHER)
            if (vrFilter) addCategory("com.meta.intent.category.VR")
        }
        if (appMetaVr) {
            r.activityInfo.applicationInfo.metaData =
                Bundle().apply { putBoolean("com.oculus.intent.category.VR", true) }
        }
        shadowOf(pm).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), r)
    }

    @Test
    fun vrByFilterCategory_classifiedVr_2dStaysTwoD() {
        install("com.game.filtervr", vrFilter = true, appMetaVr = false)
        install("com.example.twod", vrFilter = false, appMetaVr = false)
        val apps = AppScanner.query(ctx)
        assertTrue(apps.first { it.packageName == "com.game.filtervr" }.isVr)
        assertFalse(apps.first { it.packageName == "com.example.twod" }.isVr)
    }

    @Test
    fun vrByApplicationMeta_classifiedVr_metaFallbackPath() {
        // filter 无 VR category、application meta 有 → 走兜底读法（① 的等价读法正是此路径）
        install("com.game.metavr", vrFilter = false, appMetaVr = true)
        val apps = AppScanner.query(ctx)
        assertTrue(apps.first { it.packageName == "com.game.metavr" }.isVr)
    }

    @Test
    fun packageFallback_prefixStillWorks_andOwnPackageExcluded() {
        install("com.oculus.some2dtool", vrFilter = false, appMetaVr = false)
        install(ctx.packageName, vrFilter = false, appMetaVr = false)
        val apps = AppScanner.query(ctx)
        assertTrue(apps.first { it.packageName == "com.oculus.some2dtool" }.isVr)
        assertFalse(apps.any { it.packageName == ctx.packageName })   // 既有自身过滤不回归
    }
}
```

实现备注：`shadowOf(pm).addResolveInfoForIntent(Intent, ResolveInfo)` 是 Robolectric 4.x ShadowPackageManager 既有 API；若签名有出入用其变体（`addOrUpdateResolveInfoForIntent` 等）调整，语义=让 `queryIntentActivities(MAIN+LAUNCHER)` 返回注入样本。测试只断言 isVr 分类，不断言图标（shadow 图标链路非本轮语义，验收在实机）。

- 验收指标（引用 David 报告 §②）：
  1. grep `getActivityInfo` 于 AppScanner.kt **零命中**；`GET_META_DATA` 出现于 queryIntentActivities 一处；
  2. 新增 3 用例绿（filter 样本/meta 样本/2D 反例+前缀兜底）；
  3. 实机可选：loadApps 临时计时日志改前后对比（预期首扫 -100~400ms，估算值）；顺带打印 query 返回数取 N/N₂ 实际值（David 实机待补清单③）。
- 风险：
  - ShadowPackageManager 注入 API 细节可能与预期签名有出入——测试内部调整，不触碰 Robolectric 基建配置；
  - GET_META_DATA 使每条 ResolveInfo 多携带一个 Bundle（1 次查询内聚发生）——量级远小于 N₂ 次 binder 往返（90~135 次/150 应用），净收益为正；
  - meta 声明若真实应用用 string 型——现生产读法即 getBoolean，测试样本与生产读法一致，本轮不改语义。

---

## ② 图标扫描期降采样（重点，方案 A）

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/tuner/AppScanner.kt`【改】
  - `app/src/main/java/com/example/questtuner/ui/AppPickerDialog.kt`【改】
- 具体设计：

（1）类型结论（lead 征询）：**存 `android.graphics.Bitmap`**——AppScanner 在 tuner 包，不应依赖 Compose 类型；Compose 层 `asImageBitmap()`。已 grep 证实 `.icon` 全仓库仅 AppScanner（构造）与 AppPickerDialog:92-93（消费）两处，类型改动封闭在这两个文件，无第三方波及。

（2）InstalledApp【改】（AppScanner.kt:7-12）：

```kotlin
import android.graphics.Bitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    /** OPT-round10 ②：扫描期已降采样的 64px 小图（杜绝全尺寸 Drawable 常驻）；null=无图标 */
    val icon: Bitmap?,
    val isVr: Boolean,
)
```

（3）AppScanner.kt:42【改】（扫描本来就在 IO 线程，降采样就地完成）：

```kotlin
    // OPT-round10 ②：扫描期降采样 64px——内存 N×16KB（150 应用≈2.4MB，vs 全尺寸 Drawable 21~150MB）
    icon = runCatching { ri.loadIcon(pm).toBitmap(64, 64) }.getOrNull(),
```

【增】`import androidx.core.graphics.drawable.toBitmap`（core-ktx 已有，API 从 AppPickerDialog 迁来，无新依赖）。

（4）AppPickerDialog AppPickerRow【改】（:89-94 与 :101-103）：

```kotlin
@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    // OPT-round10 ②：icon 已是扫描期 64px Bitmap，直接转 ImageBitmap（删渲染期 toBitmap 重转换）
    val bmp = remember(app.icon) { app.icon?.asImageBitmap() }
    ...
            if (bmp != null) {
                Image(bmp, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Spacer(Modifier.size(40.dp))   // ②-b：无图标保留占位，行布局不塌
            }
```

【删】:31 `import androidx.core.graphics.drawable.toBitmap`（本文件不再用）；【增】`import androidx.compose.foundation.layout.Spacer`；`asImageBitmap` import（:27）已有保留。

（5）64px 与现渲染端一致（改前就是 toBitmap(64,64)），无画质回退。

- 验收指标（引用 David 报告 §①）：
  1. `InstalledApp.icon` 类型为 Bitmap（diff 可见）；grep `loadIcon` 全仓库唯一命中 AppScanner 且紧跟 toBitmap——全尺寸 Drawable 零持有点；
  2. 实机 `dumpsys meminfo com.example.questtuner`：打开选择器滚动一遍前后 Native Heap+Graphics 差值 ≤ ~5MB（vs 改前基线，David 实机待补清单①：改前先取基线再合入）；
  3. 44 用例全绿；release APK 体积变化 ≤ ±20KB（无依赖变化佐证）。
- 风险：
  - 扫描期多 N 次 toBitmap（IO 线程 ~百 ms 级/150 应用，估算）——loadApps 有 appsLoading 防重入，感知仅首扫略长，且被 ① 省下的 100~400ms 对冲；
  - Robolectric 下 toBitmap 对 shadow drawable 若行为异常：runCatching 兜成 null + ②-b 占位分支，测试不红；
  - 64px 在 480dpi 屏（40dp≈120px）有轻度上采样，与改前完全同参——如实机观感不满，唯一调参点=AppScanner 内 64→128（内存 N×64KB≈9.4MB/150 应用，仍 ~10×降），本轮不动、记录备查。

---

## ③ 选择器非空态刷新入口（一行级）

- 涉及文件：`app/src/main/java/com/example/questtuner/ui/AppPickerDialog.kt`【改】
- 具体设计：else 分支（:71-81）尾部、【增】一行（置于 LazyColumn 之后，列表位置不动）：

```kotlin
                } else {
                    if (filtered.isEmpty()) {
                        Text("无匹配应用", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(...) { ... }
                    }
                    // OPT-round10 ③：非空态补刷新入口（卸载残留/新装感知）——复用 onRefresh，
                    // loadApps 内 appsLoading 防重入；不引入新缓存结构/广播/TTL（David 已否决）
                    OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("刷新应用列表") }
                }
```

要点：`enabled = !loading`（刷新进行中防重复点击；appsLoading 期间 apps 保持旧列表 → 走的正是非空分支，按钮即刻反映状态）；**不**自动触发刷新、**不**改 `if (state.apps.isEmpty() && !state.appsLoading)` 守卫（TunerApp.kt:255，OPT 报告已确认该缓存语义保留）。

- 验收指标（引用 David 报告 §③）：非空态刷新按钮存在可点；卸载应用→打开选择器→点刷新→条目消失；新装应用→刷新→条目出现；连续快速点击不并发重扫（appsLoading 守卫）。
- 风险：无——纯 UI 增量，onRefresh 链路复用空态分支既有通路。

---

## ④ Profile 列表搜索框（~25 行纯 UI + 纯函数单测）

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/ui/TunerApp.kt`【改】
  - 【增】`app/src/test/java/com/example/questtuner/ui/ProfileFilterTest.kt`
- 具体设计：

（1）过滤逻辑抽纯函数（lead 要求，可单测；放 TunerApp.kt 顶层 internal，零依赖）：

```kotlin
/** OPT-round10 ④：Profile 列表过滤（纯函数可单测）——label/包名子串、大小写不敏感；空 query=全量 */
internal fun filterProfiles(profiles: List<AppProfile>, query: String): List<AppProfile> {
    if (query.isBlank()) return profiles
    return profiles.filter {
        it.label?.contains(query, ignoreCase = true) == true ||
            it.packageName.contains(query, ignoreCase = true)
    }
}
```

（2）TunerApp.kt【改】——关键点：**query 与 filtered 状态必须提升到 LazyColumn 之外**（:31-33 附近），不能写在 `item { }` 里——LazyColumn item 内容随滚动虚拟化销毁，item 内 remember 会"滚走丢词"：

```kotlin
@Composable
fun TunerApp(viewModel: TunerViewModel) {
    val state by viewModel.state.collectAsState()
    // OPT-round10 ④：状态提升——LazyColumn item 内 remember 随虚拟化滚动销毁，搜索词会丢
    var profileQuery by remember { mutableStateOf("") }
    val filteredProfiles = remember(state.profiles, profileQuery) {
        filterProfiles(state.profiles, profileQuery)
    }
    MaterialTheme {
        LazyColumn(...) {
            ...
            item { Text("按应用 Profile", style = MaterialTheme.typography.titleMedium) }
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = profileQuery,
                    onValueChange = { profileQuery = it },
                    placeholder = { Text("搜索名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.profiles.isNotEmpty() && filteredProfiles.isEmpty()) {
                item { Text("无匹配 Profile", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(filteredProfiles, key = { it.packageName }) { p ->   // key 仍 packageName，勿改 index
                    ProfileRow(p, connected = state.connected,
                        onApply = { viewModel.applyProfile(p) },
                        onEdit = { viewModel.editProfile(p) },
                        onDelete = { viewModel.deleteProfile(p.packageName) })
                }
            }
            ...
```

（profiles 为空时不渲染搜索框也可——Wang 可把搜索框 item 收进 `if (state.profiles.isNotEmpty())`，二选一，不改语义。）【删】原 :47-55 的无过滤 items 块；remember(edit) 编辑态键控（NewProfileCard）**不动**。

（3）单测【增】（纯 JVM，无 Robolectric）：

```kotlin
package com.example.questtuner.ui

import com.example.questtuner.tuner.AppProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPT-round10 ④：filterProfiles 纯函数单测（标题/包名、大小写不敏感、空 query 全量） */
class ProfileFilterTest {
    private val profiles = listOf(
        AppProfile(packageName = "com.meta.xr.game", label = "XR Game"),
        AppProfile(packageName = "com.example.notes", label = "Notes"),
        AppProfile(packageName = "global", label = null),
    )

    @Test
    fun blankQuery_returnsAll_inOrder() {
        assertEquals(profiles, filterProfiles(profiles, ""))
        assertEquals(profiles, filterProfiles(profiles, "   "))
    }

    @Test
    fun matchesLabelOrPackage_caseInsensitive() {
        assertEquals(listOf("com.meta.xr.game"),
            filterProfiles(profiles, "xr game").map { it.packageName })     // 标题子串
        assertEquals(listOf("com.example.notes"),
            filterProfiles(profiles, "NOTES").map { it.packageName })       // 大小写不敏感
        assertEquals(listOf("com.meta.xr.game"),
            filterProfiles(profiles, "com.meta").map { it.packageName })    // 包名前缀
    }

    @Test
    fun nullLabel_fallsBackToPackage_noMatchEmpty() {
        assertEquals(listOf("global"),
            filterProfiles(profiles, "global").map { it.packageName })      // label null → 包名匹配
        assertTrue(filterProfiles(profiles, "zzz").isEmpty())
    }
}
```

- 验收指标：3 单测绿；UI 走查：搜索框出现于"按应用 Profile"标题下、过滤即时、无结果占位文案、清空恢复全量；`items(` 的 key grep 仍为 `packageName`；**滚动到底部（新建 Profile/日志区）再滚回，搜索词不丢**（状态提升验证点，防 Wang 把 remember 写进 item）。
- 风险：remember 位置写错（item 内）→ 丢词 bug——已在要点与验收双向标注；过滤未 trim 首尾空格（与 AppPickerDialog:44-49 语义一致，不引入差异）。

---

## ⑤ TODO-round-next.md 顺带清账（2min）

- 涉及文件：`docs/plans/TODO-round-next.md`【改】
- 具体设计：「遗留技术项」中 `- **R5-O1 图标 LruCache（半步，~30 行）**` 条目改为完成态：

```markdown
- ~~R5-O1 图标 LruCache~~（round-10 **完成**：以方案 A 扫描期降采样替代——InstalledApp.icon 改存 64px Bitmap，全尺寸 Drawable 零常驻，LruCache 不再需要；icon 已是小图，Compose 层 remember 即缓存）
```

- 验证方法：通读遗留池，其余条目（编辑三旅程/R4-O7/A7/keystore）不动。

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-10`（现 HEAD fe9ce00）；中文 conventional commit（`fix:`/`feat:`/`test:`/`docs:` 按性质），建议：①+②（AppScanner，一次一 commit）→ ①测试 → ②③（AppPickerDialog）→ ④（TunerApp+测试）→ ⑤。
2. **实施顺序**：①② 改 AppScanner → VrClassifyTest（Robolectric 先跑绿，保住 round-8 基建不动）→ ②③ 改 AppPickerDialog → ④ TunerApp+ProfileFilterTest → ⑤ TODO。预估 25~30 分钟。
3. **生产改动范围**：AppScanner.kt / AppPickerDialog.kt / TunerApp.kt 三文件；无新依赖（toBitmap 为 core-ktx 既有传递可用 API）、无构建配置变化；release 体积预期 ±20KB 内。
4. **测试数预期**：38 旧 + ① 3 + ④ 3 = **44 全绿**（Robolectric 总数 4→7，同一套基建）。
5. **不动项清单**：hasVrCategory 的 filter 优先序与 packageFallback 前缀规则；`if (state.apps.isEmpty() && !state.appsLoading)` 守卫与 UiState.apps 缓存语义；NewProfileCard 的 remember(edit)；build.gradle.kts 全文；Robolectric 环境配置（user.home/maven.repo.local，严禁 android-all-instrumented）。
6. **实机待补基线**（实施轮验收第一步，David 清单三项：dumpsys 内存差值 / loadApps 计时 / N,N₂ 实际值）——先取改前基线再合入，改后复测对账。
