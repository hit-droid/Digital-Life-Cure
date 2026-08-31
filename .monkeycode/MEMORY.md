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
