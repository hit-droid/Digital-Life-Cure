# Changelog

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
