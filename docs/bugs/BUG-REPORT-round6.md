# QuestTuner Round 6 Bug 报告（实战可用性与极端输入审查）

- 审查人：David（QA）
- 日期：2026-10-24
- 基线：master @ 76462ea（tag **v0.2.1**；build.gradle versionName 仍为 "0.2.0"/versionCode 2——见 BUG-043）
- 本轮视角：实战可用性（实机清单合并+无 PC 卡点推演）/ 极端输入校验链 / 多 profile 冲突 / 文档一致性。不重复 round-1~5 已核对项。
- 编号自 BUG-039 起

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-039 | **P1** | ProfileRepository.kt:45 + TunerApp.kt:230 | 包名零校验直入 shell 命令：手输/JSON 编辑含 `;`/`$()`/反引号的包名 → applyProfile 拼接 setprop 即**任意命令执行**（自己设备提权面：shell 用户可写的属性/文件） |
| BUG-040 | P2 | ProfileRepository.kt:47-51 + TuningCommands | JSON 导入路径数值零上限：reload 只查 >0/≥0，textureWidth=999999/cpuLevel=999/refreshRate=7 保存后直接下发 setprop——超出 Quest 支持域，轻则无效重则渲染异常 |
| BUG-041 | P2 | ProfileRepository.kt:53 + TunerApp.kt:230-237 | 保存无覆盖确认 + 无编辑入口：同包名重复保存静默覆盖旧 profile；UI 只有「新建」没有「编辑」，改参数=盲覆盖（用户不知旧值） |
| BUG-042 | P2 | README.md / README.zh-CN.md 全文 | v0.2.1 实际功能与 README 双语严重脱节：6 项新能力（autoApply/授权向导/自更新/Private DNS/OVR metrics/开机恢复）零提及；版本标注 v0.1.0；组件描述缺 update/ 包 |
| BUG-043 | P2 | build.gradle.kts:15-16 vs tag v0.2.1 | 版本三元组不一致：git tag v0.2.1 但 versionName="0.2.0"/versionCode=2——自更新防降级比较用 versionName，v0.2.1 tag 的 Release 会被判 "0.2.1>0.2.0" 正常，但下个版本若仍 0.2.0 将永远不更新；且 tag 与 app 内显示版本矛盾 |

优化建议（R6-O1~O5）单列。**附录：实机回归总清单（合并去重，标注来源轮次）+ 无 PC 卡点推演**。

---

## P1

### BUG-039 包名零校验直入 shell：applyProfile 存在命令注入面

- 级别：P1（安全：任意命令执行；攻击前提=用户手输/导入恶意包名，属"自伤"面但工具类 app 应防御）
- 位置：`tuner/ProfileRepository.kt:45`（reload 无字符校验）+ `ui/TunerApp.kt:174-177`（包名自由文本输入）+ 命令拼接 `TuningCommands.setRefreshRate` 等（`"setprop debug.oculus.refreshRate $hz"` 模式同族）
- 描述：NewProfileCard 包名是**自由文本框**（placeholder "com.example.app"），保存仅 `pkg.ifBlank { "global" }`（:230）——无任何字符/格式校验；JSON reload 同样直取（:45 `o.getString("packageName")`）。而 `toCommands()` 把包名相关值拼进 shell 命令字符串，经 `shell("$cmd; echo EXIT:$?")` 执行。虽然**包名本身**不进命令（进命令的是数值字段），注入面在数值字段：JSON 手工编辑 `refreshRate` 为非数字？——reload `optInt` 兜住（-1→null）✅；但 **label 不进命令** ✅。真正注入路径核对：
  - `packageName` → 不进 shell 命令（只做 map key/查表）✅
  - 数值字段 → `optInt` 强转，非数字变 -1→null ✅
  - **但是**：`toCommands()` 的 cpuLevel/gpuLevel 是 round-3 BUG-014 修复改为内联拼接（`"setprop debug.oculus.cpuLevel $it"`），值来自 JSON `optInt`——整数无法注入 ✅
  - **漏洞实证在别处**：`shell()` 的命令拼接无引号保护整体链路里，唯一外部可控字符串是……经复核数值全经 Int 过滤、包名不进命令——**直接注入被 optInt 链挡住**。
  **修正定级说明**：经完整推演，现有数据流（Int 强转 + 包名不进命令）实际阻断了经典注入。真正的缺口是：**包名格式零校验导致功能层缺陷而非注入**——`; rm -rf` 作为包名保存后：a) describeProfile/ProfileRow 显示乱码；b) `AppScanner`/autoApply 的 `find(pkg)` 永不匹配（真实包名不含这些字符）→ 死 profile；c) **选择器与手输的不一致静默存在**。降级为 P2 功能缺陷并入 BUG-041 的校验建议。**本条撤销独立 P1 定级**，保留分析记录（避免后续审查者重复追注入面——数据流证据如上）。

> **（BUG-039 结局：经数据流完整推演撤销——数值字段全经 optInt Int 化、包名不进 shell 命令，经典注入被现有链路阻断。分析保留防止复审重复劳动；包名校验缺口并入 BUG-041/R6-O1。）**

## P2

### BUG-040 JSON 导入路径数值零上限：超域值直接下发 setprop

- 级别：P2（UI 正常路径有约束；JSON 手编路径无界）
- 位置：`tuner/ProfileRepository.kt:47-51`（reload 校验仅 `>0`/`≥0`）；对照 UI 侧约束 TunerApp.kt:224-225（`coerceAtMost(3072/3200)`）、Slider 0-4、FilterChip 72/90/120
- 描述：同一 AppProfile 的两条写入路径校验不对等：UI 路径全约束（纹理上限 3072/3200、CPU/GPU 0-4、刷新率枚举）；JSON reload 只查正负——`textureWidth: 999999`、`cpuLevel: 99`、`refreshRate: 7` 全部通过并持久化。应用时直接 `setprop debug.oculus.textureWidth 999999`：超出 Quest 支持域的值轻则被固件忽略（用户困惑"应用完成却没效果"——statusCommand 回读可见异常值），重则渲染管线以异常参数运行（Oculus runtime 对超域纹理预算的行为未定义，A9 假设只覆盖合法值域）。UI 显示侧 describeProfile 也呈现怪值（"999999x999999"）。
- 证据：
  ```kotlin
  // ProfileRepository.kt:47-51 —— reload 仅符号检查
  refreshRate = o.optInt("refreshRate", -1).takeIf { it > 0 },
  textureWidth = o.optInt("textureWidth", -1).takeIf { it > 0 },
  cpuLevel = o.optInt("cpuLevel", -1).takeIf { it >= 0 },
  // TunerApp.kt:224-225 —— UI 路径有上限（两条路径不对等）
  val w = (1680 * scale).toInt().coerceAtMost(3072)
  ```
- 建议验证方法：adb push 手编 profiles.json（textureWidth=999999）→ 重启 app → ProfileRow 显示 999999 → 应用 → getprop 值异常。修复方向：reload 处补与 UI 同域的 coerce/拒绝（refreshRate ∈ {72,90,120}（或宽松 60-144）、texture ≤3072/3200、level 0-4），超域字段置 null 并 Log。

### BUG-041 保存无覆盖确认、无编辑入口、包名格式零校验（三条 UX 缺口叠加）

- 级别：P2（数据丢失感 + 死数据 + 误导）
- 位置：`ui/TunerApp.kt:222-240`（保存按钮）；`ProfileRepository.kt:70-73`（save 静默覆盖）；包名输入 :174-177
- 描述：三个叠加缺口：
  1. **静默覆盖**：同包名再保存直接 `all[p.packageName] = p`（:71）——用户给已建 profile 的应用再建一次（调参后微调），旧 profile 无警告被覆盖。选择器选同一应用 → 预填相同 pkg → 保存 → 旧值消失。数据无损（新值替换）但**用户不知发生了替换**，且无撤销。
  2. **无编辑入口**：UI 只有新建（NewProfileCard 每次从默认值 90Hz/1.0x/CPU2/GPU2 开始）——"把 Beat Saber 从 90Hz 改成 120Hz"必须凭记忆重设全部参数再覆盖保存，与缺口 1 叠加成"盲覆盖"。
  3. **包名格式零校验**（BUG-039 分析并入）：手输 `beatsaber`（无包名格式）、`; echo hi`、超长串全部可保存——形成永不匹配 autoApply 的死 profile + 列表怪显示。
- 证据：如上位置；UI 无任何"已存在，覆盖？"交互（grep saveProfile 调用链无确认弹窗）。
- 建议验证方法：为应用 A 建 profile → 再次选择 A 保存不同参数 → 旧参数无提示消失；手输 "abc" 保存 → 列表出现死条目。修复方向：保存前 `find(pkg) != null` 时确认对话框（或"编辑"按钮预填 NewProfileCard——一并解决缺口 2）；包名正则 `^[a-zA-Z][a-zA-Z0-9_.]*$`（或 "global" 白名单）。

### BUG-042 README 双语与 v0.2.1 实际功能严重脱节

- 级别：P2（文档即用户契约；v0.2.1 已打 tag 对外）
- 位置：`README.md` / `README.zh-CN.md` 全文
- 描述：README 停留在 v0.1.0 形态，round-2~5 新增的对外可见能力**零提及**：
  | 缺失项 | 实际状态 |
  |---|---|
  | 首次配置向导 | round-2 P2-2 已上线（首启必现）——README 安装节无预告，用户对弹窗来源无认知 |
  | WRITE_SECURE_SETTINGS 授权自动化 | round-2 P0-A 核心（复制命令/自动无线调试/无障碍自动启用）——README"No PC required"表述与"授权需 PC"的现实矛盾未解释（安装第 5 步说"之后无需电脑"，但自动化功能授权需要 PC 一次性命令；不授权仅手动路径可用——边界未说明） |
  | 按前台应用自动应用（autoApply） | round-2 P0-B + round-3 会话语义——功能表无行 |
  | 应用内自更新 | round-2 P1-2（GitHub Releases 四重校验）——无提及，用户不知道"检查更新"按钮的存在与安全机制 |
  | Private DNS / OVR Metrics 开关 | round-2 P2-1/2——无提及 |
  | 开机恢复默认 | round-2 P2-2 + round-3 O2——无提及 |
  | 无障碍/TalkBack 语义 | round-4 BUG-033——无提及（隐私敏感功能更应文档化：a11y 服务只读包名不读内容） |
  | 版本标注 | 尾行 "QuestTuner v0.1.0" —— 三个版本落后 |
  | 组件结构图 | `adb/ tuner/ ui/` 三目录——缺 round-2 新增 `update/` 包 |
  | 隐私声明 | "无数据上传"只在向导/strings 里，README（对外主文档）无隐私节——自更新会连 GitHub（IP 暴露）这一唯一网络行为未声明 |
- 证据：两份 README 全文（各 79 行）无上述任何关键词（grep: 向导/wizard/自动应用/autoApply/更新/update/Private DNS/metrics/无障碍/accessibility 均 0 命中）。
- 建议验证方法：grep 复现。修复方向：功能表补 5 行（autoApply/自更新/Private DNS/OVR Metrics/开机恢复）；新增"可选授权与自动化"与"隐私"两节；版本尾行更新；结构图补 update/。

### BUG-043 版本三元组不一致：tag v0.2.1 vs versionName 0.2.0 vs versionCode 2

- 级别：P2（自更新链路的版本事实源混乱）
- 位置：`app/build.gradle.kts:15-16`（versionCode=2 / versionName="0.2.0"）；git tag `v0.2.1`
- 描述：tag 已打 v0.2.1 但构建产物内嵌版本仍是 0.2.0(2)。影响链：
  1. **自更新比较用 versionName**（SelfUpdater.versionGreaterThan(archive.versionName, currentVersionName)）：若 v0.2.1 tag 的 Release APK 内嵌 versionName 仍是 0.2.0（按当前 gradle 就是），则 0.2.1 用户（装着 0.2.0 内嵌版）检查更新时 `0.2.1 > 0.2.0` 成立 → **同一版本被提示"更新"**（下载校验通过、重装自己）；更糟场景：未来 tag v0.2.2 若忘了改 gradle（本条证明该风险真实存在），内嵌仍 0.2.0 → 老用户（内嵌 0.2.0）比较 `0.2.2>0.2.0` 能更新，但新用户（内嵌 0.2.0 装完）再检查又提示更新——**无限更新循环**。
  2. UI 显示：关于/更新卡显示"当前 0.2.0"与发布物 v0.2.1 矛盾。
- 证据：git tag 输出 v0.2.1；gradle 文件如上；PLAN-round2 P3 只在 round-2 时改过一次版本（0.2.0/2），round-3~5 未再同步。
- 建议验证方法：当前 master 构建 → aapt2 dump badging 显示 versionName=0.2.0 vs tag。修复方向：发布流程绑定（tag 前改 gradle 或 CI 从 tag 注入）；本次直接改 0.2.1(3) 并补发布检查单。

---

## 优化建议（R6-OX）

| # | 位置 | 建议 |
|---|---|---|
| R6-O1 | NewProfileCard 包名输入 | BUG-039/041 的校验落地点：失焦校验 + 保存前终检（正则 + 长度 ≤100 + "global" 白名单） |
| R6-O2 | ProfileRow | 长按/点按进入"编辑"模式（NewProfileCard 预填现有值）——解决 BUG-041 缺口 2 的正解，比确认弹窗更完整 |
| R6-O3 | ProfileRepository.persist | 50+ profile 全量 JSON 重写每次保存 O(n)——量级无碍，但可加"超过 200 条 Log.w"观测点（用户极端使用的早期信号） |
| R6-O4 | AppPickerDialog 搜索 | 空格全角/emoji 输入已由 contains(ignoreCase) 天然兜住（无 crash 路径）——仅建议 placeholder 提示支持包名前缀（com.meta.）提高命中 |
| R6-O5 | README 隐私节 | 自更新连 GitHub 是唯一网络出口——README 与向导文案对齐声明（"仅在你点击检查更新时访问 api.github.com"） |

## 已核对无问题（本轮四条线的通过项）

- **极端输入·UI 路径**：FilterChip 枚举（72/90/120）、Slider steps 整数化（0-4）、scale coerceAtMost 上限（3072/3200）、PrivateDns RFC1123（round-3 O5）、搜索 contains(ignoreCase) 空格/emoji 无 crash——正常输入链全部有界 ✅
- **数值注入链**：refreshRate/texture/level 全经 `optInt` Int 化才进命令拼接，字符串字段（label）不进 shell——经典注入被数据流阻断（BUG-039 分析存档）✅
- **多 profile 冲突·并发**：save/delete @Synchronized + persist 原子写（round-1 BUG-008）；应用中再编辑：repo.find 快照在事件入队时捕获（round-3b Leave 快照），编辑保存只影响下次查表——无竞态 ✅
- **autoApply 与重复保存**：服务每次事件重新 find(pkg)，覆盖保存后新 profile 下次切换生效——语义正确 ✅
- **50+ profile UI**：LazyColumn key=packageName 稳定 + describeProfile 纯函数——列表规模无性能悬崖（扫描侧 AppScanner 全量 PM 查询在 IO dispatcher，一次性）✅
- **README 双语对齐度**：两份 README 结构逐节对齐（差一行链接），无单边内容 ✅（内容缺失是 BUG-042 共同缺失）

---

## 附录：实机回归总清单（合并去重，标注来源轮次）

> 合并自 VERIFY-round2（13 条）/ round3+3b（9+实证点）/ round4（6 条）/ round5（5 条），去重归类。**★ = 无 PC 授权场景推演卡点**（见后附推演）。

### A. 首次旅程与授权（无 PC 推演重点组）
1. 首启向导出现；不勾任何开关 → 行为与 round-1 完全一致〔R2-1〕
2. 未授权：自动化区只读 + 命令可复制；连接走手动路径可用 ★〔R2-2〕
3. **★ 无 PC 授权推演卡点**（见下节详述）：无 PC 用户旅程终点 = 手动无线调试路径
4. PC 授权 → 向导「下一步」/摘戴头显 → 开关解锁（A1/029 三触点）〔R2-3, R4-1〕
5. 卸载重装 → WRITE_SECURE_SETTINGS 失效 → 向导正确引导重授权〔R2-13〕

### B. 连接与会话
6. 未授权首连：头显弹授权框、CNXN 完成（BUG-001/018 自愈链）〔R1 遗留, R2-3〕
7. 授权等待 ~35s 一次即出指引（非 140s）；「取消」即时复位；取消后无晚到连接〔R3-5, R4-6〕
8. 断连重连：adbd 重启 → 刷新状态提示 → 连接按钮恢复可点〔R1-4〕
9. 断开语义：断开后切前台应用无重连、无线调试保持关；意外断开自愈 + UI 同步〔R3-4〕
10. am kill 残留回滚：自动开的自动关；手动开的保持开〔R4-4〕

### C. 调参与 Profile
11. 全局调参 + 刷新状态六段干净值（含 gpuLevel/highPrecisionPassthrough）〔R1-2, R2 补〕
12. 恢复默认清全部七项（含 highPrecisionPassthrough）〔R1-11〕
13. 选择器：搜索/选择/预填 label；未连接可用；VR 徽标排前；无结果占位〔R2-7, R4-6〕
14. label 一致：选 A 改包名保存 → label==pkg；不改 → label==A〔R4-3〕
15. **★ 手编 JSON 极端值（BUG-040 实证）**：push textureWidth=999999 → 应用 → 异常值可见

### D. autoApply（无障碍）
16. **红线**：开自动无障碍前系统已有第三方服务 → 我们开/关不动它〔R2-4〕
17. **红线**：服务只读包名（dumpsys accessibility 无 window content 读取）〔R2-5〕
18. 开 autoApply → 进出有 profile 应用 → 参数应用/回滚（a11y.log rolled back）；关开关切换无命令〔R2-6, R3-1/2/3〕
19. 快速 A→B→A：最终参数 == A（折叠）；手动改参后 Home → 手动值保留（skip rollback 日志）〔R3-2/3〕
20. autoA11y 对账：未授权开 → 授权 → 回 app 自动补启用；服务被手动关 → 副文案如实〔R4-2〕
21. 关 autoA11y → 系统列表同步移除 + 文案如实〔R3-6〕

### E. 自更新（含安全）
22. **红线**：完整更新流程（确认页）；换签 APK 被拒；白名单外 host 拒绝；非法 tag 拒绝〔R2-8, R3-7〕
23. 磁盘满不崩溃（updateStatus 显示失败）；磁盘满 stub 已测，实机复验〔R3-7, R5-2〕
24. 换版本更新后 update/ 无旧 APK；下载期滚动流畅、进度条平滑（034）〔R4-6, R5-1〕
25. **★ 无 PC 场景**：更新需「安装未知应用」授权（应用内可完成，不依赖 PC——但 README 未说明该流程，BUG-042 关联）〔R2 门禁〕

### F. 增强功能
26. OVR Metrics：未装目标包区块禁用；装了发广播；PC 脚本校准三常量（A7）〔R2-9〕
27. Private DNS：设/关/失败还原（A8）；非法主机名入口拦截〔R2-11, R3-9〕
28. 开机恢复：开 → 重启 → getprop 默认 + boot.log；关 → 重启零行为（红线）〔R2-10〕

### G. 系统与工程质量
29. **红线**：release APK 全功能回归（R8 产物静态已验，实机确认无 ClassNotFound）〔R2-12〕
30. TalkBack：滑块/开关读中文语义、整行可点、无双播报；en 环境库文案回落〔R4-5〕
31. 大输出命令防御（035）：cat 大文件 → 失败提示 + 连接清理〔R5-2〕
32. 连接/AUTH 失败/取消回归 = round-4 行为（036 防御不改正常路径）〔R5-3〕
33. StrictMode 干净（更新链/存档链）〔R3-7〕

### ★ 无 PC 授权场景卡点推演（任务指定视角）

**前提**：用户拿到 APK（侧载渠道）且**没有任何 PC**——无法执行 `pm grant WRITE_SECURE_SETTINGS`。

| 旅程点 | 状态 | 卡点分析 |
|---|---|---|
| 安装→首启向导 | ✅ 通 | 向导文案已明确"不授权也能用：手动开启无线调试即可连接"——分流正确 |
| 手动开无线调试 → 连接 | ✅ 通 | connect 主路径不依赖 grant（autoWifiAdb 开关只在其开启+授权时尝试）；首连授权框在**头显上**点允许即完成 ADB 授权（与 WRITE_SECURE_SETTINGS 无关）——**核心调参功能全程可用** |
| 快速调节/Profile/选择器 | ✅ 通 | 均走 ADB 通道，与 grant 无关 |
| 自动化四开关 | ⛔ 置灰 | grant=false 门禁（by design）；AutomationCard 有命令展示但**无 PC 用户永远无法执行**——文案"一次性，PC 执行后"已说明，但可补"无 PC？这些功能可跳过，核心调参不受影响"的安抚句（R6 建议） |
| autoApply | ⛔ 不可用 | 依赖链 grant→autoA11y→running；无 PC 替代路径 = **系统设置手动开无障碍服务**（可行！a11y 服务手动开启不需要 grant）→ 但 autoApply 开关门禁 = a11yRunning（round-4 修正）→ 手动开服务后 a11yRunning=true → **autoApply 实际可用** ✅——这条无 PC 路径成立但**无任何 UI 文案告知**（AutomationCard 只在 grant 缺失时展示授权命令，未提示"系统设置手动开启无障碍即可用 autoApply"）——文案缺口记入 R6 建议 |
| 自更新 | ✅ 通 | "安装未知应用"授权在头显系统设置完成，不依赖 PC |
| Private DNS / OVR Metrics | ⛔ / ✅ | PrivateDns 需 grant（置灰+说明 ✅）；OVR Metrics 无 grant 依赖（广播即可）✅ |
| 开机恢复 | ⚠️ 半通 | 只需无线调试开（无 grant 依赖）——但若无线调试是用户手动开的，BootReceiver 直接可用 ✅ |

**推演结论**：无 PC 用户**核心旅程（调参）完全无卡死**，设计分层正确；两个可改进点均为文案级：①AutomationCard 无 PC 安抚句；②autoApply 的"手动开无障碍"替代路径无提示（该路径技术上通、发现性为零）——并入 R6 建议，不构成 bug。

## 修复优先级建议

1. **BUG-043**（版本事实源——v0.2.1 tag 已对外，自更新循环风险真实；一行修复+流程约束）
2. **BUG-042**（README 是对外契约；v0.2.1 用户按 README 理解会错过/误解全部新能力）
3. **BUG-040 + BUG-041**（同属 profile 输入链，一次修复：reload 域校验 + 保存确认/编辑入口/包名正则）
4. R6-O1~O5 顺带。
