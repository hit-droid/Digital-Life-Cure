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
