# QuestTuner Round 1 Bug 报告

- 审查人：David（QA）
- 日期：2026-10-24
- 基线：master @ 88674b2（"Baseline: QuestTuner v0.1.0 initial build (working APK)"）
- 范围：`app/src/main/java/com/example/questtuner/`（adb/ 4 个、tuner/ 4 个、ui/ 2 个、MainActivity.kt）、AndroidManifest.xml、app/build.gradle.kts、根 build.gradle.kts、settings.gradle.kts、res/values/*
- 说明：只报告真实缺陷（协议错误 / 并发 / 资源泄漏 / 逻辑 / 边界 / UI 状态 / 存储），不含风格问题。

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-001 | P0 | AdbKey.kt:40-52 | ADB 公钥编码不符合协议：n0inv 取模数高 32 位（应取低 32 位），modulus/rr 按 BE 写入（应为 LE）→ AUTH 必然失败 |
| BUG-002 | P1 | TuningCommands.kt:67 | statusCommand() 把 gpuLevel 写成 `setprop`（应为 `getprop`），读状态变成写操作 |
| BUG-003 | P1 | AdbConnection.kt:74 | shell OPEN payload 以 `:` 结尾而非 NUL 终止，命令可能带尾随冒号执行、属性值被污染 |
| BUG-004 | P1 | AdbConnection.kt:27-39 | socket 无读超时：等待头显授权确认时 connect() 永久阻塞，UI 卡"连接中…"无法取消 |
| BUG-005 | P1 | AdbConnection.kt:72-120, TuningExecutor.kt:39-50 | 连接无并发保护：并发 shell() 交错读写同一 socket（localId 恒为 1）导致协议流损坏 |
| BUG-006 | P1 | TuningExecutor.kt:20, TunerViewModel.kt:45-49 | 连接断开后无法恢复：connected 永不复位、连接按钮永久禁用、ensureConnected 对死连接返回 true |
| BUG-007 | P1 | AdbConnection.kt:66-70, TuningExecutor.kt:41-45 | 协议成功 ≠ 命令成功：不检查退出码/错误输出，setprop 被拒仍显示"完成" |
| BUG-008 | P1 | ProfileRepository.kt:34-54, 72-87 | JSON 损坏时 reload 静默清空全部 profiles + persist 非原子写 → 数据永久丢失 |
| BUG-009 | P1 | AppScanner.kt:57-65 | getActivityInfo flags=0 未带 GET_META_DATA → metaData 恒为 null，VR 检测 meta 路径完全失效 |
| BUG-010 | P1 | AppScanner.kt:57 | getActivityInfo 未捕获 NameNotFoundException，卸载竞态导致整个扫描崩溃（App 闪退） |
| BUG-011 | P2 | TuningCommands.kt:52-59 | resetAll() 中 textureHeight 出现两次、漏掉 highPrecisionPassthrough，无法恢复默认 |
| BUG-012 | P2 | AdbProtocol.kt:55-57 | verifyCrc() 恒返回 false（crcOf 硬编码返回 0），decodeHeader 丢弃 CRC，均为陷阱式死代码 |
| BUG-013 | P2 | AdbConnection.kt:66-70 | shell 输出按包独立 UTF-8 解码，多字节字符跨 WRTE 包截断产生乱码 |
| BUG-014 | P2 | ProfileRepository.kt:22, TunerApp.kt:134-135 | toCommands 要求 cpu+gpu 成对才生成命令，UI 却独立显示 → 只设一项时"看似已设置实则不生效" |
| BUG-015 | P2 | TunerApp.kt:187 | "保存 Profile" 是纯本地操作却被 `state.connected` 禁用；反向：应用按钮不检查连接状态 |
| BUG-016 | P2 | ProfileRepository.kt:86, TunerViewModel.kt:92-95 | persist()/profiles() 在主线程做磁盘 IO（StrictMode 违规，文件增大后 ANR 风险） |
| BUG-017 | P2 | TunerViewModel.kt:68-75 | AppScanner 扫描结果 state.apps 加载后 UI 从未使用（死数据），新建 Profile 只能手输包名 |
| BUG-018 | P2 | AdbKeyStore.kt:26-28 | 密钥文件损坏时无恢复路径：两文件存在即不重建，decode 抛异常后永久"密钥生成失败" |

---

## P0

### BUG-001 ADB 公钥编码不符合协议（n0inv 取错位数 + modulus/rr 字节序错误）

- 级别：P0
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbKey.kt:40-52`（影响 `AdbKeyStore.kt:57-72` 解析）
- 描述：ADB 公钥格式（android_pubkey，524 字节）中：
  1. `n0inv = -1/n0 mod 2^32` 的 `n0` 必须是模数**最低 32 位**（`n mod 2^32`，Montgomery 常数由最低 word 决定），而代码取的是 BE 字节序数组的**前 4 字节**（即模数最高 32 位）；
  2. `modulus` 与 `rr`（R² mod N）在 wire 上是**小端（little-endian）**字节序，代码直接 `buf.put(modulus)` 放入大端 BigInteger 字节。
  adbd 侧 `android_pubkey_decode` 校验 n0inv 并以 LE 读入模数，两者任一错误都会导致公钥被拒或签名验证失败 → 首次连接（AUTH RSAPUBLICKEY）必然失败，应用核心功能全部不可用。baseline 提交信息声称 "working APK"，但该路径在未授权设备上不可能工作。
- 证据：
  ```kotlin
  // AdbKey.kt:22-23（注释即误解：“n0 = 模数最高 32 位” —— 协议应为最低 32 位）
  //   uint32 n0inv    = -1/n0 mod 2^32 (LE, n0 = 模数最高 32 位)
  // AdbKey.kt:40-42
  val n0 = BigInteger(1, modulus.copyOfRange(0, 4))  // 模数高 32 位（BE 读出）→ 错
  val n0inv = n0.modInverse(BigInteger.TWO.pow(32)).negate().mod(BigInteger.TWO.pow(32))
  // AdbKey.kt:50-51  modulus/rr 为 BigInteger.toByteArray() 的大端序，直接写入
  buf.put(modulus)   // 应写入 LE（reversedArray()）
  buf.put(rrBytes)   // 同上
  ```
  参考实现（python-adb / AdbLib 等）均为 `n0inv = -inverse(n % 2**32) mod 2**32`、`int.to_bytes(n, 256, 'little')`。
- 验证建议：
  1. 单测：用固定测试向量（已知 n）断言 `encodePublic` 输出的前 8 字节 == `<64 LE><n0inv LE>`，且 `modulus` 段等于 `n` 的 256 字节 LE 表示；
  2. 对拍：PC 上取 `~/.android/adbkey` 的公私钥，用本 app 的 `encodePublic` 重新编码同 n，与 `~/.android/adbkey.pub` 的 base64 段逐字节对比；
  3. 实机：修复后在未授权 Quest 3 上连接，头显应弹出 USB 调试授权框且 CNXN 完成。
  4. 注意修复时同步修改 `AdbKeyStore.parsePublicKeyFile`（按 LE 反解），并处理已落盘的旧格式 `adbkey.pub`（建议直接重建密钥）。

## P1

### BUG-002 statusCommand() 把 gpuLevel 写成了 setprop

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/TuningCommands.kt:67`
- 描述：聚合状态读取命令中，最后一项误写为 `setprop debug.oculus.gpuLevel`（缺参数）。后果：每次"刷新状态"都会尝试执行一条 setprop（shell 返回 usage 错误文本），状态面板显示错误信息而非 gpuLevel 当前值；用户看到杂乱输出会误以为调参失败。同函数也未读取 `highPrecisionPassthrough`。
- 证据：
  ```kotlin
  // TuningCommands.kt:62-68
  fun statusCommand(): String = listOf(
      "getprop debug.oculus.refreshRate",
      "getprop debug.oculus.textureWidth",
      "getprop debug.oculus.textureHeight",
      "getprop debug.oculus.cpuLevel",
      "setprop debug.oculus.gpuLevel",   // ← 应为 getprop
  ).joinToString("; echo ---; ")
  ```
- 验证建议：连接后点"刷新状态"，修复前输出包含 `setprop` usage 报错（且无 gpuLevel 值），修复后输出 5 项 getprop 值。

### BUG-003 shell OPEN payload 以冒号结尾，未按协议 NUL 终止

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt:74`
- 描述：ADB 协议要求 OPEN 的 service 名为 NUL 终止字符串（官方客户端与主流第三方实现均发送 `"shell:" + cmd + "\0"`）。本实现发送 `"shell:$command:"`。若 adbd 按 payload 长度构造 service 字符串（多数版本的行为），命令会带尾随冒号执行：`setprop debug.oculus.refreshRate 90:` → 属性值被写成 `"90:"`，调参值全部被污染且不生效。即使个别 adbd 版本容忍，也不符合协议。
- 证据：
  ```kotlin
  // AdbConnection.kt:74
  sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command:".toByteArray(Charsets.UTF_8))
  ```
- 验证建议：
  1. 修复后实机执行 `statusCommand`，观察 getprop 输出的值是否曾带尾随 `:`（污染痕迹）；
  2. 对拍：PC `adb` 连接同设备抓包（或查 adbd 源码 `handle_open`），确认官方 payload 为 NUL 结尾；
  3. 修复：`"shell:$command\u0000"`。

### BUG-004 socket 无读超时，授权等待期间 connect 永久阻塞且无法取消

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt:18, 27-39`；`ui/TunerViewModel.kt:40`
- 描述：`connectTimeoutMs` 只用于 TCP 连接（line 29），之后 `readPacket()` 阻塞读无 SO_TIMEOUT。首次连接时 adbd 收到 RSAPUBLICKEY 后会等待用户在头显上点"允许"，期间不回包——`connect()` 将无限期阻塞在 `readPacket()`，UI 停留在"连接中…"、按钮禁用、无任何取消途径，用户只能杀进程。用户拒绝授权时 adbd 关闭连接才能解除。
- 证据：
  ```kotlin
  // AdbConnection.kt:27-31 —— 只设置了 TCP connect 超时，未设 soTimeout
  val sock = Socket()
  sock.tcpNoDelay = true
  sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
  // AdbConnection.kt:39 —— 若 adbd 等待用户授权确认，此处永久阻塞
  val p = readPacket()
  // TunerViewModel.kt:39-40 —— connecting=true 后无超时/取消路径
  if (_state.value.connecting) return
  _state.value = _state.value.copy(connecting = true, adbStatus = "连接中…")
  ```
- 验证建议：首次（未授权）连接实机，不点头显上的确认框，观察 UI 是否永久停留"连接中…"；修复应设置 `sock.soTimeout`（如 30s）并将 connect 放入可取消的协程（`invokeOnCancellation` 关闭 socket）。

### BUG-005 ADB 连接无并发保护，并发 shell() 导致协议流损坏

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt:72-90, 104-120`；`tuner/TuningExecutor.kt:39-50`
- 描述：`TuningExecutor` 用单条连接复用所有命令，但 `AdbConnection` 的 `shellStreaming/readPacket/sendPacket` 无任何锁或互斥；`shellStreaming` 固定 `localId = 1`。两个协程并发调用（例如 applyProfile 执行中用户点"刷新状态"——按钮仅以 `connected` 为条件，或 connect 成功路径中 `refreshStatus()` 与后续操作）会在同一 socket 上交错读写：一个流读到另一个流的 WRTE 包、两个 OPEN 使用相同 localId，流 ID 错配后 adbd 可能直接 CLSE 或静默丢弃，输出串包。另外 `TuningExecutor.lastError` 是共享可变状态，并发下错误信息会张冠李戴。
- 证据：
  ```kotlin
  // AdbConnection.kt:73-74 —— localId 硬编码，无互斥
  val localId = 1
  sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command:"...)
  // TuningExecutor.kt:39-41 —— 多协程可同时进入 conn.shell
  suspend fun runCommand(cmd: String): String? = withContext(Dispatchers.IO) {
      val conn = connection ?: return@withContext null
      val r = conn.shell(cmd)   // 无锁
  ```
- 验证建议：写一个 Instrumented/单元测试（伪 socket 或本地 adbd）并发调用 `runCommand` 两个 shell，断言输出不串流；代码层面应加 Mutex（每连接串行化 shell）并递增分配 localId。

### BUG-006 连接断开后进入不可恢复状态

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt:20, 52-55`；`ui/TunerViewModel.kt:45-49`；`ui/TunerApp.kt:61`
- 描述：一旦 TCP 断开（adbd 重启、设备休眠杀连接），`connection` 仍非 null、`UiState.connected` 仍为 true：
  1. `ensureConnected` 第 20 行 `if (connection != null) return true` 对死连接直接判定成功；
  2. UI 连接按钮 `enabled = !state.connected && !state.connecting` 永久禁用，用户没有任何途径重连；
  3. "刷新状态"只会一直显示"读取失败"。只能杀 App 恢复。附带问题：若在并发下重建连接，旧 socket 直接被覆盖不 close（泄漏）。
- 证据：
  ```kotlin
  // TuningExecutor.kt:19-20
  suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
      if (connection != null) return@withContext true   // 死连接也返回 true
  // TunerApp.kt:61
  Button(onClick = viewModel::connect, enabled = !state.connected && !state.connecting)
  // runCommand 失败只置 lastError，从不清理 connection / connected
  ```
- 验证建议：连接成功后用 `adb forward --remove`/重启 adbd（或断网），再点"刷新状态"→ 持续"读取失败"，且"连接"按钮不可点；修复应在 runCommand 失败时关闭并置空 connection、复位 connected 状态，允许重连。

### BUG-007 把协议成功当作命令成功，setprop 失败仍显示"完成"

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt:66-70`；`tuner/TuningExecutor.kt:41-45`；`ui/TunerViewModel.kt:80-87`
- 描述：`shell()` 只在 ADB 协议异常时失败，stdout/stderr 文本与命令退出码完全被丢弃。`setprop debug.oculus.*` 若被 shell 用户权限拒绝（v69+ 固件收紧）或属性不存在，错误只出现在被丢弃的输出里；`runCommand` 返回非 null → `runCommands` 返回 true → UI 显示"应用 xxx 完成"。用户以为调参生效，实际全部失败，与 README 声称的"应用后用 dumpsys 验证"矛盾。
- 证据：
  ```kotlin
  // AdbConnection.kt:66-70 —— 输出整体拼接返回，无退出码
  fun shell(command: String): Result<String> = runCatching {
      val sb = StringBuilder()
      shellStreaming(command) { chunk -> sb.append(String(chunk, Charsets.UTF_8)) }
      sb.toString()
  }
  // TunerExecutor.kt:41-42 —— isSuccess 只反映协议层
  val r = conn.shell(cmd)
  if (r.isSuccess) r.getOrNull() else ...
  ```
- 验证建议：实机连接后应用一个固件不存在的属性（如 setprop debug.oculus.notexist 1）或观察输出中是否含 "Could not set property"；修复建议 shell 后追加 `; echo EXIT:$?` 并检查，或把 stderr 呈现给用户。

### BUG-008 Profile 存储：损坏 JSON 静默清空 + 非原子写 → 数据永久丢失

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt:34-54, 72-87`
- 描述：两处叠加造成数据丢失链：
  1. `reload()` 先 `all.clear()`，再 `runCatching { 解析 }`——解析抛异常被吞掉，内存变成空表且无任何日志/提示；
  2. 之后任何一次 `save()/delete()` 都会以空表为基础 `persist()` 覆盖写文件 → 原文件被彻底覆盖，profiles 全部丢失。
  且 `persist()` 用 `writeText` 直接覆盖目标文件（非临时文件+rename），写入中途断电/被杀会留下半截 JSON，下次启动即触发上述清空链。
- 证据：
  ```kotlin
  // ProfileRepository.kt:34-37
  fun reload() {
      all.clear()
      if (store.exists()) {
          runCatching {           // 异常被吞，all 已清空
  // ProfileRepository.kt:86
  store.writeText(arr.toString(2))   // 非原子覆盖写
  ```
- 验证建议：单测——写入合法 JSON 后手工截断文件一半 → new ProfileRepository → 断言 profiles 为空；再 save 一条 → 断言磁盘上旧数据被覆盖丢失。修复：先读到局部变量再替换 all；解析失败保留旧文件并上报；persist 用临时文件 + `renameTo`。

### BUG-009 VR 检测的 meta-data 路径恒失效（getActivityInfo 未带 GET_META_DATA）

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/AppScanner.kt:57-65`
- 描述：`pm.getActivityInfo(component, 0)` 的 flags=0 不含 `PackageManager.GET_META_DATA`，因此 `full.applicationInfo.metaData` 恒为 null，`metaVr` 恒 false——精心设计的两个 meta key 检查（`com.oculus.intent.category.VR` / `com.meta.intent.category.VR`，注意与注释中常量名也不一致）从未生效。结果除 `com.oculus.*`/`com.meta.*` 包名兜底外，所有第三方 VR 应用（Beat Saber 等第三方）都被归入 2D 列表，"VR 优先排序"功能失效。
- 证据：
  ```kotlin
  // AppScanner.kt:57 —— flags=0，无 GET_META_DATA
  val full = pm.getActivityInfo(android.content.ComponentName(info.packageName, info.name), 0)
  // AppScanner.kt:61-64 —— metaData 为 null，恒 false
  val appMeta = full.applicationInfo.metaData
  val metaVr = appMeta?.getBoolean(VR_CATEGORY_META, false) ?: ...
  ```
- 验证建议：在装有第三方 VR 应用（如 Beat Saber，包名 com.beatgames.beatsaber）的设备上运行扫描，`isVr` 全为 false（仅包名兜底命中系统应用）；修复传入 `PackageManager.GET_META_DATA` 后 Beat Saber 应进入 VR 组并排在前面。

### BUG-010 AppScanner 未捕获 NameNotFoundException，卸载竞态导致闪退

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/AppScanner.kt:57`
- 描述：`getActivityInfo(ComponentName(...), 0)` 在包查询瞬间被卸载/隐藏（`PackageManager.NameNotFoundException`）时抛受检风格异常，未捕获、未 try/catch。`query()` 在 `viewModelScope.launch` 中被调用，异常未捕获 → 进程崩溃（整个应用列表加载只要有一个包消失即闪退）。头显上边卸载应用边刷新的竞态完全可能触发。
- 证据：
  ```kotlin
  // AppScanner.kt:34-45 —— forEach 内无 per-item 保护
  resolveInfos.forEach { ri ->
      ...
      val isVr = hasVrCategory(pm, ri.activityInfo)   // ← 内部 getActivityInfo 可抛
  // TunerViewModel.kt:69-70 —— 协程内未捕获异常 → crash
  viewModelScope.launch(Dispatchers.IO) { val apps = AppScanner.query(getApplication()) ... }
  ```
- 验证建议：测试中 stub PackageManager 使某一项抛 NameNotFoundException，断言 query 返回剩余列表而非抛异常；修复：单项 runCatching 降级为 `isVr=false`。

## P2

### BUG-011 resetAll() 重复 textureHeight、漏掉 highPrecisionPassthrough

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/tuner/TuningCommands.kt:52-59`
- 描述：`resetAll` 列表中 `setprop debug.oculus.textureHeight ""` 出现两次（复制粘贴错误），而 `setPassthroughQuality` 会写入的 `debug.oculus.highPrecisionPassthrough` 没有对应重置项——一旦开过高精度透视，"恢复默认"无法复原它。另外 `setPassthroughQuality` 全工程无调用方（UI 无入口），属于未接通的功能（与 BUG-017 同类）。
- 证据：
  ```kotlin
  // TuningCommands.kt:54-57
  "setprop debug.oculus.textureWidth \"\"",
  "setprop debug.oculus.textureHeight \"\"",
  "setprop debug.oculus.textureHeight \"\"",   // ← 重复；此处应为 highPrecisionPassthrough
  ```
- 验证建议：设置 highPrecisionPassthrough=1 后点"恢复默认"，getprop 应读出空；修复后补 `setprop debug.oculus.highPrecisionPassthrough ""` 并删除重复行。

### BUG-012 AdbProtocol.verifyCrc 恒返回 false、decodeHeader 丢弃 CRC

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbProtocol.kt:41-57`
- 描述：`crcOf(p)` 硬编码 `return 0`（注释承认 "not used"），导致公共函数 `verifyCrc()` 实际等价于 `crc32(payload) == 0`，几乎恒 false；`decodeHeader` 解析出 crc 字段后直接丢弃（构造的 Packet 不带 crc）。当前 `AdbConnection.readPacket` 自行解析所以未触发，但这两个公共 API 是陷阱：任何人一旦调用 verifyCrc 做校验将得到错误结果。
- 证据：
  ```kotlin
  // AdbProtocol.kt:55-57
  fun verifyCrc(p: Packet, payload: ByteArray): Boolean = crc32(payload) == crcOf(p)
  private fun crcOf(p: Packet): Int = 0 // not used; see decodeFull
  ```
- 验证建议：单测：`verifyCrc(decodeHeader(h), payload)` 对合法包应返回 true，当前返回 false；修复：Packet 携带 crc 或删除这两个误导性函数。

### BUG-013 shell 输出按包独立 UTF-8 解码，跨包多字节字符乱码

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt:66-70`
- 描述：每个 WRTE payload（≤4096 字节）单独 `String(chunk, UTF_8)` 解码后拼接。一个 3 字节 UTF-8 字符（中文）若恰好被拆在两个 WRTE 包边界，两半各自解码为 U+FFFD 替换字符。命令输出含中文（如应用名、本地化错误）时会随机出现 �。正确做法是累积字节流、按 UTF-8 边界解码。
- 证据：
  ```kotlin
  shellStreaming(command) { chunk -> sb.append(String(chunk, Charsets.UTF_8)) }  // 每包独立解码
  ```
- 验证建议：伪流测试：把 "中文" 的 UTF-6 字节按 3+3 拆成两个 chunk 喂入，断言结果仍是 "中文"；当前会得到 "\uFFFD\uFFFD"。

### BUG-014 cpu/gpu 命令生成与 UI 显示规则不一致

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt:22`；`ui/TunerApp.kt:134-135`
- 描述：`toCommands()` 只有 cpuLevel 与 gpuLevel **同时**非空才生成 setprop；而 `describeProfile` 对两者**独立**显示（"CPU2"）。手工构造/JSON 只含 cpuLevel 的 profile（reload 支持 null 单独存在）在列表里显示"CPU2 已设置"，点应用却不会执行任何 cpu/gpu 命令——用户被 UI 误导。
- 证据：
  ```kotlin
  // ProfileRepository.kt:22 —— 成对门槛
  cpuLevel?.let { c -> gpuLevel?.let { g -> addAll(TuningCommands.setCpuGpuLevel(c, g)) } }
  // TunerApp.kt:134-135 —— 独立显示
  p.cpuLevel?.let { append("CPU$it ") }
  p.gpuLevel?.let { append("GPU$it") }
  ```
- 验证建议：保存一个仅 cpuLevel=4 的 profile，UI 显示 "CPU4"，应用后 getprop debug.oculus.cpuLevel 不变；修复：单独一项也生成命令（或 UI 明示需成对）。

### BUG-015 保存 Profile 按钮被连接状态错误禁用（应用按钮反向不受限）

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/ui/TunerApp.kt:187`（对照 `:102, :122`）
- 描述：`保存 Profile` 是纯本地持久化（repo.save），不经过 ADB，却 `enabled = state.connected`——未连接时无法离线编辑保存配置。相反，真正需要连接的 ProfileRow"应用"按钮（TunerApp.kt:122）与 QuickTune 一致受控，但 applyProfile 本身不检查连接，失败提示含糊。逻辑条件放错了位置。
- 证据：
  ```kotlin
  // TunerApp.kt:187 —— 本地操作被 ADB 状态 gate
  enabled = state.connected,
  ) { Text("保存 Profile") }
  ```
- 验证建议：未连接状态下尝试保存 profile，当前不可点；修复后应可保存，重连后应用。

### BUG-016 主线程磁盘 IO（StrictMode 违规）

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt:86`（writeText/readText）；`ui/TunerViewModel.kt:30-35, 91-103`
- 描述：ViewModel 的 init/saveProfile/deleteProfile 在 Main dispatcher 直接同步调用 `repo.save/delete` → `persist()` 在**主线程**做文件写（readText 同理）。当前文件小影响有限，但属于 StrictMode diskRead/Write 违规，profiles 增多或存储繁忙时造成卡顿/ANR。另外 `profiles()`/`find()`（ProfileRepository.kt:56, 70）读非线程安全的 LinkedHashMap 且未加 @Synchronized，当前恰好全在主线程访问才未出事，埋着竞态。
- 证据：
  ```kotlin
  // TunerViewModel.kt:92-94 —— viewModelScope.launch 默认 Main，save 直接落盘
  viewModelScope.launch { repo.save(p); ... }
  // ProfileRepository.kt:86
  store.writeText(arr.toString(2))
  ```
- 验证建议：开启 StrictMode（penaltyLog）运行保存/删除操作，logcat 出现 DiskWriteViolation；修复：repo 方法包 withContext(Dispatchers.IO)，profiles/find 补 @Synchronized。

### BUG-017 应用扫描结果成为死数据，"按应用 Profile"无选择入口

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/ui/TunerViewModel.kt:18-26, 68-75`；`ui/TunerApp.kt:140-155`
- 描述：`UiState.apps` 加载了完整安装应用列表（连接成功即自动扫描，含 icon Drawable 常驻内存），但整个 UI 没有任何地方消费它——`TunerApp` 只渲染 profiles，`NewProfileCard` 要求用户**手输包名**。既浪费扫描开销与内存（每个 app 的 Drawable），也让"按应用 Profile"核心体验（从列表选应用）缺失。`TuningCommands.setPassthroughQuality/getRefreshRate/getResolution/getCpuGpuLevel` 同样无调用方。
- 证据：
  ```kotlin
  // TunerViewModel.kt:70-72 —— 扫描结果只进 state
  val apps = AppScanner.query(getApplication())
  ... copy(apps = apps)
  // TunerApp.kt 全文无 state.apps 引用；NewProfileCard.kt:149-154 为手输 OutlinedTextField
  ```
- 验证建议：代码走查确认 `state.apps` 零引用；建议在 NewProfileCard 提供应用选择器（复用 isVr 排序）或暂缓扫描。

### BUG-018 密钥文件损坏后无恢复路径，永久无法连接

- 级别：P2
- 位置：`app/src/main/java/com/example/questtuner/adb/AdbKeyStore.kt:26-28`
- 描述：`loadOrGenerate` 只要 adbkey/adbkey.pub 两个文件都存在就走加载分支；若 privFile 内容非法 base64 或 pubFile 格式损坏（写一半/存储异常），`Base64.decode`/`parsePublicKeyFile` 抛异常向上传播——`ensureConnected` 捕获后报"密钥生成失败"，但**永远不会重新生成**（文件存在即不重建），用户每次连接都失败，只能清除应用数据。缺一条"加载失败 → 重新生成"的自愈路径。
- 证据：
  ```kotlin
  // AdbKeyStore.kt:26-28
  if (privFile.exists() && pubFile.exists()) {
      privateKeyEncoded = Base64.getDecoder().decode(privFile.readText().trim())  // 可抛
      publicKey = parsePublicKeyFile(pubFile.readText())                          // 可抛
  } else { /* 只有都不存在才重建 */ }
  ```
- 验证建议：单测：写入非法内容到两文件 → loadOrGenerate 抛异常且再次调用仍失败；修复：捕获加载异常后删除旧文件并走生成分支（注意 BUG-001 修复导致的旧格式 pub 也应自愈）。

---

## 附：已核对无问题的点（避免复查重复劳动）

- `AdbProtocol.crc32` 与标准 CRC32 实现一致（0xEDB88320 反射、初值/末值 0xFFFFFFFF）；`encode` 的 magic = `cmd ^ 0xFFFFFFFF` 正确。
- `AdbConnection` OKAY 回包方向 `sendPacket(OKAY, localId, p.arg0)`（arg0=local_id, arg1=remote_id）符合协议。
- AUTH 轮换逻辑（SHA256 → SHA1 → RSAPUBLICKEY，rounds>8 上限）结构合理；CNXN payload `host::\0` 正确。
- `connect()` 失败路径由 TuningExecutor 调用 `conn.close()`，无 socket 泄漏（单次连接场景）。
- minSdk 32 下使用 `java.util.Base64`（API 26+）无兼容问题；Compose BOM/Kotlin 2.0.0/compose plugin 版本互相匹配；`keystore/debug.keystore` 存在，debug 签名配置有效。
- `manifest` INTERNET 权限齐全（本机 TCP socket 到 127.0.0.1:5555 需要）；themes 与 ComponentActivity+Compose 组合可用。
- `NewProfileCard` 的 Slider steps=3 在 0..4 恰好产生 5 档整数；纹理上限 coerce 无溢出。
- `runCommands` 使用 `all{}` 短路，失败即停止后续命令，行为合理。

## 修复优先级建议

1. **先修 BUG-001**（否则一切 ADB 功能在未授权设备上不可用）——修的时候连带 `AdbKeyStore.parsePublicKeyFile`（BUG-018 一并处理旧文件自愈）。
2. **BUG-003 + BUG-002**（命令层正确性：值污染、状态误读）。
3. **BUG-004/005/006/007**（连接健壮性：超时、并发、恢复、结果判定）。
4. **BUG-008**（存储数据丢失链）。
5. P2 按人力余量安排。
