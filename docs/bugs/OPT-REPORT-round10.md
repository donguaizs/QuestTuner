# QuestTuner Round 10 优化机会评估报告（OPT-REPORT-round10）

- 审查人：David（QA）
- 日期：2026-10-26
- 基线：master @ bca3415（Round 9 收官后）；分支 fix/round-10（本报告唯一提交物）
- 方法：静态代码走查（file:line 引用）+ 复用 round-7 发布资产取证数据（dex 体积）+ 内存/耗时账面推算。**本轮无实机**：所有含"N（装机数）"的量均为公式化估算并标注，实机测量命令在专节给出，供实施轮验收时取改前基线。
- 性质：优化机会评估，非缺陷报告；供 Li 出方案与验收指标引用。

## 汇总

| 靶点 | 结论 | 关键量化（改前基线） |
|---|---|---|
| ① 图标内存 | **确认（重点）** | Drawable 常驻：每图标 0.14~1.0MB 像素内存（估算公式与边界见下），150 应用 ≈ **21~150MB**；改后（扫描期降采样）≈ **2.4MB**（~30~60×降幅） |
| ② N+1 IPC | **确认（重点）** | 改前 = 1 次 queryIntentActivities + **N₂ 次 getActivityInfo**（N₂ = 无 VR filter category 的应用数，估 60~90% of N）；改后 = **1 次总数**。语义等价性已论证（GET_META_DATA 会同时填充 applicationInfo.metaData） |
| ③ 重复扫描 | **部分确认（lead 前提修正）** | "每次打开选择器都重扫"**不成立**（TunerApp.kt:255 有 isEmpty 守卫，缓存事实上已存在）；真实缺口 = **非空态无刷新入口 + 卸载残留**；实际重扫时机 = 每次连接成功（TunerViewModel.kt:158） |
| ④ Profile 搜索 | **确认（低风险）** | ~20~25 行 UI 层改动，AppPickerDialog 已有同构模式可照搬（:43-49）；无 VM/数据层改动 |
| 附加 R9-O1 workingDir 固化 | **否决（维持延后）** | 残渣系一次性事件（round-9 复现实验排除现行写出）；.gitignore 已兜底；环境刚稳定不值再动 |
| 附加 release 体积 | **否决** | classes.dex 1,721,708B / APK 1,768,014B = **97.4%**（round-7 取证实测）；R8+shrinkResources+resConfigs 已全开，无可压缩项 |

---

## ①【内存·重点】应用图标全量常驻 —— 确认

### 现状证据

- `AppScanner.kt:42`：`icon = runCatching { ri.loadIcon(pm) }.getOrNull()` —— 扫描期对**每个**应用急切 loadIcon，返回的 Drawable 连同其底层 Bitmap（像素内存）进入 `InstalledApp.icon`；
- `TunerViewModel.kt:333`：结果整表存入 `UiState.apps`（StateFlow 长期持有，进程存活期常驻）；
- `AppPickerDialog.kt:92-94`：渲染时才 `toBitmap(64,64)` 下采样，且 `remember(app.icon)` 只在行组合存活期有效——滚动出屏即回收、再滚回**重新转换**（CPU 抖动源），而全尺寸 Drawable 始终驻留。

### 内存账（改前基线，估算——公式可复算）

单图标像素内存 = `宽 × 高 × 4B`（ARGB_8888）：

| 图标源尺寸 | 单图标 | N=100 | N=150 | N=200 |
|---|---|---|---|---|
| 192×192（小图标下界） | 144KB | 14MB | 21MB | 28MB |
| 432×432（自适应图标最常见源） | 731KB | 71MB | 107MB | 142MB |
| 512×512（lead 假设，上界） | 1.0MB | 100MB | 150MB | 200MB |

- 假设声明：Quest 3 密度取 xhdpi~xxhdpi（320~480dpi），自适应图标解码尺寸落在 192~512px 区间；**取中位 432×432、N=150 ≈ 107MB** 为代表性估算。Android 8+ 像素数据在 native heap（dumpsys meminfo 的 Native Heap 项），非 Java 堆。
- 附带 CPU 账：首次滚动 150 行 × toBitmap(64)（每行 16KB 产物 + 1~5ms 转换，估算）≈ 累计 150~750ms 转换开销，集中在首次滚动（jank 感知点）。
- **实机测量命令（验收取改前基线用）**：
  ```
  adb shell dumpsys meminfo com.example.questtuner
  # 记录 Native Heap + Graphics；打开选择器滚动一遍后重测，差值 ≈ 图标内存实测
  ```

### 处置建议（两方案对比，推荐 A）

- **方案 A（推荐）：扫描期降采样**——loadIcon 后立即 `toBitmap(64,64)`（IO 线程，扫描本来就在 IO），`InstalledApp.icon` 类型改存 64px Bitmap（或直接 ImageBitmap），行渲染直接用、删 toBitmap。内存定格 **N×16KB（150 应用 ≈ 2.4MB，降 ~30~60×）**；顺带消除滚动重转换 jank。改动面：AppScanner.kt + AppPickerDialog.kt 两个文件，无新依赖、无异步编排。代价：扫描期多 N 次小图转换（IO 线程，~百 ms 级，可接受）。
- **方案 B（备选）：懒加载 + LruCache**（R5-O1 原案，key=packageName→ImageBitmap，容量~120 ≈ 2MB 稳态）：Drawable 不驻留，miss 时再 loadIcon。缺点：Compose 异步加载需要状态失效编排（~60-80 行），滚动 evict 后再滚回要重解码，收益不比 A 多。
- **共同红线**：不缓存/不持有全尺寸 Drawable；两方案都达成。R5-O1 的"虚拟化继续延后"结论不变（LazyColumn 本就虚拟化行组合，瓶颈在图标非行数）。

### 验收指标建议（供 Li 引用）

1. `InstalledApp` 不再持有全尺寸 Drawable（类型/构造可见于 diff）；
2. 实机 dumpsys meminfo 打开选择器前后差值 ≤ ~5MB（vs 改前实测基线，待实机取）；
3. 38/38 单测全绿；release APK 体积变化 ≤ ±20KB（无依赖变化佐证）。

## ②【IPC·重点】hasVrCategory 的 N+1 —— 确认

### 现状证据

- `AppScanner.kt:33`：`pm.queryIntentActivities(intent, 0)` —— flags=0，**不带 GET_META_DATA** → 返回的 `ri.activityInfo.metaData` 与 `applicationInfo.metaData` 均为 null；
- `AppScanner.kt:55-77`：hasVrCategory 先读 filter categories（零 IPC，:57-63）；filter 无 VR category 的应用落到 :65-70 `pm.getActivityInfo(ComponentName, GET_META_DATA)` —— **每个这类应用一次 binder IPC**，读的是 `full.applicationInfo?.metaData`（application 级 meta，:71-74）；最后 packageFallback（纯字符串，零 IPC）。

### IPC 账（改前基线）

- 改前 = **1 次 queryIntentActivities + N₂ 次 getActivityInfo**。N₂ = filter 中无 VR category 的应用数 = 全部 2D 应用 + 未在 filter 声明 VR category 的 VR 应用；按 Quest 常见装机构成（VR 应用多带 filter category、2D 工具类多不带）估 N₂ ≈ 60~90% of N（N=150 → **90~135 次 IPC**）。单次 getActivityInfo binder 往返 1~3ms（估算）→ 首扫多耗 **~100~400ms**。
- 改后（queryIntentActivities 传 `PackageManager.GET_META_DATA`）= **1 次总 IPC**。

### 语义等价性论证（能否直接读 metaData——lead 问点）

1. **官方语义**：`PackageManager.GET_META_DATA` 描述为 "include metaData associated with **components and applications**"——queryIntentActivities 传此 flag 后，ResolveInfo 内的 ActivityInfo.metaData（component 级）与其 applicationInfo.metaData（application 级）**都会被填充**（ComponentInfo.applicationInfo 随 ResolveInfo 一并返回，这是 Android 资源/组件元数据加载的标准行为，广泛依赖）。
2. **与现读点的对齐**：现 fallback 读的是 application **级** meta（`full.applicationInfo?.metaData`）——改后等价读法 = `ri.activityInfo.applicationInfo?.metaData`，语义不变；注意**不是** `ri.activityInfo.metaData`（那是 component 级，多数应用为空）。
3. **filter categories 是否已足够**：对标准 Quest VR 启动声明（`com.oculus.intent.category.VR` / `com.meta.intent.category.VR` 写在 intent-filter）已足够；application 级 meta 兜底是为非标准声明准备——保留（等价读法零 IPC 成本），不要删。
4. **实施验证钩子**：Robolectric 可确定性验证（ShadowPackageManager 安装双样本：filter 声明的 VR 应用 + application meta 声明的应用，断言 `AppScanner.query` 两者 isVr 分类正确）——round-8 的 Robolectric 基建可直接复用，建议实施轮补此测试作回归防线（避免"省 IPC 却丢分类"）。

### 验收指标建议

1. 代码走查：getActivityInfo 调用点消失（grep 零命中），metaData 直读替换；
2. Robolectric 双样本分类测试绿（新增）；
3. 实机首扫耗时（可选）：loadApps 加临时计时日志，改前后对比（预期 -100~400ms，估算值）。

## ③【重复扫描】—— 部分确认（lead 前提修正）

### 前提修正（证据）

"loadApps() 每次打开选择器都全量重扫"**不成立**：
- `TunerApp.kt:253-256`：点"选择应用"时 `if (state.apps.isEmpty() && !state.appsLoading) viewModel.loadApps()` —— **apps 非空时重开选择器不重扫**，`UiState.apps` 本身就是会话级缓存；`AppPickerDialog` 的 remember(apps, query) 过滤（:44-49）建立在该缓存上。
- 真实重扫时机：**每次连接成功**（`TunerViewModel.kt:158` connect 成功路径调 loadApps）+ 列表为空时的首次打开。

### 真实缺口（确认存在的两点）

1. **非空态无刷新入口**：AppPickerDialog 的"加载应用列表"按钮只出现在 apps 为空分支（:61-69）；**列表非空时用户没有任何刷新手段**——卸载了的应用一直残留显示，新装的应用永远不出现（直到下次重连成功）。
2. **失效策略现状**：无广播监听、无 TTL；唯一"天然失效"= 下次 connect。

### 处置建议（低成本组合）

1. 选择器**非空态也显示刷新按钮**（复用 onRefresh，一行级 UI 改动）——解决新装/卸载感知的主路径；
2. （可选，非必须）PackageInstallReceiver 监听 `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` → 置空 apps 缓存（TTL **不推荐**：引入定时复杂度且无用户价值）；receiver 是进程内静态注册需 API 33+ 动态注册豁免——本项目 targetSdk 32 静态注册可行，但**建议先只做第 1 条**看实机反馈。
3. 扫描成本本身由 ②（IPC）与 ①（图标改小图）消化后大幅下降，③无需独立缓存层改动——**不要**引入新缓存结构（现状 UiState.apps 即缓存，改多了是负收益）。

### 验收指标建议

非空态刷新按钮存在；卸载应用→打开选择器→点刷新→条目消失；新装应用→刷新→条目出现。

## ④【UX·低风险】Profile 列表搜索 —— 确认

### 现状证据

- `TunerApp.kt:47-55`：profiles 直接 `items(state.profiles, key=packageName)`，无过滤；条目多时（每应用可建一条 + 全局）只能滚动目视。

### 处置建议

- 照搬 AppPickerDialog 既有模式（:43-49：`var query by remember` + `remember(apps, query) { filter }`）：在"按应用 Profile"标题下加 OutlinedTextField + remember 过滤，**~20~25 行，纯 UI 层**，不动 VM/数据层/remember(edit) 编辑态键控（注意过滤后列表的 key 仍为 packageName，勿改成 index）；
- 无结果显示文案沿用选择器"无匹配应用"惯例；空 query = 全量（现行为）。
- 收益判断：条目 ≤8 时无感，>10 明显；成本趋零、风险趋零，值得做。

## 附加项裁定

### R9-O1 测试 JVM workingDir 固化 —— 否决（维持延后）

- 事实链：残渣系 round-8 当晚异常 classpath 下的一次性泄漏（round-9 复现实验：fresh daemon + CWD=仓库根复跑 Robolectric，**零写出**）；`.gitignore` 4 条锚定兜底已入库（round-9 ①），即使再生也不可能误提交。
- 收益≈0 vs 风险>0（Robolectric 环境刚稳定，动 testOptions 需重验 4.12.2 全链路）。**维持延后**，触发条件改为：编辑三旅程 UI 测试落地时顺带（与 TODO 池现条目一致），或残渣再现（现已被 ignore，检出靠 `git check-ignore` 例行抽查）。

### release 体积 —— 否决（明确不硬凑）

- 量化（round-7 发布取证实测，v0.2.2 release 资产）：APK 1,768,014B，其中 classes.dex **1,721,708B（97.4%）**；其余为 resources.arsc/manifest 等微量。R8 minify + shrinkResources + resConfigs(zh,en) 均已生效（round-4 起落地，体积 1837→1776KB 历史已榨过一轮）。
- 剩余理论空间 = 依赖树再裁（Compose 基线占 dex 大头，砍功能才有效）+ zip 层已无冗余。**结论：无可压缩项，1.77MB 现状即为该技术栈的合理下界，否决任何体积专项。**

---

## 复现命令与测量方法（数字可复现性）

| 数字 | 来源 | 复现方式 |
|---|---|---|
| dex 97.4% | round-7 取证实测（tag v0.2.2 重建产物） | `unzip -l app-release.apk` 看 classes.dex 尺寸 vs 总长 |
| IPC 次数账 | 静态代码路径推导（本报告 file:line） | 走查 AppScanner.kt:33/65-70 + N₂ 占比实机待测 |
| 图标内存公式 | w×h×4B 静态推算 | 实机：`adb shell dumpsys meminfo com.example.questtuner`（开选择器前后 Native Heap+Graphics 差值） |
| toBitmap 开销 | 估算（1~5ms/行） | 实机：首次滚动 Perfetto/logcat 帧耗时（可选） |
| 重扫时机 | 静态触发点走查 | TunerApp.kt:255（isEmpty 守卫）/ TunerViewModel.kt:158（connect 后）grep 可证 |

**实机待补基线清单**（实施轮验收第一步）：①dumpsys 内存差值；②loadApps 计时（临时日志）；③首扫 N/N₂ 实际值（临时日志打印 query 返回数）。

## 优先级建议

1. **② N+1 消除**（一行 flag + 等价读法 + Robolectric 回归测试）——IPC 从 1+N₂ → 1，首扫体感改善最直接；
2. **① 方案 A 扫描期降采样**（两文件）——内存 30~60×降幅 + 滚动 jank 消除；
3. **③ 非空态刷新按钮**（一行级）+ ④ 搜索框（~25 行）——UX 补齐；
4. R9-O1 / release 体积：否决，不进方案。
