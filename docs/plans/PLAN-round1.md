# QuestTuner Round 1 修复方案（PLAN-round1）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round1.md（David，18 个缺陷：P0×1、P1×9、P2×8）
- 基线：master @ 88674b2；修复在 `fix/round-1` 分支进行
- 行号均以基线源码核对过；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】的行给出改动后代码；标注【删】直接删除；标注【增】为新增行

## 总览

| 编号 | 级别 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-001 | P0 | 修（重写 encodePublic） | AdbKey.kt、AdbKeyStore.kt |
| BUG-002 | P1 | 修（一行 + 补一项） | TuningCommands.kt |
| BUG-003 | P1 | 修（一行） | AdbConnection.kt |
| BUG-004 | P1 | 修（soTimeout + 协程取消） | AdbConnection.kt、TuningExecutor.kt |
| BUG-005 | P1 | 修（Mutex 串行化 + localId 分配） | AdbConnection.kt、TuningExecutor.kt |
| BUG-006 | P1 | 修（连接失效检测 + 状态复位） | TuningExecutor.kt、TunerViewModel.kt |
| BUG-007 | P1 | 修（追加 `; echo EXIT:$?` 判退出码） | TuningExecutor.kt、TunerViewModel.kt |
| BUG-008 | P1 | 修（局部变量装载 + 原子写） | ProfileRepository.kt |
| BUG-009 | P1 | 修（GET_META_DATA） | AppScanner.kt |
| BUG-010 | P1 | 修（单项 runCatching） | AppScanner.kt |
| BUG-011 | P2 | 修（补重置项、删重复行） | TuningCommands.kt |
| BUG-012 | P2 | 修（Packet 携带 crc + verifyCrc 修正） | AdbProtocol.kt |
| BUG-013 | P2 | 修（字节累积 + Decoder 解码） | AdbConnection.kt |
| BUG-014 | P2 | 修（单项也生成命令） | ProfileRepository.kt |
| BUG-015 | P2 | 修（enabled 移除连接 gate） | TunerApp.kt |
| BUG-016 | P2 | 修（Dispatchers.IO + @Synchronized） | ProfileRepository.kt、TunerViewModel.kt |
| BUG-017 | P2 | 不修（详见 §17，给后续建议） | — |
| BUG-018 | P2 | 修（加载失败自愈重建，与 001 联动） | AdbKeyStore.kt |

**建议实施顺序**（依赖关系）：① 001+018（同文件，一起改）→ ② 003+002+011（TuningCommands/AdbConnection 协议正确性）→ ③ 013+005+004+006（AdbConnection 结构改动集中，按此序减少行号漂移）→ ④ 007 → ⑤ 008+016（同文件）→ ⑥ 014+015 → ⑦ 009+010 → ⑧ 012（纯新增单测触达）。

---

## BUG-001（P0）ADB 公钥编码不符合协议（n0inv 取错位数 + modulus/rr 字节序错误）

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/adb/AdbKey.kt`（encodePublic）
  - `app/src/main/java/com/example/questtuner/adb/AdbKeyStore.kt`（parsePublicKeyFile，联动 BUG-018）
- 修复思路：android_pubkey 结构中 `n0inv` 的 `n0` 必须是模数**最低 32 位**（`n mod 2^32`，Montgomery 常数由最低 word 决定），而 `modulus` 与 `rr` 在 wire 上是**小端（LE）**。当前代码取 BE 数组前 4 字节（模数最高 32 位）算 n0inv，且 modulus/rr 直接按 BE 写入。修复即按协议重写取值与写入。修复后编码格式变化，`parsePublicKeyFile` 必须同步按 LE 反解，且已落盘的旧格式 `adbkey.pub` 无法反解出合法 RSA key → 通过 BUG-018 的自愈路径直接重建密钥（旧密钥本就从未成功授权过，无保留价值）。
- 具体改动点：

  **（a）AdbKey.kt:40-51（encodePublic 内）**

  现状：
  ```kotlin
  val n0 = BigInteger(1, modulus.copyOfRange(0, 4))  // 模数高 32 位（BE 读出）
  // n0inv = -1/n0 mod 2^32
  val n0inv = n0.modInverse(BigInteger.TWO.pow(32)).negate().mod(BigInteger.TWO.pow(32))
  // rr = R^2 mod N, R = 2^2048
  val rr = BigInteger.TWO.pow(2048).modPow(BigInteger.TWO, n)
  val rrBytes = bigToBytes(rr, 256)

  val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
  buf.putInt(64)
  buf.putInt(n0inv.toInt())
  buf.put(modulus)
  buf.put(rrBytes)
  buf.putInt(pub.publicExponent.toInt())
  require(!buf.hasRemaining())
  ```

  改为：
  ```kotlin
  //【增】LE 序的 256 字节 modulus（wire 格式）
  val modulusLe = modulus.reversedArray()
  //【改】n0 = 模数最低 32 位（n mod 2^32，Montgomery 常数由最低 word 决定）
  val n0 = n.mod(BigInteger.TWO.pow(32))
  // n0inv = -1/n0 mod 2^32
  val n0inv = n0.modInverse(BigInteger.TWO.pow(32)).negate().mod(BigInteger.TWO.pow(32))
  // rr = R^2 mod N, R = 2^2048
  val rr = BigInteger.TWO.pow(2048).modPow(BigInteger.TWO, n)
  //【改】rr 同样按 LE 写入 wire
  val rrBytes = bigToBytes(rr, 256).reversedArray()

  val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
  buf.putInt(64)
  buf.putInt(n0inv.toInt())
  //【改】modulus/rr 用 LE 序写入
  buf.put(modulusLe)
  buf.put(rrBytes)
  buf.putInt(pub.publicExponent.toInt())
  require(!buf.hasRemaining())
  ```

  依据：AOSP `system/core/libcrypto_utils/android_pubkey.cpp` 的 `android_pubkey_encode`：`n0inv = -1 / (n % 2^32)`，modulus/rr 均 `to_bytes(..., 'little')`（LE）。python-adb / AdbLib 一致。

  **（b）AdbKey.kt:23（类注释，可选但建议）**【改】
  ```kotlin
  //   uint32 n0inv    = -1/n0 mod 2^32 (LE, n0 = 模数最低 32 位，即 n mod 2^32)
  ```

  **（c）AdbKeyStore.kt:57-72（parsePublicKeyFile，联动修复）**

  现状 `bb.get(m)` 读出的 `m` 直接 `BigInteger(1, m)`（按 BE 解析）——与 LE wire 格式不匹配。改为：
  ```kotlin
  private fun parsePublicKeyFile(text: String): RSAPublicKey {
      val b64 = text.trim().split(" ")[0]
      val bytes = Base64.getDecoder().decode(b64)
      val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      require(bytes.size == 524) { "pubkey must be 524 bytes" }   //【增】先校验长度
      bb.int // word count（=64）
      bb.int // n0inv
      val m = ByteArray(256)
      bb.get(m)
      val rr = ByteArray(256)
      bb.get(rr) // R^2 mod N, not needed for JDK RSA
      val e = bb.int
      //【改】m 是 LE 序，反转后构造 BigInteger（BE）
      val modulus = java.math.BigInteger(1, m.reversedArray())
      val exponent = java.math.BigInteger.valueOf(e.toLong() and 0xffffffffL)
      return KeyFactory.getInstance("RSA")
          .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
  }
  ```

  **（d）旧格式 adbkey.pub 自愈**：旧文件编码错误、修复后必然反解失败或反解出"错 key"。自愈放在 BUG-018 的加载失败处理中统一实现（见 BUG-018 改动点）：加载/解析任一步失败 → 删除两文件 → 走生成分支重建。不要单独写"格式版本号"之类的复杂方案——旧密钥从未成功授权过，直接重建即可。

- 风险与注意事项：
  - `modInverse` 要求 n0 与 2^32 互素；RSA-2048 模数是两个奇素数之积，必为奇数，`n mod 2^32` 为奇数，必然互素——安全。
  - `n mod 2^32`：用 `n.mod(...)` 而不是从 256 字节数组尾部取——两者等价，但 `n.mod` 语义更清晰且不依赖数组序。
  - 修复后密钥编码变化，测试机上已授权的旧密钥（若有）需重新授权一次（弹出 USB 调试授权框属预期，非回归）。
  - `fingerprint()`（AdbKey.kt:66-74）未按协议算（对 modulus||e 做 MD5）——仅展示用、不参与协议，本 bug 不连带改它（改动无收益、徒增风险）。
- 验证方法：
  1. 单测（标准 JVM）：固定种子生成 RSA-2048 keypair（或用固定测试向量 n），断言 `encodePublic` 输出 base64 解码后 524 字节：前 4 字节 LE == 64；n0inv 字段 == `-1/(n mod 2^32) mod 2^32`（LE）；modulus 段 == n 的 256 字节 LE；rr 段 == `R^2 mod N` 的 LE；exponent == 65537。
  2. 回环测试：`encodePublic(pub)` → `parsePublicKeyFile(text)` 反解出的 modulus/exponent 与原 key 一致。
  3. 对拍（可选但最有说服力）：PC 上取 `~/.android/adbkey`（PKCS8）与 `adbkey.pub`，用本 app 的 `encodePublic` 重新编码同一 n，与 `adbkey.pub` base64 段逐字节比对。
  4. 实机：修复后未授权 Quest 3 连接，头显弹出授权框、CNXN 完成（结合 BUG-018 旧文件自愈路径）。

---

## BUG-002（P1）statusCommand() 把 gpuLevel 写成了 setprop

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/TuningCommands.kt`
- 修复思路：笔误修正 + 顺带补上遗漏的 `highPrecisionPassthrough` 读取（报告指出同函数未读该项），保持"一次聚合读取"结构不变。
- 具体改动点：TuningCommands.kt:62-68：
  ```kotlin
  fun statusCommand(): String = listOf(
      "getprop debug.oculus.refreshRate",
      "getprop debug.oculus.textureWidth",
      "getprop debug.oculus.textureHeight",
      "getprop debug.oculus.cpuLevel",
      "setprop debug.oculus.gpuLevel",     //【删】
      "getprop debug.oculus.gpuLevel",     //【增】
      "getprop debug.oculus.highPrecisionPassthrough",  //【增】补漏读项
  ).joinToString("; echo ---; ")
  ```
- 风险与注意事项：无实质风险。`getprop` 未设属性输出空行，UI 已按多行文本展示，空行可接受。
- 验证方法：连接后点"刷新状态"：修复前输出含 `setprop` usage 报错且无 gpuLevel 值；修复后输出 6 段（含 highPrecisionPassthrough）且无报错。

---

## BUG-003（P1）shell OPEN payload 以冒号结尾，未按协议 NUL 终止

- 涉及文件：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt`
- 修复思路：ADB 协议要求 OPEN 的 service 名为 NUL 终止字符串，官方客户端发送 `"shell:" + cmd + "\0"`。当前发送 `"shell:$command:"`，命令带尾随冒号执行（属性值被污染为 `"90:"`）。
- 具体改动点：AdbConnection.kt:74：
  ```kotlin
  //【删】sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command:".toByteArray(Charsets.UTF_8))
  //【增】sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
  ```
- 风险与注意事项：无。若用户设备上曾用旧版写入过 `90:` 这类污染值，`resetAll`（空串）或重新 setprop 即可覆盖，无需迁移。
- 验证方法：修复后连接 → 点"刷新状态"（getprop）观察历史值是否曾带尾随 `:`；再 setprop 一个值后 getprop 复查，值干净无冒号。

---

## BUG-004（P1）socket 无读超时，授权等待期间 connect 永久阻塞且无法取消

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/adb/AdbConnection.kt`
  - `app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt`
- 修复思路：`connectTimeoutMs` 只管 TCP 连接，之后的 `readPacket()` 无 SO_TIMEOUT，首次授权等待时永久阻塞。两层修复：(1) socket 设 `soTimeout`，让阻塞读变成可超时失败；(2) 协程取消时主动关 socket 解除阻塞（`withTimeout` + `invokeOnCancellation`）。超时阈值要大于人工在头显上点"允许"的常规耗时（30s 经验值：给用户足够时间看完授权框，又不至于无限等）。
- 具体改动点：

  **（a）AdbConnection.kt:27-29（connect 内）**：
  ```kotlin
  val sock = Socket()
  sock.tcpNoDelay = true
  sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
  //【增】读超时：adbd 等待用户在头显上确认授权期间不回包，无 soTimeout 会永久阻塞
  sock.soTimeout = readTimeoutMs
  socket = sock
  ```
  并在 AdbConnection.kt:18 构造参数处【增】：
  ```kotlin
  private val readTimeoutMs: Int = 30_000,
  ```

  **（b）TuningExecutor.kt:26-27**：connect 包进 `withTimeout` 并支持取消：
  ```kotlin
  val conn = AdbConnection("127.0.0.1", 5555)
  val result = runCatching {
      withTimeout(35_000L) {          //【增】略大于 socket soTimeout，双保险
          val r = conn.connect(keyStore)
          r.getOrThrow()              // connect 返回 Result，统一转异常便于 runCatching
          // 返回 deviceInfo 字符串
      }.let { Result.success(it) }
  }.onFailure {
      conn.close()                    //【增】超时/取消/失败都关 socket
      Result.failure<String>(it)
  }
  if (result.isSuccess) { connection = conn; ... } else { lastError = ...; }
  ```
  （精确结构调整允许，但必须保证：任何失败路径 `conn.close()`；协程被取消时 socket 被关闭。如需更严格的取消语义，可在 `withContext(Dispatchers.IO)` 内用 `coroutineContext[Job]?.invokeOnCancellation { conn.close() }`。）

  注意：BUG-005 会在 TuningExecutor 加 Mutex，本处 withTimeout 结构可先落位，005 的锁不会与此冲突（connect 不在 shell Mutex 内）。
- 风险与注意事项：
  - soTimeout 对**整个连接生命周期**生效——shell 长命令（如 dumpsys）执行超 30s 会被断开。`dumpsys display` 等命令通常秒级完成，30s 足够；若担心极端慢设备，shell 阶段可临时调大（不强制）。
  - `withTimeout` 触发时 `conn.connect` 内的阻塞 read 收到 socket closed 异常退出——两个超时联动可接受。
- 验证方法：未授权实机连接、不点头显确认框：修复前 UI 永久"连接中…"；修复后约 30s 报"连接失败: timeout…"且按钮恢复可点。授权路径正常连接不受影响。

---

## BUG-005（P1）ADB 连接无并发保护，并发 shell() 导致协议流损坏

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/adb/AdbConnection.kt`
  - `app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt`
- 修复思路：单连接复用所有命令，但 `shellStreaming/readPacket/sendPacket` 无互斥、localId 恒为 1，并发时流 ID 错配、输出串包。采用**连接级 Mutex 串行化 shell**（最小改动且与 ADB 单连接多路复用的实际需求匹配——本 app 不需要真并发流），同时 localId 改为 `AtomicInteger` 递增分配（即使将来放开并发也稳健）。
- 具体改动点：

  **（a）AdbConnection.kt**：
  - 类头【增】字段：
    ```kotlin
    private val shellMutex = java.util.concurrent.locks.ReentrantLock()
    private val nextLocalId = java.util.concurrent.atomic.AtomicInteger(1)
    ```
    （Kotlin stdlib 的 `kotlin.concurrent.withLock {}` 扩展可直接用于 ReentrantLock。）
  - AdbConnection.kt:72-90 `shellStreaming` 整体包锁：
    ```kotlin
    fun shellStreaming(command: String, onChunk: (ByteArray) -> Unit) {
        shellMutex.withLock {                       //【增】串行化整个 shell 会话
            val localId = nextLocalId.getAndIncrement()   //【改】递增分配，不再恒为 1
            sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
            // ……其余 readPacket/OKAY/CLSE 循环逻辑不变，包在 withLock 内……
        }
    }
    ```
    注意 `CMD_CLSE -> return` 在 withLock 内直接 return（非局部返回）也可，但更稳妥写 `return@withLock` 结束；为最小 diff 保留原结构即可。

  **（b）TuningExecutor.kt**：
  - 【增】`private val adbMutex = kotlinx.coroutines.sync.Mutex()`
  - runCommand/runCommands/ensureConnected 的**命令执行段**用 `adbMutex.withLock {}` 包裹（ensureConnected 只需包 connect+状态设置段）。这层是防 UI 侧（applyProfile 中 refreshStatus 等跨函数竞争），AdbConnection 内层的锁是最终防线。两层不冲突。
  - lastError 是共享可变状态：在持有 adbMutex 时读写即可（上述包裹已覆盖）。可选增强：`lastError` 改 per-call 记录太侵入，保持现状 + 锁内读写已消除张冠李戴。

- 风险与注意事项：
  - 若 future 需要真并发多流，当前"每命令完整 OPEN→…→CLSE 串行"模型不支持，需重构流多路复用——超出本 bug 范围，不做。
  - ReentrantLock 可重入，同线程嵌套不会死锁；跨协程（不同线程）由锁保证串行。
- 验证方法：
  1. 单测：伪 socket / 本地模拟 adbd 服务器，两个线程并发 `shell`，断言两次输出各自完整、不串包、localId 不同。
  2. 实机：applyProfile 执行中立即点"刷新状态"（修复前按钮此时可点——仅以 connected 为条件），输出不交错。
  3. 代码走查确认所有 sendPacket/readPacket 调用点（connect 的 CNXN/AUTH 除外——connect 由 ensureConnected 的 adbMutex 保护）均在锁内。

---

## BUG-006（P1）连接断开后进入不可恢复状态

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt`
  - `app/src/main/java/com/example/questtuner/ui/TunerViewModel.kt`
- 修复思路：断连后 `connection` 仍非 null、`connected` 仍 true，`ensureConnected` 对死连接返回 true、连接按钮永久禁用。核心是**失败时清理连接 + 状态回调复位**。TuningExecutor 是 object（无 lifecycle），不能直接持有 StateFlow——采用"失败即清理 + ViewModel 侧检测"的最小方案。
- 具体改动点：

  **（a）TuningExecutor.kt:39-46（runCommand 失败清理）**：
  ```kotlin
  suspend fun runCommand(cmd: String): String? = withContext(Dispatchers.IO) {
      val conn = connection ?: return@withContext null
      val r = adbMutex.withLock { conn.shell(cmd) }
      if (r.isSuccess) {
          r.getOrNull()
      } else {
          lastError = r.exceptionOrNull()?.message
          //【增】shell 失败大概率是连接已死（IOException）——关闭并置空，允许重连
          if (r.exceptionOrNull() is java.io.IOException) {
              conn.close()
              connection = null
          }
          null
      }
  }
  ```
  （`connection` 是 `@Volatile`，置空即线程可见。判断条件用 IOException 区分"协议失败（连接已死）"与"命令失败（BUG-007 的 EXIT 码非 0，连接仍活）"——**不要**在 EXIT 码失败时误杀连接。）

  **（b）TunerViewModel.kt:61-66（refreshStatus 失败后复位 UI 状态）**：
  ```kotlin
  fun refreshStatus() {
      viewModelScope.launch {
          val out = TuningExecutor.runCommand(TuningCommands.statusCommand())
          if (out == null) {
              //【增】读取失败且连接已被清理 → 复位连接状态，允许重连
              _state.value = _state.value.copy(
                  connected = false,
                  adbStatus = "连接已断开: ${TuningExecutor.lastError ?: "读取失败"}",
              )
          }
          _state.value = _state.value.copy(currentStatus = out ?: "读取失败")
      }
  }
  ```
  其余调用 runCommand/runCommands 的函数（applyProfile:80-88、resetAll:107-112）失败分支同样追加 `connected = false` 复位（【增】一行 copy 即可，同上模式）。

  **（c）TunerApp.kt:61 连接按钮**：`enabled = !state.connected && !state.connecting` 本身正确，(b) 修复复位后按钮自然恢复可点——**无需改**。复位后 `TuningExecutor.connection == null` → 用户点"连接"走完整 ensureConnected 重建。覆盖泄漏场景：重建时旧 socket 已在 (a) 中 close。

- 风险与注意事项：
  - (a) 的清理条件必须是 IOException（连接级失败）；BUG-007 引入的"EXIT 码非 0"属命令级失败，连接仍活，不能清连接——两 bug 的失败语义要分开，实施时注意先后（007 在 006 之后实施则天然不冲突）。
  - 状态复位依赖 ViewModel 侧检测，存在"清理了连接但 UI 尚未感知"的短窗口——用户此期间点"应用"会走 ensureConnected 重连（可接受，行为正确）。
- 验证方法：连接成功后重启设备 adbd（`adb shell su -c "stop adbd; start adbd"` 或断开无线调试）→ 点"刷新状态"：修复前持续"读取失败"、按钮不可点；修复后显示"连接已断开"、连接按钮恢复，点"连接"成功重连。

---

## BUG-007（P1）把协议成功当作命令成功，setprop 失败仍显示"完成"

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt`、`ui/TunerViewModel.kt`
- 修复思路：ADB shell v2 协议里 stderr 与退出码需 `shell,v2` 才有标准通道，但兼容做法是命令后追加 `; echo EXIT:$?` 从 stdout 取退出码（官方 adb shell 默认不回传退出码，this trick 是业界标准做法）。同时把命令输出呈现给用户（applyProfile 已拼 lastCommandOutput，需把 stdout 内容带上）。
- 具体改动点：

  **（a）TuningExecutor.kt:39-46（runCommand 包装）**：
  ```kotlin
  suspend fun runCommand(cmd: String): String? = withContext(Dispatchers.IO) {
      val conn = connection ?: return@withContext null
      //【改】追加退出码探针：stdout 末尾出现 "EXIT:0" 才算命令成功
      val wrapped = "$cmd; echo EXIT:$?"
      val r = adbMutex.withLock { conn.shell(wrapped) }
      if (!r.isSuccess) {
          lastError = r.exceptionOrNull()?.message
          if (r.exceptionOrNull() is java.io.IOException) { conn.close(); connection = null }
          return@withContext null
      }
      val out = r.getOrNull() ?: return@withContext null
      //【增】解析 EXIT 码：取最后一次出现的 "EXIT:" 行
      val exitMark = out.lastIndexOf("EXIT:")
      if (exitMark < 0) {
          lastError = "no exit marker; output: ${out.take(200)}"
          return@withContext null
      }
      val code = out.substring(exitMark + 5).trim().toIntOrNull()
      if (code != 0) {
          lastError = "exit=$code: ${out.removeSuffix(out.substring(exitMark)).take(300)}"
          return@withContext null
      }
      //【改】返回去掉 EXIT 行的原始输出
      out.substring(0, exitMark).trimEnd('\n')
  }
  ```
  （`code` 解析失败按失败处理，lastError 带输出片段。）

  **（b）TunerViewModel.kt:77-89（applyProfile 呈现输出）**：
  ```kotlin
  fun applyProfile(p: AppProfile) {
      viewModelScope.launch {
          val cmds = p.toCommands()
          val ok = TuningExecutor.runCommands(cmds)
          val sb = StringBuilder()
          sb.append("应用 ${p.label ?: p.packageName} ")
          sb.append(if (ok) "完成\n" else "失败\n")
          //【增】失败时把 runCommand 截获的实际命令输出/错误呈现给用户
          if (!ok) sb.append("错误: ${TuningExecutor.lastError}\n")
          cmds.forEach { sb.append("  $it\n") }
          _state.value = _state.value.copy(lastCommandOutput = sb.toString())
          refreshStatus()
      }
  }
  ```

- 风险与注意事项：
  - 命令输出本身包含 "EXIT:" 文本会干扰：用 `lastIndexOf`（命令正常结束时探针在最后）+ trim 解析，可覆盖绝大多数场景；极罕见对抗性输出可接受。
  - statusCommand 是多命令 `; echo ---; ` 聚合后整体再被包一层 `; echo EXIT:$?` —— 聚合串整体退出码 = 最后一条命令的退出码，`getprop` 不会失败，兼容。
  - runCommands 用 `all{}` 短路（report 已确认合理），保持。
- 验证方法：
  1. 实机连接后执行 `setprop debug.oculus.notexist 1`（固件无此属性）：修复前显示"完成"；修复后显示"失败 exit=…"+ stderr 片段（"Could not set property"）。
  2. 正常 setprop 后 getprop 验证值已写入。
  3. statusCommand 聚合读取不受 EXIT 探针影响（输出尾部多一行 "EXIT:0" 被剥离）。

---

## BUG-008（P1）Profile 存储：损坏 JSON 静默清空 + 非原子写 → 数据永久丢失

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt`
- 修复思路：三处：(1) reload 先读入局部变量、解析成功后再替换 `all`（失败不清空、不覆盖）；(2) 解析失败保留旧文件并记录（供排查），不写回；(3) persist 临时文件 + renameTo 原子替换。
- 具体改动点：

  **（a）ProfileRepository.kt:33-54（reload）**：
  ```kotlin
  @Synchronized
  fun reload() {
      if (store.exists()) {
          //【改】先解析到局部变量，成功后才替换 all；失败不动 all（保持内存旧值/空表）
          val parsed = LinkedHashMap<String, AppProfile>()
          val ok = runCatching {
              val arr = JSONArray(store.readText())
              for (i in 0 until arr.length()) {
                  val o = arr.getJSONObject(i)
                  val p = AppProfile(
                      packageName = o.getString("packageName"),
                      label = o.optString("label").ifEmpty { null },
                      refreshRate = o.optInt("refreshRate", -1).takeIf { it > 0 },
                      textureWidth = o.optInt("textureWidth", -1).takeIf { it > 0 },
                      textureHeight = o.optInt("textureHeight", -1).takeIf { it > 0 },
                      cpuLevel = o.optInt("cpuLevel", -1).takeIf { it >= 0 },
                      gpuLevel = o.optInt("gpuLevel", -1).takeIf { it >= 0 },
                  )
                  parsed[p.packageName] = p
              }
          }.isSuccess
          if (ok) {
              all.clear()
              all.putAll(parsed)
          } else {
              //【增】解析失败：保留旧文件与内存现状，记日志（不覆盖、不清空）
              android.util.Log.w("ProfileRepository", "profiles.json corrupted, keep ${all.size} in-memory")
          }
      }
  }
  ```

  **（b）ProfileRepository.kt:72-87（persist 原子写）**：
  ```kotlin
  private fun persist() {
      val arr = JSONArray()
      all.values.forEach { p -> /* ……原构造逻辑不变…… */ }
      store.parentFile?.mkdirs()
      //【改】临时文件 + renameTo 原子替换，避免写一半被杀留下半截 JSON
      val tmp = File(store.parentFile, store.name + ".tmp")
      tmp.writeText(arr.toString(2))
      if (!tmp.renameTo(store)) {
          //【增】极少数文件系统 rename 失败（目标被占用等）回退直接写
          store.writeText(arr.toString(2))
      }
      tmp.delete()
  }
  ```
  （`arr` 构造部分 74-84 行原样保留，只改写盘最后两行。）

- 验证方法：单测（标准 JVM，org.json 需测试依赖或用 Robolectric——注意 org.json 在 Android 是 stub，纯 JVM 单测会抛 "Stub!"，建议放 androidTest 或用 org.json:jsonalias 测试依赖；简化验证也可实机）：写入合法 JSON → 手工截断一半 → new ProfileRepository → 断言 profiles 为空但磁盘旧文件仍在；再 save 一条 → 断言磁盘内容为 "旧内容被新 persist 覆盖" 是**不可能**（因 all 为空、新 persist 只写新表）——核心断言：截断场景下 save 后磁盘文件是仅含新条目的合法 JSON（而非空数组覆盖旧文件后数据全丢的旧链路）。
- 风险与注意事项：renameTo 在同目录下原子；回退分支保证极端情况仍可写。Log.w 不影响主流程。

---

## BUG-009（P1）VR 检测的 meta-data 路径恒失效（getActivityInfo 未带 GET_META_DATA）

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/AppScanner.kt`
- 修复思路：`pm.getActivityInfo(component, 0)` flags=0 不含 GET_META_DATA → metaData 恒 null。传 `PackageManager.GET_META_DATA` 即可。同时把 meta key 常量与注释对齐（现注释写的是 `com.oculus.vr.intent.category.VR`，常量是 `com.oculus.intent.category.VR`——以常量为准）。
- 具体改动点：AppScanner.kt:57：
  ```kotlin
  //【删】val full = pm.getActivityInfo(android.content.ComponentName(info.packageName, info.name), 0)
  //【增】val full = pm.getActivityInfo(
  //     android.content.ComponentName(info.packageName, info.name),
  //     PackageManager.GET_META_DATA,
  // )
  ```

- 风险与注意事项：
  - GET_META_DATA 会增加解析开销（每 app 一次 IPC），扫描已在 Dispatchers.IO，可接受。
  - meta key 的正确性：Oculus/Meta SDK 的 VR launcher 声明的是 intent-filter **category** `com.oculus.intent.category.VR` / `com.meta.intent.category.VR`（且多为 activity 级），而非 application meta-data boolean。现实现把 category 名当 meta-data boolean 读——**修复 GET_META_DATA 后该路径依然大概率不命中**（ Beat Saber 等第三方 VR 应用不写这个 meta）。真正可靠路径是读 activity 的 intent-filter categories：`ri.filter`（IntentFilter）遍历 `countCategories()`。建议 Wang 修复时顺带把 hasVrCategory 改为优先读 `ri.filter` categories，meta-data 作为兜底——但注意 `queryIntentActivities` 返回的 ResolveInfo.filter 可能为 null（匹配非 filter 语义时），需判空。
  - 若测试设备上 Beat Saber 仍未命中，用 `aapt dump xmltree app.apk AndroidManifest.xml | grep -A5 category` 确认其声明形式再定。
- 验证方法：装有第三方 VR 应用（Beat Saber，com.beatgames.beatsaber）的设备扫描：修复前 isVr 全 false（仅包名兜底）；修复后 Beat Saber 进入 VR 组且排前。若 filter 路径实施，同样设备验证覆盖。

---

## BUG-009 补充：hasVrCategory 的 filter 优先实现（建议，与上条同 PR）

```kotlin
private fun hasVrCategory(pm: PackageManager, ri: android.content.pm.ResolveInfo): Boolean {
    // 1) intent-filter categories（VR launcher 的标准声明方式）
    val f = ri.filter
    if (f != null) {
        for (i in 0 until f.countCategories()) {
            val c = f.getCategory(i)
            if (c == VR_CATEGORY_META || c == VR_CATEGORY_META2) return true
        }
    }
    // 2) 兜底：application meta-data（需 GET_META_DATA，配合 BUG-009 主修复）
    val full = runCatching {
        pm.getActivityInfo(
            android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
            PackageManager.GET_META_DATA,
        )
    }.getOrNull() ?: return packageFallback(ri.activityInfo.packageName)
    val appMeta = full.applicationInfo?.metaData
    val metaVr = appMeta?.getBoolean(VR_CATEGORY_META, false)
        ?: appMeta?.getBoolean(VR_CATEGORY_META2, false)
        ?: false
    if (metaVr) return true
    return packageFallback(ri.activityInfo.packageName)
}

private fun packageFallback(pkg: String): Boolean {
    val p = pkg.lowercase()
    return p.startsWith("com.oculus") || p.startsWith("com.meta.")
}
```

（此实现同时覆盖 BUG-010 的 runCatching；若 Wang 判断 filter 签名改动过大，可只做 009 主修复 + 010 的 runCatching，filter 路径留待 round 2。）

---

## BUG-010（P1）AppScanner 未捕获 NameNotFoundException，卸载竞态导致闪退

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/AppScanner.kt`
- 修复思路：`getActivityInfo` 在包被瞬间卸载时抛 `NameNotFoundException`，query 内 forEach 无保护 → 协程未捕获异常闪退。单项降级：runCatching 包住，失败按 `isVr=false` 继续。
- 具体改动点：AppScanner.kt:57（若按 BUG-009 补充实现重构 hasVrCategory，已内置 runCatching，本条即完成）；否则最小改法：
  ```kotlin
  //【改】AppScanner.kt:57 原 getActivityInfo 调用包进 runCatching
  val full = runCatching {
      pm.getActivityInfo(
          android.content.ComponentName(info.packageName, info.name),
          PackageManager.GET_META_DATA,
      )
  }.getOrNull() ?: return false   // 单项降级：查不到（已卸载/隐藏）视为非 VR，不中断扫描
  ```
  （`return false` 前可保留包名兜底：`return pkgLower.startsWith("com.oculus") || pkgLower.startsWith("com.meta.")`——注意原函数末尾的兜底逻辑位置，降级路径走兜底更一致。）

- 风险与注意事项：降级仅影响该项 isVr 判定，不影响整表。若采用 BUG-009 补充的重构版，`packageFallback` 已统一。
- 验证方法：单测 stub PackageManager：某项 getActivityInfo 抛 NameNotFoundException，断言 query 返回剩余列表不抛异常。实机：扫描过程中卸载一个应用，列表正常出全。

---

## BUG-011（P2）resetAll() 重复 textureHeight、漏掉 highPrecisionPassthrough

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/TuningCommands.kt`
- 修复思路：删重复行、补缺失项。修复简单无争议。
- 具体改动点：TuningCommands.kt:52-59：
  ```kotlin
  fun resetAll(): List<String> = listOf(
      "setprop debug.oculus.refreshRate \"\"",
      "setprop debug.oculus.textureWidth \"\"",
      "setprop debug.oculus.textureHeight \"\"",
      "setprop debug.oculus.textureHeight \"\"",                         //【删】重复行
      "setprop debug.oculus.highPrecisionPassthrough \"\"",              //【增】补缺失项
      "setprop debug.oculus.cpuLevel \"\"",
      "setprop debug.oculus.gpuLevel \"\"",
  )
  ```
- 风险与注意事项：无。`setPassthroughQuality` 无调用方属 BUG-017 范畴（本方案建议不修 017，见 §17），但**重置项必须补**——因为该属性一旦被外部工具（QGO 等）设过，恢复默认就应清它。
- 验证方法：外部设置 highPrecisionPassthrough=1 → 本 app 点"恢复默认" → `getprop debug.oculus.highPrecisionPassthrough` 输出空。

---

## BUG-011 关联说明：setPassthroughQuality 无调用方

`TuningCommands.setPassthroughQuality`（TuningCommands.kt:47-49）全工程无调用方。属"未接通的功能"，与 BUG-017 同类。本方案不新增 UI 入口（超出修复范畴），保留函数（resetAll 补项后语义完整）。

---

## BUG-012（P2）AdbProtocol.verifyCrc 恒返回 false、decodeHeader 丢弃 CRC

- 涉及文件：`app/src/main/java/com/example/questtuner/adb/AdbProtocol.kt`
- 修复思路：Packet 携带 crc 字段、verifyCrc 按 Packet 内真实 crc 比对；decodeHeader 把 crc 填进 Packet。消除陷阱式死代码。
- 具体改动点：
  - AdbProtocol.kt:27【改】`data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray, val crc: Int = 0)`
  - AdbProtocol.kt:41-53（decodeHeader）【改】解析出 crc 后填入 Packet：
    ```kotlin
    return Packet(cmd, a0, a1, ByteArray(len).also { /* crc filled by caller */ }, crc)
    ```
  - AdbProtocol.kt:55-57【改】：
    ```kotlin
    fun verifyCrc(p: Packet, payload: ByteArray = p.payload): Boolean = crc32(payload) == p.crc
    //【删】private fun crcOf(p: Packet): Int = 0
    ```

- 风险与注意事项：
  - Packet 是 data class 且新增字段带默认值——现有构造调用点（readPacket、decodeFull、测试）不 break。`data class` 的 copy/equals 会包含 crc——Payload 为 ByteArray 时 equals 本就是引用比较（Array 在 data class equals 中用 equals()，即引用），行为无实质变化。
  - decodeFull 已校验 crc（69 行），不变。
- 验证方法：单测：encode 一包 → decodeHeader(header) 得 Packet → `verifyCrc(p, payload)` 应 true（修复前 false）；篡改 payload 一字节 → false。

---

## BUG-013（P2）shell 输出按包独立 UTF-8 解码，跨包多字节字符乱码

- 涉及文件：`app/src/main/java/com/example/questtuner/adb/AdbConnection.kt`
- 修复思路：每个 WRTE payload（≤4096B）独立 `String(chunk, UTF_8)` 解码，多字节字符跨包截断产生 U+FFFD。改为字节累积、用 `CharsetDecoder`（报告错误动作 REPORT）按 UTF-8 边界解码，流结束 flush 余量。
- 具体改动点：AdbConnection.kt:66-70（shell）：
  ```kotlin
  fun shell(command: String): Result<String> = runCatching {
      //【改】字节累积 + CharsetDecoder 按边界解码，避免多字节字符跨 WRTE 包截断
      val bytes = java.io.ByteArrayOutputStream()
      shellStreaming(command) { chunk -> bytes.write(chunk) }
      bytes.toString("UTF-8")
  }
  ```
  （最简实现：先全部累积为字节、最后一次解码——天然无跨包问题。命令输出规模（KB 级）下内存开销可忽略。无需 CharsetDecoder 增量 API。）

- 风险与注意事项：无。`shellStreaming` 的 onChunk 回调签名不变，其他调用方（若有）不受影响；当前 shell 是唯一消费方。
- 验证方法：单测：伪流按 3+3 字节拆 "中" 的 UTF-8（3 字节）跨两个 chunk 喂入 shellStreaming → shell 结果仍为 "中"（修复前 "\uFFFD"）。实机：读取含中文应用名的输出无乱码。

---

## BUG-014（P2）cpu/gpu 命令生成与 UI 显示规则不一致

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt`、`ui/TunerApp.kt`
- 修复思路：`toCommands` 要求 cpu+gpu 成对才生成命令，UI 却独立显示——只设一项时"看似已设置实则不生效"。改 toCommands：单项也生成命令（setprop 两条命令本来就相互独立，无必须成对理由）。
- 具体改动点：ProfileRepository.kt:22：
  ```kotlin
  //【删】cpuLevel?.let { c -> gpuLevel?.let { g -> addAll(TuningCommands.setCpuGpuLevel(c, g)) } }
  //【增】cpu/gpu 各自独立生成（setprop 相互独立，无成对必要；与 describeProfile 独立显示一致）
  cpuLevel?.let { addAll(listOf("setprop debug.oculus.cpuLevel $it")) }
  gpuLevel?.let { addAll(listOf("setprop debug.oculus.gpuLevel $it")) }
  ```
  同理 textureWidth/textureHeight（ProfileRepository.kt:21）也存在同样的成对门槛与 UI（TunerApp.kt:131 `if (p.textureWidth != null && p.textureHeight != null)` 成对显示）——**UI 这边是成对的，与命令生成一致**，所以 texture 对**不改**（改了反而不一致）。只改 cpu/gpu。TuningCommands.kt:39-42 的 `setCpuGpuLevel(cpu, gpu)` 保留（成对调用方仍用），新增单项内联命令即可。

- 风险与注意事项：
  - 修改后仅 cpu 或仅 gpu 的 profile 会真实生效——行为变化符合预期（UI 本来就这么显示的）。
  - `setCpuGpuLevel` 函数保留避免破坏其他调用方（当前仅 toCommands 用）。
- 验证方法：保存仅 cpuLevel=4 的 profile（手工构造 JSON 或 UI 编辑存储）→ UI 显示 "CPU4" → 应用 → `getprop debug.oculus.cpuLevel` 返回 4。修复前不变。

---

## BUG-015（P2）保存 Profile 按钮被连接状态错误禁用

- 涉及文件：`app/src/main/java/com/example/questtuner/ui/TunerApp.kt`
- 修复思路：保存是纯本地操作（repo.save），被 `state.connected` 错误 gate；反向 applyProfile 不检查连接但按钮有 enabled 条件。删掉保存按钮的连接 gate 即可。
- 具体改动点：TunerApp.kt:187：
  ```kotlin
  //【删】enabled = state.connected,
  ```
  （删除该行，Button 其余不变，默认 enabled=true。）

  反向问题（应用按钮不检查连接）：ProfileRow 的"应用"按钮（TunerApp.kt:122）当前无 enabled 条件。建议【增】`enabled = state.connected`——但 ProfileRow 签名需要传 state，改动涉及 composable 签名。两案选一：
  - 方案 A（最小）：只删保存按钮的 gate。应用按钮点击后 runCommands 会因 connection==null 返回 false、提示"错误: null"——提示不友好但无功能错误。
  - 方案 B（推荐）：ProfileRow 增加 `connected: Boolean` 参数，TunerApp.kt:41-45 调用处传 `state.connected`，Button 122 行加 `enabled = connected`。约 4 行改动，提示语义清晰（未连接时按钮置灰）。
  - 本方案推荐 B，Wang 可按余量选择。

- 风险与注意事项：方案 B 改 composable 签名，注意 TunerApp.kt:41-45 调用点同步更新（唯一调用方）。
- 验证方法：未连接状态：保存 profile 可点且成功；方案 B 下应用按钮置灰。连接后全部正常。

---

## BUG-016（P2）主线程磁盘 IO（StrictMode 违规）

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ProfileRepository.kt`、`ui/TunerViewModel.kt`
- 修复思路：repo 的磁盘方法包 Dispatchers.IO；`profiles()/find()` 补 @Synchronized（当前只有 save/delete/reload 有，读方法裸奔 LinkedHashMap，埋竞态）。
- 具体改动点：

  **（a）ProfileRepository.kt**：
  - :56 `fun profiles(): List<AppProfile> = all.values.toList()` →【改】
    ```kotlin
    @Synchronized fun profiles(): List<AppProfile> = all.values.toList()
    ```
  - :70 `fun find(packageName: String): AppProfile? = all[packageName]` →【改】
    ```kotlin
    @Synchronized fun find(packageName: String): AppProfile? = all[packageName]
    ```
  - save/delete/persist/reload 涉及磁盘，改 suspend + withContext(IO) 或保持同步、由调用方切 IO。**推荐后者**（改动小、repo 无协程依赖）：ViewModel 的 saveProfile/deleteProfile/init 已在 viewModelScope.launch（Main），追加 `withContext(Dispatchers.IO){}` 包裹 repo 调用。

  **（b）TunerViewModel.kt**：
  - :34-36 init 的 `repo.profiles()` →【改】`viewModelScope.launch { val ps = withContext(Dispatchers.IO) { repo.profiles() }; _state.value = _state.value.copy(profiles = ps) }`
  - :91-96 saveProfile 的 `repo.save(p)` / `repo.profiles()` →【改】包 `withContext(Dispatchers.IO)`（两次调用同一 withContext 块内完成）。
  - :98-103 deleteProfile 同上【改】。

- 风险与注意事项：
  - @Synchronized 加在 profiles/find 上后，与 save/delete/reload 的 @Synchronized（同一对象监视锁）互斥——读写安全。
  - persist 由 save/delete 在锁内调用；ViewModel 切 IO 后磁盘写在 IO 线程、锁语义不变。
- 验证方法：StrictMode penaltyLog 下保存/删除 profile：修复前 logcat 出现 DiskWriteViolation；修复后无。主线程流畅性（保存大 profiles 文件）无卡顿。

---

## BUG-017（P2）应用扫描结果成为死数据，"按应用 Profile"无选择入口

- **结论：本 bug 不修（建议 round 2 作为功能需求处理）。**
- 理由：
  1. 这是**功能缺失**（NewProfileCard 无应用选择器），不是缺陷修复——新增 UI 组件（应用选择器 Dialog/BottomSheet + 列表渲染 + 选中回填包名）改动面大（新增 composable、状态管理、内存考量），与 round 1 "bug 修复"定位不符。
  2. `state.apps` 零引用不产生运行时错误，仅浪费一次扫描开销与内存（icon Drawable 常驻）——影响是"低效"而非"错误"。
  3. 修复 009/010 后扫描本身正确且不崩，保留数据供给 round 2 的选择器直接消费，反而是合理铺垫。
  4. 若强行最小修（暂缓扫描/loadApps 不调），会砍掉 connect 后自动扫描行为，round 2 又要加回——反复改。
- 给 round 2 的建议（不在本方案范围）：NewProfileCard 增加应用选择入口（复用 isVr 排序、VR 组前置展示）；扫描改懒加载（进入选择器才扫）+ icon 不常驻（软引用或即时渲染）。`setPassthroughQuality/getRefreshRate/getResolution/getCpuGpuLevel` 无调用方问题同理留 round 2（getCpuGpuLevel 在 statusCommand 有等价 getprop 路径）。
- 验证方法：不适用（不修）。round 1 验收时确认 state.apps 加载不崩溃即可（009/010 已保证）。

---

## BUG-018（P2）密钥文件损坏后无恢复路径，永久无法连接

- 涉及文件：`app/src/main/java/com/example/questtuner/adb/AdbKeyStore.kt`
- 修复思路：`loadOrGenerate` 只要两文件存在就走加载，加载抛异常后**永远不重建**。加自愈路径：加载失败 → 删旧文件 → 走生成分支重建。与 BUG-001 联动：001 修复后编码格式变化，旧格式 adbkey.pub 反解失败正好走此自愈（顺带校验 n0inv 是否等于新公式预期值，不等即视为旧格式，强制重建——旧 key 从未成功授权，无保留价值）。
- 具体改动点：AdbKeyStore.kt:21-37（loadOrGenerate）：
  ```kotlin
  @Synchronized
  fun loadOrGenerate() {
      if (publicKey != null) return
      val privFile = File(dir, "adbkey")
      val pubFile = File(dir, "adbkey.pub")
      val loaded = privFile.exists() && pubFile.exists() && runCatching {
          privateKeyEncoded = Base64.getDecoder().decode(privFile.readText().trim())   // 可抛（非法 base64）
          publicKey = parsePublicKeyFile(pubFile.readText())                            // 可抛（损坏/旧格式）
          //【增】旧格式检测：n0inv 与按新公式计算不一致 → 视为 BUG-001 修复前的旧编码，自愈重建
          val expectN0Inv = expectedN0Inv(publicKey!!.modulus)
          check(isExpectedN0Inv(pubFile.readText(), expectN0Inv)) { "legacy pubkey format" }
          privateKeyEncoded != null && publicKey != null
      }.getOrDefault(false)
      if (!loaded) {
          //【增】自愈：加载失败/旧格式 → 删除旧文件，走生成分支重建
          privFile.delete()
          pubFile.delete()
          val pair = AdbKey.generate()
          privateKeyEncoded = pair.private.encoded
          publicKey = pair.public as RSAPublicKey
          privFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
          pubFile.writeText(AdbKey.encodePublic(pair.public as RSAPublicKey, deviceName()))
      }
  }

  //【增】私有辅助：从 pub 文本解析出 wire 中的 n0inv 字段
  private fun wireN0Inv(text:String):Long{
      val bytes=Base64.getDecoder().decode(text.trim().split(" ")[0])
      val bb=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      bb.int;return bb.int.toLong() and 0xffffffffL
  }
  private fun expectedN0Inv(n:java.math.BigInteger):Long{
      val m=BigInteger.TWO.pow(32)
      return n.mod(m).modInverse(m).negate().mod(m).toLong()
  }
  ```

- 风险与注意事项：
  - **自愈=换 key**：已授权设备上重建后需重新授权（弹授权框），属预期行为，UI 提示即可。
  - privFile 单独损坏（pub 完好）场景：条件 `privFile.exists() && pubFile.exists()` 已覆盖——decode 失败同样走自愈。
  - `Base64.getDecoder().decode` 对非法字符抛 IllegalArgumentException——runCatching 捕获。
  - 需要导入 `java.math.BigInteger`（AdbKeyStore.kt 现无此 import，注意补）。
  - 重建分支与 BUG-001 的 encodePublic 新格式一致（encodePublic 已修复）。
- 验证方法：
  1. 单测：两文件写非法内容 → loadOrGenerate 不抛、成功重建新 key；再次调用幂等（publicKey != null 直接 return）。
  2. 旧格式回归：按旧公式（BE modulus、错误 n0inv）生成一份 pub 文件 + 合法 priv → loadOrGenerate 触发自愈重建。
  3. 实机：首次连接成功授权 → 手工损坏 adbkey.pub → 再次连接：不报"密钥生成失败"，自动重建并弹授权框。

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-1`（master 为稳定分支，源码修复不允许在 master 直接 commit）。
2. **实施顺序**：见总览后"建议实施顺序"——同文件 bug 集中改（AdbKey/AdbKeyStore → TuningCommands → AdbConnection → TuningExecutor/ViewModel → ProfileRepository → TunerApp → AppScanner → AdbProtocol），减少行号漂移与冲突。
3. **单测**：AdbKey/AdbProtocol/ProfileRepository 是纯 JVM 可测（AdbKey 编码、AdbProtocol 编解码、ProfileRepository 用临时目录），建议 Wang 在 `app/src/test/` 下补单测（需加 `org.json:json` 测试依赖或 androidTest，见 BUG-008 验证段）。ADB 连接类（AdbConnection）建议伪 socket 单测或仅实机验证。
4. **实机回归清单**（全部修完后一次性回归）：未授权首连（001/018：授权框弹出、自愈重建）→ 刷新状态（002/003/007：六段干净值）→ 并发操作（005：不串包）→ 断连重连（006）→ 授权等待超时（004：约 30s 报错可重试）→ 保存/删除 profile（008/015/016：未连接可保存、断电模拟原子写、StrictMode 干净）→ 应用部分项 profile（014）→ 恢复默认（011）→ 应用扫描（009/010：Beat Saber 在 VR 组、卸载竞态不崩）→ 中文输出（013）。
5. **行号漂移**：本方案行号基于基线 88674b2；同文件多 bug 按建议顺序改，每改完一个 bug 重新定位下一个的行号（以代码片段内容为锚点）。
6. **不建议本轮做的**：BUG-017（功能缺失非 bug，round 2 处理）；AdbKey.fingerprint 真协议化（展示用，无收益）；shell v2 协议（stderr 独立通道——当前 `; echo EXIT:$?` 方案够用且兼容性最好）。
