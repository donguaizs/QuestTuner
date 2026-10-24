# QuestTuner Round 2 修复验证报告（VERIFY-round2）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-2`（fix/round-1..fix/round-2 共 9 commits，10f868a..885f253）
- 方案依据：docs/plans/PLAN-round2.md（Li，含假设台账 A1-A9）
- 验证方式：逐 commit diff 审查 + 与 PLAN 逐项对照 + **安全红线专项审查**（lead 划定）+ clean 全量构建（debug/release/单测）+ **release APK 产物级 R8 核验**（dexdump/aapt2 实测）

## 总体结论

**P0-A / P0-B / P1-1 / P1-2 / P2-1 / P2-2 / P3 全部 7 项 PASS；安全红线 6 项专项全部 PASS；建议合入 master。**

- 构建：`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 32s**
- 单测：9/9 通过（MergeA11yListTest 4、VersionCompareTest 5）
- 体积：debug 21,677KB → release **1,837KB**（R8 + shrinkResources 生效，缩小 91.5%）
- 假设台账 A1-A8 均有「运行时探测 + 降级 + 开关隔离」三要素（逐项见下）
- 3 处方案偏差全部正当（lint 禁用有据、PackageInstaller API 修正正确、isA11yRunning 修正正确）
- 4 个轻微观察点（不阻断，见 RISK 节）

## Wang 报告的 3 处方案偏差核实

| # | 偏差 | 核实结论 |
|---|---|---|
| 1 | lint `disable += "ExpiredTargetSdkVersion"` | **正当**。targetSdk=32 是 PLAN 明示的刻意决策（defaultConfig 注释），非 Play 分发不适用该检查；lint 块注释如实记录理由（build.gradle.kts:48-52） |
| 2 | `installer.openSession(installer.createSession(params))` 替代 PLAN 的 `openSession(params)` | **正确且必要**。PLAN 原稿 API 用法有误（openSession 签名收 Int sessionId）；Wang 的 createSession→openSession(id) 才是 PackageInstaller 的正确用法（SelfUpdater.kt:144-145），且 `session.close()` 在 commit 后调用，无泄漏 |
| 3 | `isA11yRunning` 用 `packageName`+`name` 两字段匹配（替代 PLAN 的 `componentName` 单字段） | **正确**。PLAN 原稿的 `serviceInfo.componentName` 拼接方式不可靠（ServiceInfo.componentName 是运行期属性且跨版本语义不稳）；ResolveInfo.serviceInfo 的 packageName/name 是稳定的 documented 字段，两字段联合等价于 ComponentName 匹配（SecureAutomation.kt:123-124, 136-137） |

## 逐项验证结论

### P0-A 一次性授权自动化（WRITE_SECURE_SETTINGS）— **PASS**

commit 10f868a + 40fd5eb。与 PLAN §P0-A 一致：

- TunerPrefs：8 个开关全默认 false ✅；SharedPreferences（receiver/service 同步读需求）✅
- SecureAutomation：CapProbe 四态探测模型、A1 checkPermission 门禁、A2 putInt+getInt 回读、A3 4×1.5s 重试、A4 写入回读+行为级轮询（3s/200ms）✅
- `ensureWifiAdbThenConnect`：自动化失败仍继续尝试连接（用户可能手动开着）→ 降级到手动路径不阻断 ✅
- manifest 未声明 WRITE_SECURE_SETTINGS（grant-only 权限，声明无意义且误导）✅
- connect 走组合入口 + note 实时更新 adbStatus + 失败降级文案 ✅

**安全红线 1：SecureAutomation 对 enabled_accessibility_services 的写入 — PASS**

- `enableA11yService`（SecureAutomation.kt:82-98）：读当前值 → `contains(flat)` 判重 → `mergeA11yList` **追加式**合并写入 → 回读验证。**绝不清除别人的服务** ✅
- `mergeA11yList` 是纯函数且有 4 个单测覆盖（空串/空白/单服务/多服务）✅
- `disableA11yService`（:100-112）：只 `filter { it != flat }` 移除自己，其余服务保留；**仅当列表清空才写 accessibility_enabled=0**（有其他服务时不动总开关）✅ —— 这比"移除后必须回读验证"更稳：写 rest 后无回读是小瑕疵（见 RISK-4），但语义正确
- `accessibility_enabled` 只会写 "1"（启用路径）或在无任何服务残留时写 "0"——不会把别人开着的服务禁掉 ✅
- 单独异常路径：runCatching 包裹，失败返回 CapProbe.Error，不抛出 ✅

### P0-B 无障碍服务（前台自动应用 Profile）— **PASS**

commit 2f4ca2d。与 PLAN §P0-B 一致：

**安全红线 2：ForegroundProfileService 权限面 — PASS**

- `canRetrieveWindowContent="false"`（accessibility_service_config.xml:6）——已在 release APK 产物级复核（见红线 5 段），确认为 false ✅
- 事件类型仅 `typeWindowStateChanged`，代码只消费 `event.packageName`（ForegroundProfileService.kt:34-37），无任何 `rootInActiveWindow`/`getText` 内容读取 ✅
- manifest：`android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`（系统签名级绑定保护，只有系统可绑定）+ `exported="true"`（targetSdk 32 带 intent-filter 的显式要求，无 injected `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 风险面）✅
- 开关隔离：`prefs?.autoApplyProfile != true` 时服务 no-op（:36）——即使系统保持服务连接也不执行任何命令 ✅
- 只回滚自己：`lastAppliedPkg` 标记，仅离开"我们应用过 profile 的包"时 resetAll（:48-53）；失败时复位标记允许下次重试 ✅
- 防抖：XML notificationTimeout=800 系统级节流 + 包级去重（:42）✅
- 本地日志 a11y.log 64KB 粗截断，runCatching 包裹 ✅
- strings.xml 隐私说明文案如实（"不读取屏幕内容…不上传任何数据"）✅

行为核对：applyOrRetry 失败→lastAppliedPkg 复位重试✅；服务重启→onServiceConnected 重建（幂等 setprop 多应用一次无害）✅；与 UI 并发→adbMutex 串行化（round-1 成果）✅。

### P1-1 应用选择器（修 BUG-017）— **PASS**

commit a22136c。与 PLAN §P1-1 一致：搜索过滤（label/包名模糊）✅、图标 toBitmap(64,64) 按需下采样 + runCatching ✅、VR 徽标 ✅、空列表显示"加载应用列表"按钮 ✅、`usePlatformDefaultWidth=false` 全屏 ✅、"选择应用"按钮在 apps 为空时先 loadApps 再开弹窗（TunerApp.kt:178-181）✅、选中预填 pkg+pickedLabel、保存时 `label = pickedLabel ?: pkg.ifBlank { "全局" }`（:205）✅、未连接可用（loadApps 本就不依赖连接）✅。

### P1-2 自更新 — **PASS**

commit 4b5bb6f。与 PLAN §P1-2 一致 + 两处正当增强：

**安全红线 3：SelfUpdater 下载校验链 — PASS**

校验链逐环核对：
1. **HTTPS**：API 与下载 URL 均为 `https://`（REPO=donguaizs/QuestTuner 已按 lead 决定填入真实坐标，SelfUpdater.kt:19-20）✅；无任何 cleartext 配置（PLAN"明确不做"清单遵守）✅
2. **包名一致**：`archive.packageName == context.packageName`（:112）✅
3. **签名证书一致**：GET_SIGNING_CERTIFICATES 读取两侧 signingInfo.apkContentsSigners，逐证书 `toByteArray().contentEquals` 比对（:113-119）——换签 APK 被拒 ✅
4. **防降级**：`versionGreaterThan(archive.versionName, currentVersionName)`（:120-122）+ 检查阶段远端 tag 与本地比较（:52）双层 ✅
5. **校验失败删文件**：VM 侧 `onFailure { f.delete() }`（TunerViewModel.kt:353-357）+ 下载异常 `dest.delete()`（SelfUpdater.kt:95-97）+ receiver 终态删 apkPath（InstallResultReceiver.kt:22,26）——三处覆盖，**校验失败绝不进入安装** ✅
6. PackageInstaller 失败=旧版本原地保留（天然回滚），失败文案附 PC adb install 备选（:162）✅
7. 门禁：canRequestPackageInstalls 检查 + ACTION_MANAGE_UNKNOWN_APP_SOURCES 引导（:129-139）✅
8. PendingIntent FLAG_MUTABLE（系统需回填 status）+ receiver exported=false（仅本 app 可触发）✅
9. 增强项 1：404 → "暂无发布版本"（:41）——比 PLAN 更如实（不假装"已是最新"）✅
10. 增强项 2：手动触发（无自动检查）、notes take(2000) 截断 ✅

版本比较 5 个单测全过（等长/不等长/非数字段/v 前缀语义边界）✅。

### P2-1 OVR Metrics 开关 — **PASS**

commit f3fc36c。与 PLAN §P2-1 一致：isAvailable 包安装探测（NameNotFoundException → false）✅、未安装整块禁用文案 ✅、三个常量集中 + TODO(验证后修正) 标注（A7 假设如实登记）✅、"无法在应用内确认效果"文案如实 ✅、"重新发送全部开关"幂等按钮 ✅、开关默认 false ✅。

### P2-2 Private DNS / 向导 / 开机恢复 — **PASS**

commit f3fc36c。与 PLAN §P2-2 一致：

**PrivateDns（A8）**：写前快照 oldMode/oldSpec → 写入 → 回读两键 → 不一致/异常**自动还原旧值**（PrivateDns.kt:40-51）✅；off() 带回读 ✅；NoPermission 门禁 ✅。

**SetupWizard**：两步向导、授权命令展示+复制、"不授权也能用"降级说明 ✅；第二步顶部 `!hasSecureGrant` 提示（PLAN"如余量可加"的可选项已做，SetupWizard.kt:49-52）✅；firstRunDone 持久化 ✅。

**BootReceiver（A5）**：action 过滤 + restoreOnBoot 开关门禁 ✅；**best-effort 语义**（不持 pendingResult，拉协程即返回；PLAN 已论证 goAsync 10s 装不下 35s AUTH）✅；完成后 disconnect + disableWifiAdbIfWeEnabled（不留常驻连接、只回滚自己开的）✅；boot.log 本地记录 ✅。

**安全红线 6：RECEIVE_BOOT_COMPLETED / BootReceiver 行为 — PASS**

- BOOT_COMPLETED 是普通广播权限，manifest 声明 + exported=true 是接收该保护广播的标准做法；receiver 内部逻辑仅读自身 prefs 开关（默认关）→ 未开启时**零行为** ✅
- 开启后行为链全部在 app 自身作用域：本机 5555 连接（无外部网络）、resetAll setprop、写自身 filesDir 日志——无数据外发、无外部存储、无 UI 打扰 ✅
- 开机恢复失败静默放弃（无提示轰炸）✅

### P3 R8 release 工程化 — **PASS**

commit 22ff54a。与 PLAN §P3 一致：minify+shrinkResources+debug keystore 签名（TODO 正式 keystore 迁移已标注）✅、versionCode=2/versionName=0.2.0 ✅、ui-tooling-preview 已移除 ✅、junit + org.json:json 测试依赖（org.json JVM stub 问题按 PLAN 注意事项 6 处理）✅。

**安全红线 4/5：R8 keep 规则 — PASS（产物级实测，非仅代码审查）**

这是 lead 特别标注"最容易出运行时问题"的点，我做了产物级验证（方法：解包 release APK → dexdump 全 dex + aapt2 dump resources/xmltree）：

| 验证对象 | 结果 |
|---|---|
| manifest 四组件类名 | MainActivity / ForegroundProfileService / BootReceiver / InstallResultReceiver 在 dex 中**全部按原名 PRESENT**（AGP 自动 keep 生效，proguard-rules.pro 无需手写）|
| a11y 配置资源 | manifest `meta-data resource=@0x7f0c0000` → arsc `xml/accessibility_service_config` → APK 内 `res/-N.xml` 实际存在，aapt2 dump 内容确认含 `typeWindowStateChanged(0x20)` / `notificationTimeout=800` / **`canRetrieveWindowContent=false`**——资源收缩器正确识别 meta-data resource 引用，未误删 |
| a11y 描述字符串 | `@string/a11y_service_desc`（0x7f090000）经 arsc 解析存在 |
| 无用组件误删 | 无（四个组件全在）|
| tooling 残留 | dex 中 **0 处** PreviewActivity ✅ |
| 体积 | 21,677KB → 1,837KB ✅ |

proguard-rules.pro 的"无需额外 keep"判断被产物证明成立：工程确无反射调用（grep 无 Class.forName/getStringExtra 动态类），manifest/资源引用链均被 AGP+AAPT2 自动处理。

## 假设台账 A1-A8 三要素核对（lead 硬约束）

| # | 运行时探测 | 失败降级 | 开关隔离（默认关）|
|---|---|---|---|
| A1 | checkPermission（:32-36）| 自动化区置灰 + 可复制命令 | 总门禁 grant |
| A2 | putInt+getInt 回读（:46-47）| "请手动开启"文案，继续手动连接 | autoWifiAdb |
| A3 | 4×1.5s 连接重试（:157-160）| 超时→手动文案，不崩溃 | 同 autoWifiAdb |
| A4 | 存储回读（:94-96）+ 行为级轮询 3s/200ms（:115-130）| "请到系统设置手动开启" | autoA11y |
| A5 | boot.log 记录连接结果（BootReceiver:33）| 静默放弃，下次开机重试 | restoreOnBoot |
| A6 | receiver EXTRA_STATUS 终态 | 旧版本原地保留 + PC adb install 文案 | 手动触发（检查更新按钮）|
| A7 | isAvailable 包安装检测（OvrMetrics:28-33）| "无法确认效果"如实文案 + 未装禁用整块 | 三个独立开关 |
| A8 | 写后回读两键 | **自动还原旧值** + 错误提示 | 需 A1 grant |

全部 8 条三要素齐备 ✅（A9 沿用 round-1 已实机验证前提，statusCommand 回读即探测）。

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
环境：JAVA_HOME=F:\workspace\toolchain\jdk-extract\jdk-17.0.20.1+1 / GRADLE_USER_HOME=F:\workspace\toolchain\gradle-home / TEMP,TMP=F:\workspace\tmp
结果：BUILD SUCCESSFUL in 32s（92 tasks：47 executed, 42 from cache, 3 up-to-date）
单测：MergeA11yListTest 4/4 + VersionCompareTest 5/5 = 9/9 通过
产物：app-debug.apk 21,677KB / app-release.apk 1,837KB（versionCode=2, versionName=0.2.0）
```

## 观察点（RISK，均不阻断合入）

**RISK-1（轻微，A4 探测窗口）**：`waitA11yRunning` 轮询 3s；若个别固件服务冷启超 3s，UI 显示"已写入但服务未运行"而实际稍后起来了——文案已如实（"请到系统设置手动开启"是兜底建议），init 的 `isA11yRunning` 会在下次冷启时纠正。可接受。

**RISK-2（轻微，开机竞态）**：BootReceiver 开机即连 5555，若 adbd 晚于 receiver 就绪，ensureWifiAdbThenConnect 的 4 次重试（含 delay）总计约 6s + 连接超时预算——极端慢启动设备可能首次失败（best-effort 语义内，boot.log 可见）。可接受。

**RISK-3（轻微，下载 URL 直连）**：`browser_download_url` 来自 GitHub API 响应，未校验其 host 属 github.com（API 是 HTTPS 的，中间人风险已被 TLS 覆盖；但若仓库被接管，恶意资产 URL 可指向任意外部 host）。四重校验（包名+签名）使恶意 APK 无法通过校验，实际风险=下载了校验不过的文件然后被删除。纵深防御可选：校验 URL host ∈ {github.com, objects.githubusercontent.com}。round 3 可选加固。

**RISK-4（轻微，disableA11yService 无回读）**：与 enable 的"写后回读"纪律相比，disable 分支写 rest 后未回读验证（SecureAutomation.kt:108-110）。写入失败会返回 CapProbe.Ok（runCatching 只捕异常）。影响：开关关闭但服务可能仍在列表（下次 init 的 isA11yRunning 会如实显示）。round 3 可补一行回读。

**RISK-5（提示，向导遮挡）**：向导第二步勾选 autoA11y 时降级文案写入 adbStatus 但被 AlertDialog 遮挡——PLAN 已知并接受（第二步顶部已加未授权提示，缓解）。

## 实机验证建议清单（交付后自验，按 PLAN 全局注意事项 8 的 12 条 + 我补充的重点）

1. **回归保证**：首启向导不勾任何开关 → 行为与 round-1 完全一致
2. 未授权：自动化区只读 + 命令可复制；连接走手动路径
3. PC 执行 `pm grant` → 重进 app 开关可动（A1）
4. **重点（红线 1）**：开自动无障碍前先用系统设置启用一个第三方无障碍服务 → 开我们的开关 → 确认那个服务**仍在启用列表**；关我们的开关 → 它仍启用
5. **重点（红线 2）**：系统设置里本服务的描述文案正确；开启后切换应用，`adb shell dumpsys accessibility` 确认无 window content 读取
6. 自动应用：进出有 profile 的 VR 应用 → 参数应用/回滚（a11y.log 有记录）；关开关后切换无命令
7. 应用选择器：搜索/选择/预填 label；未连接可用；VR 徽标排前
8. **重点（红线 3）**：完整更新流程（A6 确认页）；用换签 APK 替换资产 → 校验失败拒绝（安全行为）
9. OVR Metrics：未装目标包区块禁用；装了发广播 + 按 PLAN P2-1 的 PC 脚本校准三个常量
10. **重点（红线 6）**：开 restoreOnBoot → 重启 → getprop 默认 + boot.log 记录；关掉开关 → 重启无任何行为
11. Private DNS：设假主机名回读 hostname；关→off；回读失败自动还原
12. **release APK**：装 release 版跑全部回归（R8 产物已静态验证，实机再确认一遍运行时无 ClassNotFound）
13. 我补充：卸载重装后 WRITE_SECURE_SETTINGS 失效（Android 行为）→ 向导/降级文案正确引导重授权

## 最终判定

**fix/round-2 的 7 个实施项全部按 PLAN 正确实现（含 3 处正当偏差），lead 划定的 6 条安全红线全部 PASS（其中 R8/资源保留为产物级实测），构建与单测全绿。建议合入 master。**
