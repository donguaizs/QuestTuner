# QuestTuner Round 4 修复验证报告（VERIFY-round4）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-4`（master..fix/round-4 共 8 commits，85793a4..1c47640）
- 方案依据：docs/plans/PLAN-round4.md（Li）；bug 依据：docs/bugs/BUG-REPORT-round4.md（BUG-029~033 + R4-O1~O8）
- 验证方式：逐 commit diff 审查 + 6 个重点专项深审 + clean 三件套构建 + 产物级 resConfigs 验证

## 总体结论

**5/5 个 bug 修复 + 6/7 优化项全部 PASS（R4-O7 按方案延后）。建议合入 master。**

- 构建：`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 33s**
- 单测：**26/26 通过**（新增 CleanStaleApksTest 2 例；既有 24 例保持绿）
- resConfigs 产物级生效：release APK `locales: '--_--'`（仅默认 locale），体积 1837KB → **1776KB**（-61KB）
- Wang 2 处偏差均合理（其一为编译错误自纠，见专项⑥）

## 重点专项详审

### 专项① BUG-029 三触点重探测时序 — **PASS**

commit 85793a4（+ d4ac90b 顺序修正）。与 PLAN §BUG-029 逐行一致：

- **三触点覆盖全部授权路径**：
  1. `MainActivity.onStart`（:22-26）——摘头显去 PC 授权再戴回的旅程主路径 ✅
  2. 向导「下一步」按钮 onClick 内 `viewModel.refreshCapabilities()`（SetupWizard.kt:71-75）——确定性兜底，不依赖生命周期事件 ✅
  3. VM init 调用（TunerViewModel.kt:80）——进程首起 ✅
  三触点互为冗余：即使 onStart 因异常未触发，向导路径兜底；非向导场景（firstRunDone）onStart 覆盖。
- **可重入性**：每次调用 launch 独立 IO 协程；探测全部幂等（checkPermission/isA11yRunning 只读）；对账分支的 `enableA11yService` 内含 `contains(flat)` 序列化检查（SecureAutomation.kt:89）——已启用则跳过写入，并发/重复调用无副作用累积 ✅（PLAN 风险段的推演成立）。
- **init 瘦身正确性**：原 init 的三个能力快照行已删（hasSecureGrant/a11yRunning/privateDnsMode 移交 refreshCapabilities），prefs 镜像字段保留 init（开关值只经 setXxx 变化，无需重读）——职责划分与 PLAN 一致 ✅。
- **R4-O3 属性初始化顺序**：`prefs` 声明移到 `_state` 之前（`UiState(wizardVisible = !prefs.firstRunDone)` 构造需要）——Kotlin 属性按声明序初始化，d4ac90b 的自纠后顺序正确 ✅（见专项⑥偏差 2）。首帧即向导、无主界面闪现 ✅。

### 专项② BUG-032 持久化对账安全面 — **PASS**

commit a94a063。与 PLAN §BUG-032 一致：

- **标记同步时机（关键安全语义）**：
  - enable：回读确认 `== 1` 才写 `wifiAdbByUs = true`（SecureAutomation.kt:48-50）——归属声明只在实际生效后落 ✅
  - disable：**回读确认 `== 0` 才清标记**；WriteRejected 时标记保留（下次启动重试回滚）（:63-68）✅
- **reconcile 只回滚自己标记**：`if (wifiAdbEnabledByUs) return`（本会话已开，disconnect 负责）+ `if (prefs.wifiAdbByUs) disableWifiAdb(context)`（:84-90）——持久标记 false（用户手动开/从未开）**永不被启动对账触碰** ✅。
- **与 connect/disconnect 并发收敛**：两种顺序推演均正确——connect 的 enable 先成功 → 内存标记 true → reconcile 跳过；reconcile 先跑 → disable 残留 → connect 再 enable。单一事实源是系统设置本身，标记只是归属声明 ✅。
- **保守失败方向**：enable 成功但 prefs apply() 落盘前被杀 → 标记未写成 → 下次视为非我们所开 → 不回滚（宁漏不误）——PLAN 风险段论证的"保守方向正确失败"被实现结构保证 ✅。
- **卸载重装边界**：数据清空 → 标记消失 → 不回滚（且新装无权限）——PLAN 已记录为平台边界，行为可接受 ✅。
- 调用收口单一：VM init 一个入口（:81-84），BootReceiver 不加对账（PLAN 决策，receiver 非通用启动钩子）✅。

### 专项③ BUG-030 门禁闭环 — **PASS**

commit 816f9e5。条件链验证：`grant →（开启）autoA11y →（写入+确认）a11yRunning →（门禁）autoApply`：

- AutomationCard 中四个开关自上而下排列恰好是依赖链（autoWifiAdb/autoA11y 门禁=grant；autoApply 门禁=**a11yRunning**）——UI 结构显式化了依赖顺序 ✅
- 副文案按实态区分（R4-O4）：a11yRunning=false 时「无障碍服务未运行，自动应用不会触发——请先开启上方…」——静默失效消除 ✅
- **autoApply ON 但服务后来被关**：开关保持 ON（checked）但置灰 + 副文案如实——「开着但不会生效」的诚实表达 ✅
- **对账补启用不会重复 enable**：refreshCapabilities 的补启用条件 `grant && prefs.autoA11y && !running`（:117-121），enableA11yService 内部 contains 检查使重复调用幂等（无二次写入）✅
- 向导开关门槛同步（R4-O5）：WizardSwitch 增加 enabled 参数，autoApply 门禁=a11yRunning 与 AutomationCard 统一 ✅
- 未授权前置：对账 enable 有 grant 前置——授权前不写系统设置 ✅

### 专项④ BUG-031 三层防线边界 — **PASS**

commit fb73dfe。与 PLAN §BUG-031 一致：

- **入口防线**：onValueChange 中 `if (it != pickedPkg) pickedLabel = null`（TunerApp.kt:184-188）——手改任何字符立即失效陈旧 label ✅
- **锚点记录**：onSelect 时 `pickedPkg = app.packageName`（:173）✅
- **保存守卫（R4-O6）**：`val label = (if (pkg == pickedPkg) pickedLabel else null) ?: pkg.ifBlank { "全局" }`（:212）——防御性终检 ✅
- **误拒合法编辑审查**：唯一边界场景「选择 A → 手改 → 又改回 com.a」：中间值已清 label，改回时 it==pickedPkg 成立但 label 已 null → 回退用包名做 label。这不是误拒——用户编辑过包名，字段语义以用户输入为准（PLAN 风险段已论证接受）；「选择后完全不动」的正常路径 label 保留（pickedPkg==pkg 恒成立，onValueChange 不触发）✅。键盘/输入法重组不触发 onValueChange（只有真实编辑触发），无误清 ✅。

### 专项⑤ BUG-033 toggleable 规范性 + resConfigs — **PASS**

commit 816f9e5 + 0022789：

- **toggleable 规范做法**：SwitchRow/WizardSwitch 的 Row 加 `.toggleable(value, enabled, role = Role.Switch, onValueChange)`，内部 `Switch(checked, onCheckedChange = null)`——这是 Compose 官方推荐的开关行模式：整行可点（触达面积增大）、TalkBack 聚焦读出「标题+副文案+开关+状态」、`onCheckedChange=null` 消除行与 Switch 的**双重播报/双触发** ✅；enabled 门禁由 toggleable 承接（disabled 行不可点不响应）✅
- **Slider 语义绑定**：纹理缩放/CPU/GPU 三个 Slider 加 `semantics { contentDescription }`（TunerApp.kt:208-220）——TalkBack 读「CPU 性能等级，滑块，2/4」类完整语义（Slider 自带 stateDescription）✅
- **resConfigs 产物级验证**：`resourceConfigurations.addAll(listOf("zh","en"))`（build.gradle.kts:17-18）→ release APK badging `locales: '--_--'`（依赖库多语言资源剥离，仅剩默认）+ 体积 1837→1776KB ✅；AGP 8.5 Kotlin DSL 写法编译通过（PLAN 预留的 Groovy 备选未需要）✅
- (d) 可选增强（FilterChip/DNS label）未做——PLAN 标注"不强制" ✅

### 专项⑥ Wang 的 2 处偏差 — **均合理**

1. **d4ac90b 属性声明顺序修正**：R4-O3 引入 `UiState(wizardVisible = !prefs.firstRunDone)` 时 prefs 仍声明在 _state 之后 → 初始化顺序错误编译失败；Wang 自纠把 prefs 移前。这正是任务描述让我专项检查的"属性初始化顺序是否引入新问题"——**引入过、已自纠、最终状态正确**（Kotlin 属性按声明序初始化）。偏差合理 ✅
2. **1c47640 cleanStaleApksIn 拆纯函数**：PLAN 给的是 `cleanStaleApks(context, keepTag)` 单函数；Wang 拆出 `cleanStaleApksIn(dir, keepTag)` 纯函数（Context 版本薄包装）——单测无需 Context stub（CleanStaleApksTest 用临时目录直测），且 missingDir 用例（listFiles() null 不抛）顺带覆盖。测试性改进，无行为差异 ✅

## 其余优化项

- **R4-O1 PASS**：cleanStaleApks 在 downloadAndInstall 下载前调用（:436-437）；纯函数单测 2 例（keep 保留/旧版与 beta 清除、目录缺失不抛）✅
- **R4-O2 PASS**：cancelConnect 追加 `markSessionInactive() + disconnect()`（:169-174）——消除「连接活但 sessionActive=false」中间态（服务自愈重连违背取消意图的路径堵死）✅。小观察（不阻断）：取消场景若 enableWifiAdb 已成功，无线调试保持开（标记在）直到显式 disconnect 或下次启动对账——语义上「取消连接≠断开会话」，用户取消后常立即重连，保持开合理；残留窗口最大为进程存活期。
- **R4-O8 PASS**：搜索无结果「无匹配应用」占位（AppPickerDialog.kt:72-74）✅
- **R4-O3 PASS**（并入专项①）；**R4-O4/O5 PASS**（并入专项③）；**R4-O6 PASS**（并入专项④）
- **R4-O7 延后**：PLAN 论证充分（纯观感、低收益高回归风险，round-5 UI 状态层重构时顺带）✅

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
环境：JAVA_HOME/GRADLE_USER_HOME/TEMP,TMP 同前三轮
结果：BUILD SUCCESSFUL in 33s（92 tasks：46 executed, 43 from cache, 3 up-to-date）
单测：26/26（IntentFold 3 / LeaveSnapshot 3 / MergeA11y 4 / ValuesMatch 5 / CleanStaleApks 2 / UpdateSafety 4 / VersionCompare 5）
产物：release 1776KB（locales 仅默认，resConfigs 生效）
```

## 实机验证建议清单（交付后自验，按 PLAN §6 提炼）

1. **029 旅程**：向导复制命令 → PC pm grant → 点「下一步」→ 第二步授权提示消失、开关可用；摘戴头显（onStart）AutomationCard 解锁；`dumpsys package | grep WRITE_SECURE` 与 UI 一致
2. **030 对账**：未授权开 autoA11y → 授权 → 回 app 自动补启用（系统设置可见）；grant=true 未开无障碍时 autoApply 置灰+副文案；服务被手动关后回 app 副文案如实变化
3. **031 label**：选 A 改包名保存 → label==pkg；选 A 不改 → label==A
4. **032 回滚**：连接（自动开）→ `am kill` → 重开 app 无线调试自动关；手动开的 am kill 后**保持开**；正常 connect→disconnect 回归
5. **033 TalkBack**：滑块读「纹理缩放/CPU 性能等级…」；开关行读标题+状态、整行可点、无双击双重切换；en 环境依赖库文案回落
6. R4 项：换版本更新后 update/ 无旧 APK；取消连接后无晚到连接（状态栏不出现"由自动应用恢复"）；冷启首帧直接向导；搜索无结果占位

## 最终判定

**fix/round-4 的 5 个 bug 修复 + 6 个优化项全部按 PLAN 正确实现（2 处偏差合理），构建三件套全绿（26/26），resConfigs 产物级生效。建议合并到 master。**
