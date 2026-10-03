# Changelog

## v1.128.0 (2026-10-03)

### 多智能体深化：子智能体协作台账（控制台可见）

多智能体早就可委派（`delegate_task` 串行 / `delegate_parallel` 并行 / `delegate_pipeline` 依赖链），但**委派发生过什么完全不可见**：智能体控制台只有「活动 / 工具日志 / 计划 / 脑日志」四个 Tab，`SubagentRunner` 也不写 `BrainLog`。跑了几个子智能体、什么任务、成没成、各花多久，只能靠聊天里一次性消失的卡片猜。

改法：

- **`harness/subagent/SubagentLedger`**（新，纯 Java 无 Android 依赖，可单测）：环形缓冲上限 200 条，记录 `agent / 任务 / 成功失败 / 错误原因 / 耗时 ms / 时间戳`；任务入库前折叠换行并截断到 60 字；读写加锁（并行批次是多线程写入）。提供 `record/recent/total/okCount/failCount/clear`。
- **`SubagentRunner.run` 统一埋点**：串行 / 并行 / 依赖链三条路径最终都汇到这一个方法，因此只在这一处记录即全覆盖。**所有出口都留痕**——未知 preset、空任务、无模型配置、超时、模型报错、正常返回，这样「模型点了委派但没跑起来」在控制台也看得见。
- **`AgentConsoleActivity` 第 5 个 Tab「多智能体」**：顶部汇总「共 N 次 · 成功 X · 失败 Y」，下面倒序明细（时间 / ✓✗ / agent / 耗时 / 任务摘要 / 失败原因），Tab 角标显示总次数，空态给两条可复现的示例指令。5 个 Tab 后单格更窄，收窄横向内边距并锁单行，避免「工具日志」被挤成两行。
- 顺带把控制台顶栏写死的 `v1.115.0` 改成读真实 `versionName`（沿用 `AboutActivity` 的写法），此前每次发版后它都不会更新。

新增 10 条单测（`SubagentLedgerTest`）。单测 270 → 280。

## v1.127.0 (2026-10-03)

### 消息支持「引用回复」（长按菜单）

由协作者 workbuddy 提交（PR #16），发布负责人 trae 合并、统一版本注释并发版。

用户气泡与 AI 气泡的长按菜单此前只有 朗读 / 复制 / （重新发送 / 分享 / 重新生成 / 删除），想把某条消息引到输入框里追问只能手打。

改法：

- **`ui/chat/ChatTextOps.buildQuote`**（纯逻辑，可单测）：每行加 `> ` 前缀，默认最多 4 行、超出补一行 `> …`，结尾强制换行（光标不会粘在引用行上）；**引用前先剥掉折叠提示**，否则「▸ 展开全文」会被一起引用进输入框；
- **`ChatActivity.quoteIntoInput`**：把引用块追加进输入框（已有内容先补换行），光标置末尾并唤起软键盘；
- 两处菜单都在「复制」之后插入「引用回复」，其后项索引顺延：
  - 用户气泡：朗读 / 复制 / **引用回复** / 重新发送 / 分享
  - AI 气泡：朗读 / 复制 / **引用回复** / 分享 / 重新生成 / 删除

新增 10 条单测（`ChatTextOpsQuoteTest`）。

## v1.126.0 (2026-10-03)

### 多智能体深化：补齐 `writer` / `critic` 子智能体

v1.124.0 加了 `delegate_pipeline`（有序依赖链），但内置子智能体只有 `researcher` / `secretary` —— 最常见的「**研究 → 写稿 → 审校**」链条缺最后两环，主智能体只能让 researcher 兼任写作与审校，角色提示词不对口、效果打折。

本版补齐两个**只读型**角色（`SubagentPresets`）：

- **`writer`（撰稿人）**：把素材/结论组织成结构清晰的中文稿件；不编事实、保留来源、缺口标「待补充」；白名单 `skill_summary`/`load_skill`/`memory_search`/`memory_recall`；
- **`critic`（审校员）**：先列问题清单（事实 / 依据 / 逻辑 / 表达）再给修订稿；不编事实、拿不准标「待核实」；白名单 `memory_search`/`memory_recall`。

两者都无 `delegate_task`（保持「子智能体不可递归委派」）、无写操作 / 联网工具、步数受限。

现在一条 `delegate_pipeline` 即可完成「researcher 调研 → writer 成稿 → critic 审校」。单测 258 → 259。

## v1.125.0 (2026-10-03)

### 表格单元格支持行内 Markdown（剥离标记后再算列宽）

由协作者 workbuddy 提交（PR #12），发布负责人 trae 合并并发版。

表格一直是等宽对齐的纯文本渲染（`appendTable`），单元格里的 `**加粗**`、`` `代码` `` 等标记**既渲染不出效果，又按字面占列宽把对齐撑歪**。

改法：

- 新增 `MarkdownRenderer.stripInline(String)`：剥掉 `**加粗**` / `*斜体*` / `` `代码` `` / `~~删除线~~` / `[文字](链接)` 标记；
- `appendTable` 在**计算列宽之前**先剥标记——顺序是关键，否则 `**` 仍占宽度、对齐照样歪；
- 单星号放在双星号之后处理，避免 `**` 被拆开当斜体吃掉；不成对的单星号（如 `2 * 3`）不会被误吃；
- `stripInline` 设为包内可见，便于单测。

顺带补齐 `MarkdownRenderer` 的单测空白（此前单测集中在 harness / memory / tools，这个渲染类一个都没有）。新增 12 条单测。单测 246 → 258。

## v1.124.0 (2026-10-03)

### 多智能体深化：`delegate_pipeline` 有序依赖链编排

多智能体此前只有 `delegate_task`（单个、顺序）与 `delegate_parallel`（互不依赖、并发），缺少「有先后依赖」的多步编排。后一步依赖前一步产出的任务只能让主智能体多轮调用 `delegate_task`，中间结论回流主对话，既费 token 又污染主历史。

本版新增 `delegate_pipeline`：一次调用编排一条**有序依赖链**。

- **逐步串行执行**，每一步都能拿到前序步骤的结论（截断后注入，避免撑爆上下文）；
- **失败即停**：某步失败后其余步骤标为「未执行」，报告给出失败原因，不抛整批异常；
- 步数上限 5，每步超时与 `SubagentRunner` 一致；
- 复用独立 harness 树 / 最小权限 / 超时；复用 `onTeamStep`，**UI 并行协作卡片无需改动**即可显示每一步。

实现要点：

- 新增 `harness/subagent/SubagentPipeline.java`（编排 + 汇总渲染），与 `SubagentTeam` 并列；
- `SubagentRunner` 新增 `run(..., progressTask, ...)` 重载——依赖链会把前序结论拼进喂给模型的 task，但 UI 事件仍用步骤原文，卡片才配对得上；
- `Tools.register` 的数组 schema 增加 `steps`（与 `tasks` 同构）；
- `delegate_pipeline` 与 `delegate_parallel` 一并在 `installParallelDelegateTool` 装配，**不改 `ui/ChatActivity.java`**（该文件按 AGENTS.md 9.3 归 workbuddy）。

新增 15 条单测（`SubagentPipelineTest`），单测 231 → 246。

## v1.123.0 (2026-10-03)

### 修复：历史会话时间条显示的是「打开会话的时间」

由协作者 workbuddy 提交（PR #9），发布负责人 trae 合并并发版。

恢复历史会话时 `appendUserBubble` / `appendAiBubble` 只接收 `content`，时间条内部一律取 `System.currentTimeMillis()`，导致两个问题：

1. 历史消息的时间条显示的是**打开会话的时间**，而非消息真实发送时间；
2. 历史恢复是紧循环执行的，相邻消息间隔远小于 5 分钟阈值 → **只有第一条能插出时间条**，其后全部被 `return` 吞掉。

改法：

- `appendTimeDividerIfNeeded(long ts)` 接收消息真实时间戳，`ts <= 0` 时回退为当前时间；
- `appendUserBubble` / `appendAiBubble` 增加 `long ts` 重载，单参版本委托并回退为当前时间，其余 13 处调用点无需改动；
- 历史恢复处传入 `StoredMsg.timestamp`；
- 相邻间隔比较改用 `Math.abs`，避免乱序时间戳导致判断失效。

> 注：本次只修「时间条」；每条气泡各自的 HH:mm 时间戳留作后续 PR（见 Issue #7）。

## v1.122.0 (2026-10-03)

### MCP 客户端协议正确性修正

MCP 其实早已实现（Streamable HTTP + SSE、配置持久化、设置页入口、服务启动自动连接），但核心客户端有几处协议级错误，会让遵规范的远端服务器连不上。本版修正它们。

- **修「握手后 session 被清零」**：`initialize()` 里写了一行 `sessionId = null`，把 `request()` 刚从响应头取到的 `Mcp-Session-Id` 直接抹掉。标准 Streamable HTTP 要求 initialize 之后的每个请求都回带该 id，于是后续 `tools/list` 会失败或每请求新开会话。已删除该行，并加注释说明不要清。
- **补 `notifications/initialized`**：协议要求 initialize 成功后再发这条通知。新增 `notify()`（无 id、best-effort，失败不阻断握手）。
- **修 SSE 解析**：原实现把所有 `data:` 行**无分隔拼接**再 `new JSONObject`——多事件（如服务端通知 + 本次响应）时必然拼成非法 JSON，多行事件也会撑坏 JSON。抽出纯逻辑 `McpResponseParser`：按 SSE 规范切事件（空行分隔、同事件多行 `data:` 以 `\n` 连接）、跳过注释/`event:`/`[DONE]`、**优先返回与请求 id 匹配的帧**。
- **修 JSON-RPC id 精度**：id 原用 `System.nanoTime()`（约 1e18，超出 double 精度），org.json 以 double 存取会失真导致 id 比对不可靠；改为静态 `AtomicLong` 小整数递增。
- 顺带：`request()` 在无 `result` 时返回空对象而非 null（消除调用方 NPE）；HTTP 判定放宽为 2xx；通知与请求共用 `post()`，去重。
- 新增 15 条单测（`McpResponseParserTest`），单测 216 → 231。

## v1.121.0 (2026-10-03)

### 记忆召回修正：中文相关召回原先形同虚设

v1.120.0 给对话大脑接上了「按当轮问题召回记忆」，但这次排查发现**召回逻辑本身是坏的**——等于白接。

- **根因：两套分叉的"关键词召回"实现**。
  - `MemoryStore.retrieveRelatedFacts`（桌宠大脑走）有完善的中文 2~6 字滑窗 + 英文 ≥3 字符 + 停用词提取；
  - `MemoryRetriever`（对话大脑走，即 v1.120.0 新接 query 的那条）直接用**整句话**做 `content.contains(query)`。
  - 中文没有空格，用户问"我喜欢喝什么咖啡"永远匹配不到记忆"喜欢喝美式"，相关召回实际命中率≈0。
- **收敛为单一实现**：新增纯逻辑类 `com.digitallife.util.MemoryRelevance`（关键词提取 / 命中计数 / 归一化相关度），`MemoryStore` 与 `MemoryRetriever` 共用，消除分叉。`MemoryRetriever` 的关键词维度改为按命中关键词数排序取 top N，得分区间与时间/重要度维度可比。
- **顺带修正**："高重要度 5 条"此前按 `getAllFacts()` 的 `last_confirmed DESC` 顺序取，实为"最近的高权重"；现改为按 `confidence` 降序取，与注释一致。
- 新增 13 条单测（`MemoryRelevanceTest`），单测 203 → 216。

## v1.120.0 (2026-10-03)

### 记忆闭环：查询相关召回 + 对话记忆自动提取

本版把此前"各就各位但没接上"的三段记忆能力接成一个闭环：对话 → 记忆源 → 相关召回 → 沉淀 facts。这三处都是**已写成但从未生效**的死代码，属于对标 OpenMinis「设备端记忆」的收尾。

- **修「记忆注入永远查空」**：`MemoryPlugin` 一直调用 `renderForPrompt("")`，检索器里已有的"关键词相关"维度被完全跳过，注入的永远只是"最近 + 高权重"。现在插件从 `HarnessContext` 读取 `MemoryPlugin.KEY_QUERY`，`ChatActivity` 在每轮 `startTurn` 前把当轮用户问题交付给它；未提供时退化为旧行为，不影响其他调用方。
- **对话接入长期记忆**：对话大脑（含模型小房间）此前只写 `ChatStore`，从不写 `MemoryStore`，导致记忆检索与自动提取的数据源只有桌宠大脑。现在用户/助手消息在落库时一并并入长期记忆（助手侧先剥掉折叠/中断角标）；护理大脑不参与，避免医疗会话混入角色记忆。
- **自动提取真正跑起来**：`MemoryExtractor.schedulePeriodic()` 此前**没有任何地方调用**，"每 6 小时自动提取"实际不存在。现在由常驻前台服务 `PetService` 在启动时调度、销毁时取消。
- **提取结果解析下沉为纯逻辑**：新增 `MemoryExtractionParser`（无 Android 依赖，可单测），把原先埋在 `MemoryExtractor.handleResult` 里的解析收敛成：抽 JSON（兼容 ```json 包裹 / 正文混入）、限 5 条新记忆 / 3 条遗忘、单条截断 60 字、`weight` 夹到 [0,1]、分类归一到 `facts` 表实际取值（如模型爱写的 `personality` → `profile`，否则 `getProfile` 查不到）。`extractJson` 还修了字符串内花括号/转义引号会打乱括号计数的问题。
- 新增 19 条单测（`MemoryExtractionParserTest`），单测总数 184 → 203。

## v1.119.0 (2026-10-03)

### 重构：ChatActivity 纯逻辑下沉 + 46 条单测

`ChatActivity` 长期是 2661 行的"上帝类"，承担气泡、折叠、搜索、语音、建议、工具循环、团队卡片等全部逻辑，却是全项目**唯一没有任何测试**的高频改动文件。本版把其中不依赖 Android 的纯逻辑下沉到 `com.digitallife.ui.chat`，改为可单测。

- 新增 `ChatTextOps`：折叠提示/中断角标的剥离、工具调用 JSON 解析（`parseToolName`/`parseToolArgs`）、参数 JSON 美化、时间分隔线文案（`now` 由调用方传入，测试可固定时间）。
- 新增 `HistoryBudget`：上下文按字符预算裁剪（预算内原样返回、至少保留最近 6 条、插入"已省略 N 条"说明）。
- 新增 `FailoverPolicy`：模型降级判定（401/403/408/409/429 与 5xx/网络错误值得切换，400/404 等不换）。
- 新增 `SuggestionEngine`：建议栏历史指纹、冷启动三条、上下文拼装、模型返回 JSON 解析（容忍 ``` 包裹、限 3 条 / 20 字）。
- `ChatActivity` 2661 → 2490 行，删除 8 个私有方法，改调上述类；行为保持不变（同一份逻辑原样搬迁）。
- 新增 46 条单测（`ChatTextOpsTest`/`HistoryBudgetTest`/`FailoverPolicyTest`/`SuggestionEngineTest`），单测总数 138 → 184。这几条覆盖的是原先完全裸奔的边界：历史截断条数、超长建议截断、错误码分支穷举、时间分割线跨天/跨年。

> 说明：测试暴露了一个此前未记录的库行为——org.json 对"单键平坦对象"的 `toString(2)` 不换行（仅补空格），多键/嵌套才缩进；已在该测试中注明。

## v1.118.0 (2026-10-03)

### 工程化加固：CI 单测门禁 / 测试代理注入 / 版本号去硬编码

本版不新增功能，集中还技术债，堵住几个会持续放大的口子。

- **CI 增加单测门禁**：此前 `build.yml` 只跑 `assembleDebug`，138 个单测从不在 CI 执行，回归完全拦不住（v1.43 那种"只有调用没有定义"的半成品能直接进发行版）。现在 `testDebugUnitTest` 前置，失败即不出包、不发 Release；并缓存 `~/.m2/repository/org/robolectric`，避免每次 CI 重下 android-all jar（约十几分钟）。
- **修 `tools/verify.sh` 在代理环境下必挂**：Robolectric 的测试 worker 是独立 JVM，不继承 shell 的 `HTTP_PROXY`，会卡在拉 `android-all` jar 上。`app/build.gradle` 现在把代理环境变量转成测试 JVM 系统属性（代理地址不写死，无代理时空操作），本地全量验证恢复可用。
- **修两处用户可见的写死版本号**：`DeveloperActivity` 的"版本信息"曾硬编码 `v1.24.0 (versionCode 27)`，`OperitDrawer` 头部曾硬编码 `v1.24.0 · 小汐 · 在线`，与实际版本严重不符。改为从 `PackageManager` 实时读取，随发版自动更新。
- **清理误入库文件**：删除仓库根目录 4 个临时发布脚本（`wait_build.py` / `poll.py` / `deliver_m7.py` / `deliver_finish.py`）——其中 `wait_build.py` 硬编码了一个明文 GitHub PAT，且脚本内容属于另一个仓库（`maiden-dungeon-apk`），与本项目无关。
- **文档校正**：`AGENTS.md` 的源码文件数与单测数（77 / 85 → 118 / 138）已失真，一并更新，并补充代理环境说明与 v1.115.0~v1.118.0 的版本记录。

> 注意：被删除脚本里的 PAT 仍在 git 历史中，且当前工作区的 remote URL 内嵌了推送令牌，建议尽快在 GitHub 侧吊销旧令牌并改用 credential helper。

## v1.117.0 (2026-10-03)

### 多智能体并行编排：delegate_parallel（fan-out / fan-in）

主智能体此前只能用 `delegate_task` 逐个委派子智能体（串行、单任务）。本版新增并行编排：把多个**互不依赖**的子任务同时交给多个子智能体执行，全部结束后汇总成一份带结论/原因的报告回传。

- 新增 `harness/subagent/SubagentTeam`：并发调度 + 结果汇总。一次最多 5 个子任务，每个子任务独立超时（默认 180s），跑在守护线程池上；每个任务仍复用 `SubagentRunner` 的独立 harness 树与最小工具权限，上下文互不污染。
- **失败隔离**：某个子任务失败（未知 preset / 空任务 / 超时 / 模型报错）只影响它自己那一条，其余照常完成；汇总报告逐条标注成功/失败与原因。
- 新增 `delegate_parallel` 工具（`SubagentRunner.installParallelDelegateTool`）：参数 `tasks` 为 `{agent, task}` 数组，并兼容模型把数组序列化成字符串的写法；空数组与超量会显式报错，引导模型自我纠正。
- 工具 schema 增强：`Tools.register` 支持 `tasks` 这类「对象数组」参数，模型能看懂每个子任务的字段结构。
- 可视化：并行协作时每个子智能体各占一张卡片（按「agent + 任务原文」索引），开始显示任务、结束回填结论或失败原因；同一 agent 并发接多个任务也各有各的卡片，不会互相覆盖或残留「协作中」。为此进度回调新增 `onTeamStep(agent, task, phase, detail)`，把自己一并透出（默认退回三参数版本，顺序委派与测试无需感知）。
- 修复：停止生成时取消**全部**在途子智能体客户端（此前只记录最后一个，并行后会漏取消）。
- 新增 16 条单测（`SubagentTeamTest`）：真并发（栅栏证明同时运行）、失败隔离、超时、参数校验、字符串数组容错、schema 形状。

## v1.116.0 (2026-09-19)

### 修复 Live2D 原生崩溃：模型文件缺失时空指针（SIGSEGV at 0x0）

真机日志出现 `libmaidendungeon.so` 原生崩溃：`Signal: SIGSEGV (11) / Fault address: 0x0`，发生在启动/切换某个模型时。

- **根因**：`LAppModel::LoadAssets()` 里 `CreateBuffer()` 拿到的 `buffer` 未判空。模型文件缺失时 Java 侧 `loadFile` 返回 `null`、C++ `LoadFileAsBytesFromJava` 返回 `NULL`，随即被 `new CubismModelSettingJson(buffer, size)` 当 JSON 解析 → 空指针解引用，整个进程段错误退出。
- **完整防御式修复**（模型坏掉时只跳过该模型，不再拖垮 App）：
  - `LoadAssets()`：buffer 判空 + 解析结果判空，失败置 `_loadFailed` 并打日志（含缺失文件路径）
  - `SetupModel()`：moc / expression / physics / pose / userdata / motion 六处 `CreateBuffer` 全部判空；moc 缺失时清理 `_modelSetting` 防泄漏
  - `PreloadMotionGroup()` / `StartMotion()`：动作数据缺失跳过，不再对 `NULL` 调用 `SetFadeInTime` 等
  - `~LAppModel()`：`_modelSetting` 判空后再访问（此前无条件解引用，`LoadAssets` 提前返回时析构必崩）
  - `SetupTextures()`：`_modelSetting` 与 renderer 双重判空
  - `LAppLive2DManager::ChangeScene()`：加载失败即移除半初始化模型；`OnUpdate()` / `OnDrag()` / `OnTap()` 每帧路径全部补空模型守卫
- 新增 `LAppModel::IsLoadFailed()` 供调用方判断是否跳过该模型。

## v1.115.0 (2026-09-19)

### DeepSeek Harness 完善：Profile 组装、可卸载插件、提示词分段

对齐 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的「everything is a plugin」：没有特权核心，能力由有序 profile 挂载，卸载时副作用一并撤销。

- **Profile 组装**：`chat` 11 插件（session / prompt / tools / agent-loop / agents / persona / memory / skills / clock / compress / guard）；`isolated` 8 插件给子智能体与护理大脑。
- **插件生命周期**：`Plugin.deactivate` + `unmount(id)`；Clock / Guard 的 waterfall 登记为可逆 effect。
- **`ctx.agents`**：`AgentRegistry` 跟踪在途句柄，turn 结束或取消时注销。
- **提示词分段**：Persona / Memory / Skill / Clock 各自织进 `PromptAssembler` 段；对话页只在模型小房间覆盖 persona。
- **上下文压缩 seam**：`ContextCompressor` 作为纯消息投影，超 6k 字符从尾部保留并插入截断说明；循环在 `deriveMessages` 后应用，并落 `request/header`。
- **Guard 审计回接**：对话循环的 `tools/post-execute` 写 `ToolUsageLog`，与 AICore 共用同一套记录。

## v1.114.0 (2026-09-13)

### 真机前自动化测试体系（Robolectric）+ 低版本兼容修复

- **Robolectric 接入**：容器无 `/dev/kvm`（Firecracker microVM 内无 vmx/svm），模拟器不可用，改用 Robolectric 在 JVM 上运行真实 Android 框架。探针实测 `SDK_INT=33`、`getPackageName()` 返回真实包名，确认非 `returnDefaultValues` 桩。
- **单测 85 → 110**：新增 `CareToolHostTest`（护理工具 seam 的 schema/截断/流水线接线）与 `ToolGovernanceTest`（禁用短路、审计落盘、参数留存、边界值）。
- **修 6 处 minSdk 21 崩溃点**（lint `NewApi`，真机低版本会 `NoSuchMethodError`）：`ByteArrayOutputStream#toString(Charset)`（需 33）、`Map#getOrDefault` / `List#sort` / `ArrayList#sort` / `Collection#removeIf`（需 24）、`AlarmManager#setAndAllowWhileIdle`（需 23）。
- **修 20 处 locale 敏感调用**：`toLowerCase()` / `toUpperCase()` / `String.format` 补 `Locale.ROOT`，避免土耳其语等区域下工具名与关键词匹配失效。lint `DefaultLocale` 警告 20 → 0。
- **修一处静默失败**：`play_motion` 在执行层不可用时返回成功，模型会以为动作已播放、后续决策基于假前提，改为显式报错。
- **策略装配收敛**：新增 `ToolGovernance.install()` 统一装配禁用策略与使用日志，AICore 不再内联这段逻辑。
- **自动验证脚本**：`tools/verify.sh` 一条命令跑完编译 + 单测 + lint + APK（约 41 秒），失败即打印详情中止。

## v1.113.0 (2026-09-13)

### Harness 内核化：seam 拆分、单测覆盖、大脑收敛

- **LLM seam**：新增 `LlmAdapter`（Service Definition），`LLMClient` 实现之，`AgentLoop` 只依赖接口——循环首次可在无网络下被脚本化 provider 驱动。
- **85 个内核单测**：覆盖会话投影（含 tool_calls 配对、system 截断说明）、waterfall 委托与短路语义、`generation` 防过期回调串轮、step 上限收尾、取消路径、工具把关与审计。`testOptions.unitTests.returnDefaultValues` + `junit`/`org.json` 依赖。
- **工具装配插件化**：`delegate_task` 装配移入 `SubagentRunner.installDelegateTool`，ChatActivity 不再手工拼工具循环。
- **子智能体改为真 seam**：从裸 `LLMClient` + `CountDownLatch` 改为独立 harness 实例（`bootIsolated`，不覆盖主对话引用），共用同一套 plugin 树，差异只在 `SubagentPreset`（提示词 + 工具白名单 + 步数上限）；新增 `ScopedTools` 在 schema 与执行两侧同时收敛权限。删除已被替代的 `AgentTeam`。
- **护理大脑收敛**：`CareAI.doConverse` 改由 harness 投影历史、驱动 step；新增 `CareToolHost` 适配 `CareTools`（含 `play_motion`）。保留护理侧两处行为——结果成败仍用 `isToolResultOk` 判定（`CareTools` 内部吞异常并返回「❌ …」文本），工具往返补写回内存历史。
- **修复工具日志断流**：`ToolUsageLog` 原先由 `AgentBrain` 的 post hook 写入，该文件删除后日志彻底断流，现由 `AICore` 的 `ToolPipeline` 接回；补测试锁住「被拒绝的调用也要留审计记录」。
- **删除死代码**：`AgentBrain` 全仓库无实例化点（`AICore` 已取代）。
- **本地构建环境**：新增 `tools/local-build.sh` 固化 JDK/SDK 路径，`gradle.properties` 按容器内存下调堆（1024m + 单 worker），可在无 Android Studio 环境下编译、跑测试、出 APK。

## v1.112.0 (2026-09-13)

### DeepSeek Harness（一切皆插件）

对话大脑改为 DeepSeek Harness 形态，对齐 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的插件树 / 会话日志 / agent-loop。

- **插件内核**：`HarnessContext` 贡献服务、类型化事件与可逆副作用；`chat` profile 挂载 session / system-prompt / tools / agent-loop / guard。
- **轮次流程**：`turn/start` → `agent/pre-step` → `step/start` → 流式请求 → `tool/call` 经 `tools/pre-execute|execute|post-execute` → 欠下一步则续 step（上限 6）→ `turn/end`。
- **模型可见即已记录**：`SessionLog.deriveMessages()` 从仅追加事件投影模型历史；取消与失败写入 `assistant/attempt`。
- **能力 seam**：工具禁用走 guard 的 `tools/pre-execute` waterfall；控制台展示插件树与会话事件。

## v1.24.0 (2026-08-30)

### 智能体升级（7 大模块全量升级）

- **1. 工具系统**（核心）：内置工具从 5 个扩展到 20+（新增 15 个），覆盖表达/记忆/系统/实用/信息 5 大类。ToolMarketActivity 可视化开关、查看今日调用统计。Hook Runner 拦截器链（pre/post/error）：权限检查（用户禁用工具直接拒绝）、使用统计（ToolUsageLog 持久化 500 条）、上下文注入。MemoryTools 把记忆操作（search/recall/save/forget）作为 LLM 工具暴露。侧栏加「工具市场」入口。参考 Operit AI 工具市场 + Hook Runner 架构。
- **2. 记忆系统重构**：MemoryRetriever 混合检索（最近 20 + 关键词 10 + 高重要度 5，时间衰减 + 重要度加权）+ MemoryExtractor 每 6h LLM 自动提取关键信息到 facts 表 + MemoryEntry 统一条目 + MemoryGraphView 词云可视化（按分类着色）。MemoryManageActivity 加"AI 提取"按钮和词云头部。系统 prompt 接入，让 LLM 看到"我记起来…"。借鉴 Operit AI hybrid retrieval + auto-extraction。
- **3. 角色系统 Persona**：Persona（角色卡：人格/API配置/记忆空间/工具集/Live2D/语音）+ PersonaStore（SharedPreferences 持久化，默认角色"小汐"）+ PersonaManager（全局入口 + 切换回调）+ PersonaActivity（卡片式 UI，可创建/编辑/删除/切换）。AgentBrain system prompt 接入 Persona 人格。侧栏加「角色管理」入口。借鉴 Operit AI per-character binding。
- **4. 计划模式 + 主动行为**：PlanExecutor 解析 LLM 回复中的 `{"plan":[...]}` JSON 段，顺序执行多步工具调用（每步结果反馈 LLM 继续决策）；ProactiveEngine 每 30 分钟 tick 一次，根据时间/情绪/未读时长触发主动气泡（"主人～晚上好"）+ 系统通知（锁屏可见），内置静默时段（23-8）、每日上限（6 次）；AgentNotifier（NotificationCompat + BigTextStyle + Android 8+ 渠道）。AICore system prompt 加 plan 提示。PetService 1Hz tick 循环每 30 分钟调度一次。借鉴 Operit AI multi-step plan + proactive tick。
- **5. 智能体控制台 AgentConsoleActivity**：4 个 Tab（活动 / 工具日志 / 计划 / 脑日志），1s 实时刷新，显示 AICore 忙碌状态、Hook 钩子数、最近 200 条工具调用、最近 150 条脑日志、最近 50 个计划步骤。底部操作：主动互动 / 清空 / 导出。BrainLog 500 条 ring buffer，AICore / PlanExecutor / PetService 关键节点打日志。侧栏加「智能体控制台」入口。借鉴 Operit AI agent log console。
- **6. 视觉精致化**：PersonaActivity 当前角色卡 `bg_card_active`（淡紫渐变 + 紫色描边）高亮。ToolMarketActivity 加总览卡（今日调用/已启用/分类 3 列统计），分类 header 紫色 pill 徽章。AgentConsoleActivity 补渐变顶栏。新增 `bg_card_active.xml` / `bg_category_badge.xml` / `bg_summary_card.xml` 三个 drawable。借鉴 Operit AI 统计卡 + pill badge。
- **7. 开发者工具 + 文档**：DeveloperActivity 重写：版本信息（App/角色/智能体/Java/Android）、智能体状态（Hook 计数/脑日志/工具调用）、调试操作（导出/清空脑日志 + 工具日志到剪贴板）。ToolUsageLog 加 `getInstance()` 单例。

## v1.23.2 (2026-08-30)

### 改进
- **路由占位升级**：THEMES / DEVELOPER / ABOUT 三个高级路由从 toast 占位升级为独立 Activity（ThemesActivity / DeveloperActivity / AboutActivity），含 Operit 风格顶栏（渐变 + 返回按钮）+ 深色内容区骨架。
- **侧栏 Header 精致化**：纯文字 Header → 左侧 48dp 圆形头像（ic_launcher）+ 右下角绿色在线状态点 + 右侧"数字生命"品牌名 + "v1.23.2 · 小汐 · 在线"状态行，对齐 Operit AI DrawerContent Header 结构。
- **顶栏副标题 letterSpacing**：加 0.04em 字间距，提升精致感。
- **OperitDrawerItem subtitle 启用**：每个菜单项显示简短描述（如"和她说说话，聊聊今天"），不再只有标题。
- **全链路深色适配**：批量替换所有页面的 page_bg/text_primary/text_secondary/text_hint → operit_bg/operit_text_*，消除浅色残留。覆盖 ConversationTabView / ContactsTabView / DiscoverTabView / PluginTabView / ChatActivity / MemoryManageActivity / ApiProfileSection / SettingsTabView / PetOverlayView / CareModelsActivity / UiKit 工具类。

## v1.23.1 (2026-08-30)

### 改进
- **深度还原 Operit AI 视觉质感**：逐像素对照 Operit AI `CompactNavigationDrawerItem` / `NavigationDrawerAppearance` / `CustomScaffold` 源码，消除"硬编码纯色"廉价感。
- 顶栏：纯色 `#6D5BBA` → 紫→深紫渐变 drawable（`bg_operit_topbar.xml`），90° 角度。
- 侧栏：纯色 `#2A2A2A` → liquidGlass 背景（`bg_operit_drawer.xml`：半透明 `#B32A2A2A` + 高光 `#0DFFFFFF`，左上左下 16dp 圆角，8dp elevation）。
- 侧栏分组：加 accent 色 divider（`operit_divider`，alpha 0.42），header 与 primary/advanced 之间各一条。
- 菜单项：纯背景色切换 → `operit_drawer_item_background` selector + `operit_drawer_item_ripple` RippleDrawable（水波纹 touch feedback）+ `operit_drawer_item_elevation` StateListAnimator（默认 4dp / 选中 6dp shadow）。
- 选中态：纯紫底 → `primaryContainer (#2A2545)` 半透明叠加（`#402A2545`，alpha 25%）+ accent 前景色（图标/标题/chevron 全紫），更接近 Material You `selectedContainerColor`。
- 图标尺寸 22dp → 20dp（Operit `Modifier.size(20.dp)`），垂直间距 12dp → 4dp（Material compact 8dp / 2 = 4dp）。
- 侧栏开合动画：线性 220ms/180ms → `DecelerateInterpolator(1.8f)` 缓出曲线，打开 280ms / 关闭 220ms；主内容区加 20dp 视差平移（侧栏滑入时内容右移，模拟 Operit ModalNavigationDrawer 主内容偏移）。
- statusBar：深色顶栏适配浅色图标（API 30+ 清除 `APPEARANCE_LIGHT_STATUS_BARS`；低版本走 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`）。
- colors.xml：新增 `operit_primary_container`（`#2A2545`，深浅模式同色），对齐 Material You `primaryContainer` 角色。

## v1.23.0 (2026-08-29)

### 重构
- **全量重构仿 Operit AI 视觉骨架**：将原生 Bottom-Tab 导航换成 Operit 风格的深色紫色 Material 化壳。
- **Operit 侧栏 (ModalNavigationDrawer)**：顶栏左侧汉堡按钮 (☰) 唤出 280dp 宽抽屉，分"数字生命"和"高级"两组共 10 个菜单项（对话 / 模型小房间 / 记忆与发现 / 插件 / 设置 / 记忆管理 / 护理大脑 / 主题 / 开发者 / 关于），选中态紫底高亮 + 紫色 accent 图标。点击遮罩或菜单项关闭侧栏。
- **Operit 路由控制器 (NavController)**：新增 `com.digitallife.ui.shell` 包 (`OperitRoute` / `OperitContentView` / `OperitNavController` / `OperitDrawer` / `OperitDrawerItem`)，5 个主壳 Tab 通过路由工厂 `obtain` 缓存 View 复用（切走再切回保留滚动 / 输入状态），高级路由走 Intent 跳子 Activity。移除 70+ 行 Bottom-Tab 切换冗余代码，初次启动读 `last_route` 恢复上次路由。
- **设置页 Operit 化**：根背景换 `#1A1A1A`，顶部加紫色 Operit 风格角色卡 (OverviewCard) 展示"数字生命·小汐" + StatChip 行 (版本/配置状态)，4 个分组 (启动与权限/桌宠与功能/互动/开发者与调试) 改用 `operit_surface` 卡片背景。
- **Operit 工具组件 (UiKit)**：新增 `sectionTitle` / `listTile` / `switchTile` / `roleCard` / `statChip` 5 个深色紫色风格工具，供后续子页 (主题/开发者) 复用。
- **Operit 主题色 (colors.xml)**：新增 12 个 `brand_operit` / `operit_bg` / `operit_surface` / `operit_surface_variant` / `operit_text_*` / `operit_accent` 颜色，深浅模式同色，定位为强制深色 App 主题基础。

### 新增
- **长按桌面图标快捷菜单**：Android 7.1+ ShortcutManager 动态注册 2 个动态快捷方式：
  - 「打开悬浮窗」一键启动 PetService 显示桌宠悬浮窗（无需打开 App）
  - 「停止桌宠」一键关闭悬浮窗
  点桌面图标仍走 MainActivity，长按弹系统菜单，两条入口独立。

## v1.22.0 (2026-08-29)

### 改进
- 护理/对话思考提示：原先 LLM 等待期只显示 "……"，现在分阶段动态提示「正在发送 → 等待 AI 响应 → AI 思考中 → 模型推理中」，让用户清楚知道当前进度，避免"干等"焦虑。
- 工具执行状态标识：工具过程卡片的状态行加 🔄 图标，与完成后的 ✅/❌ 视觉上区分更清楚。
- 对话导出功能：顶栏新增「导出」按钮，支持导出当前会话为 Markdown 或纯文本格式，走系统分享面板，可分享给微信、邮箱、便签等任何应用。
- 导出 Markdown 含会话元信息（标题、导出时间、会话类型、消息数）和每条消息的时间戳。

## v1.21.0 (2026-08-29)

### 改进
- 设置页分组折叠：把原先 7 个扁平卡片按「启动与权限 / 桌宠与功能 / 互动 / 开发者与调试」四组分块，「开发者与调试」默认折叠，减少视觉密度，让常用项更突出。新增 `UiKit.expandableCard` 通用折叠组件。

## v1.20.0 (2026-08-29)

### 修复
- CareAI：纯文本回复后无限递归 LLM 请求
- MemoryStore：迁移逐条 trim 导致早期历史丢失
- PetService：无障碍监听与 1Hz Runnable 泄漏
- ChatActivity：buildFileContext 附件读取未关闭流
- CI：仓库 p12 缺失导致 `assembleDebug` 签名失败 → 改为 `actions/cache` 持久化密钥
- CI：artifact storage quota 满导致无法发布 → 改为 CI 内直接创建 GitHub Release 并上传 APK

### 新增
- 记忆管理功能：设置页查看/编辑/删除/导出/导入长期记忆
  - 分类查看：事实/画像/事件、每日摘要
  - 单条编辑/删除（修正错误记忆）
  - 导出 JSON 备份、导入恢复（换机延续）
  - 规格见 `.monkeycode/specs/memory-management/`

### 改进
- CI 签名机制：`actions/cache` 固定 key 持久化 p12，签名长期稳定
- GitHub Release 直接发布 v1.20.0，APK 作为 release asset

## v1.19.0

修复 8 个 bug 与护理大脑工具失败，完成 4 项体验打磨。
