# TODO（round-next）

> 本文件为 2026-10-25 09:00 迭代循环收尾时的最终遗留池。8 轮迭代的完整 QA 痕迹见 docs/bugs、docs/plans、docs/verification。

## 发布检查单（每次打 tag 前执行，源自 BUG-043/BUG-044 教训）
1. `app/build.gradle.kts`：versionCode +1、versionName 按语义化递增
2. 恒等式核对：即将打的 tag 名 == "v" + versionName（自更新比较用 versionName）
3. `assembleRelease` 后 `aapt2 dump badging app-release.apk | grep versionName` 确认内嵌值
4. **资产一致性验证（BUG-044 修订版，可执行）**：Gradle 构建非字节级可复现（zip 容器层差异），
   **禁止用整体 SHA-256 比对**。正确做法（R7-O1）：
   a. 上传后从 Release 页**下载回**资产，与本地构建产物比对 SHA-256（防上传截断/替换）
   b. 或用 GitHub API 的 asset digest 字段核对
   c. 语义等价核验：`aapt2 dump badging` 两侧一致 + `apksigner verify --print-certs` 证书一致
5. 换 release keystore 前重读 PLAN-round2 P3 的 TODO(lead)（换签 = 已装用户自更新校验失败，需迁移策略）

## 遗留技术项（收尾时最终状态）
- **编辑三旅程 Compose UI 测试（round-8 降级项，优先级最高）**：测试代码框架已在 PLAN-round8.md（5 用例设计完整），阻塞点是 Compose+Robolectric 环境适配；重试时注意 round-8 沉淀的两条环境教训（android-all-instrumented 严禁入 testImplementation；user.home/maven.repo.local 重定向 F:\workspace\toolchain\robolectric-home）（届时顺带补"保存中点编辑"旅程用例——BUG-052 的 UI 级回归）
- **R9-O1 测试 JVM workingDir 固化**：testOptions.unitTests.all { workingDir = build/tmp/robolectric-cwd }——BUG-047 兜底根治半步；Robolectric 环境刚稳定，待编辑三旅程 UI 测试落地后再动（需先实测对 4.12.2 无副作用）
- **R5-O1 图标 LruCache（半步，~30 行）**：滚动卡顿最可能源；虚拟化继续延后
- **R4-O7 adbStatus 覆盖链去重**：UI 状态层重构时（round-5 034 修复后收益进一步降低）
- **OVR Metrics 三常量实机校准（A7）**：转实机任务，验证脚本见 PLAN-round2，清单在 BUG-REPORT-round6 附录
- **keystore 迁移**：产品决策项（当前用户量小，换签窗口成本最低；决定后按检查单第 5 条执行）
- ~~R5-O2 双源标记收窄~~（round-7 审计**关闭**：round-4 对账已结构性解决，再收窄零收益）
- ~~BUG-017 应用扫描结果无 UI 入口~~（round-2 AppPickerDialog 已修）
- 实机回归总清单（33 条，7 组分类）：BUG-REPORT-round6 附录——装到真机后按清单走一遍即可完成全部实机验证；执行备注：round-9 BUG-050 条件断开补一条实机项（restoreOnBoot=ON + 重启后 30s 恢复窗口内进 app 点连接 → 连接不被踢断）

## 环境备忘（本项目构建/测试已知坑）
- 构建：JAVA_HOME=F:\workspace\toolchain\jdk-extract\jdk-17.0.20.1+1，GRADLE_USER_HOME=F:\workspace\toolchain\gradle-home，TEMP/TMP 必须重定向 F:\workspace\tmp（用户 Temp 目录 ACL 问题）
- Robolectric：user.home 与 maven.repo.local 重定向 F:\workspace\toolchain\robolectric-home（android-all jar 已离线预取在该 .m2）
- Gradle daemon 偶发 BindException：gradle --stop 后重试
- wrapper：仓库已入库 gradle wrapper（8.7，distributionSha256Sum 固定）；日常构建用 .\gradlew.bat，离线/兜底用 F:\workspace\toolchain\gradle-extract\gradle-8.7\bin\gradle.bat（distributionUrl 指本地 file:// 的改法见 README Build 节）
- wrapper 首跑若卡在下载（本机网络对 services.gradle.org 可能不可达）：可用离线法——临时把 distributionUrl 指向 file:///F:/workspace/toolchain/gradle-8.7-bin.zip（SHA-256 与 properties 固定值一致，round-9 已实测走通 sha256 校验+解包），或预装 Gradle 8.7 兜底；GRADLE_USER_HOME=F:\workspace\toolchain\gradle-home 目前无 dists 缓存，勿指望直接命中
