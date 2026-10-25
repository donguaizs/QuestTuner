# QuestTuner Round 9 修复方案（PLAN-round9）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round9.md（David，BUG-047~054 + R9-O1~O4）+ lead 决策（2026-10-25）
- 基线：master @ b3750c7；本轮在 `fix/round-9` 分支进行（BUG-REPORT 已在 c1c9d74 入库）
- 行号基于 c1c9d74 核对；【改】【删】【增】同前几轮惯例
- lead 决策摘要：BUG-047 只做 .gitignore（残渣 4 目录 David 已亲手删除并复核）；R9-O1 本轮**不做**记延后池；BUG-048 采纳报告选项 A；BUG-054 补 wrapper（distributionUrl 保持官方 https）。

## 总览（优先级即实施顺序）

| # | 来源 | 结论 | 涉及文件 | 预估 |
|---|---|---|---|---|
| ① | BUG-047 | 实施（仅 .gitignore 兜底 4 行） | .gitignore | 2min |
| ② | BUG-049 | 实施（选资产抽纯函数 + UpdateSafetyTest 补 3 用例） | SelfUpdater.kt、UpdateSafetyTest.kt | 8min |
| ③ | BUG-050+051 | 实施（条件断开 + 双重检查入锁） | BootReceiver.kt、TuningExecutor.kt | 8min |
| ④ | BUG-052+053 | 实施（save 条件清 + delete 对齐清） | TunerViewModel.kt | 6min |
| ⑤ | BUG-048 | 实施（选项 A：删 grantPermissions 行 + 恒假分支 + 重写注释） | A11yRoundTripTest.kt | 5min |
| ⑥ | BUG-054 | 实施（wrapper 入库 + README Build 节改写） | gradlew.bat、gradle/wrapper/*、README.md、README.zh-CN.md | 8min |
| ⑦ | R9-O4 | 实施（TODO-round-next.md 清账 + 环境备忘补 wrapper） | docs/plans/TODO-round-next.md | 3min |
| — | R9-O1 | **延后**，入 TODO 池（见⑦） | — | — |

R9-O2/O3 已并入②③；R9-O1 延后理由：Robolectric 环境刚稳定（round-8 两轮折腾），workingDir 配置未实测副作用，不冒险。残渣 4 目录删除由 David 已完成（字节取证 + git status 复核），本轮**不再**动工作区。

---

## ① BUG-047 .gitignore 兜底（P2，最优先——下次 git add 前必须生效）

- 涉及文件：`.gitignore`【增】
- 具体设计：文件末尾追加一段（与报告处置建议逐字一致）：

```gitignore
# Robolectric/jar 解包残渣（BUG-047：曾泄漏到仓库根，2026-10-24）
/META-INF/
/android/
/org/
/sqlite4java/
```

要点：路径模式**锚定仓库根**（前导 `/`）——`android/`、`org/` 这类通用名不锚定会误伤未来任何子目录下的同名目录（如 app/src/main/java/org/...）；`/META-INF/` 同理。app/ 与 docs/ 下不受影响。

- 验证方法：
  1. `git status --short` 零输出（工作区干净，残渣已被 David 清除且新条目不产生噪音）；
  2. 防再发演练（可选）：`mkdir org` → `git status` 应不显示 → 删除。
- 注意事项：不删既有条目；不动 keystore/ 段。**本条必须在其他任何 git add 之前完成**（防止万一再生的残渣被 add -A 卷入）。

---

## ② BUG-049 自更新资产选择偏好 release 变体（P2）

- 涉及文件：
  - `app/src/main/java/com/example/questtuner/update/SelfUpdater.kt`【改】【增】
  - `app/src/test/java/com/example/questtuner/update/UpdateSafetyTest.kt`【增】
- 具体设计：**抽纯函数**（lead 征询，结论=抽。理由：与既有 isTagSafe/checkHost/cleanStaleApksIn 纯函数风格一致；UpdateSafetyTest 是纯 JVM 测试，不抽函数无法对选择逻辑断言）。

SelfUpdater.kt:70-73【改】为调用新纯函数；在 isTagSafe/checkHost 附近（~:41）【增】：

```kotlin
/**
 * 纯函数（可单测）：从 GitHub Release assets 名称列表中选出目标 APK。
 * BUG-049：assets 数组顺序无保证（GitHub 按 asset id/上传完成序返回，v0.2.2 实测
 * debug 在前）——偏好 -release.apk 变体，fallback 任意 .apk；跳过 sources/mapping
 * 等明显非目标资产。找不到任何 apk 返回 null（checkLatest 上游按无资产处理）。
 */
fun selectApkAsset(names: List<String>): String? =
    names.firstOrNull { it.endsWith("-release.apk") }
        ?: names.firstOrNull { it.endsWith(".apk") && !it.contains("sources") && !it.contains("mapping") }
```

checkLatest 内【改】：

```kotlin
val asset = o.optJSONArray("assets")?.let { arr ->
    (0 until arr.length()).map { arr.getJSONObject(it) }
        .let { list -> list.firstOrNull { selectApkAsset(listOf(it.optString("name"))) != null } }
} ?: return@runCatching null
```

（实现备注：也可先 map 出 name 列表再 `selectApkAsset(names)` 后回查对应 JSONObject——Wang 按可读性择一，语义必须等价：**优先 -release.apk，其次首个非 sources/mapping 的 .apk**。）

UpdateSafetyTest.kt 末尾【增】（该文件为纯 JVM，无 Robolectric，直接加）：

```kotlin
// ---- selectApkAsset（BUG-049） ----

@Test
fun selectApkAsset_prefersRelease() {
    // v0.2.2 实测顺序：debug 在前（20MB）→ 必须选中 release
    assertEquals("QuestTuner-v0.2.2-release.apk", SelfUpdater.selectApkAsset(
        listOf("QuestTuner-v0.2.2-debug.apk", "QuestTuner-v0.2.2-release.apk")))
}

@Test
fun selectApkAsset_fallsBackToAnyApk() {
    assertEquals("QuestTuner-v0.3.0.apk", SelfUpdater.selectApkAsset(
        listOf("QuestTuner-v0.3.0.apk", "QuestTuner-v0.3.0-sources.jar")))
    assertEquals("a.apk", SelfUpdater.selectApkAsset(listOf("README.md", "a.apk")))
}

@Test
fun selectApkAsset_skipsSourcesAndMapping_andNull() {
    assertEquals("QuestTuner-v0.3.0-release.apk", SelfUpdater.selectApkAsset(
        listOf("x-mapping.txt", "QuestTuner-v0.3.0-release.apk", "y-sources.jar")))
    assertNull(SelfUpdater.selectApkAsset(listOf("x-sources.jar", "y-mapping.txt", "notes.md")))
    assertNull(SelfUpdater.selectApkAsset(emptyList()))
}
```

（需在文件头补 `import org.junit.Assert.assertNull`。）

- 注意事项：
  1. **不动 checkHost 白名单、四重校验、MAX_DOWNLOAD_BYTES**——纯选择层改动，安全面零变化；
  2. fallback 允许选中 debug.apk 是刻意的（单资产 Release 的老行为不变）；keystore 换签后签名校验会自然拒绝错签资产——本修复让"选错"不再发生；
  3. `endsWith("-release.apk")` 不误伤 `foo-release-notes.txt`（非 .apk 结尾）。
- 验证方法：`testDebugUnitTest` 全绿（38 = 35 旧 + 3 新）；针对性验证：对 v0.2.2 Release 真调 `checkLatest`（或临时打印 `ReleaseInfo.url`）→ browser_download_url 应为 `QuestTuner-v0.2.2-release.apk`（1.77MB 而非 20MB）。

---

## ③ BUG-050 + BUG-051 连接生命周期（P2，同轮打包）

### ③-a BUG-050 BootReceiver 收尾条件断开

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/BootReceiver.kt`【改】
- 具体设计：按报告处置建议，BootReceiver.kt:39-40【改】：

```kotlin
            // BUG-050：收尾必须条件断开——connection 是全局单例，若用户恰在 30s
            // 恢复窗口内显式连接（会话已激活），无条件 disconnect 会踢断用户连接、
            // disableWifiAdbIfWeEnabled 会关掉用户 connect 流程刚开启的无线调试。
            // 非会话态（用户未连接）→ 维持原清理语义。
            if (!TuningExecutor.adbSessionActive) {
                TuningExecutor.disconnect()
                SecureAutomation.disableWifiAdbIfWeEnabled(appContext)
            }
```

要点：
  1. `adbSessionActive` 是 BUG-027 已有的会话标志（TuningExecutor.kt:42），用户 connect 成功路径会 `markSessionActive()`（TunerViewModel.kt:151）——语义精确匹配"用户显式连接过"；
  2. BootReceiver 自身保持不 markSessionActive（设计如此，文件头注释已明确，**不改**）；
  3. ok 路径下恢复连接非会话态，`if` 成立 → 依旧收尾断开，开机恢复语义不变。
- 验证方法：代码走查为主（真机双触发源难复现）：确认 disconnect 前有 `!adbSessionActive` 守卫、markSessionActive 仅存在于用户 connect 路径（grep 验证）。可选实机项（记入实机清单）：restoreOnBoot=ON + 重启后 30s 内进 app 点连接 → 连接不被踢断。
- 注意事项：不改动 while 轮询循环本身（BUG-027 已界定轮询失败自清理只关自己实例，安全）。

### ③-b BUG-051 ensureConnected 双重检查入锁

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/TuningExecutor.kt`【改】
- 具体设计：采用报告"最小改动"分支（赋值前重检 + 泄漏实例关闭），ensureConnected.kt:61-91 改为：

```kotlin
    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext true   // 快路径：已有连接直接返回（读竞态无害）
        val keyStore = AdbKeyStore(context)
        runCatching { keyStore.loadOrGenerate() }.onFailure {
            lastError = "密钥生成失败: ${it.message}"
            return@withContext false
        }
        val conn = AdbConnection("127.0.0.1", 5555)
        // BUG-051：检查+connect+赋值必须整体在锁内才是完整的双重检查锁定——
        // 原实现第二条并发连接会在锁外覆盖第一条，首条实例悬挂泄漏。
        val result = runCatching {
            adbMutex.withLock {
                if (connection != null) {
                    conn.close()      // 输给了别的并发调用方：关掉自己多建的实例
                    return@withLock true
                }
                withTimeout(35_000L) {
                    conn.connect(keyStore).getOrThrow()
                }
            }
        }
        if (result.isSuccess) {
            // BUG-051：赋值前重检（锁内）——disconnect 竞态窗口下旧实例不覆盖、不泄漏
            adbMutex.withLock {
                if (connection == null) {
                    connection = conn
                    _connectionState.value = true
                    lastError = null
                    lastFailureWasRefused = false
                } else {
                    conn.close()
                }
            }
            true
        } else {
            val e = result.exceptionOrNull()
            lastError = e?.message ?: "connect failed"
            // A11：拒连=adbd 未就绪（可重试）；超时/IOException=AUTH 等待或链路问题（不重试）
            lastFailureWasRefused = e is java.net.ConnectException
            conn.close()
            false
        }
    }
```

（若 Wang 觉得两段锁冗余，可简化为**整个流程一把锁**：`adbMutex.withLock { if (connection != null) return@withContext true; connect; 赋值 }`——代价是并发第二调用方会阻塞等待第一条连完再发现已有连接，行为同样正确且更简单，**推荐此简化版**；上面展开版仅作语义说明。注意 `return@withContext` 不能写在 `withLock` lambda 里，需重构为锁内局部变量返回。）

要点：
  1. 快路径锁外读 `@Volatile connection` 保留（性能无损，竞态输家走锁内慢路径兜底）；
  2. `withTimeout` 只包 connect（维持原语义，35s 上限不因等锁而变化——等锁时间在 withTimeout 之外）；
  3. 输家路径 `conn.close()` 防泄漏，返回 true（连接确实存在，只是不是自己的实例）。
- 验证方法：编译 + 既有 35 用例全绿（TuningExecutor 无既有单测，行为回归靠编译 + 实机连接冒烟：connect 成功 → 断开 → 重连成功）；并发单测评估结论：**不加**——需注入 AdbConnection 假实现（现为直接 new 的具体类），为测试改生产结构不值当，如实记代码评审级验证。
- 注意事项：`interpretShellResult` 的 `if (connection === conn)` 引用比对逻辑不变；disconnect() 不加锁（现状即为 fire-and-forget 语义，收口靠 ③-a 与赋值前重检）。

---

## ④ BUG-052 + BUG-053 编辑态回填对称缺陷（P3，一并修）

- 涉及文件：`app/src/main/java/com/example/questtuner/ui/TunerViewModel.kt`【改】
- 具体设计：

saveProfile（:365-378）【改】回填处：

```kotlin
            }.let { ps ->
                _state.value = _state.value.copy(
                    profiles = ps,
                    // BUG-052：条件清——只退出"自己这次保存"的编辑态；保存 IO 期间用户
                    // 已点开另一个编辑（editingProfile 已换引用）则保留之
                    editingProfile = _state.value.editingProfile?.takeIf { it != savedRef },
                )
            }
```

前置：saveProfile 签名不动，函数体首行【增】`val savedRef = p`（协程 lambda 内捕获；`p` 参数被捕获后不会被重绑定，直接用 `it != p` 也行——Wang 择一，语义=与**保存时传入的引用**比较）。

deleteProfile（:402-411）【改】回填处：

```kotlin
            }.let { ps ->
                _state.value = _state.value.copy(
                    profiles = ps,
                    // BUG-053：删除的是当前编辑对象 → 同步退出编辑态，防止悬挂引用
                    // 让 NewProfileCard 停留编辑态、点保存"复活"已删除的 profile
                    editingProfile = _state.value.editingProfile?.takeIf { it?.packageName != pkg },
                )
            }
```

要点：
  1. save 用**引用比较**（`it != p`，AppProfile 是 data class，同内容不同实例也会判等——这里恰是期望行为：保存 A 后又点编辑 A'（内容同）不该被误清？注意：data class 相等即视为同一编辑目标，窗口内用户点编辑 B（内容不同）必不被清；点编辑同内容 A' 被清属可接受的边界，概率趋零且后果=回新建模式，无害）；
  2. delete 用 **packageName 比较**：UI 删除入口传 pkg 字符串（TunerApp.kt ProfileRow onDelete），与 editingProfile.packageName 对齐即可；编辑中用户改过包名输入框也不影响（editingProfile 原始引用的 packageName 才是被删锚点）；
  3. Compose 层 `remember(edit)` 键控**不改**——editingProfile 引用变 null 即触发重置，正是 BUG-041 机制的正确复用；
  4. 不做"保存期间禁用编辑入口"（报告备选方案）——多改 UI 层且条件清已足够。
- 单测评估（lead 征询，结论=**不加 TunerViewModel 单测**）：VM 是 AndroidViewModel，编辑态断言需构造 Application + Robolectric 环境，且 saveProfile 是 `viewModelScope.launch` 异步——同步断言要么引入 Main dispatcher 注入（改生产构造），要么轮询 StateFlow（脆弱）。收益/成本比低于既有 35 用例平均水平，本轮以代码评审级验证；若未来做编辑三旅程 UI 测试（延后池第一优先），届时在 EditJourneyUiTest 补"保存中点编辑"旅程即可（已记入 TODO⑦）。
- 验证方法：代码评审级（如实记录）：确认两处回填的条件清存在且比较键正确（save=引用/相等、delete=packageName）；`testDebugUnitTest` 既有 35 用例全绿保回归。手工走查时序表：保存中点编辑B→编辑B保留✓；删编辑中X→回新建✓；正常保存→退出编辑✓。
- 注意事项：两处改动都在 `.let { ps -> }` 回填 lambda 内，勿动 IO 块；文案与注释保持与 BUG-041 既有注释风格连贯。

---

## ⑤ BUG-048 A11yRoundTripTest 权限授予清理（P3，选项 A）

- 涉及文件：`app/src/test/java/com/example/questtuner/tuner/A11yRoundTripTest.kt`【改】
- 具体设计（lead 已拍板选项 A）：

setup()（:28-54）改为：

```kotlin
    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ctx = app
        // BUG-048：权限授予唯一承重层 = PackageInfo 手术（把 WRITE_SECURE_SETTINGS 写进
        // requestedPermissions + flags 置位后 shadowPm.installPackage）。探针实验证明：
        // 仅 shadowOf(app).grantPermissions(...) 不生效——SecureAutomation.hasWriteSecureSettings
        // 走 PackageManager.checkPermission 路径（SecureAutomation.kt:33-35），4.12 的
        // grantPermissions 对该路径无效。此手术是 Robolectric 4.12 无 grantPermission(String,String)
        // 下的未文档化 Shadow 行为依赖：**删改前先跑临时探针用例确认 NoPermission 门禁仍可放行**，
        // 否则 4 个用例全红且报错（CapProbe.NoPermission）不指向权限层，难排查。
        val pm = app.packageManager
        val pi = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val perms = pi.requestedPermissions?.toMutableList() ?: mutableListOf()
        if (!perms.contains(android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
            perms.add(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            pi.requestedPermissions = perms.toTypedArray()
        }
        val granted = pi.requestedPermissionsFlags ?: IntArray(perms.size)
        // REQUESTED_PERMISSION_GRANTED = 1<<1；flags 数组与权限表逐位对齐置位
        for (i in perms.indices) {
            if (i < granted.size) granted[i] = granted[i] or 2
        }
        pi.requestedPermissionsFlags = granted
        val shadowPm = shadowOf(pm)
        shadowPm.installPackage(pi)
        Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", "")
    }
```

三点改动：
  1. 【删】:52 `shadowOf(app).grantPermissions(...)` 一行（探针证明单独无效，留着误导）；
  2. 【删】:47 `else if (i == granted.size) {}` 恒假死分支（循环边界 `i < perms.size` 且 `granted.size == perms.size` 使其永不成立，空体无操作，纯噪音）；
  3. 【改】注释整段重写（上面代码块内）：明确 PackageInfo 手术承重、checkPermission 路径原因、"删改前先跑探针"警告——修正原注释"grantPermissions 对 ShadowContextImpl 生效"的错误归属。
- 注意事项：
  1. **只动 setup，4 个 @Test 方法一个字符都不动**；
  2. 原 ROBOLECTIC 教训不受影响：不引入任何新依赖、不动 build.gradle.kts、不动 user.home/maven.repo.local 配置；
  3. `import org.robolectric.Shadows.shadowOf` 仍被 :50 使用，**保留 import**（勿顺手删）。
- 验证方法（lead 要求：Wang 修完须确认 4 用例仍绿）：`gradle :app:testDebugUnitTest --tests "com.example.questtuner.tuner.A11yRoundTripTest"` 4/4 绿，再全量 `testDebugUnitTest` 38/38。破坏性复核（可选，与 round-8 探针同法）：临时注释 :39-42 的 perms.add 块 → enable 应红于 NoPermission——证明承重层真的是手术段。

---

## ⑥ BUG-054 gradle wrapper 入库（P3，lead 决策=补）

- 涉及文件：
  - 【增】`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`、`gradle/wrapper/gradle-wrapper-main.jar`（8.7 生成物以实际为准）
  - 【改】`README.md:41-48`（Build 节）、`README.zh-CN.md:41-48`
- 具体设计：

（1）生成（在仓库根执行，用 toolchain gradle——它就是 Gradle 8.7 本体）：

```powershell
cd F:\workspace\QuestTuner
F:\workspace\toolchain\gradle-extract\gradle-8.7\bin\gradle.bat wrapper --gradle-version 8.7
```

（2）distributionSha256Sum：**已实算**——`F:\workspace\toolchain\gradle-8.7-bin.zip` 确认为 bin 变体（134,184,980 字节，文件名 -bin.zip，gradle-extract 解包源即此 zip），SHA-256：

```
544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d
```

生成的 `gradle/wrapper/gradle-wrapper.properties`【改】为：

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
distributionSha256Sum=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

（lead 决策：distributionUrl 保持官方 https；本项目 GRADLE_USER_HOME 已重定向 F:\workspace\toolchain\gradle-home，下载一次后全团队共享。）

（3）README Build 节（README.md:41-48）【改】：

```markdown
## Build

Requirements: **JDK 17**, **Android SDK** (compileSdk 34).

```bash
.\gradlew.bat :app:assembleDebug        # Windows (or ./gradlew on Linux/macOS)
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper (8.7, SHA-256 pinned) downloads Gradle automatically on first run —
an internet connection is required once. Fully offline builds can point
`distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to a local
`file:///` copy of `gradle-8.7-bin.zip`, or use a preinstalled Gradle 8.7
(`gradle :app:assembleDebug`) as fallback.
```

README.zh-CN.md:41-48 同构改写（构建要求删去 "Gradle 8.7+" 改为 wrapper 说明；主命令 `.\gradlew.bat :app:assembleDebug`；补一句：首次运行需联网下载 Gradle 8.7（SHA-256 已固定）；完全离线环境可将 distributionUrl 指向本地 file:// 副本，或退回使用预装 Gradle 8.7）。

- 验证方法：
  1. 全新校验：删/换 GRADLE_USER_HOME 下的 wrapper dists 缓存（或临时 GRADLE_USER_HOME=F:\workspace\tmp\gw-test）→ `.\gradlew.bat --version` → 应显示 Gradle 8.7 且 sha256 校验通过；
  2. `.\gradlew.bat :app:assembleDebug` 成功，产物与 toolchain gradle 构建等价；
  3. `git status` 只出现 wrapper 新文件（.gitignore 不误伤：确认无 `*.jar` 全局忽略——现 .gitignore 无此条，gradle-wrapper.jar 可正常入库）。
- 注意事项：
  1. **wrapper 任务会触达 gradle daemon**——若遇 BindException 先 `gradle --stop`（环境备忘既有条目）；
  2. 生成后**逐字核对** gradle-wrapper.properties 的 distributionSha256Sum 与上面实算值一致（wrapper 任务不会自动填，需手改）；
  3. local.properties 已被 ignore，不受影响；`.gradle/` 已 ignore，wrapper 生成的工作目录文件不入库；
  4. 本机已验证过 zip 是 bin 变体，但 Wang 生成后应顺手复核 `gradle --version` 与 8.7 一致。

---

## ⑦ TODO-round-next.md 同步更新（R9-O4）

- 涉及文件：`docs/plans/TODO-round-next.md`【改】
- 具体设计（三处）：
  1. **遗留技术项**【增】一条（置于"编辑三旅程"之后）：`- **R9-O1 测试 JVM workingDir 固化**：testOptions.unitTests.all { workingDir = build/tmp/robolectric-cwd }——BUG-047 兜底根治半步；Robolectric 环境刚稳定，待编辑三旅程 UI 测试落地后再动（需先实测对 4.12.2 无副作用）`；同时在"编辑三旅程"条目补一句：`（届时顺带补"保存中点编辑"旅程用例——BUG-052 的 UI 级回归）`；
  2. **实机回归清单引用处**【增】：BUG-050 条件断开真机项（restoreOnBoot=ON + 30s 窗口内显式连接不被踢断）并入 round-6 附录实机清单的执行备注（一行即可，不重排 33 条）；
  3. **环境备忘**【增】两条：
     - `wrapper：仓库已入库 gradle wrapper（8.7，distributionSha256Sum 固定）；日常构建用 .\gradlew.bat，离线/兜底用 F:\workspace\toolchain\gradle-extract\gradle-8.7\bin\gradle.bat（distributionUrl 指本地 file:// 的改法见 README Build 节）`
     - `wrapper 首跑若卡在下载：GRADLE_USER_HOME=F:\workspace\toolchain\gradle-home 已有完整 dists 缓存，勿删`
- 验证方法：通读一遍，确认 8 轮痕迹引用（docs/bugs、docs/plans、docs/verification）未被改动；新条目与 PLAN-round9 术语一致。

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-9`（现 HEAD c1c9d74）。commit 中文 conventional（docs/fix/test 前缀按改动性质），建议每项一 commit 或按 ①⑦ / ② / ③ / ④ / ⑤ / ⑥ 分组。
2. **实施顺序**：①（2 分钟，git add 防线）→ ⑤（测试清理，先确保 4 用例基线绿）→ ②（纯函数+测试）→ ④（VM 回填）→ ③（连接生命周期，走查验证）→ ⑥（wrapper，可能吃 daemon 波动，放后）→ ⑦（TODO 收尾）。
3. **生产代码改动范围**：SelfUpdater.kt、BootReceiver.kt、TuningExecutor.kt、TunerViewModel.kt 四个文件——全部是行为缺陷修复，无新依赖、无签名/权限/构建配置变化；release 体积预期不变（可顺带核对）。
4. **测试数量预期**：35 旧 + ②新增 3 = 38 用例全绿（BUG-048 只删不加）。
5. **不动项清单**：.gitignore 只追加不重排；A11yRoundTripTest 的 @Test 方法；TunerApp.kt（BUG-053 的 remember(edit) 不改）；build.gradle.kts（R9-O1 延后）；master 分支。
6. **时间盒**：约 35 分钟。若 ⑥ wrapper 下载校验卡网络，允许先入库生成物+手改 sha256 并以 `--version` 冒烟，完整 dists 校验记入 VERIFY 补做——不阻塞其余项交付。
