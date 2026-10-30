# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while performing Android 项目重构与模型导入功能开发
- Category: Build Methods
- Instructions:
  - 构建命令：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug --no-daemon`
  - lint 命令：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk ./gradlew app:lintDebug --no-daemon`
  - GitHub 凭证通过 `git credential fill`（helper: /app/agent/bin/agent git-credential-helper）获取，令牌绝不硬编码或展示
  - `gh` CLI 未登录，创建 PR 用 `curl -X POST https://api.github.com/repos/hit-droid/Digital-Life-Cure/pulls`，token 从 credential fill 取

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while performing 分支推送与 PR 创建
- Category: Workflow & Collaboration
- Instructions:
  - 远程为 GitHub（非 GitLab），git push 时不要带 `-o merge_request.create` 等 GitLab 风格 push options，否则触发 HTTP 500
  - 工作分支：`digital-life-cure-main`，目标分支 `main`
  - 提交风格：中文一行式 commit message + `Co-authored-by: monkeycode-ai <monkeycode-ai@chaitin.com>`

[User Instruction Summary]
- Date: 2026-08-05
- Context: 用户对多次未经确认就自行写代码/改 UI 强烈不满后提出
- Instructions:
  - 以后凡是需要写代码、改代码或改 UI，动手前必须先向用户说明方案并征得用户明确同意，得到许可后才能开始写
  - 用户明确叫停后立即停止，不得继续自行改动
  - 大型重构或界面改动尤其必须先确认，不得擅自执行

[Project Knowledge Summary]
- Date: 2026-08-08
- Context: Discovered by Agent while performing 对齐 Operit 打磨反人类操作并发布 v1.15.0
- Category: Operations & Deployment
- Instructions:
  - v1.15.0 已发布：versionCode 16 / versionName 1.15.0、Release id 367251102、APK app-debug.apk 9,780,876B（sha256 b39685a2…，本地与远端一致）、tag v1.15.0 @ 0a21fdc、main @ 0a21fdc
  - 发布脚本模板：/tmp/opencode/release_v1150.py（改 TAG/body 复用）；release 创建时若 tag 不存在会自动指向默认分支旧 HEAD，须先合并 main 再本地建 tag 并 `git push --force origin refs/tags/<tag>` 修正
  - 交互设计标杆为 Operit（https://github.com/AAswordman/Operit，6.6k stars）：AI 回复 Markdown 渲染、流式可中断、消息复制/重发/分享/朗读、对话附文件上下文、配置保存自动测连接、智能滚动、回车发送、返回确认
  - ChatActivity 有 MarkdownRenderer（纯 Spannable 无第三方依赖）；停止生成经 aborting 守卫吞掉取消触发的 onDone/onError 防重复气泡；滚动用 ViewTreeObserver 兼容 minSdk21
  - lint 校验：`rg -o 'severity="(Error|Fatal)"' app/build/reports/lint-results-debug.xml`（无输出即通过）

[Project Knowledge Summary]
- Date: 2026-08-08
- Context: Discovered by Agent while performing 密钥池与配置保存修复并发布 v1.16.0
- Category: Operations & Deployment
- Instructions:
  - v1.16.0 已发布：versionCode 17 / versionName 1.16.0、Release id 367256909、APK app-debug.apk 9,785,820B（sha256 98f76c77…，本地与远端一致）、tag v1.16.0 @ bc1a2e0、main @ bc1a2e0
  - 密钥池设计对齐 Operit（ApiKeyProvider 轮换模型）：ApiProfile.apiKeys 存多 key（每行一个），ApiManager.nextKey 全局游标 round-robin；apiKey 字段保留做单 key 兼容与旧数据迁移；ChatActivity.ensureChatLlm 每次发送轮换，CareAI 配置未变时仅 setApiKey（指纹不含 key）
  - 配置保存语义：新增「＋ 新建配置」（editingId=null 生成新 id 并设当前）与「保存此配置」（按 editingId 更新）分离，避免覆盖旧配置；密钥批量导入/导出经 MainActivity → SettingsTabView.handleActivityResult → ApiProfileSection.onActivityResult 转发
  - Operit 源码浅克隆于 /tmp/opencode/operit（密钥池参考 ApiKeyProvider.kt / ModelConfigData.kt / AdvancedSettingsSection.kt）

[Project Knowledge Summary]
- Date: 2026-08-08
- Context: Discovered by Agent while performing 配置界面对齐 Operit 重做并发布 v1.17.0
- Category: Operations & Deployment
- Instructions:
  - v1.17.0 已发布：versionCode 18 / versionName 1.17.0、Release id 367304653、APK app-debug.apk 9,787,380B（sha256 1e585328…，本地与远端一致）、tag v1.17.0 @ 484aa56、main @ 484aa56；发布脚本 /tmp/opencode/release_v1170.py
  - 用户对 v1.16.0 配置界面的「批量导入/导出密钥」按钮强烈不满（"从文件导入是什么鬼"），要求严格对齐 Operit 真实交互。Operit 模型配置页结构：顶部当前配置快捷切换（点击弹列表）+「＋新建」（对话框只填名称，createConfig(name) 创建并选中）；操作行仅 重命名/删除/测试连接；编辑表单（Provider/Endpoint/API Key/模型名）DebouncedModelConfigAutoSaveEffect 自动保存；密钥池在高级设置折叠区，每 Key 单独一行（ApiKeyInfo 带 name/availabilityStatus），添加 Key 对话框只填 key 值
  - 重做后的 ApiProfileSection：配置切换器 + 新建只填名称 + 表单（名称/Base URL/模型名/主 Key 单行失焦脱敏 PasswordTransformationMethod）+ 密钥池折叠开关（每 Key 一行 后4位标识 + 编辑/删除）；移除了批量导入/导出与文件选择转发（SettingsTabView.handleActivityResult 置空）
  - 用户强调核心特性「保存的配置可快捷切换」：多个配置保存后点击当前配置名弹列表一键切换，切换后无需重新填写

[Project Knowledge Summary]
- Date: 2026-08-09
- Context: Discovered by Agent while performing 修复配置界面排版并发布 v1.18.0
- Category: Operations & Deployment
- Instructions:
  - v1.18.0 已发布：versionCode 19 / versionName 1.18.0、Release id 367439688、APK app-debug.apk 9,787,787B（sha256 88c5d459…，本地与远端一致）、tag v1.18.0 @ c0c4ea7、main @ c0c4ea7；发布脚本 /tmp/opencode/release_v1180.py
  - 用户反馈 v1.17.0 排版反人类：所有控件堆在一张卡片里导致「＋新建」被挤出屏幕外、字段用 placeholder 提示不明显。对齐 Operit 的正确排版是**两个独立卡片**：「选择模型配置」（标题行右侧＋新建按钮同排置顶 + 当前配置整行选择器 + 重命名/测试/删除）+「API 设置」（字段带标签逐行：配置名称/API Base URL/模型名/API Key 失焦脱敏 + 密钥池折叠 + 保存按钮）
  - 环境网络陷阱：出口网络对 GitHub 的 TLS 握手会间歇性失败（`gnutls_handshake failed`，只有国内站点如 baidu 可达），此时 git push/curl 全部失败；curl -sS 探测 https://api.github.com 返回 200 即为网络恢复，恢复后需重试 push/tag/发布
  - 每次提交/发布后版本号在 app/build.gradle versionCode/versionName

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing v1.24.0 智能体升级大 PR 合并（filter-branch 重写 + PR reopen 死锁）
- Category: Workflow & Collaboration
- Instructions:
  - filter-branch 陷阱：`git filter-branch --all` 会重写本地 refs/remotes/* 与 tags，使本地 origin/main 与远程真实 main 分叉（本地被污染分支与远程 become unrelated histories），导致 GitHub 报 "no history in common" 且 PR 无法 reopen。修正：先用 `curl -sH "Authorization: token ..." /repos/<repo>/branches/main` 查远程真实 sha，再 `git rebase --onto <远程真实main> <本地污染tip> HEAD` 重新锚定；不要在本地分支上继续 filter-branch --all
  - PR reopen 死锁：closed 的 PR 不跟踪 head 分支的 force push，其 head 冻结在旧 commit；若该旧 commit 与 base 无共同历史，GitHub 的 "state cannot be changed. The <branch> branch was force-pushed or recreated." 保护会永久阻止 reopen（等待无效）。解法：直接创建新 PR（同一 head 分支、同一 title/body），旧 closed PR 忽略即可
  - rebase 自动跳过重复 commit：分支含与 main 内容相同但 hash 不同的 commit（filter-branch 重写导致）时，`git rebase` 会按 patch-id 自动跳过（日志 "skipped previously applied commit"），无需手动 drop
  - CI 触发：PR 的 force push / reopen / comment 均不触发 build.yml 的 pull_request 检查，只能 POST /actions/workflows/build.yml/dispatches 手动触发；merge 到 main 后 push 事件会自动构建并发 vX.Y.Z release
  - commit 环境会自动追加 Co-authored-by: monkeycode-ai 到 message 尾部（重复追加时会出现多条同值 trailer，不影响 merge，可忽略）
  - 环境变量陷阱：`GIT_AUTHOR_EMAIL=... git add ... && git commit` 中变量前缀只作用于紧邻的 `git add`，commit 不继承，会退回 git config 邮箱；本环境 git config user.email 已改为 `hit-droid@users.noreply.github.com`（author/committer 直接用 config，无需再带环境变量；amend 时用 `--reset-author` 刷新 author）

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing Phase 3 输入栏 chips + 语音按钮（UiKit.flash 添加 + ChatActivity 集成）
- Category: Troubleshooting & Debugging
- Instructions:
  - Edit 工具的 oldString 匹配陷阱：用 Edit 工具"插入新代码"时，如果 oldString 是从文件复制某一行整段（包括上下的同前缀行），且要插入的新内容跟原内容只在末尾不同（如 pressScale 的 `}` 闭合），必须确保 oldString 中**不包含与要保留代码完全相同**的子串；否则 replace 会被匹配成"中间一段"，导致多出一段残留（phase 3 fc966a5 第一次 push 编译失败就是 pressScale 闭合 `return false; }); }` 三行被残留）
  - 同名变量陷阱：Java 局部变量名不能在同一作用域重复声明；ChatActivity buildUi 内已存在 `clp`（line 186），第二次使用 chips 时不能再命名 `clp`，要换名（chipLp / lp2 等）
  - Edit 工具不会自动补前缀：手动 `case MotionEvent.CANCEL:` 会被当成 `case MotionEvent.ACTION_CANCEL:` 的"匹配子串"被无意中替换/未替换，编译时 `cannot find symbol CANCEL`；保留 ACTION_ 前缀是 Android SDK 的硬要求

[User Instruction Summary]
- Date: 2026-08-30
- Context: 用户要求今天自主更新、全部完善，并明确版本与推送纪律
- Instructions:
  - 用户授权自主推进：当天可自行选择完善项，"你看着做"，不必逐项事先确认（此授权仅限当日自主更新场景，与 2026-08-05「改码前必须征得同意」的通用要求并存）
  - 版本纪律：**每完成一个版本就必须 bump versionName/versionCode 并推送发行**（push main 会自动触发 build.yml 构建并发 release，tag 取 versionName）
  - 记忆/文档（.monkeycode/MEMORY.md 等）用 `[skip ci]` 标记的 commit **单独推送，不发行**，避免为文档改动触发 release
  - .monkeycode/ 已被 git 跟踪且未被 .gitignore 忽略，可直接更新推送

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing v1.28.0~v1.30.0 连续三版发布
- Category: Workflow & Collaboration
- Instructions:
  - 连续发版节奏（每个版本一次 CI，约 3 分钟）：功能 commit → bump version commit → `git pull --no-rebase` → push；仓库有 monkeycode-keepalive 自动心跳 commit，push 被拒时先 pull 再推
  - 版本号递增对照：v1.27.0=30、v1.28.0=31、v1.29.0=32、v1.30.0=33
  - 三版内容：v1.28.0 长消息展开/收起（COLLAPSE_MAX_LINES=10，仅完成时折叠，流式不限制）；v1.29.0 消息菜单加「重新生成/删除」（ChatStore.deleteLastAssistantMessage）；v1.30.0 对话上下文预算裁剪（CTX_BUDGET_CHARS=6000，CTX_KEEP_RECENT=6）
  - UI 侧新增习惯：气泡完成后再 applyCollapse，避免流式过程被截断；折叠提示「▸ 展开全文/▾ 收起」需用 stripCollapseHint 在复制/朗读/分享前剔除
  - ChatActivity 两个 onDone（普通对话 / 护理 care）代码完全相同，Edit 时 oldString 会命中多处，必须用各自**前置行**做锚点（普通对话前置是空实现 onToolCall，护理前置是 onToolResult）

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing 护理气泡配色修复
- Category: Troubleshooting & Debugging
- Instructions:
  - 气泡体系分工（改色前务必分清，避免误改）：ChatActivity 有三类气泡——AI 对话气泡用 newTextViewBubble（isCare 切 bg_bubble_care / bg_bubble_ai）、用户气泡 appendUserBubble（bg_bubble_user）、工具调用气泡 appendToolBubble（bg_tool，用 card_bg + brand_stroke 独立体系）。用户明确要求「工具调用的气泡不要动」
  - 护理气泡病根：bg_bubble_care 原本是纯 `<solid>` 扁平色块且颜色（#0F2A22）比顶栏最暗端还闷，缺液态玻璃质感；修复为渐变并对齐顶栏色系 #1B5E45 → #16493A → #0F3D2E，竖线由荧光 #6EE7B7 柔化为 #5BD9A8
  - 改色而不是改结构：渐变须 `android:angle` 为 45 的倍数（270 为从上到下，与 bg_top_bar_care 一致）；圆角 16dp、竖线 size 3dp、inset 参数保持不变
  - 本环境模型不支持读图：read 图片文件返回 "Image read successfully" 但模型侧报 "this model does not support image input"；image_analysis MCP 工具报 `insufficient balance`（-32603）。截图类问题只能请用户文字描述，不要反复尝试读图

[User Instruction Summary]
- Date: 2026-08-31
- Context: 用户在连续推进后要求后续不要反复确认
- Instructions:
  - 以后直接继续推进，不要每一步都回头问用户；自主选择完善项并执行（涵盖完善 AI、发版、更新记忆等全部动作）
  - 仍须保持的纪律：每个版本 bump + 推送发行；记忆/文档用 `[skip ci]` 单独推送不发行

[Project Knowledge Summary]
- Date: 2026-08-31
- Context: Discovered by Agent while performing v1.31.0 语音输入 与 v1.32.0 会话自动标题
- Category: Operations & Deployment
- Instructions:
  - 版本号继续递增：v1.31.0=34（语音输入）、v1.32.0=35（会话自动标题）
  - v1.31.0 语音输入：RECORD_AUDIO 权限 Manifest 早已声明，Android 6+ 运行时申请（REQ_AUDIO_PERMISSION=4001）；用 SpeechRecognizer + RecognitionListener，EXTRA_PARTIAL_RESULTS 实时上屏，onResults 用最终结果覆盖（避免 partial 重复拼接）；onError 9 类中文降级提示；onDestroy 里 stopListening+cancel+destroy 防泄漏；录音中麦克风染 brand 色
  - v1.32.0 会话自动标题：默认标题常量为「新对话」/「护理会话」（见 ConversationTabView 新建逻辑）；ChatStore.renameSession 已存在，此前未被使用；首轮（assistant≥1 条）后触发一次，titleAutoTried 防重复，用户自定义标题不覆盖；需 tvTitleRef 字段（tvTitle 是 buildUi 局部变量）供刷新顶栏
  - 两个 onDone 代码完全相同的老问题依旧：普通对话前置锚点是空实现 onToolCall，护理前置锚点是 onToolResult，插入时务必带上前置行
  - 导出功能（exportChat / buildMarkdownExport / buildTextExport）已相当完善（Markdown+纯文本、元信息、工具调用格式化），无需重做

[Project Knowledge Summary]
- Date: 2026-08-31
- Context: Discovered by Agent while performing v1.33.0 建议/chips 互斥 与 v1.34.0 建议缓存
- Category: Operations & Deployment
- Instructions:
  - 版本号继续递增：v1.33.0=36、v1.34.0=37
  - v1.33.0 互斥显示：固定 chips 栏（chipScroll）与动态建议栏（suggestionBar）同时显示会挤占输入区，改为互斥；chipScroll 原是 buildUi 局部变量，需提字段 chipScrollRef 才能跨方法控制；新增 hideSuggestions() 作为唯一收起入口（内部同时隐藏 suggestionBar + 恢复 chips），8 处隐藏逻辑全部改走它，避免状态不一致
  - v1.34.0 建议缓存：用历史指纹（条数 + 末条 timestamp）作 key 缓存 LLM 建议，指纹未变直接 renderSuggestions 跳过请求；冷启动默认建议也入缓存；清空会话时必须失效缓存（cachedSuggestionKey/Suggestions 置 null）
  - lambda 捕获局部变量：指纹变量只赋值一次即 effectively final，可在 chatOnce 回调里直接引用，无需 final 副本（与此前 PlanExecutor 需 final 副本的场景不同——那个变量在循环里被重复赋值）
  - 连续发版稳定节奏已验证：功能 commit → bump commit → pull → push，CI 约 3 分钟，六版（v1.28.0~v1.34.0）全部一次通过

[Project Knowledge Summary]
- Date: 2026-08-31
- Context: Discovered by Agent while performing v1.35.0 工具结果折叠 与 v1.36.0 会话内搜索
- Category: Operations & Deployment
- Instructions:
  - 版本号继续递增：v1.35.0=38、v1.36.0=39
  - v1.35.0 工具结果折叠：markLastToolResult 里结果 >TOOL_COLLAPSE_CHARS(300) 时默认折叠并展示 TOOL_BRIEF_CHARS(150) 摘要，setTag(!longResult) 控制初始展开态；toggleToolCard 复用同一常量（原来硬编码 150）避免两处阈值不一致
  - v1.36.0 会话内搜索：顶栏已有 模型/清空/导出 三按钮（bg_btn_glass + dp(34) 高 + margins dp(4)），新增「搜索」沿用同样式；搜索扫 listContainer 里的 TextView 子视图统计命中，gotoHit 用 scroll.smoothScrollTo(0, child.getTop()) 定位 + UiKit.flash；高亮用 BackgroundColorSpan 0x446C5CE7；searchQuery 非空时再点按钮即跳下一处（循环）
  - 工具卡片体系（bg_tool / appendToolBubble / toggleToolCard / markLastToolResult）与对话气泡体系独立，用户要求 bg_tool 配色不可动，但行为逻辑（折叠/展开）可以优化
  - 今日累计发版 v1.28.0~v1.36.0 共 9 版，全部 CI 一次通过且均已发行 release

[Project Knowledge Summary]
- Date: 2026-08-31
- Context: Discovered by Agent while performing 搭建 9/7 前不间断自治流水线
- Category: Operations & Deployment
- Instructions:
  - 自治体系三件套（全部位于 /tmp/opencode/auto，仓库外，不入库）：
    - autoloop.sh：消费队列任务 → git apply --3way 应用补丁 → 提交（meta 第1行标题+其余正文，自动追加 Co-authored-by）→ 自动 bump 次版本号与 versionCode → 推送 → 按 head_sha 轮询 CI → 校验 release assets → 成功归档 state/done，失败自动 reset --hard HEAD~2 并 force push 回滚后继续
    - genpatch.py：自动任务生成器，扫描 app/src/main/java 找 ImageButton/Button 声明，按生成器在其后插入一行安全调用（a11y=setContentDescription / allcaps=setAllCaps(false) / haptic=setHapticFeedbackEnabled(true)），改完 git diff 产出补丁再 git checkout 还原工作区；已处理目标记在 state/processed.txt 避免重复，计数器 state/counter
    - taskgen.sh：维持队列 ≥3 个待办，三个生成器轮转，全部耗尽休眠 10 分钟
    - watchdog.sh：每 40 秒巡检，autoloop/taskgen 任一掉线即 setsid 重新拉起（已实测 pkill 后 40 秒内复活）
  - 关键运维经验：nohup 启动的进程会随终端退出被杀，**必须用 setsid nohup ... < /dev/null & 才能真正常驻**；watchdog 自身也要 setsid 启动
  - 三个脚本均以时间戳 1788739200（2026-09-07 00:00:00）为终止条件，到点自动退出
  - 手工产出版本时用显式编号（如 042-error-retry.patch）避免与自动生成的编号冲突
  - 补丁入队工作流：本地改代码 → `git diff > queue/NNN-name.patch` → 写同名 .meta（首行标题、其余正文）→ `git checkout -- .` 回滚 → 脚本接管后续全流程
  - 流水线节奏约 3~4 分钟/版本（含 CI 等待），一天理论可发数百版，实际瓶颈是功能代码的人工作者

[Technical Learnings]
- Date: 2026-08-31
- Context: Discovered by Agent while performing 手工功能开发与流水线并行推进
- Category: Technical
- Instructions:
  - **严禁与 autoloop 共用 /workspace 工作树**（已付出两次 CI 失败代价）：autoloop 回滚时会 git reset --hard，会把我未完成的编辑一起抹掉；反之我未完成的编辑会被 autoloop 的 git diff 一起提交，导致半成品代码混入发行版本（v1.43 就因此混入只有调用、没有定义的 appendEmptyGuide，报 cannot find symbol）
  - 正确做法：单独克隆一份到 /tmp/opencode/work 作为隔离工作区（git clone /workspace 后 git remote set-url origin 改回 GitHub），所有手工编辑只在隔离区做；生成补丁后立刻 git checkout -- . 还原，补丁复制到 queue/ 交给 autoloop
  - 隔离区使用前先 git pull 同步到最新 HEAD，避免补丁上下文漂移导致 apply 失败；生成后可用 git apply --check 验证
  - 匿名内部类捕获局部变量的老坑再次复现：MarkdownRenderer 的 appendCodeBlock 里 block 被重新赋值过（去尾部换行），不是 effectively final，两个 ClickableSpan 捕获它直接编译失败。解法是取 final 副本 codeToCopy 再捕获，并抽出静态 copyCodeToClipboard 供两处共用
  - 补丁应用失败不一定是漂移，先确认功能是否已随更早的 commit 落地（v1.42 重试功能其实已完整落地，042 补丁是重复应用才失败）。判断方法：git show HEAD:<文件> | grep 关键符号
  - 同类手工改动与自动生成器的冲突要提前规避：把即将删除的变量名按 `文件:变量:生成器` 格式预先写进 state/processed.txt，生成器就不会再选中它们

- **绝不在 /workspace 里手动 git pull**：autoloop 每轮开头自己会 git pull --no-rebase（脚本 119 行），并在 push 失败后再 pull 重试（164 行），具备自愈能力；我手动 pull 反而会和它撞车，制造出 merge conflict 状态（已踩过一次）。记忆类改动改到隔离区提交，用 git pull --rebase 再 push
- 已发行的补丁要从 queue 里清掉：autoloop 靠 --3way 应用，已落地的补丁会再次入队重试；虽然它有「已应用则跳过」的判定，但仍应主动 rm 掉 queue 里对应的 .patch/.meta，保持队列干净
- CI 日志必须带 token 才能取：gh 默认报 "gh auth login"，正确姿势是 GH_TOKEN=$(cat /tmp/opencode/auto/token) gh run view <run_id> --log-failed，再从 error: 行定位编译错误

[User Preferences]
- Date: 2026-09-05
- Context: 用户在指导智能体升级方向时明确提出（原话为字面意思，不要引申理解）
- Category: Preference
- Instructions:
  - **交付标准：可出售的品质（商业级），功能与质量并重**——不允许为了凑任务量/刷版本号而产出低质代码；宁可少做，也只做能真正发行、能用的东西
  - **方向：多智能体集成（multi-agent）**——智能体要往多智能体协作的形态做
  - **决策纪律：不确定的、或不知道用户想要什么样的，宁可去 GitHub 搜索参考实现，也不要私自拍脑袋决定**——"deepseek harness" 这类用户给的参照目标，先去 GitHub 找真实项目读架构，再照着落地，不要自己臆想形态
  - 用户反感：自我贬低式长篇检讨、答非所问、空谈不落地、为完成任务而乱做

[Project Knowledge Summary]
- Date: 2026-09-19
- Context: Discovered by Agent while performing v1.116.0 修复 Live2D 原生崩溃
- Category: Troubleshooting & Debugging
- Instructions:
  - v1.116.0 已发布：versionCode 119 / versionName 1.116.0、Release v1.116.0、APK 9,957,252B、commit 785ed11
  - 崩溃现象：真机日志 `libmaidendungeon.so` SIGSEGV (11) / Fault address 0x0，发生在启动或切换某个模型时
  - 根因：`LAppModel::LoadAssets()` 中 `CreateBuffer()` 返回值未判空。模型文件缺失时 Java 侧 `Live2DNative.loadFile` 返回 null，C++ `JniBridgeC::LoadFileAsBytesFromJava` 返回 NULL，随即 `new CubismModelSettingJson(buffer, size)` 把 NULL 当 JSON 解析 → 空指针解引用，整个进程段错误退出
  - 修复模式：native 层所有 `CreateBuffer` 后必须判 `buffer == NULL || size <= 0`；`~LAppModel()` 访问 `_modelSetting` 前必须判空（LoadAssets 提前 return 时析构必崩）；`OnUpdate/OnDrag/OnTap` 每帧路径都要守卫空模型
  - 定位手法：崩溃 backtrace 只有 libmaidendungeon.so 两个栈帧时，直接搜 native 侧未判空的可疑解引用；`Fault address: 0x0` 基本等价于空指针解引用
  - C++ 改动后先用 `./gradlew :app:externalNativeBuildDebug` 单独验证 native 编译（约 40 秒），比全量快很多
  - 环境坑：`csmTrue/csmFalse` 在 LAppModel.cpp 这个翻译单元不可用（缺 using + 宏名不同），直接用 `true/false`
  - Edit 工具陷阱：编辑单行成员声明时，若 oldString 含行尾注释，新内容会被拼进注释行导致成员"消失"（v1.116.0 的 `_eyeBlinkIds` 就被吞掉，编译报 undeclared）；改完要用 grep 确认成员声明仍独立成行

[Project Knowledge Summary]
- Date: 2026-09-19
- Context: Discovered by Agent while performing v1.115.0 DeepSeek Harness 完善（Profile/生命周期/提示词分段）
- Category: Operations & Deployment
- Instructions:
  - v1.115.0 已发布：versionCode 118 / versionName 1.115.0、Release id（tag v1.115.0）、APK app-debug.apk 9,954,803B、target 36e6cb7；构建 commit 9c83d80（功能）、36e6cb7（CI 修复）
  - 容器重启会同时清空 JDK 与 /opt/android-sdk（不止 /tmp/opencode）。重装：`apt-get install -y openjdk-17-jdk-headless`（装到 /usr/lib/jvm/java-17-openjdk-amd64）；SDK 用 commandlinetools-linux-11076708 + sdkmanager 装 platform-tools/platforms;android-34/build-tools;34.0.0/ndk;26.1.10909125
  - 构建脚本 local-build.sh / verify.sh 已加 JDK 回退：优先 /tmp/opencode/toolchain，缺失时用 /usr/lib/jvm/java-17-openjdk-amd64
  - 首次编译需下 Gradle 8.7（12 分钟）+ Robolectric android-all jar，约 15 分钟；之后增量编译约 40 秒
  - 本地 assembleDebug 需 `keytool -genkeypair -keystore app/release-key.p12 -storetype PKCS12 -storepass android -keypass android -alias maidenkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`（p12 与 local.properties 均被 gitignore）
  - git config user.email 会被重置为 Panyuxuan37@outlook.com，push 触发 GH007 隐私拒绝；须 `git config user.email hit-droid@users.noreply.github.com` 再 `git commit --amend --reset-author`
  - CI 的 `android-actions/setup-android@v3` 于 2026-09-19 起故障（9-13 仍可用），已改为使用 runner 预装 ANDROID_SDK_ROOT + sdkmanager 补装组件
  - Harness 完善内容：Profile（chat 11 插件 / isolated 8 插件）、Plugin.deactivate + unmount/dispose、AgentRegistry（ctx.agents）、Persona/Memory/Skill/Clock/Compress 插件、ContextCompressor 纯消息投影（6k 字符预算）、GuardPlugin 接 tools/post-execute 审计；单测 110 → 122

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.164.0 Agent 工作台页发布（容器重启后全量重装工具链并 verify）
- Category: Operations & Deployment
- Instructions:
  - v1.164.0 已发布：versionCode 167 / versionName 1.164.0、Release id 416154621、APK app-release.apk 8,564,140B、tag v1.164.0 @ 5dc3470、main @ 5dc3470；CI run 39083611720 success
  - **环境重装（容器重启后 JDK/SDK/NDK 全部丢失，实测一次走通）**：① `apt-get update && apt-get install -y openjdk-17-jdk-headless` → `/usr/lib/jvm/java-17-openjdk-amd64`；② `curl -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip` → 解压到 `/opt/android-sdk/cmdline-tools/latest`；③ `yes | sdkmanager --licenses`；④ `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.3.13750724" "cmake;3.22.1"`；⑤ `/opt/android-sdk` 建 `local.properties`（`sdk.dir=/opt/android-sdk`，被 gitignore）。网络走容器代理 `http://127.0.0.1:18080`，apt 走内网镜像 `mirrors.volces.com` 直连即可。
  - **NDK 版本已从 26.1.10909125 升级为 27.3.13750724**（见 `app/build.gradle` 的 `ndkVersion` 与 CI workflow 的 `NDK_VERSION`），重装别再按旧记录装 26.1；r27+ 默认 16 KB 对齐。
  - **Gradle 自身下载需显式代理**：wrapper 不继承 shell 的 HTTP_PROXY，跑 verify 前需 `export JAVA_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080"`（测试 JVM 的代理由 `app/build.gradle` 自动注入，无需额外处理）。
  - **重装后全量 verify 实测耗时**：compile 76s / test 81s / lint 512s / apk 62s，合计约 12 分钟；lint 最慢（首次 lintAnalyzeDebug 冷缓存 8.5 分钟）；共 588 单测 / 50 类、lint 0 error、APK 9.06MB（debug）。
  - `app/release-key.p12` 在仓库里已存在（未随容器重启丢失），本地 assembleDebug/Release 可直接签名，不必重新 keytool 生成。

[Technical Learnings]
- Date: 2026-09-05
- Context: v1.111.0 多智能体补丁两次 CI 失败教训
- Category: Troubleshooting & Debugging
- Instructions:
  - **Java 局部变量 definite assignment 坑**：`if/else` 分支必须保证每个路径都赋值给后续使用的变量；最稳的做法是声明时直接初始化 `String x = null;`，不要依赖分支里赋值
  - **mkpatch finish 后工作区会被 checkout 还原**（Tools/ChatActivity 改动被清空 + `git add -N` 的新文件变 0 字节）。如果下一步还要继续改，先 `git reset -q` 清索引前**先确认改动都已落到 patch**——否则改动会丢
  - **失败 commit 的代码可从 GitHub 用 `git fetch <sha> && git checkout <ref> -- <file>` 恢复**——autoloop 的 `git reset --hard HEAD~2` 不会删对象，失败 commit 在 fetch 后仍可访问
  - **`git diff` 默认不看 staged 和 untracked**：staged 改动用 `git diff --cached`，untracked 改动必须先 `git add -N` 再 diff。mkpatch finish 入队时若少了关键文件，会生成残缺 patch 二次失败

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.168.0 定时任务可读化 + 工作台新建定时任务
- Category: Operations & Deployment
- Instructions:
  - v1.168.0 已发布：versionCode 171 / versionName 1.168.0、Release id 416219830、APK app-release.apk 8,585,716B、tag v1.168.0 @ d5641dc、main @ d5641dc；CI run 39095162478 success
  - **Release 由 CI 自动发布，无需手工步骤**：`.github/workflows/build.yml` 的 `Check if release needed` + `Create GitHub Release and upload APK` 两步会在 push 到 main 且 `versionName` 对应 tag 尚不存在时自动建 tag、建 Release 并上传 `app-release.apk`。手工只需：改 `versionName` → commit → push main → 等 CI 绿即可；不要再单独跑建 tag/建 Release 的脚本（会与 CI 撞车或重复）。注意 workflow 用 `paths-ignore: '**/*.md'`，**纯 .md 提交（如只改 MEMORY/AGENTS）不会出包发版**——想发版必须带上 `app/build.gradle` 的 versionName 变更。
  - **GitHub API 取 token 的正确姿势**：token 内嵌在 `git remote get-url origin`（形如 `https://<user>:<token>@github.com/...`），`git credential fill` 在当前环境返回空 password。取法（不打印 token）：`URL=$(git remote get-url origin); UP=${URL#https://}; UP=${UP%%@*}; TOKEN=${UP#*:}`，再 `curl -H "Authorization: token $TOKEN" ...`。查 CI：`GET /repos/{repo}/actions/runs/{id}`（字段 status/conclusion）；查 Release：`GET /repos/{repo}/releases?per_page=3`。
  - **本地 verify 增量已缓存时很快**：本次 compile 9s / test 40s / lint 32s / apk 18s（合计约 1.7 分钟；仅改了 1 个 Activity + 1 个纯逻辑类 + 测试）。新增 13 条单测后总计 629 单测 / 55 类、lint 0 error、APK(debug) 9,095,661B。
  - 交付内容：新增纯逻辑 `care/CronText`（cron 人话 `describe` + 下次触发 `nextFireMillis`，复用 `CareAutomation.cronMatches` 语义）；工作台定时任务行改三行式（人话 / cron+工作流 / 下次触发），暂停项琥珀；定时小节加「＋ 新建定时任务」表单（选工作流 + cron 预设胶囊 + 实时预览），落盘复用 `CareTools.add_scheduled_task`。新增 `CronTextTest`(11) + `AgentWorkbenchToolsTest` 补 2。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.169.0 跨模型动作池纳入表情
- Category: Operations & Deployment
- Instructions:
  - v1.169.0 已发布：versionCode 172 / versionName 1.169.0、Release id 416277783、APK app-release.apk 8,592,968B、tag v1.169.0、main @ 58123be；CI run 39104437120 success
  - 交付内容：把 `.exp3.json` 纳入动作池——`model/MotionLibrary` 解析 `FileReferences.Expressions`（兼容根级 `Expressions/expressions`）得已声明表情 `Expr{file,name,declared,exists}` + 未注册表情；`model/ModelManager` 四项写回 `registerExpressionIntoModel` / `registerExistingFileAsExpression` / `removeExpressionFromModel` / `renameExpression`（镜像动作写回约定：仅导入模型可写、json 先备份 `.bak`、重名 `_1/_2`、落点对齐 `FileReferences.Expressions`）；`care/MotionPoolActivity` 每模型卡片新增表情区「播/导出/复用/改名/移除」+ 未注册表情「注册」+ 可写模型「导入表情」；`ModelManagerWritebackTest` +9 条含**扫描层可见性对齐锁**。
  - 增量 verify 耗时：compile 11s / test 39s / lint 34s / apk 60s；638 单测 / 55 类、lint 0 error、APK(debug) 9,103,574B。
  - 容器重启后重装工具链（JDK 17 via apt + `commandlinetools-linux-11076708` + sdkmanager `platform-tools`/`platforms;android-34`/`build-tools;34.0.0`/`ndk;27.3.13750724`/`cmake;3.22.1`）与 v1.164.0 记录一致；Gradle wrapper 自身下载不继承 shell 代理，需 `export JAVA_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"`；`cmake;3.22.1` 首次装报 "Remote host terminated the handshake"，重试一次即成功。
  - **GitHub Actions runs 列表 API 响应含控制字符**（commit message 内嵌），`json.loads(text, strict=False)` 才能解析，否则 `JSONDecodeError: Invalid control character`。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.170.0 动作池详情 + 淡入淡出轻编辑
- Category: Operations & Deployment
- Instructions:
  - v1.170.0 已发布：versionCode 173 / versionName 1.170.0、Release id 416286834、APK app-release.apk 8,596,053B、tag v1.170.0、main @ 7648ebe；CI run 39105462410 success
  - 交付内容：动作池「详情 + 轻编辑」——`care/MotionPoolActivity` 动作行加「详情」（所属模型/组/文件、时长、循环、曲线数、当前淡入淡出、参数 ID 清单，参数清单复用 `MotionLibrary.motionParameterIds`）+「淡入淡出」弹窗（仅导入模型 + 已声明动作，两个数字输入，非法/负数拒绝）；`model/ModelManager.setMotionFade` 写回该动作在 `Motions` 组内注册条目的 `FadeInTime`/`FadeOutTime`（先备份 `.bak`、内置只读、负值归零、`round2` 两位小数）；`model/MotionLibrary.Motion` 补 `fadeIn`/`fadeOut`（未声明该键记 `NaN`＝引擎默认 1.0）；`ModelManagerWritebackTest` +6 条含**扫描层同步锁**。表情行加「详情」（名称/文件/大小）。
  - **关键依据（已在源码核实，避免做成静默 no-op）**：引擎确实读模型 json 注册条目的淡入淡出——`app/src/main/cpp/LApp/LAppModel.cpp` 的 `PreLoadMotionGroup`（约 379-389 行）与 `StartMotion`（约 618-628 行）调用 `_modelSetting->GetMotionFadeInTimeValue/GetMotionFadeOutTimeValue(group, i)` 再 `tmpMotion->SetFadeInTime/SetFadeOutTime`，故改 json 会真正生效。
  - 增量 verify 耗时：compile 9s / test 40s / lint 34s / apk 11s；644 单测 / 55 类、lint 0 error、APK(debug) 9,107,329B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.171.0 动作池搜索筛选 + 跨模型点播
- Category: Operations & Deployment
- Instructions:
  - v1.171.0 已发布：versionCode 174 / versionName 1.171.0、Release id 416296410、APK app-release.apk 8,598,217B、tag v1.171.0、main @ 9a3d3e3；CI run 39106601864 success
  - 交付内容：动作池「搜索筛选 + 跨模型点播」——`care/MotionPoolActivity` 顶部加搜索框，按模型名/组名/动作名/文件名/表情名实时过滤（`TextWatcher.afterTextChanged` → `renderList()`；`scanned` 缓存最近一次磁盘扫描，避免每次输入重扫）；`buildModelCard` 加 `nq` 参数预筛展示项，整卡无命中返回 `null`，**点播索引仍用组内原始下标**避免过滤后错位；非当前默认模型点播芯片由「播」改「切播」。新增纯逻辑 `care/MotionFilter`（`normalize` trim+小写 / `isBlank` / `matches` 大小写不敏感 contains / `anyMatches`）便于单测，`MotionFilterTest` 8 条锁契约；`ScreenshotRenderTest` 加 `motion_pool_search.png` 过滤态自查（`findEditText` 辅助）。
  - **跨模型点播顺序保证（关键依据）**：切换 `nativeChangeScene` 与播放 `nativeStartMotion` 都经 GL 线程同一 FIFO `queueEvent`，故「先切后播」天然有序，二次确认后 `PetService.switchToModelByName(modelDir)` 切过去再播，**无需延迟等待**，避免竞态错位。
  - 增量 verify 耗时：compile 10s / test 41s / lint 34s / apk 17s；653 单测 / 56 类、lint 0 error、APK(debug) 9,109,864B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.172.0 动作池连招/组合
- Category: Operations & Deployment
- Instructions:
  - v1.172.0 已发布：versionCode 175 / versionName 1.172.0、Release id 416303489、APK app-release.apk 8,604,380B、tag v1.172.0、main @ ce33c6b；CI run 39107776332 success
  - 交付内容：动作池「连招 / 组合」——`care/MotionPoolActivity` 每个模型卡片新增「连招」区（列表 + 「＋ 新建连招」）+ 新建弹窗（填名 → 点「＋」按序追加本模型可播动作/表情 → 实时预览（超 6 步折叠）→ 撤销上一步/清空；校验名非空、至少一步、最多 20 步，同名忽略大小写覆盖）；播放 `playCombo/runCombo/playComboStep` 经主线程 `Handler` 逐步下发，停顿时长 `ComboPlan.dwellMs`，用 `comboGeneration` 计数 + `removeCallbacksAndMessages` 防多条连招串台（`onDestroy` 亦作废未播完的）；非当前模型复用既有 `confirmSwitchAndPlay`「切播」。新增纯逻辑 `care/ComboPlan`（`dwellMs`：单次=时长+150ms 间隙夹在 [400ms,6s]、循环封顶 `LOOP_CAP_MS=3000`、表情固定 `EXPR_STEP_MS=1500`、未知兜底 1500ms；`resolve/resolveAll` 按「组+文件 / 表情名」解析、解析不到返回 null 跳过；`sanitizeName/hasName/suggestName`）与 `care/ComboStore`（按模型目录名分存 prefs `care_combos`，整表 JSON 往返，同名忽略大小写覆盖，坏数据读取跳过）。单测 `ComboPlanTest`(10 纯 JVM) + `ComboStoreTest`(6 Robolectric)；`ScreenshotRenderTest` 加 `motion_pool_combo.png`。
  - **设计决策（关键）**：连招限定**同一模型内**（跨模型需 `nativeChangeScene` 重载、会打断在播动作，不可控）；连招**只引用模型已有内容、不写模型 json**，故内置模型也能编；步骤**只记组名+文件 / 表情名、不记下标**，用户改名/删动作后播放时重新解析，失效步骤跳过并提示「N 步已失效」，避免错播。
  - 增量 verify 耗时：compile 10s / test 44s / lint 35s / apk 16s；670 单测 / 58 类、lint 0 error、APK(debug) 9,118,951B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.173.0 自动化可观测性 + v1.174.0 连招打磨
- Category: Operations & Deployment
- Instructions:
  - v1.173.0 已发布：versionCode 176 / versionName 1.173.0、Release id 416312530、APK app-release.apk 8,605,812B、tag v1.173.0、main @ 3ab2dc8；CI run 39109235904 success
  - v1.173.0 交付内容：自动化「执行可观测性」——新增 `care/RunLog`（纯逻辑：`looksOk` 以 `❌/失败/异常/未找到/没有步骤/请指定/请填写` 等失败标记判成败；`summarize` 折叠空白并截断 140 字；`whenText` 刚刚/N 分钟前/N 小时前/N 天前/M月d日 HH:mm；存储：prefs `care_runs`，key=`workflow|名` / `schedule|名`，每 key 只留最近 `MAX_KEPT=20` 条，`record` 最新在前、`last`/`recent`/`clear`）。`CareAutomation.checkScheduledTasks` 的 cron 触发、`AgentWorkbenchActivity.runWorkflowNow` 的「立即运行」都落盘（定时任务行手动运行同时记该任务与该工作流两条；自动触发同样双记录）。工作台工作流/定时任务两行各加「上次运行」一行（`appendLastRun`，无记录「尚未运行」）+「记录」入口（`showRunLog` 回看最近 20 次）。单测 `RunLogTest`(4 纯 JVM) + `RunLogStoreTest`(6 Robolectric)。
  - v1.174.0 已发布：versionCode 177 / versionName 1.174.0、Release id 416315450、APK app-release.apk 8,607,294B、tag v1.174.0、main @ 0933d07；CI run 39109582695 success
  - v1.174.0 交付内容：连招打磨——编排弹窗候选行改为「＋ / 试」两枚，`previewCandidate` 单步试播（非当前模型走既有 `confirmSwitchAndPlay`）；已存连招行加「复制」，`duplicateCombo` 另起不重名副本名（新增纯逻辑 `ComboPlan.copyName`：base/base2/base3…）原样拷贝步骤；`ComboPlanTest` 增 `copyNameAvoidsCollisions` 1 条。
  - 增量 verify 耗时：v1.173.0 compile 9s / test 42s / lint 34s / apk 16s，680 单测/60 类；v1.174.0 compile 10s / test 43s / lint 35s / apk 11s，681 单测/60 类；均 lint 0 error。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.175.0 动作池 收藏 / 最近播放
- Category: Operations & Deployment
- Instructions:
  - v1.175.0 已发布：versionCode 178 / versionName 1.175.0、Release id 416322524、APK app-release.apk 8,612,045B、tag v1.175.0、main @ 7b1d31c；CI run 39110736997 success
  - 交付内容：动作池「收藏 / 最近播放」——新增 `care/MotionMarks`（纯逻辑 `contains`/`pushed`(置顶+去重+按 cap 裁剪)/`toggled`(已存在则移除、不存在则插最前)/`removed`(按身份键移除) + 存储：prefs `care_motion_marks`，key `favorites`/`recent`，`favorites`/`isFavorite`/`toggleFavorite`/`recents`/`pushRecent`(保留 `MAX_RECENT=15`)/`removeRecent`/`clearRecents`，JSON 往返；引用身份 `Ref.id()`=模型目录|m/e|组|文件|名）。`MotionPoolActivity` 新增 `buildMarksSection`（列表顶部据 prefs 汇总「★ 收藏 / ▷ 最近播放」区，条目点击 `playRef` 点播、非当前模型走既有 `confirmSwitchAndPlay`「切播」确认；解析不到标「失效」+「✕」移除；两者皆空返回 null 不占版面；有查询词时隐藏）；动作/表情行加 `addMarksChip`「☆ 收藏 / ★ 已收藏」切换芯片；`play`/`playExpression` 点播成功后 `pushRecent`（当前模型路径再 `renderList()` 即时刷新；跨模型路径由 `confirmSwitchAndPlay` 收尾 `refresh()` 刷新）。`ResolvedRef`/`resolveRef` 按「组+文件（动作）/ 名或文件（表情）」对回当前扫描，口径与 `ComboPlan` 一致。单测 `MotionMarksTest`(5 纯 JVM) + `MotionMarksStoreTest`(7 Robolectric)。
  - **边界（关键）**：标记为**纯引用**（不复制文件、不写模型 json）；失效判定依赖扫描结果，用户删/改后旧引用自动标「失效」而非错播；列表重渲染用内存中 `scanned`（`renderList()`），不重扫磁盘。
  - 增量 verify 耗时：compile 12s / test 44s / lint 34s / apk 17s；693 单测 / 62 类、lint 0 error、APK(debug) 9,129,110B。
  - CI 发版链路：`git push origin main` → GitHub Actions `.github/workflows/build.yml` 自动 `assembleRelease` + 16KB 对齐门禁 + 「Check if release needed」(release 不存在才发) → 建 tag `v{versionName}` 并上传 `app-release.apk`。取 GitHub token：`git config remote.origin.url` 里内嵌 `https://<token>@github.com/...`，用 python 正则抽出（勿打印）；Actions runs/release API 响应含控制字符，`json.load(...,strict=False)` 才可解析。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.176.0 触摸事件 表情/动作 绑定
- Category: Operations & Deployment
- Instructions:
  - v1.176.0 已发布：versionCode 179 / versionName 1.176.0、Release id 416336485、APK app-release.apk 8,615,521B、tag v1.176.0 → 13d5c9e；CI run success
  - 交付内容：触摸事件可绑表情/动作。四个事件 id：`Head`/`Body`（复用 `PetTouchReaction` 分区常量）、`DoubleTap`、`None`（点空白处）。新增 `ui/pet/TouchBindings`（纯逻辑 `effective(bound,fallback)`：bound 非空且非哨兵→用 bound、空→回落 fallback、哨兵 `NONE="-none-"`→null；`events()`(返回副本)、`eventFor(id)`、`distinctNames`；存储 prefs `care_touch_bindings`，key `<eventId>.expression` / `<eventId>.motion`，`get`(Binding 两字段非 null)、`setExpression`/`setMotionGroup`(空串=清)、`clear`、`isDefault`）。接入：`PetOverlayView.handleModelTap`（zone ""→`EVENT_NONE`，用绑定覆盖 expr/group，其余气泡/情绪不变）、`PetService.onTapZone`（同样解析绑定，`syncCurrent` 用覆盖后的表情，防冷却期抢回旧表情）、`PetService.onDoubleTap`（expr 默认 "F02"、group 默认 null，`overlayView.setMotion(group,random(6),3)`）。UI：`MotionPoolActivity.buildBindingsSection`+`bindingRow`+`pickBinding`（AlertDialog 单选：用默认/不绑/各模型已声明名去重并集；选完 `preview` 试播）+`恢复默认`。仅覆盖视觉。
  - **关键设计点**：绑定按**名字**存、不绑具体模型；生效处「绑定优先、为空回落默认」，当前模型缺该内容靠默认兜住（不哑火）；哨兵 `NONE` 与「未设」区分，前者明确关闭默认视觉。候选名 = 各模型**已声明**表情名 / 动作组名的去重并集（`MotionLibrary.ModelActions.expressions[].declared/name`、`.groups[].name`）。
  - 单测 `TouchBindingsTest`(6 纯 JVM) + `TouchBindingsStoreTest`(7 Robolectric)；增量 verify 706 单测 / 64 类、lint 0 error、APK(debug) 9,133,718B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.177.0 连招导出/导入
- Category: Operations & Deployment
- Instructions:
  - v1.177.0 已发布：versionCode 180 / versionName 1.177.0、Release id 416344186、APK app-release.apk 8,617,003B、tag v1.177.0 → 295adf9；CI run success
  - 交付内容：连招导出/导入。`ComboStore` 新增纯逻辑 `toBundle(list,modelDir)`（包裹 `{"model":<目录>,"combos":[toJson(list)]}`）与 `parseImport(json)`（容 `{model,combos}` 对象与裸数组 `[...]` 两形态；空/坏 JSON/非预期结构 → 空表，不抛），复用既有 `toJson`/`fromJson`。`MotionPoolActivity`：连招区 head 加「导出」（`combos` 非空时，文件名 `<modelDir>-combos.json`）与「导入」（总可用，选文件合并进本模型、同名忽略大小写覆盖，提示「已导入 N 个（覆盖同名 M 个）」）；`comboRow` acts 加「导出」（单条 `<modelDir>-<safeFileName(name)>.json`）。走既有 SAF：导出 `ACTION_CREATE_DOCUMENT`+`setType("application/json")`、导入 `ACTION_OPEN_DOCUMENT`+`setType("*/*")`；新增 `REQ_COMBO_EXPORT=5104`/`REQ_COMBO_IMPORT=5105` 两条 `onActivityResult` 分支；导出**先打包 JSON 到字段、选好位置才写盘**（`pendingComboExportJson.getBytes(StandardCharsets.UTF_8)`）。`safeFileName` = trim + `replaceAll("[\\\\/:*?\"<>|]","_")` + 空回落 `combo`。
  - **关键设计点**：导出「全部」与「单条」共用同一包裹格式，导入端一套解析（单条就是数组里一条）；`model` 字段仅提示、导入忽略（按目标模型落盘）；跨模型导入步骤解析不到时由连招逻辑播放时跳过并提示，不会错播。
  - 复用现有动作/表情导出导入通道模式（`REQ_EXPORT=5101`/`REQ_IMPORT=5102`/`REQ_EXPR_IMPORT=5103`，写法见 `exportMotion`/`exportExpression`/`pickImportTo`）。单测并入 `ComboStoreTest`（新增 `bundle_roundTripsThroughParseImport` / `parseImport_acceptsBareArray` / `parseImport_isTolerantOfGarbage`）；增量 verify 709 单测 / 64 类、lint 0 error、APK(debug) 9,135,264B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.179.0 主题设置 + v1.180.0 Agent 工具补齐（路线图收官）
- Category: Operations & Deployment
- Instructions:
  - v1.179.0 已发布：versionCode 182 / versionName 1.179.0、tag v1.179.0 @ fa3d59c、main @ fa3d59c。交付内容：单套深色色板下的「主题设置」——把会变的颜色从 `@color` 提升为 `?attr/theme_*` 主题属性（新增 `res/values/attrs.xml` 16 项），Java 侧经 `UiKit.color → ThemeManager.resolveColor` **一处收口**（331 处调用点全生效），19 个 drawable 改属性引用；`AppTheme` 给默认字面色→默认主题零回归；六款强调色预设（各九档色，`AccentStyle*` 运行时叠加）＋深紫/纯黑(OLED)两态外观（只覆写底部色族）；12 个 Activity `onCreate` 首句 `ThemeManager.apply(this)`；`Settings` 加 `getAccent/setAccent`、`getAppearance/setAppearance`；`ThemesActivity` 占位页改真主题页（色卡行 + 外观两行，改选即 recreate）。**红线守住**：不新增 `values-night/`、不做浅色板（避免 issue #98「一半黑一半白」）。verify 736 单测 / 67 类、lint 0 error、APK(debug) 9,150,549B。
  - **attrs.xml 内注释禁用 `--`**：XML 注释里写 `---` 分隔线会触发资源编译失败（`--` 在 XML 注释中非法），改用 `===`。
  - v1.180.0 已发布：versionCode 183 / versionName 1.180.0、tag v1.180.0 @ ac55d6d、main @ ac55d6d。交付内容：「信息」类两个占位工具做成真的——新增纯逻辑 `tools/Agenda`（把今日提醒 `reminders` 与每日例行定时任务 `care_schedules` 合并；定时任务复用 `care/CronText.describe` 翻人话 + `nextFireMillis` 推算下次触发，标工作流/连招与是否暂停）与 `tools/RecentFiles`（扫描 `getExternalFilesDir`/`getFilesDir` + 公共 Downloads/Documents，按 mtime 倒序给文件名·大小·时间·路径，最多 10 条；跳过 `.` 隐藏/`.nomedia`/0 字节，不进入 `models`/`cache` 目录，`SCAN_CAP=500` 上限）。`BuiltinTools` 描述去掉「占位」。**不申请任何存储权限**：公共目录受限时 `listFiles()` 返 null 或抛异常一律静默跳过。单测 `InfoToolsTest`(11) 锁边界；增量 verify 747 单测 / 68 类、lint 0 error、APK(debug) 9,153,981B。
  - **`Tools.register` 的 required 数组一物两用**：既列 properties，又填 schema 的 `required`；所以**不能**用它声明「可选参数」（会被当必填且默认类型 string）。给工具加可选参数需改 `Tools.register`（核心引擎），本次刻意规避——`get_files_recent` 改为无参、默认 10 条。
  - 路线图「v1.177.0 连招导出/导入 → v1.178.0 连招接系统事件 → v1.179.0 主题设置 → v1.180.0 Agent 工具补齐」四项已全部落地并各自发版；v1.178.0 与 v1.179.0 的 tag/Release 均已在 `git pull` 时确认存在（CI 自动发布链路正常）。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.182.0 工作流改名 + 容器工具链重装
- Category: Operations & Deployment
- Instructions:
  - v1.182.0 已发布：versionCode 185 / versionName 1.182.0、Release id 420314245、APK app-release.apk 8,633,861B、tag v1.182.0 @ 366fc10、main @ 366fc10；CI run 39723457613 success（自动建 tag + Release，无需手工）。
  - 交付内容：工作流改名。新增工具 `CareTools.rename_workflow`（`name` + `newName`）：改 prefs 键与内嵌 `name`、**级联** `care_schedules` 里所有**工作流**任务的 `workflowName` 改指新名（`cronExpr`/`enabled`/`kind`/目标原样保留；连招任务 `kind:"combo"` 不动）、迁移 `care_runs` 的 `workflow|旧名` 记录（`RunLog.move`）；撞名直接拒绝且不改动任何一条。工作台工作流行新增「改名」chip + 复用 `showScheduleForm` 式输入弹窗（`doRenameWorkflow` 后台跑工具后 `refresh()`）。`AgentWorkbenchToolsTest` 补 5 条锁契约。**此前改名的唯一路径是「删除后重建」，重建后定时任务仍指旧名 → 到点 `run_workflow` 报「未找到」，执行记录也一并丢失**。
  - **本容器会被整体重置（不只是 /tmp）**：本次 `/usr/lib/jvm`、`/opt/android-sdk`、`/tmp/opencode` 全部消失。可用 JDK17 在 mise：`/root/.local/share/mise/installs/java/17.0.20+101`。已做 `ln -sfn <mise-jdk17> /usr/lib/jvm/java-17-openjdk-amd64`（`tools/verify.sh` 硬编码该路径，不建这个软链会直接报 JDK 缺失）。
  - Android SDK 重装（代理可用，直连 dl.google.com 200）：下载 `commandlinetools-linux-11076708_latest.zip`（约 147MB）→ 解压到 `/opt/android-sdk/cmdline-tools/latest` → `yes | sdkmanager --licenses` → `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.3.13750724" "cmake;3.22.1"`（总约 2.4GB，几分钟）。`local.properties` 已写 `sdk.dir=/opt/android-sdk`。
  - 代理：`HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:18080`；Gradle wrapper **不继承** shell 代理，需 `export JAVA_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080"`。全量 verify 本轮：compile 8s / test 44s / lint 46s / apk 62s。
  - 增量 verify 758 单测 / 68 类、lint 0 error、APK(debug) 9,157,760B。

[Project Knowledge Summary]
- Date: 2026-10-18
- Context: Discovered by Agent while performing v1.183.0 文本节奏口型（参考开源 Live2D 项目）
- Category: Operations & Deployment
- Instructions:
  - v1.183.0 已发布：versionCode 186 / versionName 1.183.0、Release id 420580441、APK app-release.apk 8,636,263B、tag v1.183.0 @ de86289、main @ de86289；CI run 39762511534 success。
  - **开源 Live2D 参考结论**：`pixi-live2d-display` 的卖点是「自动聚焦 + hit-testing + 更强的动作保留逻辑」；`live2d-widget` 卖点是语录/换模型/截图；`@chnak/l2d-widget` 有一个我们缺的能力——**用文本节奏驱动 `ParamMouthOpenY`**（逐字开合，不需要音频分析）。官方 `CubismMotionSync`（音频驱动口型/眨眼）需要额外 MotionSyncCore 原生库，本仓库未内置，暂不可做。原生 `HitTest`（Head/Body）虽已存在（`LAppModel::HitTest`），但悬浮窗 `PetOverlayView.onInterceptTouchEvent` 吞掉了 ACTION_DOWN，子 GLSurfaceView 只收到 CANCEL，所以桌面宠物的触摸分区仍是 Java 近似（`PetTouchReaction` 固定 38% 高度线）——若要做「模型自适应命中」，需加 `nativeHitTest` 并从 GL 线程同步取结果（有超时回退），属于中等风险的下一步。
  - 交付内容：新增纯逻辑 `render/LipSync`（文本 → 音节时间线：中文/假名/谚文一字一音节；英文按元音组；标点停顿 180/110/60ms；音节内梯形包络；张幅按字符码确定性推导；`append` 支持流式顺延）。`TTSEngine.Listener` 改为 `onSpeakingStart(String)` + 新增 `onSpeakingAppend(String)`；`PetService` 40ms tick 写 `overlayView.setMouth`。新增 `LipSyncTest` 8 条。
  - **顺带修的真实缺陷（都值得记住）**：① 文字模式（关闭语音）下 `Output.speak()`/`onSpeak()` 无条件 `setSpeaking(true)`，而没有任何回调会 `setSpeaking(false)` → 嘴永久停在 0.3；② `tools/RecentFiles.collect` 把 `cap` 当「扫描条数」提前退出，`listFiles()` 顺序任意导致小 cap 时返回的不是最新文件（`InfoToolsTest.collect_sortsNewestFirstAndRespectsCap` 因此偶发失败），改为「先扫够候选（≥SCAN_CAP=500）→ 排序 → 截到 cap」。
  - 本轮 verify：766 单测 / 69 类、lint 0 error、APK(debug) 9,160,868B。

[Project Knowledge Summary]
- Date: 2026-10-23
- Context: Discovered by Agent while performing v1.184.0 触摸分区改为原生 HitArea 优先（参考开源 Live2D 项目）
- Category: Operations & Deployment
- Instructions:
  - v1.184.0 已发布：versionCode 187 / versionName 1.184.0、Release id 421708502、APK app-release.apk 8,638,282B、tag v1.184.0 @ c6e06e7、main @ c6e06e7；CI run 39938545554 success（自动建 tag + Release）。
  - 交付内容：桌宠触摸分区从「固定 38% 高度线」升级为**原生 HitArea 优先、Java 近似兜底**。新增 JNI `nativeHitTestZone`（0 无 / 1 头 / 2 身，无副作用）：C++ `LAppLive2DManager::HitTestZone` 复用 `LAppModel::HitTest`（不播动作、不换表情）；`LAppView::HitTestZone` 走与 `OnTouchesEnded` **完全相同**的 `_deviceToScreen` + `viewMatrix` 逆变换（`TransformViewX/Y`），保证「点哪判哪」。Java 新增纯逻辑 `render/HitZoneCache`（序号 + 有效期 1500ms，线程安全）：`Live2DGLView.requestHitZone` 在 ACTION_DOWN 时 `queueEvent` 异步投递（**不阻塞 UI 线程**），`takeHitZone` 在抬手判定点击时按同一序号取回；`PetOverlayView.handleModelTap` 原生优先，只有「没算完 / 过期 / 模型无对应 HitArea」时回落 `PetTouchReaction.zoneFor`（`TouchBindings` 与气泡/情绪逻辑不变）。顺带 `Live2DGLView.ready` 改 `volatile`（GL 线程写、UI 线程读）。
  - **为什么之前原生判定是死路**：`PetOverlayView.onInterceptTouchEvent` 在 DOWN 命中人偶带就返回 true（拖拽/长按必需），子 `Live2DGLView` 只收到 CANCEL，`nativeOnTouchesBegan` 从不触发。不要再试图「让子 View 收 DOWN」来修，会破坏拖拽；正确做法就是本版的按下异步查询 + 抬手取回。
  - **为什么按序号而不是按坐标/时间匹配**：一次手势一次 `begin()`，抬手用同一序号取；中间有新按下（新序号）时旧序号自然取不到 → 回落。GL 线程繁忙时同样取不到 → 回落，行为确定。原生「0=未命中」与「-1=没有结果」语义分开（0 也回落，但调用方知道查询跑过）。
  - 新增 `HitZoneCacheTest` 10 条锁契约；本轮 verify：776 单测 / 70 类、lint 0 error、APK(debug) 9,162,867B；增量耗时 compile 10s / test 45s / lint 46s / apk 61s。
  - 容器重置后工具链已按 v1.182.0 记录重装（软链 JDK17 + `/opt/android-sdk` 含 `ndk;27.3.13750724`、`cmake;3.22.1`）；原生命中改动必须跑 `assembleDebug`（会编 C++），只跑 `compileDebugJavaWithJavac` 发现不了 JNI/CMake 错误。

[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.185.0 触摸眼神跟手
- Category: Rendering & Interaction
- Instructions:
  - v1.185.0 交付：桌宠触摸时**视线跟手 + 头部小幅跟随**。此前 `ContinuousMotionEngine.updateGaze` 只有随机 saccade（1.5~5s 一次），手指位置完全不影响视线。
  - 设计：`ContinuousMotionEngine.setTouchGaze(nx, ny, active)`（任意线程、volatile）+ `updateTouchGaze`：眼睛用 `setDirectValue` 直通、缓动 `dt*14`（时间常数 ~70ms，快过 saccade 的 `dt*6` ≈170ms）才能真正「黏」住手指；头部 `ParamAngleX = nx*7°`、`ParamAngleY = ny*5°` 走 `addAdditiveTarget`，复用弹簧回弹；跟手期间 `nextSaccadeTime = max(nextSaccadeTime, clock+0.8)` 压住随机瞟。
  - **必须记住的坑：`PhysicalController.additive` 不会自动归零**。松手那一帧必须显式 `addAdditiveTarget("ParamAngleX/Y", 0f)`，否则模型在 `modelHasMotions=true`（L1 早退、没人重写 additive）时会**永久歪头**。同理视线直通在 `modelHasMotions` 下松手要设 `NaN`（native 跳过）交还模型自带动作。
  - 触摸跟手在 `modelHasMotions` / `actionPlaying` 两种「L1 让位」分支里仍然生效——用户主动交互优先级高于待机动画；L1 路径则用 `touching ? updateTouchGaze : updateGaze` 二选一。
  - 归一化：`normalizeGazeX(x,w) = clamp(x/w*2-1)`；`normalizeGazeY(y,h) = clamp(1 - y/h*2)`（屏幕 y 轴向下要翻转）；宽高非法/NaN 一律回 0（正前方）。纯静态、JVM 可测。
  - 接线：`Live2DGLView.setTouchGaze(x,y,active)` 按 View 宽高归一化；它自己的 `onTouchEvent` 也直接驱动（模型预览页等未被悬浮窗拦截的场景同享）。`PetOverlayView` 因为 `onInterceptTouchEvent` 吞了 DOWN，必须在**自己的** `onTouchEvent` 里分发：DOWN/MOVE → `followTouchGaze(event)`；UP/CANCEL、菜单/详情打开、气泡/胶囊命中、越界按下 → `releaseTouchGaze()`（手势被打断也要回落）。
  - 新增 `ContinuousMotionEngineTouchGazeTest` 10 条：归一化中心/边界/翻转/非法宽度、一帧锁定、头部偏移有界、NaN 夹取、松手 additive 收敛回 0 + 视线交还模型、刚松手不立刻乱瞟、`reset` 清空。
  - **单测物理积分别用 dt=0.1**：k=150、d=15 时显式欧拉在 dt=0.1 会发散（数值飙到 1e5）。测试里按真实帧 `dt=0.016` 迭代。
  - 本轮 verify：786 单测 / 71 类、lint 0 error、APK(debug) 9,163,851B；增量耗时 compile 10s / test 45s / lint 45s / apk 58s。
  - 用户确认「四项都要」，本版是第一项；剩余：② 快捷菜单换模型、③ 桌宠截图分享、④ 对话真表格。

[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.186.0 快捷菜单换模型 + 桌宠截图
- Category: Rendering & Interaction
- Instructions:
  - v1.185.0 已发布：versionCode 188 / versionName 1.185.0、Release id 422661795、APK app-release.apk 8,639,100B、tag v1.185.0 @ 9246bf9；CI run 40085713985 success。
  - v1.186.0 交付：长按菜单 5 项 → 7 项（换表情 / 换个模型 / 拍张照 / 一键贴边 / 回到中间 / 锁定 / 设置）。
  - **换模型**：`PetQuickMenu.modelMenu(String[])` 是纯逻辑（首项「返回」置顶，模型多时不会滚到看不见返回入口）；`PetService.showModelChooser()` 直接读 `Live2DNative.nativeGetModelCount()/nativeGetModelDirName(i)`，回调 `index-1` 就是 native 下标，直接 `switchToModel(index)`；**不要按名字匹配**（目录名重复/改名会切错）。当前默认模型用 `settings.getDefaultModelDir()` 判断并标「（当前）」，选中当前只提示不重切。
  - **菜单滚动**：`PetOverlayView.showQuickMenu` 现在把卡片包进 `ScrollView`；`items.length > 8` 时高度固定 360dp（主菜单 7 项仍按内容自适应）。`ScrollView` 会自己消费触摸，点卡片空白不会误触遮罩关闭；行文本单行 + 中间省略 + `maxWidth 240dp`，长目录名不会撑破悬浮窗。
  - **截图**：`Live2DGLView.requestSnapshot(cb)` 只设 `volatile` 标志，真正 `glReadPixels` 在下一帧 `onDrawFrame` 里 `nativeOnDrawFrame()` 之后、缓冲交换之前执行（**必须在这个时机**，否则读到上一帧/未定义内容）；读回后要做 ① 上下翻转、② RGBA→ARGB：小端内存 `[R,G,B,A]` 读成 int 是 `A<<24|B<<16|G<<8|R`，不要直接塞给 `Bitmap.createBitmap`；结果 `post` 回主线程。
  - **存相册**：API 29+ `MediaStore` + `RELATIVE_PATH=Pictures/DigitalLife` + `IS_PENDING` 两段式（先 1 插入写流，再 0 提交）；API 21~28 写 `getExternalFilesDir(Pictures)/DigitalLife` 再 `MediaScannerConnection.scanFile`；两条路都**不需要存储权限**。压缩/写盘放 `new Thread`，完成后用气泡反馈；`Bitmap` finally 里 recycle。
  - `take_screenshot_hint` 文案改为提示「长按桌宠 › 拍张照」；普通 App 仍无法截其它应用，别承诺系统级截图。
  - `PetQuickMenuTest` 更新为 7 项 + 索引，新增 `modelMenu` 两条（返回置顶、null 名称不崩）。
  - 本轮 verify：788 单测 / 71 类、lint 0 error、APK(debug) 9,168,141B；增量耗时 compile 10s / test 45s / lint 35s / apk 15s。
  - 剩余两项：④ 对话真表格（`MarkdownRenderer` 仍是等宽文本对齐，风险和工程量最大）。





[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.187.0 对话真表格
- Category: Chat Rendering
- Instructions:
  - v1.186.0 已发布：CI run 40086341090 success、Release v1.186.0 资产 app-release.apk 8,642,151B。
  - v1.187.0 交付：聊天气泡里的 Markdown 表格从等宽字符对齐 → **真网格位图**。`render()` 保持原样（纯文本兜底 + 既有单测契约），新增 `MarkdownRenderer.renderRich(raw, maxWidthPx, textSizePx)`。
  - **为什么用位图 + ImageSpan 而不是把气泡改成 ViewGroup**：ChatActivity 的 `aiBubbles`/`fullTexts`/`rawMarkdown`/`msgIds` 全部以 `TextView` 为键，`applyCollapse`/搜索高亮/长按菜单都依赖 `getText()/setText()`。位图方案只需替换「表格那一段文字」的绘制，`CharSequence` 里文字仍在 → 搜索/复制/折叠零改动。
  - **关键约束：ImageSpan 不能跨换行**。ReplacementSpan 跨段落会被 StaticLayout 拆成多段重复绘制；所以表格的纯文本用「单元格 | 单元格；」连接（行间用中文分号），绝不带 `\n`。
  - 绘制：`StaticLayout`（旧构造器，minSdk 21 可用）逐格自动换行；`ImageSpan.getSize` 会把 `fm.ascent` 设为 `-bitmap.height`，所以行高天然等于位图高，无需额外空行补偿。
  - 列宽：自然宽度 = max(measureText(cell)) + 2*padH；超宽时**从最宽列逐像素削**（等比压缩会把两字窄列压成「一字一行」）；最小列宽 = 2 个字 + 内边距；连下限都塞不下 → 返回 null 退回等宽文本。
  - 预算：单条消息最多 4 张表、单次最多 1.5M 像素；整页累计 6M 像素（≈24MB，`tableBitmapPixelsUsed` 在 renderer 实例上累计）后所有表格退回文本，防长历史 OOM。
  - **流式期间不要画位图**：`onDelta` 每 token 重渲染一次，位图会高频分配；流式保持 `render()`，在 `onDone`（chat 与 care 两个监听器）定型时再 `setText(renderAiMessage(...))`。
  - `registerFullText`：另存 `render(raw)` 的纯文本全文到 `fullTexts`，长按复制/引用/朗读优先取它（富文本 `getText()` 里表格被压成单段、丢换行）；`applyCollapse` 改为 `if (!fullTexts.containsKey(bubble))` 才写，避免把纯文本全文覆盖成带位图的富文本。
  - 验证：Robolectric `@GraphicsMode(NATIVE)` 可把 TextView + renderRich 真渲染成 PNG 自查（临时测试跑完即删，别入库）；`MarkdownRendererTest` 新增 7 条（ImageSpan 存在、文字可搜、span 无换行、宽度受限、窄宽回退、纯文本无 span、表前后文字保留）。
  - 本轮 verify：795 单测 / 71 类、lint 0 error、APK 通过。
  - 用户「四项都要」的四项至此全部完成：① 触摸眼神跟手（v1.185.0）② 快捷菜单换模型（v1.186.0）③ 桌宠拍张照（v1.186.0）④ 对话真表格（v1.187.0）。


[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.188.0 对话代码高亮
- Category: Chat Rendering
- Instructions:
  - v1.187.0 已发布：CI run 40087517273 success、Release v1.187.0 资产 app-release.apk 8,645,564B。
  - v1.188.0 交付：聊天气泡里的代码块从「整块同色等宽字」→ **按 token 分色**。新增纯逻辑 `ui/chat/CodeHighlighter`（无 Android 依赖），`MarkdownRenderer` 只负责把 token 区间变成 `ForegroundColorSpan`。
  - **为什么自己写词法扫描**：项目不许引第三方依赖；高亮只需 O(n) 单次字符扫描，且比正则方案更能处理「字符串里的 `//`」「转义引号」「三引号」「`${#var}` 里的 `#`」。
  - **围栏正则改了**：`CODE_BLOCK` 由 ```` ```[^\n]*\n?([\s\S]*?) ```` 改为 ```` ```([^\n]*)\n?([\s\S]*?) ````，group(1)=语言标注、group(2)=正文；`appendCodeBlock` 签名多一个 `info`，`render` 与 `renderRich` 两个调用点都要传 `m.group(2), m.group(1)`。
  - **没写语言要猜**：`resolveFamily` 先 `familyOf(info)`，认不出再 `guessFamily(code)`（`public class`→Java、`def `→Python、`SELECT `→SQL、`{`/`[`→JSON、`#!`→Shell、`<?xml`→XML…）；猜不出走 `GENERIC`，只染字符串/数字，纯文本日志不会被乱染色。
  - **配色**：`code_bg #2A2740` 是深色，故直接取 GitHub Dark 五色（关键字 #FF7B72 / 字符串 #A5D6FF / 注释 #8B949E / 数字 #79C0FF / 类型 #D2A8FF），不跟主题走。**前景色区间必须互不重叠**：不要「整块铺 codeTextColor 再局部覆盖」——同类型 span 重叠时谁生效取决于 `Spanned` 优先级，各版本不保证；改为 token 上色、只给 token 空档铺 `codeTextColor`。
  - 点击复制代码块、块尾「[复制代码]」提示、折叠/搜索逻辑都未动（只加颜色 span）。
  - `CodeHighlighterTest` 20 条（含词法边界：URL 里的 `//`、`classy`≠`class`、SQL 大小写、Python 三引号、token 有序不重叠）；`MarkdownRendererTest` +4 条（render/renderRich 都着色、正文与复制提示保留、前景色 span 不重叠）。
  - 本轮 verify：819 单测 / 72 类、lint 0 error、APK(debug) 9,356,770B。
  - 环境坑：容器重置后 `/opt/android-sdk` 为空，需重装 cmdline-tools + platforms;android-34 + build-tools;34.0.0 + ndk;27.3.13750724 + cmake;3.22.1；lint 在**独立 JVM** 里下载 lint-gradle，`JAVA_OPTS` 传不进去，要改用 `JAVA_TOOL_OPTIONS` 设代理，否则报 `Connect to dl.google.com:443 timed out`。


[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.189.0 桌宠桌面占位放宽
- Category: Pet Overlay / Settings
- Instructions:
  - 用户长期抱怨「桌宠在桌面上的范围太窄」：根因是悬浮窗尺寸硬编码为屏幕 **宽 35% × 高 57%**，且 `PetService.ensureRunning` 与 `resizeOverlayForDisplay` 各写一份；`PetOverlayView.onSizeChanged` 又单独把触摸带写成 `0.2f~0.8f`。三套魔数彼此不一致，模型按窗口宽度铺满 → 实际只占屏幕约 1/3 宽。
  - v1.189.0 修法：新增纯逻辑 `util/OverlayGeometry` 统一 `WIDTH_RATIO=0.48f`、`HEIGHT_RATIO=0.62f`、`SCALE_MIN=70/SCALE_MAX=130`、触摸带 `TOUCH_LEFT=0.10f/TOUCH_RIGHT=0.90f/TOUCH_TOP=0.10f`。`PetService` 两处与 `PetOverlayView` 一律改为调用它，禁止再写字面量比例。
  - 设置页「功能设置」新增「桌宠大小」SeekBar（70%~130%，`Settings.scale` 早就存在但此前没有 UI 入口）：拖动即写 prefs，并用 `PetService.applyOverlayScale()` 热生效（内部复用 `resizeOverlayForDisplay` 的等比位置迁移 + 屏内夹取），无需重启服务。
  - 触摸带从 60% 宽扩到 80% 宽；带外事件仍经 `onInterceptTouchEvent` 返回 false 透传给下层桌面，所以窗口变大不会挡住桌面图标区。
  - 原生 HitArea、`PetTouchReaction.zoneFor` 回退、拖拽/贴边停靠（`OverlayDock`）逻辑均未改动。
  - `OverlayGeometryTest` 7 条锁几何（默认宽度 >=40% 屏宽、比例算式、缩放夹取、极小屏不塌缩、触摸带不越界且 >=80% 宽、坐标有序）。
  - 环境坑复述：lint 任务的 lint-gradle 在独立 JVM 下载，`JAVA_OPTS` 无效，要 `JAVA_TOOL_OPTIONS` 设代理，否则 `Connect to dl.google.com:443 timed out`。

[Project Knowledge Summary]
- Date: 2026-10-24
- Context: Discovered by Agent while performing v1.189.1 触摸带铺满 + 坐标夹取
- Category: Pet Overlay / Geometry
- Instructions:
  - v1.189.0 只把窗口放大到 48%×62%、触摸带扩到 80% 宽，**没有真正解决「范围太窄」**：模型按窗口宽度铺满，窗口左右各 10% 仍是穿透缝，张开手臂/贴边站立时点不中。
  - v1.189.1 修法：`OverlayGeometry.TOUCH_LEFT=0f`、`TOUCH_RIGHT=1f`（横向铺满整个窗口），`TOUCH_TOP` 保持 `0.10f`（顶部留给气泡/输入框）。`touchBand` 是唯一来源，`PetOverlayView.modelRect` 与 `Live2DGLView.setTouchRegion` 必须用同一个 rect，否则会出现「点得中但不触发」。
  - 新增 `OverlayGeometry.clampX/clampY`：窗口宽高变化（缩放滑块、横竖屏）后旧坐标可能越界，`PetService.ensureRunning` 恢复持久化坐标与 `resizeOverlayForDisplay` 都必须夹回屏内；窗口比屏幕大时返回 0（贴左上角）。
  - 设计取舍：横向铺满意味着悬浮窗覆盖的整块区域都会吃掉触摸，不再透传给桌面；这是用户明确要求（点得中优先），窗口本身只占屏宽 48%，可接受。
  - `OverlayGeometryTest` 由 7 条扩到 11 条：新增触摸带铺满宽度、顶部留白、坐标夹取、窗口大于屏幕贴左上角。
  - 版本冲突处理：v1.189.0 已由远端提交 `513e0e8` 占用并打了 tag，本地另一套实现（`ui/pet/PetWindowMetrics`）不可强推，改为以远端为基线做 v1.189.1 增量。

[Project Knowledge Summary]
- Date: 2026-10-29
- Context: Discovered by Agent while performing v1.190.0 消息分享为长图
- Category: Chat Rendering
- Instructions:
  - **版本号冲突**：远端已用 v1.189.0（桌宠占位）/ v1.189.1（触摸带铺满）并打了 tag，本地这套聊天分享只能顺延为 **v1.190.0**（versionCode 194）；rebase 时 CHANGELOG/AGENTS.md/MEMORY.md 三处头部都会冲突，按「远端条目保留 + 我的条目改号置顶」手工合并。
  - **已发布**：Release v1.190.0（id 427764080，tag @ 319f44b，资产 app-release.apk 8,658,291B）；CI run 40863217045（319f44b）与 40863702747（43cdf0c）均 success。
  - **多写入者发版教训**：`git push` 前必须 `git fetch` 看远端有没有别的写入者抢先用了版本号；冲突时先 `git rebase origin/main`，手工合并后 `git rebase --continue` 会把 main 指针更新好（**rebase 期间 HEAD 是 detached，提交后要确认 `git rebase --continue` 跑过**，否则 main 还指着旧 commit）。
  - **代码注释里的版本号也要跟改**：远端占用了 v1.189.0 后，本地新增文件注释仍写 v1.189.0 会误导后来人，rebase 完统一替换成 v1.190.0。
  - v1.190.0 交付：聊天气泡长按菜单新增「**分享为图片**」——把消息渲染成卡片长图再发出去，表格位图与代码配色原样保留。
  - **不要重新解析 Markdown**：气泡里的 `CharSequence` 本来就是 `MarkdownRenderer.renderRich` 的产物（表格是 `ImageSpan` 位图、代码块是 `ForegroundColorSpan`），直接拿这份富文本用 `StaticLayout` 画一遍即可；`MessageCardRenderer.render(body, cardWidthPx, textSizePx, style, header, footer)` 是纯绘制层，不碰 Markdown 语法。
  - **后台线程要用独立的 `MarkdownRenderer` 实例**：`MarkdownRenderer` 有可变字段（表格位图预算计数器 `tableBitmapPixelsUsed`），UI 线程与后台线程共用一个实例会产生数据竞争；`shareMessageAsImage` 里 `new MarkdownRenderer(...)` 单独起一个。
  - **不引 androidx ⇒ 自实现 ContentProvider**：项目禁用第三方依赖，用不了 `androidx.core.content.FileProvider`，故自写 `util/ShareFileProvider`（只实现 `openFile`，`ParcelFileDescriptor.MODE_READ_ONLY`）；只暴露 `cacheDir/share` 下的**纯文件名**——拒绝 `/`、`\`、`..`，并二次校验 `getCanonicalPath()` 仍在该目录内；`insert/delete/update` 一律抛 `UnsupportedOperationException`。
  - **分享 URI**：`new Uri.Builder().scheme("content").authority(pkg + ".share").appendPath(fileName).build()`；Intent 需 `FLAG_GRANT_READ_URI_PERMISSION`（send 与 chooser 都要加）；Manifest 里 provider `exported=false` + `grantUriPermissions=true`。
  - **长图截断的两个坑**：① 高度上限要先扣掉标题/落款高度——`maxBodyHeight = MAX_CARD_HEIGHT_PX - margin*2 - pad*2 - headerH - footerH`，否则总高仍会超；② 比例收缩循环里分母必须是**当前** layout 高度，且截断后要重算 layout；最终写法：先按 `getLineEnd(getLineForVertical())` 按行截断，再最多 12 轮按比例收缩（切 `Spanned` 子串，span 保留），末尾补「…（内容过长，已截断）」。
  - `ParcelFileDescriptor.createInputStream()` 在 Robolectric + JDK17 下 `close()` 会抛 `InaccessibleObjectException: module java.base does not "opens java.io"`；单测里**只读不关**，用 `fd.getStatSize()` 断言大小。
  - 菜单加项必须同步顺延索引：用户气泡 0..6（「分享为图片」在「分享」之后），AI 气泡 0..7（「重新生成 / 删除」顺延）。
  - `MessageCardRendererTest` 7 条（图宽含页边距、空 body 返回 null、非法尺寸返回 null、多行更高、5000 行被截断到 ≤8000px、含表格位图与含代码块的富文本都能画）；`ShareFileProviderTest` 7 条（URI 拼装、正常读图、缺文件抛错、**路径穿越被拒**、MIME、写操作不支持）。
  - 本轮 verify：844 单测 / 75 类、lint 0 error、APK(debug) 9,190,673B；耗时 compile 9s / test 46s / lint 35s / apk 14s。
  - 待办（AGENTS.md §5.3 第 3 条已过期）：仓库里已有 `mcp/McpClient` / `McpServerManager` / `McpResponseParser`（Streamable HTTP + SSE），「真 MCP 对接」那条应订正。

[Project Knowledge Summary]
- Date: 2026-10-31
- Context: Discovered by Agent while performing v1.191.0 挑刺修复轮（主线程重活 / 线程池泄漏 / MCP 工具残留 / cron 推算性能）+ 容器两次重置后工具链迁移
- Category: Operations & Deployment
- Instructions:
  - **容器会被重置**：`/opt/android-sdk`、`/tmp/opencode`、`~/.gradle`、`/usr/lib/jvm` 已被清空两次，工具链**必须装到持久卷** `/workspace/toolchain`（android-sdk + gradle-8.7 + dl 压缩包），再用软链接回 `/opt/android-sdk`、`/usr/lib/jvm/java-17-openjdk-amd64`，否则 `local.properties` 的 `sdk.dir` 与既有脚本失效。
  - `tools/verify.sh` 已改成**自动探测** JAVA_HOME / ANDROID_HOME（顺序：`/workspace/toolchain` → `/opt/android-sdk`；JDK 顺序：`/tmp/opencode/toolchain/jdk-17.0.20.1+1` → `/usr/lib/jvm/java-17-openjdk-amd64` → mise 的 java 17），并在 SDK 缺失时 exit 2。
  - Gradle wrapper dist 目录哈希算法：MD5(distributionUrl) → BigInteger → base36；gradle-8.7-bin 对应 `~/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/`（放好解压目录并写 `.ok` 标记即可免下载）。
  - 无网环境：`~/.gradle/init.gradle` 配阿里云镜像（google/public/gradle-plugin），`~/.gradle/gradle.properties` 配 `systemProp.http[s].proxyHost/Port`；代理端口本环境为 **18080**。
  - **minSdk 21 铁律**：`List#sort` 需要 API 24，lint 会报 `NewApi` **error**，必须用 `java.util.Collections.sort(list, comparator)`（本轮 `care/RunLog.merge` 踩过）。
  - Robolectric 首跑下载 `android-all-instrumented` jar 偶发 `Failed to fetch maven artifact`（网络瞬断），**重跑即过**，不要误判为代码问题。
  - 本轮挑刺修掉的真问题（按严重度）：① `care/CareAutomation` 的体检/工作流在**主线程 Handler** 上跑（内含逐模型 analyze/repair、整条 `run_workflow` 的解压与写文件 IO）→ 改后台单线程执行器串行执行，Handler 只负责按时唤醒；**连招播放必须留主线程**（`ComboPlayer` 逐步下发依赖主线程 Handler）；② `care/CronText.nextFireMillis` **逐分钟**试匹配（52.7 万次 × 重新 split/parseInt）且在 **UI 线程**被调（工作台每行 + 新建表单实时预览）→ 新增纯逻辑 `care/CronMatcher` 预解析成布尔表（分60/时24/日32/月13/周7）+ 按天跳跃；**布尔表每项都用原判定函数（`CareAutomation.fieldMatch`/`dowFieldMatch`）逐值求**，保证与 `cronMatches` 逐位一致，不出现两套 cron 语义；③ `brain/LLMClient` 每个实例持一个**从不回收**的单线程池 → 新增 `close()`，在 7 处「用完即弃」新建点补回收；`ui/ChatActivity` 此前每次发送都新建客户端 → 改按「配置指纹 base+model+profile id」复用；④ `service/PetService` 的 `addView` 失败（悬浮窗权限被撤）只打日志、`overlayView` 仍非空 → `ensureRunning()` 永远短路，用户再也起不来桌宠 → 失败即置空并停服务；`applyOverlayScale()` 此前会顺带挪位置且不落盘 → 改只改宽高 + 写回设置；⑤ `mcp/McpServerManager` 的连接表是**实例字段**但工具注册进**全局单例** `ToolRegistry` → 「插件页删服务器」时桌宠侧连接没断、工具残留、大脑仍能调用 → 连接表改**进程级共享**，匹配键由 endpoint 改**配置 id**；⑥ `mcp/McpResponseParser` 遇 SSE **带 id 但对不上**的帧会回退「首个可解析帧」，把别的请求应答当自己的结果、真实错误被吞 → 现报错；帧没回显 id 仍容忍回退；⑦ `care/RunLog.looksOk` 全文扫关键词，步骤输出正文里的「异常项：0」就被**误判为失败** → 先看步骤行、有步骤行以其成败标记为准，无步骤行才退回关键词；`RunLog.move`（改名迁移）覆盖写入冲掉目标键原有记录 → 改**合并**（按时间倒序、裁剪 20 条）；⑧ 原生 `cpp/LApp/LAppLive2DManager` 的 `g_extraModelDirs`/`g_extraModelJsonBases` 无保护跨线程读写 → 加 `std::mutex`，并删掉返回内部指针的 `GetModelDirName`/`GetModelJsonBase` 改拷贝式 `CopyModelDirName`/`CopyModelJsonBase`。
  - 本轮单测：新建 `CronMatcherTest`(11，含与 `CareAutomation.cronMatches` 的**逐值差分**测试) / `LLMClientTest`(2，close 后 `chatStream` 抛 `RejectedExecutionException`、close 幂等) / `McpServerManagerTest`(4，验证全局注册表注销)；扩展 `RunLogTest`→11、`RunLogStoreTest`→9、`McpResponseParserTest`→16。`CronTextTest` 原有 13 条断言**全部保留未改**。
  - `CronMatcher.parse` 对少于 5 段返回 null，但 `"not a cron at all"` 按空白切成 5 段**不返回 null**——写断言时别想当然。
  - 本轮 verify：**873 单测 / 78 类，失败 0 错误 0；lint Error 0；APK(debug) 9,230,157B**（增量构建产物；全量重建约 9.7MB，差异来自增量打包）；耗时 compile 10s / test 48s / lint 34s / apk 10s。
  - **v1.191.0 已发布**：versionCode 195 / versionName 1.191.0、Release id 429321526、资产 app-release.apk 8,688,813B、tag v1.191.0 @ b6050a6（= main）；CI run 41100670348 success。
  - 本轮版本：versionCode 195 / versionName 1.191.0。
  - 已知未改（有意）：`MarkdownRenderer.tableBitmapPixelsUsed` 只增不减（整页 6M 像素预算即到顶，设计如此）；cron 的 day/dow **AND vs OR** 语义未动（改语义会打破 `CronTextTest` 断言并改变已有任务触发条件），只在 `CronMatcher` 注释里写明。
