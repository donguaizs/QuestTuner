# QuestTuner Round 6 修复验证报告（VERIFY-round6）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-6`（master..fix/round-6 共 4 commits，4e10b65..71bf3ac）
- 方案依据：docs/plans/PLAN-round6.md（Li）；bug 依据：docs/bugs/BUG-REPORT-round6.md（BUG-040~043 + R6-O1~O5）
- 验证方式：逐 commit diff 审查 + 5 个重点专项深审 + clean 三件套 + badging 版本实证 + 测试覆盖度核对

## 总体结论

**4/4 bug 修复 + R6 优化项全部 PASS。建议合入 master。**

- 构建：`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 32s**
- 单测：**31/31 通过**（新增 ProfileValidationTest 5 用例；既有 26 保持绿）
- **badging 实证**：`versionCode='3' versionName='0.2.2'`——BUG-043 的版本事实源已对齐
- 2 个轻微观察（不阻断，见末节）

## 重点专项详审

### 专项① BUG-043 版本一致性 — **PASS**

commit 4e10b65。

- **badging 产物实证**：release APK `versionCode='3' versionName='0.2.2'` ✅——三元组修复为 0.2.2(3)，采用**向前对齐**（不打已存在的 v0.2.1 tag 改名，下一 tag v0.2.2 与内嵌一致）——处置正确（改历史 tag 会破坏已发布物完整性）✅
- **发布恒等式注释**：gradle 内注释明确"tag 名 == 'v' + versionName；打 tag 前必须先改本处两个值"+ 指向 SelfUpdater.versionGreaterThan 的比较事实源 ✅——BUG-043 的根因（人工记忆同步）被文档化约束
- **TODO-round-next.md 发布检查单质量**：5 条覆盖闭环——①versionCode+1/versionName 递增 ②恒等式核对 ③badging 实证内嵌值 ④Release 资产必须来自该 tag 构建 ⑤换 keystore 前重读迁移策略——第 4 条正是"资产与 tag 脱节"这一 BUG-043 变体的防线；第 5 条延续了 round-2 P3 的 TODO(lead) 不遗失 ✅。延后池 4 项与历轮在案项一致（R4-O7/R5-O1/R5-O2/A7）✅

### 专项② BUG-040 域校验对等性 — **PASS**

commit 83fbf1e。

- **三函数与 UI 域对等**：
  - `sanitizeRefreshRate`：`v in {72,90,120}`——与 FilterChip 枚举严格同域 ✅
  - `sanitizeTexture`：宽 ≤3072 / 高 ≤3208——与 UI 的 coerceAtMost(3072/3200) **兼容不冲突**（注释论证清楚：UI 3200 是缩放计算产物上限，3208 是 README 声明的硬件上限用于 JSON 存量校验；UI 产物 1760×2=3520→截到 3200 < 3208 恒过校验）✅
  - `sanitizeLevel`：0..4——与 Slider steps 同域 ✅
- **continue 写法不误伤解析循环**：`continue` 在 `runCatching` 内的 for 循环里——不抛异常、不中断循环、不影响 `isSuccess` → **ok 判定不受影响**（脏包名跳过 ≠ JSON 损坏）✅ 语义正确区分"条目级脏"与"文件级坏"
- **置 null 后的表现链**：describeProfile 对 null 字段自然跳过（"未设该项"呈现）✅；persist 的 `?.let` 链不写 null 字段——**下次保存即自愈**（超域字段从 JSON 消失）✅；超域观测 Log.w（o.has 检查避免把"未设"误报为"超域"）✅
- 设计决策核对：置 null 而非 coerce——注释明确"静默产生用户没写过的值不诚实"；与我在 BUG-040 的建议一致方向 ✅

### 专项③ BUG-041 三旅程走查 — **PASS**

commit b435d9e。

- **新建旅程**：保存 → precheckSave 包名终检（非法 → 行内红字 isError + return）→ 合法 + `edit==null && profileExists` → **AlertDialog 覆盖确认**（"已存在…保存将覆盖旧值" + 覆盖/取消）✅——静默覆盖消除
- **编辑旅程**：ProfileRow 新增"编辑"按钮 → `editingProfile=p` → NewProfileCard `remember(edit)` 键控全部字段重置预填（pkg/hz/cpu/gpu/scale 反推 `textureWidth/1680f` + coerceIn 兜超域历史值/pickedPkg+pickedLabel 锚定原值沿用 label）✅；标题切换"编辑 Profile：xxx" + 取消编辑入口 ✅；保存后 `saveProfile` 的 copy 置 `editingProfile=null` → remember(null) 重置回新建默认 ✅——**状态机闭环**（编辑→保存→自动退出编辑）
- **取消旅程**：cancelEdit → editingProfile=null → remember 键变 → 字段全部回新建默认 ✅
- **正则边界**（测试 13 例实证 + 手推）：`com.example.app` 合法、`com.a` 两段合法、`abc` 单段非法（Android 规范至少一个点）、`Com.Example.Mixed_Case1` 大小写+下划线+数字合法、长度 101+ 非法、注入字符（`; echo x`）/空格/斜杠/尾点/前导点全拒、`global` 白名单 ✅——正则 `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` 与 Android 包名规范对齐
- **reload 侧同正则**：脏包名条目 continue 跳过 + Log 自愈（存量死 profile 一次性清理）✅
- 双端一致：UI precheckSave 与 reload 用**同一个** `isValidPackageName` ✅——无两套规则漂移风险

### 专项④ BUG-042 README 双语对齐 — **PASS**

commit 71bf3ac。

- **8 处改动双语逐节对齐核对**：导语授权分层 / 功能表 +7 行（向导/自动应用/授权自动化/自更新/Private DNS/OVR Metrics/开机恢复）/ How It Works 补 update/ 包 / "可选授权与自动化"新节 / "隐私"新节 / Important Notes 补自更新门禁 / 项目树补 update/ / 尾行 v0.2.2——两份文件每处改动一一对应，无单边内容 ✅（延续既有对齐纪律）
- **隐私节技术事实核对（重点）**：
  - "唯一的网络行为是你主动点击「检查更新」时访问 GitHub API" ——与 SelfUpdater 实现一致：checkLatest（api.github.com）与 download（github.com/objects/release-assets.githubusercontent.com）**均只在用户点击检查/下载时发生**、无任何自动后台请求 ✅；下载域属同一用户动作的延续，表述"唯一的网络行为是检查更新（api.github.com）"以触发入口概括——技术上准确无夸大 ✅
  - "无障碍服务只读前台应用包名，绝不读取屏幕内容"——与 accessibility_service_config 的 canRetrieveWindowContent=false 一致（round-2 红线产物级验证背书）✅
  - "无统计、无追踪、无数据上传"——grep 全工程无 analytics/telemetry 路径 ✅
- **无 PC 替代路径表述与 030 实现一致性（重点）**："系统设置 → 无障碍 → 手动开启 QuestTuner 服务——按前台应用自动应用同样可用"——与 round-4 BUG-030 的门禁实现**精确吻合**：autoApply 门禁 = `state.a11yRunning`（非 grant），手动开启服务 → a11yRunning=true → 门禁通过 → autoApply 可用 ✅。这正是我在 round-6 报告无 PC 推演中发现的"零文案告知缺口"——README 补上了，且表述与实现一致
- 配套 UI 文案：AutomationCard 未授权提示追加无 PC 安抚句（"核心调参不受影响…系统设置→无障碍手动开启本服务后使用"）✅；选择器 placeholder 前缀提示（R6-O4）✅

### 专项⑤ ProfileValidationTest 覆盖度 — **PASS**

5 个测试方法 / 26 个断言点：

| 组 | 覆盖 |
|---|---|
| validPackageNames（5 断言） | 两段/多段/真实包名/大小写混合+下划线数字/global 白名单 |
| invalidPackageNames（8 断言） | 单段/空串/尾点/首段数字/前导点/注入字符/空格/斜杠/长度 101+——**正则全边界** |
| refreshRateDomain（6） | 三个合法枚举 + 非枚举 7/超上限 144/默认 -1 |
| textureDomain（9） | 宽高各自合法边界（3072/3208 恰好通过）+ 超一单位拒绝（3073/3209）+ 999999/0/-1 |
| levelDomain（5） | 0/4 边界通过 + 5/99/-1 拒绝 |

覆盖评价：**边界值测试法规范**（恰好通过/恰好拒绝成对）✅；纯函数抽取使 sanitize 可测（无 Context stub）✅；测试与实现同一 commit（类型锁效应——改域必须改测试）✅。缺口（可接受）：reload 的 continue 集成路径（JSON→跳过脏条目）未测——需 org.json 真实实现 + 文件 IO，成本高于收益，Log.w + 实机清单覆盖。

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
结果：BUILD SUCCESSFUL in 32s（87 tasks：42 executed, 42 from cache, 3 up-to-date）
单测：31/31（IntentFold 3 / LeaveSnapshot 3 / MergeA11y 4 / ProfileValidation 5 / ValuesMatch 5 / CleanStaleApks 2 / UpdateSafety 4 / VersionCompare 5）
badging：package: name='com.example.questtuner' versionCode='3' versionName='0.2.2' ✅
```

## 观察点（不阻断）

1. **编辑模式改包名到已存在的其他包名**：edit≠null 时完全跳过覆盖确认（"编辑=明确意图"的论证对"改成别的已存在包名"场景偏弱）——用户在编辑 A 时把包名改成已存在 B 的包名 → B 被静默覆盖。概率低（编辑通常不改包名）、后果有限（B 旧值被 A 的参数替换）。建议 round-next：编辑模式下 pkg != edit.packageName 且 target 已存在时也弹确认（一行条件）。
2. README 英文 Privacy 节正文以 "**Privacy.**" 开头与节标题重复——纯文案冗余，下次 README 触碰时顺手。

## 实机验证建议清单（增量）

1. **043**：按 TODO-round-next 检查单走一遍发布流程（tag v0.2.2 → badging 核对 → Release）——清单本身的实机演练
2. **040**：push 手编 JSON（textureWidth=999999/cpuLevel=99/refreshRate=7 + 脏包名条目）→ 重启 app → 超域字段显示"未设该项"、脏条目消失、logcat 有 2 条 Log.w → 再保存 → JSON 自愈干净
3. **041**：三旅程——新建撞名弹确认；编辑预填（含 scale 反推显示）→ 改参数保存 → 列表更新且退出编辑态；取消编辑回默认；包名红字（输 "abc" 保存）
4. **042**：按新 README 的"无电脑替代路径"走一遍：手动开无障碍 → autoApply 开关可点 → 切应用生效——README 声明的端到端实证
5. 老用户升级路径：0.2.0(2) 内嵌版 → 检查更新 → 0.2.2(3) 正常提示且安装后不再重复提示（BUG-043 修复的实测闭环）

## 最终判定

**fix/round-6 的 4 个 bug 修复 + R6 优化全部按 PLAN 正确实现，构建三件套全绿（31/31），badging 实证版本对齐（0.2.2/3），README 双语对齐且隐私/无 PC 表述与技术事实一致。建议合并到 master。**
