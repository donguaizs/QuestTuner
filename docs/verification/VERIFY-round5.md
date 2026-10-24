# QuestTuner Round 5 修复验证报告（VERIFY-round5）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-5`（master..fix/round-5 共 4 commits，5e9e81c..ad77393）
- 方案依据：docs/plans/PLAN-round5.md（Li）；bug 依据：docs/bugs/BUG-REPORT-round5.md（BUG-034~038 + R5-O3/O4/O5）
- 验证方式：逐 commit diff 审查 + 5 个重点专项深审 + grep 迁移完整性复核 + clean 三件套 + **产物级 dex 确认（Packet.crc / 依赖移除）**

## 总体结论

**5/5 bug 修复 + R5-O3/O4/O5 全部 PASS。建议合入 master。**

- 构建：`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 40s**
- 单测：**26/26 通过**（全部既有用例保持绿）
- APK：release 1720KB（round-4 1776KB → **-56KB**，依赖移除的体积收益）
- 产物级确认：`AdbProtocol$Packet` 实例字段仅 cmd/arg0/arg1/payload 四个（crc 已删）；协议解析路径（readPacket 自解析）无回归

## 重点专项详审

### 专项① BUG-034 拆流完整性 — **PASS**

commit 5e9e81c（R5-O5 落地）。

- **读写点迁移 grep 复核**：`updateProgress` 全工程命中 10 处——UiState 字段已删（data class 中无）、VM 私有 `_updateProgress` + 公开 `updateProgress: StateFlow<Int>`（:61-62）、写入 8 处（init collect :88、下载启动 :445、进度回调 :450、四条终态复位 :455/461/467/471）、消费 1 处（UpdateCard :23 局部 collectAsState）——**无遗漏、无残留** ✅
- **init collect 残留迁移**：installResult collect 中 `updateProgress = -1` 的旧 copy 行已改为 `_updateProgress.value = -1`（:88）✅
- **UpdateCard 局部 collect 生命周期安全**：`viewModel.updateProgress.collectAsState()` 在 Composable 内——collectAsState 绑定组合作用域，离开组合自动取消；VM 比 Composable 生命周期长（activity 重建），StateFlow 重收集时 replay 最新值（-1 或进行中进度）——无泄漏、无丢失 ✅
- **onNote 节流首条立即语义**：`lastNoteTime` 初始 0 → `now - 0 >= 200` 恒真 → **首条立即发布**；后续 200ms 窗口内合并 ✅。语义推演：连接过程最多 4 条 note（enable 结果 1 条 + AUTH 短路 1 条），节流收益主要在重试循环密集场景；被丢弃的中间 note 不影响终态（connect 成功/失败分支无条件覆盖 adbStatus）✅
- 高频源消除实证：下载进度现在只触发 UpdateCard 内 `progress` 读取点的重组（LinearProgressIndicator lambda），UiState 不再每百分点发射——**全树重组 → 单 Card 重组** ✅

### 专项② BUG-035 8MB 上限边界 — **PASS**

commit 5e9e81c 内（AdbConnection.kt:86-95 + companion :154-155）。

- **恰好 8MB 边界**：`if (total > MAX_SHELL_OUTPUT)` 严格大于——累计恰好 8_388_608 字节**允许**，超出 1 字节才抛 ✅（8MB 常量本身给 dumpsys 类命令留了余量，常规调参输出 <10KB）
- **超限后清连接路径**：抛出的 `IOException("shell output too large...")` 从 onChunk 内部抛出 → shellStreaming 的 withLock 内 → shell 的 runCatching 捕获 → `Result.failure` → TuningExecutor.interpretShellResult 判定 `e is IOException` → **close + connection=null + connectionState=false** ✅ 走的是既有失败链，注释明确说明了"不为超限开特例（残留未读数据破坏流解析，关连接是唯一干净处理）"——语义正确 ✅
- **与单包 1MB 检查的先后关系**：readPacket 的 1MB 检查（:126 单包层，读 header 时）先于 onChunk 回调；shell 层 8MB 是累计第二道闸——两层互补（1MB 挡单包异常 header，8MB 挡多包累计），无冲突 ✅
- 数值合理性：8MB ≈ dumpsys 全量可用、异常输出被拦——与 BUG 报告建议一致 ✅

### 专项③ BUG-036 防御语义 — **PASS**

commit 45dcdb5。

- **失败自清理**：`runCatching {...}.getOrElse { e -> close(); throw e }`（:74-77）——check 抛出的 IllegalStateException 与握手中的 IOException 统一走 close-then-rethrow ✅。**异常在 Result 链中的表现**：rethrow 后被外层调用方（TuningExecutor.ensureConnected 的 runCatching :66-72）捕获 → `Result.failure` → 既有失败分支（lastError 分类/close 兜底——此时 socket 已被 connect 自身关闭，二次 close 幂等无害）✅ **调用方行为完全不变**（对调用方而言只是"失败原因可能多一条 already-connected"）✅
- **二次 connect 防御**：`check(socket == null)`（:32-33）在 runCatching **之外**——IllegalStateException 直接抛出（不被包成 Result.failure）。核对调用方：ensureConnected 每次新建 AdbConnection 实例（TuningExecutor.kt:64），此检查当前不可能触发，纯防御性——语义"调用方错误快速失败优于静默泄漏"成立 ✅。位置在 runCatching 外意味着真触发时是硬错误而非可恢复失败——对编程错误这是正确处置（fail fast）✅
- **R5-O4**：tokenRounds≥3 重发 pubkey 的幂等语义 + rounds 总闸 8 注释（:54-56）✅
- **R5-O3**：ensureConnected KDoc 明确"纯连接不碰无线调试开关；自动开启属 ensureWifiAdbThenConnect（用户显式 connect 专用）；服务/接收器自愈只可用本函数"（TuningExecutor.kt:57-60）✅ 交叉引用了 BUG-027 会话语义。

### 专项④ BUG-037 删除完整性 — **PASS**（含产物级 CRC 确认）

commit ad77393。

- **grep 零命中复核**：`getRefreshRate|getResolution|getCpuGpuLevel|setPassthroughQuality` → 全工程（main+test）0 命中；`decodeHeader|verifyCrc|decodeFull` → 0 命中 ✅
- **resetAll/statusCommand 闭环保留**：`git show` 确认——resetAll 仍含 `highPrecisionPassthrough ""` 重置行、statusCommand 仍含其 getprop 读取（六段）✅。setPassthroughQuality 删除后该属性的重置/读取闭环**不经由独立函数**而是内联在两聚合函数里——lead 决策（无 UI 入口则不留孤立 setter）合理，语义无缺口 ✅
- **Packet.crc 删除的协议回归确认（重点，BUG-012 陷阱历史）**：
  1. 代码层：readPacket 从头就是四参构造 `Packet(cmd, arg0, arg1, payload)`（AdbConnection.kt:131，round-1 至今未变）——crc 字段（round-1 BUG-012 修复时加的、默认 0、生产从未传过）是纯死重量，删除不触达任何解析路径 ✅ CRC 校验本身**不依赖 Packet 携带 crc**：readPacket 在 :129 用局部变量 `crc`（header 解析出的）与 `AdbProtocol.crc32(payload)` 直接比对——校验逻辑完整保留 ✅
  2. **产物级确认（debug dex，未混淆可读名）**：dexdump `AdbProtocol$Packet` 类详情——Instance fields 恰好 4 个：`arg0/arg1/cmd/payload`，构造器签名 `(III[B)V` 四参 ✅ **crc 字段彻底移除得到二进制级证实**（release dex 全混淆无法按名搜索，debug dex 佐证编译单元一致性）
  3. encode 路径的 CRC（写出侧 `buf.putInt(crc32(payload))`）未动 ✅——收发两个方向的 CRC 校验/生成链与 round-1 验证状态一致，BUG-012 的修复成果未被本次清理破坏。
- AdbProtocol 剩余 API 面：encode/crc32/cmdName/Packet——全部有活跃调用方 ✅

### 专项⑤ BUG-038 传递依赖链 — **PASS**

commit 8907b57。

- **删除项**：`lifecycle-runtime-ktx` + `datastore-preferences` 两行删除，注释记录决策依据（含"datastore 零使用挂起 5 轮"清账）✅
- **传递依赖链编译期验证**：`viewModelScope`（TunerViewModel 等）由 lifecycle-viewmodel-ktx 提供、经 **lifecycle-viewmodel-compose**（保留依赖）传递——三件套编译通过即证符号解析完整 ✅；`LocalLifecycleOwner`（若 compose 内部使用）来自 compose-ui 自带——无运行时缺类
- **release 构建无新 R8 告警**：clean assembleRelease 全程无 warning 输出（R8 缺类会直接报错而非告警；MissingClass 会 fail build）✅ 构建日志干净
- **体积收益实证**：1776KB → 1720KB（-56KB），与 datastore 运行时（含 coroutines 通道部分）+ lifecycle-ktx 剥离量级吻合 ✅
- datastore 的 libdatastore_shared_counter.so（round-2 验证时见过的 native 库）随之消失——依赖面更干净 ✅

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
环境：JAVA_HOME/GRADLE_USER_HOME/TEMP,TMP 同前四轮
结果：BUILD SUCCESSFUL in 40s（87 tasks：42 executed, 42 from cache, 3 up-to-date）
单测：26/26（IntentFold 3 / LeaveSnapshot 3 / MergeA11y 4 / ValuesMatch 5 / CleanStaleApks 2 / UpdateSafety 4 / VersionCompare 5）
产物：release 1720KB（-56KB）
产物级：AdbProtocol$Packet 四字段（无 crc）——debug dex 证实
```

## 实机验证建议清单

1. **034**：完整更新流程（检查→下载→安装）——下载期间主界面滚动流畅（无整体重组卡顿）、进度条平滑更新；连接过程中状态栏文案仍逐阶段可见（首条立即）
2. **035**：profile/命令中执行大输出命令（如 `cat` 大文件）→ 失败提示"shell output too large"且连接被清理（刷新状态 → 提示重连）——属预期防御行为
3. **036**：正常连接/AUTH 失败/取消路径回归（行为应与 round-4 完全一致——防御不改变正常路径）
4. **037**：全功能回归走查（连接/调参/resetAll/状态读取六段）——清理不触碰运行路径，三件套+26 测试已是主要证据
5. **038**：完整回归（viewModelScope 相关：全部开关/连接/更新流程）——编译通过+构建干净已覆盖绝大部分风险

## 最终判定

**fix/round-5 的 5 个 bug 修复 + 3 个优化项（R5-O3/O4/O5）全部按 PLAN 正确实现，构建三件套全绿（26/26），产物级确认 Packet.crc 移除无协议回归、依赖移除有体积收益（-56KB）。建议合并到 master。**
