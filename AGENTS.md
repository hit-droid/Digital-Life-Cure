# AGENTS.md — 数字生命（Digital-Life-Cure）项目交接文档

> 本文件写给**一个完全不了解情况的新会话**。请先完整读一遍再动手。
> 最后更新：2026-10-03（版本 v1.128.0，多智能体协作台账 + 控制台「多智能体」Tab；协作约定见第 9 节）

---

## 0. 三十秒速览

这是一个 Android 智能体 App「数字生命」，AI 角色叫**小汐**。
2026-08-31 我搭了一套**无人值守自治流水线**：三个后台进程自动产出版本、跑 CI、发行 GitHub Release，一直跑到 **2026-09-07** 自动停止。

**如果你接手时流水线还在跑**：别动手改 `/workspace`，先看第 6 节确认进程状态。
**如果流水线已经死了**：第 7 节有完整的重建步骤（`tools/auto/` 里已经存了全部脚本）。

> ⚠️ **本仓库有多个写入者**（`trae` / `workbuddy` 等 AI agent，另有 `monkeycode-keepalive[bot]` 等自动化提交）。
> **动手前必读第 9 节「协作约定」**——写入通道、发布负责人、任务认领、领地和提交纪律都在那里。

---

## 1. 项目目标与完成程度

### 1.1 目标
把「数字生命」App 持续完善为智能体产品。2026-08-31 起进入无人值守自主开发模式：自建流水线持续产出版本并发行，用户要求**一直运行到 2026-09-07**。

### 1.2 技术约束（硬约束，别违反）
| 约束 | 说明 |
|---|---|
| 无 AndroidX / 无 Compose | 纯 Java + View 系统 |
| **无 XML 布局** | **整个 UI 全部用 Java 代码构建**（`res/` 下只有 drawable/values/xml，没有 layout/）。改界面必须改 Java |
| 包名 | `com.digitallife` |
| minSdk / targetSdk | 21 / 34，compileSdk 34，JDK 17 |
| commit 格式 | 中文一行式（多行正文更佳）。**不要追加 `Co-authored-by`**——2026-10-03 用户明确要求不再加猴码机器人署名 |
| push 身份 | committer 必须设为 `hit-droid@users.noreply.github.com` |

### 1.3 完成程度

- **已完成**：v1.0 → v1.128.0；v1.102.0 ~ v1.110.0 OpenMinis 对标手工轮；v1.111.0 多智能体；v1.112.0 对话大脑改 DeepSeek Harness；v1.113.0 Harness 内核化；v1.114.0 Robolectric 测试体系 + 低版本兼容修复；v1.115.0 Profile 组装 / 可卸载插件 / 提示词分段 / 上下文压缩 seam；v1.116.0 修 Live2D 模型文件缺失导致的原生 SIGSEGV；v1.117.0 多智能体并行编排（`delegate_parallel` fan-out/fan-in、失败隔离、并行协作卡片）；v1.118.0 工程化加固（CI 单测门禁、测试代理注入、版本号去硬编码、清理误入库脚本）；v1.119.0 ChatActivity 纯逻辑下沉（ChatTextOps/HistoryBudget/FailoverPolicy/SuggestionEngine）+ 46 单测；v1.120.0 记忆闭环（查询相关召回、对话接入长期记忆、自动提取启用、解析下沉 MemoryExtractionParser）；v1.121.0 记忆召回修正（统一关键词提取到 MemoryRelevance，修对话大脑中文相关召回失效）；v1.122.0 MCP 客户端协议修正（修 session 被清零、补 notifications/initialized、SSE 按事件解析并抽 McpResponseParser、JSON-RPC id 改用 AtomicLong）；v1.123.0 历史会话时间条改用真实时间戳（修「时间条显示的是打开会话时间」且紧循环下只插得出第一条；workbuddy PR #9）；v1.124.0 多智能体深化（新增 `delegate_pipeline` 有序依赖链编排：逐步串行、前序结论透传、失败即停、步数上限；复用并行卡片 UI，不改 ChatActivity）；v1.125.0 表格单元格支持行内 Markdown（`MarkdownRenderer.stripInline` 先剥标记再算列宽，修「标记占宽度把对齐撑歪」；workbuddy PR #12）；v1.126.0 多智能体补齐 writer/critic 子智能体（`delegate_pipeline` 可跑「研究→写稿→审校」；两者只读白名单、无递归委派）；v1.127.0 消息支持「引用回复」（长按菜单，引用块纯逻辑下沉 `ChatTextOps.buildQuote`；workbuddy PR #16）；v1.128.0 多智能体协作台账（新增 `SubagentLedger` 环形缓冲，`SubagentRunner.run` 一处埋点覆盖串行/并行/依赖链且所有出口留痕；控制台新增第 5 个 Tab「多智能体」，顺带修顶栏写死版本号；PR #20）
- **OpenMinis 对标手工功能**（2026-09-05，我亲自写的）：
  | 版本 | 功能 | 说明 |
  |---|---|---|
  | v1.104.0 | 模型组自动降级 | 同 scope 多配置降级链，网络错/401/403/408/409/429/5xx 自动切备用重发（`ChatActivity.tryModelFailover`） |
  | v1.105.0 | web_fetch 网页抓取 | BuiltinTools 新工具，URL → 纯文本正文 |
  | v1.106.0 | 真实 web_search | DDG lite HTML 解析，替代返回 Bing URL 的桩 |
  | v1.107.0 | 定时任务调度器 | TaskScheduler + AlarmManager 非精确闹钟 + TaskReceiver（goAsync + 90s 唤醒锁）+ 3 工具 |
  | v1.108.0 | 数据备份恢复 | BackupManager zip（databases/shared_prefs 白名单）+ 恢复前安全备份 + 路径穿越防护 + 3 工具 |
  | v1.109.0 | SKILL.md 技能包 | SkillManager + assets 内置 deep_research/daily_brief + skill_summary/load_skill |
  | v1.110.0 | **对话大脑工具调用循环** | ChatActivity 聊天模式启用 function calling：17 个工具全接入，assistant(tool_calls)+tool 拼消息链、空文本自动续轮（上限 6 轮防死循环）；Tools 支持 coreOnly 纯宿主构造（不含桌宠表情工具）；extra.tools_desc 引导模型决定何时调工具 |
  | v1.112.0 | **DeepSeek Harness** | 对话大脑改为 everything-is-a-plugin：`com.digitallife.harness`（session log / prompt assembler / tool pipeline / agent-loop / guard），ChatActivity.startChatLoop 走 `DeepSeekHarness.startTurn` |
  | v1.113.0 | **Harness 内核化** | LLM seam（`LlmAdapter`）使循环可脚本化驱动；85 个内核单测；子智能体改独立 harness 树 + `SubagentPreset`/`ScopedTools`（删 `AgentTeam`）；CareAI 收敛并新增 `CareToolHost`；AICore 工具走 `ToolPipeline`（修回断掉的 `ToolUsageLog`）；删死代码 `AgentBrain` |
  | v1.114.0 | **测试体系 + 兼容修复** | Robolectric 接入（110 单测）；修 6 处 minSdk 21 崩溃点与 20 处 locale 敏感调用；`ToolGovernance` 统一装配策略；`tools/verify.sh` 一条命令全量验证 |
  | v1.115.0 | 架构 seam | Profile 组装 / 可卸载插件 / 提示词分段 / 上下文压缩 |
  | v1.116.0 | 原生崩溃修复 | 修 Live2D 模型文件缺失导致的 SIGSEGV |
  | v1.117.0 | **多智能体并行编排** | `delegate_parallel` fan-out/fan-in，最多 5 个子任务真并发 + 失败隔离 + 逐任务超时；并行协作卡片按「agent + 任务原文」索引；`Tools` 支持对象数组 schema |
  | v1.118.0 | **工程化加固** | CI 增加单测门禁（此前 CI 只编译、138 个单测从不拦回归）；`app/build.gradle` 自动把代理环境变量注入测试 JVM（修 `tools/verify.sh` 在代理环境下必挂）；修两处写死版本号（`DeveloperActivity`/`OperitDrawer` 曾硬编码 v1.24.0）；删除误入库的临时脚本（含明文 PAT） |
  | v1.119.0 | 纯逻辑下沉 | ChatActivity 2661 → 2490 行，抽出 `ChatTextOps`/`HistoryBudget`/`FailoverPolicy`/`SuggestionEngine` 四个纯逻辑类 + 46 单测 |
  | v1.120.0 | **记忆闭环** | `MemoryPlugin` 支持查询相关召回（此前永远传空串）；对话大脑消息接入 `MemoryStore`（此前只写 ChatStore）；`MemoryExtractor.schedulePeriodic` 由 `PetService` 真正调度（此前从无调用）；解析下沉 `MemoryExtractionParser` + 19 单测 |
  | v1.121.0 | **记忆召回修正** | 发现"相关召回"有两套分叉实现：桌宠侧完善、对话侧用整句 `contains`（中文必空）。抽 `MemoryRelevance` 统一，顺带修"高重要度 5 条"实为"最近高权重"。+13 单测 |
  | v1.122.0 | **MCP 协议修正** | `initialize()` 曾把刚取到的 `Mcp-Session-Id` 清零（遵协议的远端服务器连不上）；补 `notifications/initialized`；SSE 由"多帧拼接"改为按事件切分并优先取 id 匹配帧（`McpResponseParser`）；JSON-RPC id 由 `nanoTime()` 改 `AtomicLong`（避免 double 精度失真）。+15 单测 |
  | v1.123.0 | 时间条真实时间戳 | 历史恢复时 `appendTimeDividerIfNeeded` 一律取 `System.currentTimeMillis()` → 时间条显示"打开会话的时间"，且紧循环下相邻间隔恒 <5min，只有第一条插得出。改为透传 `StoredMsg.timestamp`（`appendUserBubble`/`appendAiBubble` 加 `long ts` 重载），间隔比较用 `Math.abs`。workbuddy PR #9 |
  | v1.124.0 | **依赖链编排** | 此前只有 `delegate_task`（单个顺序）/`delegate_parallel`（互不依赖并发），缺"有先后依赖"的多步。新增 `delegate_pipeline`：逐步串行、前序结论截断后注入下一步、失败即停（后续标"未执行"）、步数上限 5。复用 `SubagentRunner` 隔离/超时与并行卡片 UI，**不改 `ChatActivity`**。+15 单测 |
  | v1.125.0 | 表格行内 Markdown | 表格是等宽纯文本渲染，单元格里 `**加粗**`/`` `代码` `` 既渲染不出又按字面占列宽把对齐撑歪。新增 `MarkdownRenderer.stripInline`，`appendTable` 先剥标记再算列宽；单/双星号顺序处理，不成对星号不误吃。+12 单测（该渲染类首个测试）。workbuddy PR #12 |
  | v1.126.0 | 角色补齐 | `delegate_pipeline` 有了依赖链却没有对口角色：补 `writer`（撰稿人）+`critic`（审校员），两者只读白名单（memory/skill）、**无 `delegate_task`**（保持子智能体不可递归委派）、步数受限。「研究→写稿→审校」一条链跑通。+1 单测 |
  | v1.127.0 | 引用回复 | 气泡长按菜单新增「引用回复」，引用块下沉 `ChatTextOps.buildQuote`（每行 `> ` 前缀、默认最多 4 行、超出补 `> …`、结尾换行、引前剥折叠提示）；两处菜单索引顺延。不改气泡布局。+10 单测。workbuddy PR #16 |
  | v1.128.0 | 多智能体协作台账 | 委派过程此前完全不可见（控制台只有四个 Tab，`SubagentRunner` 不写 `BrainLog`）。新增 `SubagentLedger`（纯 Java 环形缓冲上限 200，记 agent/任务/成败/错误/耗时，任务折叠换行截断 60 字）；`SubagentRunner.run` **一处埋点**即覆盖串行/并行/依赖链，且未知 preset、空任务、无模型配置、超时、报错、正常返回**所有出口都留痕**；控制台新增第 5 个 Tab「多智能体」（汇总 + 倒序明细，角标计总次数），顺带把顶栏写死的 `v1.115.0` 改成读真实 `versionName`。+10 单测。trae PR #20 |
- **待发行**：无
- **流水线寿命**：2026-09-07 00:00（时间戳 `1788739200`）自动停止

---

## 2. 目录结构与关键文件

### 2.1 仓库结构
```
/workspace                          ← 主工作区（autoloop 独占，见第 4 节警告）
├── app/
│   ├── build.gradle                ← 版本发版入口：versionCode / versionName
│   └── src/main/
│       ├── java/com/digitallife/   ← 全部源码（125 个 java 文件）
│       ├── res/
│       │   ├── drawable/           ← 只有 drawable，没有 layout！
│       │   ├── values/ colors.xml  ← 配色定义
│       │   └── xml/
│       └── AndroidManifest.xml
├── .github/workflows/build.yml     ← CI：push main 自动编译 + 自动发 Release
├── tools/auto/                     ← 【本轮新增】流水线脚本备份（重装用）
│   ├── autoloop.sh                 ← 流水线主脚本
│   ├── taskgen.sh                  ← 任务生成调度
│   ├── watchdog.sh                 ← 看门狗自愈
│   ├── genpatch.py                 ← 机械改进补丁生成器
│   └── mkpatch.py                  ← 安全补丁生成助手（隔离区工作流）
├── tools/local-build.sh            ← 【v1.113.0】本地构建（免 Android Studio）
├── app/src/test/java/              ← 【v1.114.0 起】内核 + 纯逻辑单测（216 个 / 18 类）
└── AGENTS.md                       ← 本文件
```

### 2.1.1 本地构建环境（v1.113.0 起可用）

本容器原本没有 JDK / Android SDK，只能推 CI 验证（一轮约 3 分钟）。现已装好并固化为脚本：

```sh
./tools/local-build.sh                # 编译 Java（增量约 10 秒）
./tools/local-build.sh testDebugUnitTest   # 跑 216 个单测
./tools/local-build.sh assembleDebug       # 出 APK
```

| 组件 | 路径 |
|---|---|
| JDK 17 | `/tmp/opencode/toolchain/jdk-17.0.20.1+1` |
| Android SDK | `/opt/android-sdk`（platform-34 / build-tools-34.0.0 / NDK 26.1.10909125） |
| Gradle | 8.7（wrapper 已缓存） |

**注意**：`/tmp/opencode/toolchain` 在重启后会消失，届时重跑第 1 步（见 CHANGELOG v1.113.0）或重装 JDK。
`app/release-key.p12` 本地需自行生成（CI 里由 workflow 自动生成，`.gitignore` 已忽略 `*.p12`），否则 `assembleDebug` 会报 `validateSigningDebug` 失败。

#### 测试能力边界（v1.114.0）

| 能做 | 不能做 |
|---|---|
| 编译、单测、lint、出 APK（`tools/verify.sh`，约 41 秒） | **真机/模拟器运行测试** |
| Robolectric 跑真实 Android 框架（Activity/Context/SharedPreferences 等） | 依赖 native 库的代码（`Live2DNative` 在 JVM 加载不了） |
| 纯 Java 逻辑与 Android 框架交互的回归验证 | 渲染、动画、真实网络、TTS/STT 等设备相关行为 |

**模拟器为什么不可用**：容器是 Firecracker microVM，无 `/dev/kvm`、CPU 无 `vmx/svm` 标志，硬件加速模拟器起不来。
**Robolectric 陷阱**：`testOptions.unitTests.returnDefaultValues = true` 会把 Android 方法桩成返回默认值，与 Robolectric 的真实实现冲突，已移除；测试类加 `@RunWith(RobolectricTestRunner.class)` + `@Config(sdk = 33)` 即可。
**首次运行慢**：Robolectric 需下载 `android-all-instrumented` jar（约 13 分钟），之后走缓存约 15 秒。
**代理环境（v1.118.0 起）**：测试 worker 是独立 JVM，不继承 shell 的 `HTTP_PROXY`，会把 Robolectric 卡在拉包上。`app/build.gradle` 已把代理环境变量转成测试 JVM 系统属性（地址不写死），所以有代理时 `tools/verify.sh` 直接可用；CI 侧另加了 `~/.m2/repository/org/robolectric` 缓存。

### 2.2 最关键的几个源文件

| 文件 | 作用 | 行数 |
|---|---|---|
| `ui/ChatActivity.java` | **主体**，聊天界面全部逻辑（气泡、折叠、搜索、语音、建议、表格…） | ~2400 |
| `ui/MarkdownRenderer.java` | Markdown → Spannable 渲染（标题/粗斜体/代码/列表/**表格**） | ~350 |
| `ui/UiKit.java` | UI 工具（按压动效等） | |
| `util/ChatStore.java` | 消息持久化（SQLite） | |
| `brain/LLMClient.java` | LLM 调用，密钥池 | |
| `brain/AICore.java` | 事实摘要压缩 | |
| `brain/PlanExecutor.java` | 计划执行 + 自纠错 | |
| `care/CareAI.java` | 护理大脑（医疗模式） | |

**ChatActivity 里的关键常量**（改之前先看这里，别重复定义）：
```
COLLAPSE_MAX_LINES    = 10     长消息折叠阈值
TOOL_COLLAPSE_CHARS   = 300    工具结果折叠阈值
TOOL_BRIEF_CHARS      = 150
CTX_BUDGET_CHARS      = 6000   上下文字符预算
CTX_KEEP_RECENT       = 6
SUGGESTION_COOLDOWN_MS= 30s
SUGGESTION_DEBOUNCE_MS= 1s
REQ_AUDIO_PERMISSION  = 4001
INPUT_MAX_LINES       = 5      输入框最大行数（v1.47）
MAX_TOOL_LOOP_ROUNDS  = 6      对话大脑工具续轮上限（防死循环，v1.110.0）
```

**关键字段**：`listContainer`（气泡容器）、`etInput`（输入框）、`curAssistantBubble`、`curToolBubble`、`speechRecognizer`、`tvTitleRef`、`chipScrollRef`、`suggestionBar`、`lastUserText` / `lastAttachContext`（失败重试用）

### 2.3 气泡体系（改配色前必须分清，否则会改错）
| 类型 | 方法 | 背景 drawable |
|---|---|---|
| AI 对话气泡 | `newTextViewBubble()` | `bg_bubble_ai`（isCare 时切 `bg_bubble_care`） |
| 用户气泡 | `appendUserBubble()` | `bg_bubble_user` |
| 工具调用气泡 | `appendToolBubble()` | `bg_tool`（**独立体系，用户明确要求配色不可动**） |
| 错误气泡 | `appendErrorBubble()` | 复用 AI 气泡 + 红色原因 + accent 色重试提示 |

---

## 3. 技术决策与原因

### 3.1 自治流水线三件套
| 脚本 | 职责 |
|---|---|
| `autoloop.sh` | 消费队列：应用补丁 → 提交 → bump 版本 → 推送 → 按 head_sha 轮询 CI → 校验 release 资产 → 成功归档 `state/done`，失败 `reset --hard HEAD~2` + force push 回滚 |
| `taskgen.sh` + `genpatch.py` | 生产任务：扫描 Java 找 `ImageButton/Button` 声明，在其后插入一行安全调用；维持队列 ≥3 个待办 |
| `watchdog.sh` | 每 40s 巡检，任一进程掉线立即 `setsid` 拉起（**已实测：pkill 后 40s 内自动复活**） |

**为什么这么设计**：
- **补丁队列**而非直接写代码：让「改代码」和「发版」解耦，脚本可以稳定重放，失败可回滚
- **taskgen 只做机械改进**（不改结构不动逻辑）：保证 CI 高通过率，17 版全部一次通过
- **失败自愈 + force push 回滚**：保证流水线永不中断，坏版本不会留在 main 上

### 3.2 `setsid` 是必须的
`nohup ... &` 启动的进程会随终端/会话退出被杀。**必须** `setsid nohup ... < /dev/null &` 才能真正常驻。watchdog 自身也要这样启动。

### 3.3 隔离工作区（血泪换来的，见第 4 节）
所有手工编辑必须在 `/tmp/opencode/work`（独立克隆）里做，**绝不能**在 `/workspace` 里改。

### 3.4 记忆文档单独推送
`.monkeycode/MEMORY.md` 用 `[skip ci]` 单独推送，不触发 CI、不发版（已验证有效）。

---

## 4. 踩过的坑（最重要的一节，别重走）

### 4.1 ⚠️ 工作树争用事故（代价：两次 CI 失败 + 一个坏版本）
**症状**：v1.43.0 编译失败，报 `cannot find symbol`。

**根因**：我和 autoloop 共用 `/workspace` 工作树。
- autoloop 回滚时执行 `git reset --hard`，**抹掉了我未完成的编辑**
- 反过来，我未完成的编辑被 autoloop 的 `git add -A` **一起提交**，导致只有 `appendEmptyGuide()` 调用、没有方法定义的半成品混进了发行版本

**解法（必须遵守）**：
```bash
# 1. 建立隔离工作区（只需一次）
git clone /workspace /tmp/opencode/work
cd /tmp/opencode/work && git remote set-url origin https://github.com/hit-droid/Digital-Life-Cure.git

# 2. 每次开工前同步到最新
python3 /tmp/opencode/mkpatch.py sync

# 3. 在隔离区改代码（随便改，不会污染流水线）

# 4. 生成补丁入队，隔离区自动还原
python3 /tmp/opencode/mkpatch.py finish <名字> "<标题>" "<正文>"
```

### 4.2 ⚠️ `block` 非 effectively final（代价：一次 CI 失败）
`MarkdownRenderer.appendCodeBlock()` 里 `block` 被重新赋值过（去掉尾部换行），不是 effectively final，两个匿名 `ClickableSpan` 捕获它直接编译失败：
```
error: local variables referenced from an inner class must be final or effectively final
```
**解法**：取一份 `final String codeToCopy = block;` 再捕获，并抽出静态 `copyCodeToClipboard()` 供两处共用。
**通用规则**：匿名内部类/lambda 捕获的局部变量，若被重复赋值，必须先提 final 副本。

### 4.3 ⚠️ 绝不在 `/workspace` 手动 `git pull`
autoloop 每轮开头自己会 `git pull --no-rebase`（脚本 119 行），push 失败时还会再 pull 重试（164 行），**具备自愈能力**。我手动 pull 反而制造出 merge conflict 状态。
**解法**：记忆类改动一律到隔离区提交，用 `git pull --rebase` 再 push。

**这个坑的真实代价**（2026-08-31 亲历）：我在 `/workspace` 手贱 pull 了一次，仓库进入 `.git/MERGE_HEAD` 冲突态。紧接着 autoloop 开始下一个任务，它开头的 `git pull` 在这个状态下直接失败 → `git apply` 也失败 → 任务被误判成补丁问题，一路丢进 `failed/`。我精心写好并验证过的 `051-multiline-input.patch` 就这么被误杀，只能重新入队（`054-multiline-input.patch`）。

**如果已经弄出了冲突态**，立刻修复（autoloop 不会自己修复，只会持续误杀队列任务）：
```bash
cd /workspace
git merge --abort 2>/dev/null           # 先尝试中止合并
git fetch origin
git reset --hard origin/main            # 强行复位到远端
git status --porcelain | wc -l          # 必须是 0
```
修完记得去 `state/failed/` 看看有没有被误杀的补丁，捞出来重新入队（先 `git apply --check` 复核一遍）。

### 4.4 取 CI 日志必须带 token
`gh` 默认报 `gh auth login`。正确姿势：
```bash
GH_TOKEN=$(cat /tmp/opencode/auto/token) gh run view <run_id> --log-failed
```

### 4.5 补丁应用失败 ≠ 漂移
先确认功能是否已随更早的 commit 落地：
```bash
git show HEAD:<文件> | grep <关键符号>
```
（v1.42 重试功能其实已完整落地，042 补丁是重复应用才失败的，白折腾一轮。）

### 4.6 与自动生成器的冲突要提前规避
`genpatch.py` 会扫描所有 `Button`/`ImageButton` 声明。如果我删掉了某个按钮变量，生成器后续仍会选中它 → 补丁失效 → 流水线回滚。
**解法**：把即将删除的变量按 `文件:变量:生成器` 格式预先写进 `state/processed.txt`。
```bash
# 已做的预标记示例
app/src/main/java/com/digitallife/ui/ChatActivity.java:btnClear:a11y
```
**注意**：如果以后要恢复那三个顶栏按钮，记得把 processed.txt 里这 9 行删掉，否则它们永远不会再被 a11y 生成器处理。

### 4.7 已发行的补丁要从队列清掉
autoloop 靠 `--3way` 应用，已落地的补丁会再次入队重试。虽然有「已应用则跳过」判定，但仍应主动 `rm` 掉 queue 里对应的 `.patch`/`.meta`。

### 4.8 ⚠️ mkpatch.py 的 `sh().strip()` 会损坏补丁（已修复，2026-09-05）
`sh()` 原来对所有输出 `strip()`，`finish()` 里的 `git diff` 尾部若含纯空格上下文行（`' '`）会被剥掉 → 补丁报 `corrupt patch`（077/078/082/083 四个补丁曾被误杀进 failed/）。
**已修复**：`sh()` 增加 `strip` 参数，`finish()` 取 diff 用 `strip=False`。修好后把 failed/ 里能 `git apply --check` 通过的补丁直接捞回 queue，损坏的（如 083）在隔离区重写后重新入队。
**教训**：生成补丁前永远 `git apply --check` 验证；`mkpatch.py finish` 之后养成再 check 一次的习惯。

### 4.9 其他小坑
- **zsh 会截断 commit message**：`\`` + `#` 组合会被截断，复杂 message 用 `git commit -F /tmp/xxx.txt`
- **Edit 工具陷阱**：`oldString` 不能包含与保留代码完全相同的子串；`case MotionEvent.CANCEL` 不会被自动补 `ACTION_` 前缀
- **模型不支持读图**：read 图片返回成功但模型侧报 `this model does not support image input`；`image_analysis` MCP 报 `insufficient balance`。截图问题只能请用户文字描述
- **气泡最大宽度**：`maxBubbleWidth` = 屏幕宽 82%

---

## 5. 还没做完的事 / 下一步

### 5.1 立即可做
- 对话大脑工具循环已接入（v1.110.0），聊天模式可直接用全部 BuiltinTools（web_search/web_fetch/schedule_task/backup_data/skill_summary…）
- failed/ 里若再有补丁，先 `git apply --check`，损坏的按 4.8 流程重写

### 5.2 流水线自身的改进方向
- 生成器现在只做 3 种机械改进（a11y / allcaps / haptic），**会耗尽**。耗尽后 taskgen 每 10 分钟才轮询一次。可以考虑增加新的安全生成器（如 `setImportantForAutofill`、给 EditText 补 `setSingleLine` 提示等）
- autoloop 目前对每个任务都等完整 CI（3~4 分钟），是吞吐瓶颈

### 5.3 OpenMinis 对标（2026-09-05 用户指定参考对象）
用户要求参考 GitHub 项目 **OpenMinis**（OpenMinis/OpenMinis，本地优先的设备端 AI Agent，iSH/PRoot 沙箱 + Skills + MCP + 模型组）。已落地的对标项：
| 版本 | 对标能力 | 说明 |
|---|---|---|
| v1.104.0 | **模型组自动降级** | 同 scope 多配置构成降级链，网络错/401/403/408/409/429/5xx 自动切备用配置重发（ChatActivity.tryModelFailover） |
| v1.105.0 | **web_fetch 网页抓取** | BuiltinTools 新工具，URL → 纯文本正文（去标签/折叠空白/截断） |
| v1.106.0 | **真实 web_search** | DDG lite HTML 解析（cleanDuckLink），替代返回 Bing URL 的桩 |
| v1.107.0 | **定时任务调度器** | TaskScheduler/TaskReceiver + AlarmManager 非精确闹钟 + WAKE_LOCK；周期任务先排下次再执行防丢 |
| v1.108.0 | **数据备份恢复** | BackupManager zip 导出/恢复，恢复前自动安全备份，路径穿越防护 |
| v1.109.0 | **SKILL.md 技能包** | SkillManager 声明式技能；assets 内置 deep_research/daily_brief |
| v1.110.0 | **对话大脑工具调用** | ChatActivity 聊天模式接入 function calling 循环（Tools coreOnly 宿主 + BuiltinTools 注入 + extra.tools_desc）——**OpenMinis 的核心 Agent 形态** |

**工具循环实现要点**（v1.110.0，改对话发送逻辑前先看）：
- `sendChatMessage` 只构建用户消息 → 存 `chatLiveMsgs` → `startChatLoop()`
- `startChatLoop`：ensureChatLlm + ensureChatTools（new Tools(true) coreOnly + BuiltinTools.install）→ extra 注入 system + tools_desc → setTools(chatTools.toJsonArray()) → chatStream(chatLiveMsgs)
- `createChatStreamListener` 单例复用：onToolCall 同步 execTool（LLM 线程）→ 气泡入队（主线程 handler 保序）→ assistant(tool_calls)+tool 消息拼入 chatLiveMsgs → onDone 判「空文本且末条 role=tool」自动续轮（chatLoopRounds < 6）
- onError 的 tryModelFailover 仍走 sendChatMessage(lastUserText) 重发，工具链随消息重建自然丢弃
- Tools.execute 回调为同步返回，execTool 用 1 元素数组捕获 res/err（lambda 只捕获 effectively final）

后续可继续对标的（按可行性排序）：
1. **对话记忆自主提取**：✅ v1.120.0 已落地（对话消息接入 `MemoryStore`，`PetService` 每 6h 跑 `MemoryExtractor` 沉淀 facts，`MemoryPlugin` 按当轮问题做相关召回注入 prompt）；✅ v1.121.0 修正召回算法——原先对话侧用整句 `contains`，中文实际召不回，已统一到 `MemoryRelevance`。
2. **浏览器/App 自动化操作**：OpenMinis 有 browser-automation；Android 上等价物是辅助功能 AccessibilityService 点击，成本高
3. **真 MCP 对接**：现只本地 17 工具，可支持远端 MCP server 的 SSE 流式调用
4. **iSH/PRoot 沙箱**：在本机跑 shell，工程量大且 Android 权限受限

### 5.4 App 功能方向（其他候选，按推荐度排序）
1. **消息时间戳**：气泡上没有时间显示，长按才知道。⚠️ 部分完成——v1.123.0 已修「历史时间条用的是打开时间」，**每条气泡各自的 HH:mm 仍未做**（见 Issue #7）
2. ~~**引用回复**：消息菜单现在只有「复制/重新生成/删除」~~ ✅ 已完成——v1.127.0 用户/AI 气泡长按菜单新增「引用回复」，引用块纯逻辑下沉 `ChatTextOps.buildQuote`（引前剥折叠提示、最多 4 行、结尾换行），workbuddy PR #16
3. **Markdown 表格增强**：当前是等宽文本对齐，可考虑真表格布局
4. **会话列表空状态**：与聊天页空态一致的引导
5. ~~表格单元格内的行内 Markdown（**加粗**等）目前不解析，只显示原始文本~~ ✅ 已完成——v1.125.0 `MarkdownRenderer.stripInline` 先剥标记再算列宽，workbuddy PR #12

### 5.5 到期后
2026-09-07 三个脚本会自动退出。届时向用户汇报：完整发行记录见 `state/releases.csv`（格式 `版本|任务名|sha`）。

---

## 6. 怎么确认流水线还活着

```bash
# 进程数应该 ≥3（autoloop / taskgen / watchdog，每个可能有子进程）
pgrep -f "autoloop.sh|taskgen.sh|watchdog.sh" | wc -l

# 看最新日志
tail -20 /tmp/opencode/auto/logs/autoloop.log

# 看发行了多少版
wc -l < /tmp/opencode/auto/state/releases.csv

# 看队列
ls /tmp/opencode/auto/queue/*.patch
```

日志目录：`/tmp/opencode/auto/logs/`（autoloop.log / taskgen.log / watchdog.log）
状态目录：`/tmp/opencode/auto/state/`（releases.csv / done/ / failed/ / processed.txt / counter）

**预期节奏**：约 3~4 分钟一版（含 CI 等待）。看到 `✅ vX.Y.Z 已发行` 就是正常。

---

## 7. 装了什么、在哪、怎么重装

### 7.1 我下载/安装的东西
**没有通过包管理器安装任何东西。** 只有：

| 东西 | 位置 | 说明 |
|---|---|---|
| 流水线脚本 | `/tmp/opencode/auto/` | **在 /tmp，机器重启就没了** |
| 脚本备份 | **`/workspace/tools/auto/`** | 【本轮新加】已提交进仓库，重装用这份 |
| 隔离工作区 | `/tmp/opencode/work/` | 独立 git 克隆 |
| GitHub Token | `/tmp/opencode/auto/token` | chmod 600，**PAT（repo 权限）** |
| 项目记忆 | `/workspace/.monkeycode/MEMORY.md` | 已提交 |

> ⚠️ **Token 安全提示**：token 明文未写入仓库（避免泄露到公开仓库被 GitHub 扫描吊销）。如果 token 掉了/过期了，需要用户重新给一个 PAT（需 `repo` 权限），写进 `/tmp/opencode/auto/token` 并 `chmod 600`。

### 7.2 完整重装步骤

```bash
# ── 1. 建目录、放 token ─────────────────────────────
mkdir -p /tmp/opencode/auto/{queue,logs,state/done,state/failed}
echo '你的GitHub PAT（repo权限）' > /tmp/opencode/auto/token
chmod 600 /tmp/opencode/auto/token

# ── 2. 从仓库恢复脚本（不用重新写！）────────────────
cd /workspace && git pull
cp tools/auto/*.sh tools/auto/*.py /tmp/opencode/auto/
cp tools/auto/mkpatch.py /tmp/opencode/
chmod +x /tmp/opencode/auto/*.sh /tmp/opencode/auto/*.py

# ── 3. 建隔离工作区 ────────────────────────────────
rm -rf /tmp/opencode/work
git clone /workspace /tmp/opencode/work
cd /tmp/opencode/work && git remote set-url origin https://github.com/hit-droid/Digital-Life-Cure.git

# ── 4. 拉起三个守护进程（setsid 是关键！）──────────
cd /tmp/opencode/auto
setsid nohup ./autoloop.sh >> logs/autoloop.out 2>&1 < /dev/null &
setsid nohup ./taskgen.sh >> logs/taskgen.out 2>&1 < /dev/null &
setsid nohup ./watchdog.sh >> logs/watchdog.out 2>&1 < /dev/null &

# ── 5. 验证 ───────────────────────────────────────
sleep 5
pgrep -f "autoloop.sh|taskgen.sh|watchdog.sh" | wc -l   # 应该 ≥3
tail -5 /tmp/opencode/auto/logs/autoloop.log
```

**注意**：`REPO=/workspace` 是硬编码在脚本里的。如果仓库路径变了，需要改 `genpatch.py` 第 16 行、`autoloop.sh` 第 17 行、`mkpatch.py` 的 `WORK` 常量。

**停止时间**：三个脚本都以 `END_TS=1788739200`（2026-09-07 00:00:00）为终止条件，到点自动退出。要改就改各脚本里的 `END_TS`。

### 7.3 手工加一个功能的标准流程
```bash
python3 /tmp/opencode/mkpatch.py sync                    # 1. 同步
# 2. 在 /tmp/opencode/work 里改代码（用 Edit 工具）
python3 /tmp/opencode/mkpatch.py finish 052-my-feature \
  "feat(chat): 我的功能（v1.56.0）" \
  "- 改动点一
- 改动点二"                                              # 3. 自检 + 入队 + 还原
# 4. 等几分钟，看 autoloop.log 出现 ✅ 就成功了
```

`mkpatch.py finish` 会自动做：静态自检（内部类捕获 effectively final）、生成 patch/meta、还原隔离区。
`meta` 文件第 1 行是 commit 标题，其余是正文。**已不再自动追加 `Co-authored-by`**（2026-10-03 用户要求），脚本里的追加逻辑已移除。

---

## 8. 关键速查

| 想做什么 | 命令 |
|---|---|
| 看流水线状态 | `tail -20 /tmp/opencode/auto/logs/autoloop.log` |
| 看已发行版本 | `cat /tmp/opencode/auto/state/releases.csv` |
| 看队列 | `ls /tmp/opencode/auto/queue/*.patch` |
| 看失败任务 | `ls /tmp/opencode/auto/state/failed/` |
| 取 CI 错误日志 | `GH_TOKEN=$(cat /tmp/opencode/auto/token) gh run view <run_id> --log-failed` |
| 当前版本 | `grep versionName /workspace/app/build.gradle` |
| 手工加功能 | 见 7.3 |
| 重建流水线 | 见 7.2 |

**仓库**：https://github.com/hit-droid/Digital-Life-Cure
**分支**：main（push main 会自动编译 + 发 Release，tag = `v{versionName}`）

---

## 9. 协作约定（多写入者）

自 2026-10-03 起，本仓库同时存在多个**有写权限的 AI agent**（`trae`、`workbuddy`），
另有 `monkeycode-keepalive[bot]` 等自动化提交。第 4.1 节的事故（`git reset --hard` 抹掉未提交
改动、半成品被 `git add -A` 混提）本质是「多个写入者共用工作树 + 无发版归属」；人变多只会更容易重演。
以下是硬约定，**不是建议**。

### 9.1 写入通道
- **main 不再接受功能直推**。功能/修复一律走 `feat/*`、`fix/*` 分支 + PR。
- 合并前必须 CI 绿；本地先跑 `./tools/verify.sh`（compile / test / lint / apk 四关）。
- 只有「发布负责人」可以把**版本相关**提交直接落到 main（见 9.2）。

### 9.2 发布负责人（单一，避免 bump 撞车）
- 当前负责人：**trae**。
- 任何一次发行，以下四处**只由发布负责人改**，其他人在 PR 里不要动：
  1. `app/build.gradle` 的 `versionCode` / `versionName`
  2. `CHANGELOG.md` 顶部新增版本段
  3. `AGENTS.md` 头部的「最后更新」与 1.3 完成程度表
  4. git tag `v{versionName}` 与 GitHub Release
- 其他人合并 PR 后由负责人统一 bump + 发版。这样"双方各自 bump → 版本号冲突/发错版"直接消失。

### 9.3 任务认领（避免重复劳动）
- 开工前先在 GitHub Issues 建或找对应 issue，并打 label 认领：`owner:trae` / `owner:workbuddy`。
- **同一文件、同一时间只允许一个 owner**。热点文件尤其要先认领再动：
  `ui/ChatActivity.java`、`brain/Tools.java`、`brain/AICore.java`、`service/PetService.java`、`AGENTS.md`。
- 领地的默认归属见 9.5；跨领地改动请在 issue/PR 里说明后再动。

### 9.4 提交纪律
- 每次开工前 `git pull --rebase`；小步提交，别攒一个大提交。
- **禁止** `git reset --hard`、`git push --force`（尤其对 main）。需要回滚用 `git revert`。
- **禁止共用工作树**：每个 agent 用自己的工作副本（做法见 4.1 的隔离工作区）。
- 提交信息格式：`type(scope): 摘要`，正文中文，说明"为什么"。
- 凭据各自持有；**禁止**把 token / PAT 写进脚本、源码或提交（本项目发生过明文 PAT 误入库）。

### 9.5 建议分工（领地，默认归属）
| 模块 / 目录 | owner | 说明 |
|---|---|---|
| `harness/`、`skill/`、多智能体编排 | trae | 内核与基础设施 |
| `memory/`、记忆检索与提取 | trae | v1.120.0 刚做完闭环 |
| `ui/` 聊天体验、OpenMinis 对标剩余项 | workbuddy | 见 5.3 / 5.4 |
| 发版、CHANGELOG、`AGENTS.md` 头部 | trae | 单一发版人 |
> 领地是**默认归属**，不构成排他；跨领地务必先认领、在 PR 里说明。

### 9.6 冲突与回滚
- 同文件冲突：以**先认领者**为准，后到者 rebase 到其分支之上再提。
- 出现坏版本：优先 `git revert` 回滚，再定位根因；**不做 force push**。
- 对本文档的修改本身也走 PR，避免两个 agent 同时改同一段。
