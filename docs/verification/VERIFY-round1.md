# QuestTuner Round 1 修复验证报告（VERIFY-round1）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-1`（master..fix/round-1 共 21 commits，基线 master @ 88674b2）
- 方案依据：docs/plans/PLAN-round1.md（Li）
- Bug 依据：docs/bugs/BUG-REPORT-round1.md（David，18 个缺陷）
- 验证方式：逐 commit diff 审查（`git diff master..fix/round-1`）+ 与 PLAN 逐条对照 + clean 全量构建

## 总体结论

**17/17 个已修 bug 全部 PASS**（BUG-017 按 PLAN §17 决定不修，本轮确认无相关改动，符合方案）。

- 构建：`clean :app:assembleDebug` → **BUILD SUCCESSFUL in 22s**（JDK 17.0.20.1 + Gradle 8.7，真实全量编译非缓存命中）
- 与 PLAN 偏差：无实质偏离；2 个轻微新观察点（见 RISK-1/RISK-2，均不阻断合入）
- 遗留建议：单测未补（PLAN 全局注意事项 3 为"建议"项，本轮未执行，见遗留项）

## 逐 Bug 验证结论

| 编号 | 级别 | 结论 | 对应 commit(s) |
|---|---|---|---|
| BUG-001 | P0 | **PASS** | 1d930ac |
| BUG-002 | P1 | **PASS** | 57c2672 |
| BUG-003 | P1 | **PASS** | 130da74 |
| BUG-004 | P1 | **PASS** | 40d575c + 5c66ab8 |
| BUG-005 | P1 | **PASS** | 76dd089 + 0a038e4 + 6e0e356 |
| BUG-006 | P1 | **PASS**（附 RISK-1 观察点） | ed3cbbb + 9ef5836 |
| BUG-007 | P1 | **PASS** | dbe732c + 9ef5836 |
| BUG-008 | P1 | **PASS** | f65ebc0 |
| BUG-009 | P1 | **PASS** | ba4b1ba |
| BUG-010 | P1 | **PASS** | ba4b1ba |
| BUG-011 | P2 | **PASS** | 770ebc5 |
| BUG-012 | P2 | **PASS**（代码走查，无单测） | 310e391 |
| BUG-013 | P2 | **PASS** | 1971332 |
| BUG-014 | P2 | **PASS** | 0ef4e89 |
| BUG-015 | P2 | **PASS**（方案 B） | aad4470 |
| BUG-016 | P2 | **PASS** | 1415896 + c83d2cc |
| BUG-017 | P2 | **N/A（按方案不修）** | — |
| BUG-018 | P2 | **PASS** | 483f016 |

---

## 详细验证

### BUG-001（P0）ADB 公钥编码不符合协议 — **PASS**

commit 1d930ac 对 AdbKey.kt / AdbKeyStore.kt 的修复与 PLAN §BUG-001 逐行一致：

1. **n0 取低 32 位**：`val n0 = n.mod(BigInteger.TWO.pow(32))` ✅（修复前错误地取 BE 数组前 4 字节即模数高 32 位）
2. **modulus LE 写入**：`val modulusLe = modulus.reversedArray()` 后 `buf.put(modulusLe)` ✅
3. **rr LE 写入**：`bigToBytes(rr, 256).reversedArray()` ✅
4. **parsePublicKeyFile 联动**：`require(bytes.size == 524)` 长度校验 + `BigInteger(1, m.reversedArray())` 反转为 BE 构造 ✅
5. 类注释同步更正为"模数最低 32 位，即 n mod 2^32" ✅

数学正确性核验：
- `negate().mod(2^32)` 结果 ∈ [0, 2^32)，`toInt()` 后经 LE `putInt` 写入，位模式无损 ✅
- RSA-2048 模数 = 两奇素数之积 → 奇数 → `n mod 2^32` 为奇数 → 与 2^32 互素 → `modInverse` 必然成功，无异常路径 ✅
- **与 BUG-018 联动无误杀**：`AdbKey.encodePublic` 的 n0inv 公式与 `AdbKeyStore.expectedN0Inv` 完全相同，新生成密钥必然通过旧格式检测，不会触发不必要的重建 ✅
- 回环一致性：encodePublic(LE 写) ↔ parsePublicKeyFile(LE 读 + reversedArray) 对称 ✅

遗留说明：PLAN 验证方法中的"对拍 PC ~/.android/adbkey"与实机授权验证需真机环境，本轮以代码审查 + 公式推导确认；建议合入后在实机做一次未授权首连回归（授权框弹出 + CNXN 完成）。

### BUG-002（P1）statusCommand 误写 setprop — **PASS**

commit 57c2672：`"setprop debug.oculus.gpuLevel"` → `"getprop debug.oculus.gpuLevel"`，并按 PLAN 补 `"getprop debug.oculus.highPrecisionPassthrough"`。修复后 statusCommand 输出 6 段 getprop，无写操作、无遗漏项。与 PLAN §BUG-002 完全一致 ✅

### BUG-003（P1）OPEN payload 未 NUL 终止 — **PASS**

commit 130da74：`"shell:$command:"` → `"shell:$command\u0000"`。与 PLAN §BUG-003 一字不差 ✅ 命令不再带尾随冒号，属性值不会被污染为 `"90:"`。

### BUG-004（P1）无读超时永久阻塞 — **PASS**

两层修复均落地：
- commit 40d575c（AdbConnection）：构造参数新增 `readTimeoutMs: Int = 30_000`，`sock.soTimeout = readTimeoutMs` 在 connect 后设置 ✅
- commit 5c66ab8（TuningExecutor）：`withTimeout(35_000L)` 略大于 soTimeout 形成双保险；失败路径走 else 分支 `conn.close()`（原有代码），超时/失败均关闭 socket ✅

阻塞读最长 30s 抛 SocketTimeoutException（IOException）→ connect 返回 failure → UI 恢复可点。授权等待不再永久卡死。与 PLAN §BUG-004 一致 ✅

### BUG-005（P1）并发 shell 无保护 — **PASS**

双层防线均实现，与 PLAN §BUG-005 一致：
- commit 76dd089（AdbConnection）：`ReentrantLock` + `kotlin.concurrent.withLock` 包裹整个 shellStreaming 会话；`AtomicInteger nextLocalId` 递增分配（不再恒 1）；`CMD_CLSE -> return@withLock` 非局部返回改标签返回 ✅
- commit 0a038e4 + 6e0e356（TuningExecutor）：`adbMutex`（kotlinx Mutex）串行化 ensureConnected 的 connect 段与 runCommand/runCommands 的命令执行段；6e0e356 修正了 withLock import 与 `return@withLock` 编译错误（自纠正确）✅

并发正确性核验：
- 无重入死锁：runCommands 在锁内直接调 `conn.shell`（不经过 runCommand），不存在锁重入路径 ✅
- applyProfile（runCommands 持锁→释放）→ refreshStatus（runCommand 再次获锁）为顺序获取，无死锁 ✅
- lastError 共享状态在 adbMutex 持有期间读写（interpretShellResult 均在锁内调用）✅

### BUG-006（P1）断线后不可恢复 — **PASS**（附 RISK-1 观察点）

- commit ed3cbbb（TuningExecutor）：`interpretShellResult` 中 `if (e is java.io.IOException) { conn.close(); if (connection === conn) connection = null }` — 按异常类型区分连接级/命令级失败，`connection === conn` 引用比较防止误杀并发新建的连接，比 PLAN 原稿更稳健 ✅
- commit 9ef5836（ViewModel）：refreshStatus / applyProfile / resetAll 失败分支均复位 `connected = false` + adbStatus 提示，连接按钮恢复可点 ✅

**RISK-1（新观察点，不阻断）**：exit≠0 的命令级失败（连接仍活，executor 未清理 connection）也会把 UI `connected` 复位为 false，提示"连接已断开"——文案与实际状态不符。用户重点一次"连接"即恢复（ensureConnected 见 connection 非空直接成功），无功能错误，较修复前"永久不可恢复"是显著改善。PLAN 提示过"EXIT 失败不要误杀连接"（executor 侧确实没杀），但 ViewModel 复位未区分失败类型。建议 round 2 让 ViewModel 按 `TuningExecutor.connection == null`（或新增 isConnectionAlive 查询）决定是否复位 UI。**判定不影响本 bug 的修复目标（可恢复重连）达成，PASS。**

### BUG-007（P1）协议成功≠命令成功 — **PASS**

commit dbe732c + 9ef5836 与 PLAN §BUG-007 一致：
- `conn.shell("$cmd; echo EXIT:$?")` 探针追加 ✅
- `interpretShellResult`：`lastIndexOf("EXIT:")` 取最后标记 → `toIntOrNull()` 解析失败按失败处理 → code≠0 时 lastError 带 `exit=$code` + 输出前 300 字符（含 stderr 文本，adbd shell v1 合并输出）→ 成功时 `substring(0, exitMark).trimEnd('\n')` 剥离探针行 ✅
- applyProfile 失败分支追加 `sb.append("错误: ${TuningExecutor.lastError}\n")` 呈现实际输出 ✅

边界核验：statusCommand 聚合串整体退出码 = 末条命令（getprop 必 0），探针剥离后 UI 不受影响 ✅；runCommands 的 `all{}` 短路保持 ✅。

### BUG-008（P1）损坏 JSON 静默清空 + 非原子写 — **PASS**

commit f65ebc0 与 PLAN §BUG-008 一致：
- reload：解析进局部 `parsed`，`isSuccess` 才 `all.clear(); all.putAll(parsed)`；失败保留内存现状 + `Log.w` 记录，不覆盖不静默 ✅
- persist：`store.name + ".tmp"` 临时文件 → `renameTo` 原子替换 → 失败回退 `writeText` → `tmp.delete()` 清理 ✅

数据丢失链（损坏→清空→覆盖写）被两端同时切断 ✅。

### BUG-009 + BUG-010（P1）VR 检测失效 + 卸载竞态闪退 — **PASS**

commit ba4b1ba 采纳了 PLAN 的"补充实现"（比最小修复更优）：
- hasVrCategory 改签名收 ResolveInfo：优先遍历 `ri.filter` categories（VR launcher 标准声明路径，`f != null` 判空）→ `getActivityInfo(GET_META_DATA)` meta-data 兜底 → `packageFallback` 包名前缀兜底 ✅
- `runCatching { getActivityInfo(...) }.getOrNull() ?: return packageFallback(...)` — 单项降级，NameNotFoundException 不再外泄，扫描不闪退 ✅
- meta key 常量（`com.oculus.intent.category.VR` / `com.meta.intent.category.VR`）同时用于 filter 匹配与 meta-data 读取，语义一致 ✅

与 PLAN §BUG-009 补充实现 + §BUG-010 逐行等价 ✅。

### BUG-011（P2）resetAll 重复/漏项 — **PASS**

commit 770ebc5：重复的 `textureHeight` 行替换为 `highPrecisionPassthrough` 重置项，7 条命令无重复无遗漏。与 PLAN §BUG-011 一致 ✅

### BUG-012（P2）verifyCrc 恒 false — **PASS**（代码走查，无单测）

commit 310e391 与 PLAN §BUG-012 一致：
- `Packet` 增加 `val crc: Int = 0` 默认参数（现有构造点不 break）✅
- decodeHeader 将解析出的 crc 填入 Packet ✅
- `verifyCrc(p, payload = p.payload)` 按 Packet 内真实 crc 比对；恒 0 的 `crcOf` 已删除 ✅

**注**：PLAN 验证方法要求单测触达（encode→decodeHeader→verifyCrc true / 篡改 false），但 `app/src/test/` 未创建（见遗留项）。本轮以代码审查确认逻辑正确：decodeHeader 填入的 crc 与 encode 写入的 `crc32(payload)` 同源，verifyCrc 比对必然吻合；decodeFull 的强校验路径未受影响（`Packet(..., crc)` 由 decodeFull 构造时… 核对：decodeFull 返回 `Packet(cmd, a0, a1, payload)` 未传 crc 使用默认 0——不影响其自身已通过的 require 校验，仅 Packet.crc 字段为 0；无调用方依赖 decodeFull 返回值的 crc 字段，无实际影响）。**PASS。**

### BUG-013（P2）跨包 UTF-8 截断 — **PASS**

commit 1971332：`ByteArrayOutputStream` 累积全部 chunk 后 `bytes.toString("UTF-8")` 一次性解码。与 PLAN §BUG-013 的最简实现一致 ✅ 多字节字符跨 WRTE 包边界不再产生 U+FFFD。

### BUG-014（P2）cpu/gpu 成对门槛与 UI 不一致 — **PASS**

commit 0ef4e89：cpu/gpu 各自独立生成 `setprop` 命令；texture 成对门槛按 PLAN 明确不动（UI describeProfile 也是成对显示，保持一致）✅ `setCpuGpuLevel` 函数保留无破坏 ✅

### BUG-015（P2）保存按钮连接 gate — **PASS**（方案 B）

commit aad4470 采纳 PLAN 推荐的方案 B：
- 保存按钮删除 `enabled = state.connected`（纯本地操作离线可用）✅
- ProfileRow 增加 `connected: Boolean` 参数（唯一调用点 TunerApp.kt:43 同步更新），"应用"按钮 `enabled = connected` 置灰 ✅

### BUG-016（P2）主线程磁盘 IO + 读方法裸奔 — **PASS**

- commit 1415896（ViewModel）：init / saveProfile / deleteProfile 的 repo 读写全部包 `withContext(Dispatchers.IO)`，state 更新回 Main ✅
- commit c83d2cc（ProfileRepository）：`profiles()` / `find()` 补 `@Synchronized`，与 save/delete/reload 共用对象监视锁，读写竞态消除 ✅

与 PLAN §BUG-016 推荐的"调用方切 IO"方案一致 ✅

### BUG-017（P2）apps 死数据 — **N/A（按方案不修）**

fix/round-1 无相关改动（未新增选择器、未砍 loadApps），符合 PLAN §17 的决定（功能缺失归 round 2）。009/010 修复后扫描本身正确且不崩，满足 PLAN 的最低验收口径 ✅

### BUG-018（P2）密钥损坏无自愈 — **PASS**

commit 483f016 与 PLAN §BUG-018 一致：
- `loaded = privFile.exists() && pubFile.exists() && runCatching { ... }.getOrDefault(false)` — 非法 base64 / 解析异常 / 长度校验失败均落入 false ✅
- `check(wireN0Inv(pubFile.readText()) == expectedN0Inv(publicKey!!.modulus)) { "legacy pubkey format" }` — 旧格式（BE 编码 + 错误 n0inv）检测：旧文件按 LE 反解出"错 key"，其 wire n0inv 与新公式期望值必然不等 → 触发自愈 ✅
- `if (!loaded) { privFile.delete(); pubFile.delete(); ...重建... }` — 删旧重建路径完整 ✅
- 联动正确性见 BUG-001 段：新旧公式一致性保证新密钥不被误杀；旧密钥按方案直接重建（从未成功授权过，无保留价值）✅

细节核验：`wireN0Inv` 的 `bb.int.toLong() and 0xffffffffL` 无符号化正确；`expectedN0Inv` 返回值 ∈ [0, 2^32) 的 Long，比较无符号截断问题 ✅

---

## 新引入问题（观察点，均不阻断合入）

**RISK-1（BUG-006 修复的副作用，轻微）**：命令级失败（exit≠0，连接仍活）时 ViewModel 也复位 `connected=false` 并提示"连接已断开"——文案与实际不符，需重点一次"连接"恢复（ensureConnected 对活连接直接返回 true，能恢复）。功能无错误。建议 round 2：ViewModel 依据 executor 连接是否真的被清理（如暴露 `val hasConnection: Boolean get() = connection != null`）决定复位。

**RISK-2（BUG-004/005 交互的边界，轻微）**：`runCommand`/`runCommands` 的 shell 读阻塞期间若 35s withTimeout 触发（TimeoutCancellationException 非 IOException），interpretShellResult 不清理连接 → 死连接短暂残留。但下次 runCommand 会因 socket 已关收到 IOException 走清理路径，自愈；且 soTimeout=30s < 35s 使该场景实际很难出现（read 先抛 SocketTimeoutException）。可接受。

**RISK-3（观察）**：`ensureConnected` 的 `withTimeout` 内 `conn.connect` 是阻塞调用，协程取消不能即时打断阻塞 read（无 invokeOnCancellation 关 socket）；soTimeout 兜底最长 30s 后线程释放。与 PLAN "如需更严格的取消语义可 invokeOnCancellation" 的可选增强一致，非必须项。可接受。

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug
环境：JAVA_HOME=F:\workspace\toolchain\jdk-extract\jdk-17.0.20.1+1
      GRADLE_USER_HOME=F:\workspace\toolchain\gradle-home
      TEMP/TMP=F:\workspace\tmp（Temp ACL 重定向）
结果：BUILD SUCCESSFUL in 22s（37 tasks：17 executed, 19 from cache）
      compileDebugKotlin 全量真实编译通过，无错误
```

（注：stripDebugDebugSymbols 对 libdatastore_shared_counter.so 的 "Unable to strip" 提示为第三方 .so 的常规打包警告，非本次修复引入。）

## 遗留项（建议 round 2 处理）

1. **单测缺失**：PLAN 全局注意事项 3"建议 Wang 补单测（AdbKey/AdbProtocol/ProfileRepository 纯 JVM 可测）"未执行，`app/src/test/` 不存在。BUG-001/008/012 的验证方法中单测环节本轮以代码审查替代。建议 round 2 补：encodePublic↔parsePublicKeyFile 回环 + 固定向量断言、verifyCrc 正反例、ProfileRepository 损坏 JSON/原子写用例。
2. RISK-1 的 UI 复位语义精细化。
3. BUG-017 的应用选择器（功能需求，PLAN §17 已给建议）。
4. 实机回归清单（PLAN 全局注意事项 4）需真机环境，本轮仅覆盖构建 + 静态审查；合入后建议按清单执行一次实机回归（重点：未授权首连 001/018、断连重连 006、并发 005）。

## 最终判定

**fix/round-1 分支 17 个已修 bug 全部验证 PASS，无阻断性新问题，构建通过。建议合入 master。**
