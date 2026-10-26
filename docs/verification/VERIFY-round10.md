# QuestTuner Round 10 优化轮验证报告（VERIFY-round10）

- 验证人：lead（David 回合两次中断未产出，由 lead 接手独立验证并如实登记）
- 日期：2026-10-26
- 对象：`fix/round-10` @ 4f4c943（fe9ce00 评估报告 → 6f682c7 方案 → 23c0676 ①② → c8053b7 ③④ → 4f4c943 ⑤）
- 基线：master @ bca3415（Round 9 收官）
- 总判定：**7 PASS / 0 FAIL**；单测 **46/46 全绿**（11 suites，`--rerun-tasks` 强制复跑，非缓存）
- 性质：性能/UX 优化专项。无缺陷修复、无依赖新增、无构建配置变更。

## 逐项结论

| # | 项 | 判定 | 证据 |
|---|---|---|---|
| ① | N+1 IPC 消除 | **PASS** | AppScanner.kt:36 `queryIntentActivities(intent, PackageManager.GET_META_DATA)`；`getActivityInfo` 全文件仅 1 处命中且为**注释**（:36）——实际调用零残留；兜底读法 :65 `ri.activityInfo.applicationInfo?.metaData`（★正确读 application 级，非 component 级）；filter 优先与 packageFallback 语义未变（:62/:76） |
| ② | 图标扫描期降采样 | **PASS** | `InstalledApp.icon: Bitmap?`（tuner 包零 Compose 依赖，import 仅 `android.graphics.Bitmap` + `androidx.core.graphics.drawable.toBitmap`）；`loadIcon` 全文件唯一（:47）且紧跟 `toBitmap(64, 64)`；AppPickerDialog 渲染期 `toBitmap` 仅剩注释（:94）、实际 `app.icon?.asImageBitmap()`（:95）；null 图标走 `Spacer(Modifier.size(40.dp))` 占位（:105）行布局不塌 |
| ③ | 选择器非空态刷新入口 | **PASS** | AppPickerDialog.kt:84 `OutlinedButton(onClick = onRefresh, enabled = !loading)`；:61 `if (apps.isEmpty())` 原守卫未破坏；无新缓存结构/广播监听/TTL |
| ④ | Profile 列表搜索框 | **PASS** | 状态提升到 LazyColumn 之外（TunerApp.kt:34-36，`profileQuery`/`filteredProfiles` 均在 `LazyColumn` 前的函数作用域）；`filterProfiles` 纯函数 :87（blank→全量、label/包名 ignoreCase、null label 走 packageName）；`items(filteredProfiles, key = { it.packageName })` :66 key 未改；无匹配占位 :63-64；搜索框仅在 profiles 非空渲染 :51 |
| ⑤ | R5-O1 清账 | **PASS** | TODO-round-next.md R5-O1 条目标记为结构性解决（方案 A 降采样替代 LruCache），理由与实现一致 |
| ⑥ | 单测全量 | **PASS** | 46/46，11 suites：A11yRoundTrip 4、**AppScannerVrClassify 5（新）**、IntentFold 3、LeaveSnapshot 3、MergeA11yList 4、ProfileValidation 5、ValuesMatch 5、**ProfileFilter 3（新）**、CleanStaleApks 2、UpdateSafety 7、VersionCompare 5。`--rerun-tasks` 强制重跑，1m31s 实跑 |
| ⑦ | release 体积 | **PASS** | 新构建 **1,770,414 B** vs Round 9 的 1,768,014 B = **+2,400 B（+0.14%）**，远在验收线 ±20KB 内（无依赖变化佐证：core-ktx 的 toBitmap 为既有 API） |

## BUG-052 编辑态语义未被破坏（回归核查）

TunerApp.kt 的 ④ 改动触及 profile 列表渲染，专项确认未波及 Round 9 的编辑态修复：
- TunerViewModel.kt:366 `val priorEdit = _state.value.editingProfile` 保留
- :380 `editingProfile = _state.value.editingProfile?.takeIf { it !== priorEdit }`（引用比较）保留
- :418 delete 的 `takeIf { it?.packageName != pkg }` 保留
- 本轮对 TunerViewModel.kt **零改动**（git diff 确认）

## Robolectric 局限登记（重要，供后续轮引用）

实施中发现并实测确认的限制：**Robolectric 4.12.2 的 ShadowPackageManager 在 `queryIntentActivities()` 返回时一律剥掉 `ResolveInfo.filter`**。四种注入方式实测 filter 均为 null：

| 注入方式 | filter 保留 | applicationInfo.metaData 保留 |
|---|---|---|
| `addResolveInfoForIntent` | ❌ null | 被替换为默认值（`com.oculus.vrfocusaware`） |
| `addResolveInfoForIntentNoDefaults` | ❌ null | ✅ **保留注入值** |
| `addOrUpdateActivity` + `addIntentFilterForActivity` | ❌ null（真实注册亦然） | ❌ 未带到 ResolveInfo |

处置（已落地）：filter categories 判定抽为纯函数 `AppScanner.intentFilterHasVrCategory(IntentFilter?)` 直接单测（JVM 下 IntentFilter 工作正常）；Robolectric 用 `addResolveInfoForIntentNoDefaults` + `FLAG_INSTALLED` 覆盖 application meta 兜底路径。两侧合计 5 用例。

**诚实声明**：`GET_META_DATA` flag 本身是否真正生效**不可由单测证明**（Shadow 不模拟 flags 差异），仅由代码走查 + grep 确认；实机验证仍需真机。

## 待补实机项（无实机环境，如实登记）

| 项 | 测量方法 | 预期 |
|---|---|---|
| ② 图标内存降幅 | `adb shell dumpsys meminfo com.example.questtuner` 打开选择器滚动前后 Native Heap + Graphics 差值 | 差值 ≤ ~5MB（vs 改前基线） |
| ① IPC 耗时 | `loadApps()` 首扫计时（改前后各一次） | -100~400ms（N₂≈90~135 次 IPC 消除） |
| ③ 刷新语义 | 卸载一应用 → 点刷新 → 条目消失；新装 → 刷新 → 出现；连点不并发重扫 | 行为符合 |

## 与 PLAN 的偏差（如实登记）

1. **测试数 46 而非 PLAN 预估 44**：+2 来自 Robolectric 局限处置方案（filter 纯函数拆出后，①的防线从"3 用例"变"5 用例"：filter 纯函数 2 个断言用例 + 前缀兜底拆分 + 图标类型契约 1 个）。PLAN 预估为下限合理区间，实际多 2 个是防线增强，非偏离。
2. **David 验证环节缺失**：David 两次 agent 回合中断（第二次无任何输出），本轮验证由 lead 接手完成。这削弱了"独立验证"的分离性——**如实登记为流程风险**，建议后续轮恢复 David 独立验证职责。
3. **Wang 实现环节部分中断**：①②由 Wang 完成主体，其回合在测试失败处中断；lead 接手完成 filter 纯函数化、测试重写、③④⑤全部实现。代码质量由 46/46 测试 + 本报告静态核查兜底。
