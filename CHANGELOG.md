# Changelog

## v1.158.0 (2026-10-05)

承接 issue #98 里派给「工具卡结构化」的那条，把聊天页的工具过程卡片从**一段带 emoji 的长文本**改成**结构化卡片**，并顺手修掉一处历史卡片串内容的隐患。

### 工具过程卡片：图标 + 工具名 + 状态胶囊分栏

此前 `ChatActivity.appendToolBubble` 用单个 `TextView` 拼串（`🔧 正在调用工具：… \n\n状态：执行中 🔄`），
状态只是正文里的一行彩色文字，工具名和状态没有视觉层级。

现在拆成：头部一行「圆角图标块（🔧）+ 粗体工具名（超长省略号）+ 右侧状态胶囊」，
下面是可折叠的结果体：

- 执行中 → 强调色胶囊「执行中」；
- 回填成功 → 绿胶囊「成功」（新增 `bg_pill_ok`）；
- 回填失败 → 红胶囊「失败」（新增 `bg_pill_err`）；
- 未知 → 中性胶囊「已完成」（复用 `bg_pill_accent`）。

长结果（> 300 字符）仍默认折叠为一行摘要（150 字符），点击卡片展开/收起，与旧行为一致。

### 修掉历史卡片串内容（顺带）

旧实现把「完整结果/工具名/展开态」放在**全局字段** `curToolFull / curToolName` 上，
点击任意一张历史工具卡都会读到**最新一张**的内容。现改为每张卡自己的 `View tag`
（`ToolCardState`）持有名称、全文与展开态，点哪张只影响哪张。

### 验证

- `bash tools/check_color_parity.sh` → PASS，exit 0；
- 本沙箱无 JDK17/Android SDK，改动由 CI 单测门禁全量编译把关，合并 main 后触发 Release 出 APK。

### 附：色彩护栏转严（workbuddy，PR #102 / Closes #100）

`tools/check_color_parity.sh` 新增第 4 条判据：`drawable/` 内 `android:color` / `startColor` / `endColor` / `centerColor`
**不得写死白透明度**（`#FFFFFF` / `#××FFFFFF`），必须引用 `glass_*` token；矢量图标的 `fillColor` / `tint` 豁免。
新增 `tools/color_guard_baseline.txt`（基线为空——v1.157.0 已把存量 7 处迁到 `glass_ripple`/`glass_sheen`）。
验收：main 上 PASS；负面用例 `#33FFFFFF` 命中 FAIL；白 `fillColor` 图标 PASS（无误伤）。tooling-only，不改 App。

## v1.157.0 (2026-10-04)

用户选定「更大胆：玻璃拟态 + 更强层次」，把全 App 统一到 **Operit Glass** 语言；同时落地 workbuddy 在 issue #98 派给 trae 的**色彩体系构建护栏**。

### Operit Glass：玻璃化全站统一

在**单一深色色板**之上建立统一玻璃语言：半透明层 + 1dp 高光描边 + 顶部渐亮，叠在偏紫深底上呈磨砂质感。

- `values/colors.xml`：新增 `glass_fill / glass_fill_strong / glass_border / glass_border_soft / glass_highlight / glass_scrim`；`operit_bg` 由中性灰 `#1A1A1A` 收敛为偏紫深底 `#12101A`，`operit_surface / variant / divider / accent / header_start / nav_pill` 同步微调。
- **仍是单套色板**：未重建 `values-night/`、未改 `styles.xml`（尊重 workbuddy 的防撞车声明）。
- 统一改造的 drawable：`bg_operit_bottom_nav`、`bg_operit_drawer`、`bg_card`、`bg_glass_tile`（新增）、`bg_chat_input(_focused)`、`bg_input(_focused)`、`bg_dialog`、`bg_tool`、`bg_chip_outline`、`bg_btn_glass`。
- `UiKit`：`listTile` / `switchTile` / 关于信息卡 由实色平铺改为 `bg_glass_tile`。
- `ChatActivity`：快捷 chips 去掉 `✦` 字符前缀，改玻璃胶囊 + 强调色文字（动态建议栏同步）。

### 色彩护栏（issue #98 派给 trae 的活）

新增 `tools/check_color_parity.sh`，三条判据：

1. `values-night/` 必须不存在（存在即 fail）；
2. 若将来恢复双套色，`values/` 与 `values-night/` 同名 color 必须逐一同值（不同即 fail）；
3. `styles.xml` 的 `AppTheme` parent 不得含 `Light`（含即 fail）。

接入 CI：`.github/workflows/build.yml` 在 checkout 后新增 `Color system guard` 步骤，PR 与 push 都跑（秒级）。

### 验证

- `bash tools/check_color_parity.sh` → PASS，exit 0；反例（造 `values-night/`、改 `Light` parent）均正确 FAIL 后已复原；
- `JAVA_HOME=/opt/jdk17 ./gradlew :app:assembleDebug` 通过。

## v1.156.0 (2026-10-04)

修掉 workbuddy 在 issue #98 用**真机像素采样**钉出来的 AI 气泡渲染 bug，并把「护理」色语从绿统一到橙。

### B2（真 bug，与深浅色无关）：AI 气泡的左侧竖条被拉伸盖满整只气泡

截图里护理页「问题确认」卡整块亮绿、白字压在同色上——这不是深浅色问题，是 drawable 结构 bug。

`bg_bubble_ai.xml` / `bg_bubble_care.xml` 用 `layer-list` 画「气泡本体 + 左侧 3dp accent 竖条」，
第二层的竖条写成 `<inset><shape><size android:width="3dp">`，但 **`<item>` 未声明 gravity，
layer-list 默认 gravity=fill**：shape 被拉伸铺满整个 inset 区域，`<size>` 被忽略——
竖条色盖住整只气泡，文字与底色同系、对比度只剩 ~1.6:1。

修法用**叠加法**（比单加 `gravity="left"` 更稳）：

- 底层放 accent 条，上下 inset 8dp → 只露中段；
- 上层放气泡本体，`android:left="3dp"` 遮住其余部分；
- 露出的正好是「8dp 内缩、2dp 圆角的悬浮竖条」，且不依赖任何 intrinsic 尺寸。

> 不用 `android:gravity="left"` 的原因：① item 的 gravity 要 **API 23** 才生效，本项目 `minSdk 21`；
> ② shape 只声明 width 时 intrinsic height = **-1**，竖条可能整根消失（尺寸塌陷）。

### 护理色统一到橙系

同一「护理」语义此前两种色：会话列表 `tag_care` 是橙 `#F2A54A`，而护理页顶栏/气泡/徽章是**绿系**。
用户 2026-10-04 拍板统一到橙：

- `bg_chat_topbar_care.xml` 起始色 `#2A5A4A`（绿）→ `#5A3F12`（琥珀）；
- `bg_bubble_care.xml` 本体 `#1B5E45→#0F3D2E`（绿）→ `#5C4318→#33240B`（琥珀），accent `#5BD9A8` → `#F2A54A`；
- `bg_pill_care.xml` 底 `#1B5E45` → `#5C4318`、描边 `#6EE7B7` → `#F2A54A`；
- `bg_top_bar_care.xml`（遗留）同步到橙，避免误用；
- `ChatActivity` 徽章文字 `#6EE7B7` → `#F7C77A`。

### 验证

`JAVA_HOME=/opt/jdk17 ./gradlew :app:assembleDebug` 通过（资源合并 + 打包）。

## v1.155.0 (2026-10-04)

**浅色模式下界面「一半黑一半白」的根因修复：真正兑现「全 App 强制深色」。**

### 症状

系统切浅色模式后，聊天页的工具卡变成**纯白底 + 浅灰字，完全读不清**；
页面其它部分是深色 —— 典型的「一半黑一半白」。逐像素采样证实：工具卡底色 `(255,255,255)`。

### 根因：两套色板长期共存

App 里一直有两套颜色：

- `operit_*`（Operit 改造新增）：`values/` 与 `values-night/` **值完全一致**，恒深色；
- 旧色（`page_bg` / `card_bg` / `text_primary` / `bubble_ai` / `code_bg` / `brand_*` …）：
  `values/` 是**亮色**、`values-night/` 是**深色**，两套完全不同。

新组件用 `operit_*`、旧组件还用旧色。于是：

- 系统**深色**模式：旧色 = 深色 → 与 `operit_*` 一致 → 看起来统一 ✅
- 系统**浅色**模式：旧色 = 亮色 → 与 `operit_*` 深色混杂 → **白卡白底 + 浅字** ❌

工具卡正是这么翻白的：`bg_tool.xml` 的底色是旧色 `card_bg`（`values/` = `#FFFFFF`），
而卡内文字已迁到 `operit_*` 浅灰 —— 白底浅字。

（`values-night/colors.xml` 的注释其实早就写着「全 App 强制深色」，但只对 `operit_*` 兑现了，
旧色那一半从没跟上。这条是 workbuddy 在 issue #98 里查出来的，定位准确。）

### 修法（方案 A）

- `values/colors.xml`：把深色那套提为**唯一色板**，废弃「亮色 values」；
- **删除** `values-night/colors.xml` 与 `values-night/styles.xml` —— 日/夜不再有第二套，
  从结构上杜绝再次漂移；
- `values/styles.xml`：`AppTheme` parent `Theme.Material.Light.NoActionBar` →
  `Theme.Material.NoActionBar`；`AppDialogTheme` 同步去掉 `Light`（顺带修掉两套 dialog parent 不一致）；
- `styles.xml` 中残留的 `@color/brand` ×5 + `@color/brand_dark` ×1 →
  `operit_accent` / `operit_bg`，旧品牌色不再参与系统控件着色。

结果：无论系统深浅色，资源解析结果完全相同，界面恒为深色。

### 说明

- 本次只统一色板，**未逐个迁移**仍引用旧色名的 drawable（`bg_tool` / `bg_input` / `bg_dialog`
  等）。它们在深色色板下已正确，逐名迁移属后续清理。
- 已请 workbuddy 补一条 CI 护栏：`tools/check_color_parity.sh`，
  `values/` 与 `values-night/` 同名色不同值、或 `values-night/` 目录重新出现即 fail。

## v1.154.0 (2026-10-04)

真机截图（v1.153.0）逐张核对后修掉的一批视觉 bug。前三条都是「上一版自己写错」的回归。

### 顶栏渐变方向反了（最严重）

- `bg_operit_topbar` / `bg_chat_topbar_care` 都写了 `angle="90"`。Android 里 **90° 是「从下往上」**，
  起点色落在**底部**——于是顶栏渲染成「底部紫、顶部暗」，并在底边**硬切**回页面底色，
  恰好是注释里说要避免的「一块突兀的纯色条」。
- 真机竖线采样为证：`y=340 → (52,43,96)` 紫、`y=360 → (26,26,26)` 底色，20px 内跳变。
- 改为 `angle="270"`（上→下），起点色回到顶部，紫色自状态栏向下渐隐融入 `operit_bg`，真正无缝。
- 护理页同款修复。

### 侧栏抽屉半透明，底层列表透字

- `bg_operit_drawer` 底色 `#B32A2A2A`（alpha ≈ 70%）且无模糊，底层「最近对话」和会话卡文字
  直接透到抽屉面板上，与「数字生命 / v1.153.0 · 小汐 · 在线」叠字。
- 改为不透明 `#FF2A2A2A`。

### 设置页「互动」标题重复

- 折叠组头是「互动」，组内卡片小节标题又是「互动」，截图上同名标题上下连着出现两次。
- 组内卡片改名「语音与快速聊天」（其余 3 组组内标题本就不同名，无此问题）。

### 护理模式输入框提示被裁

- 输入框高度写死 `dp(48)`，护理 hint「向护理大脑提问，点 ＋ 可附带模型 zip…」偏长会折成两行，
  第二行被截断（截图里「附带模型 zip…」只露半截）。
- 收紧为与普通版等长的「向护理大脑提问，点 ＋ 传模型 zip」，并在代码里注明「别再加字」。

### 发送按钮对比度

- `bg_chat_send` 起点是 `operit_accent #C4B5FD`，纯白 `ic_send` 压在上面约 **2.4:1**，真机看着发糊。
- 起点改 `brand_operit_light #8A7DD8`（约 3.5:1），pressed 态仍压深一档。

## v1.153.0 (2026-10-04)

「仿 Operit AI」这条线收到**聊天页本身**——外壳（v1.150.0）、会话列表（v1.151.0）、
主 Tab 与二级页（v1.152.0）都已换新语言，唯独一进会话又退回旧观感，割裂感最强的一块。本版收口。

### 聊天页（对话大脑 / 护理大脑共用）

- **顶栏**：旧品牌紫实心顶栏（带投影）改为 Operit 渐隐 hero header（`bg_operit_topbar`），
  与 `UiKit.pageTopBar` 同一语言、与内容区无缝衔接；护理模式保留专属薄荷渐变（新增 `bg_chat_topbar_care`）做入口区分。
- **输入框**：此前是纯白底（全站共用的 `bg_input`，在深色聊天页上是一块刺眼亮斑），
  改为聊天页专用深色 surface（新增 `bg_chat_input` / 聚焦态 `bg_chat_input_focused`），
  并**显式补上 `setTextColor` / `setHintTextColor`**——底色变深后不再沿用默认黑字。
- **气泡**：用户气泡由旧品牌紫渐变换到 `operit_accent` 亮紫（新增 `bg_chat_bubble_user`）配深色文字；
  AI 气泡保持原深紫底浅字（本就是 Operit 语言）。两侧对比清晰可辨。
- **图标按钮**：附件 / 语音 / 返回改圆形涟漪（`operit_icon_button_ripple`），与顶栏返回同源；
  模型按钮改描边胶囊；发送 / 停止按钮改 `operit_accent` 系渐变（新增 `bg_chat_send`）。
- 本页残留的 `R.color.brand` 系引用（建议 chip / 录音态 / 工具状态 span / 审批按钮）统一到 `operit_accent`。
- **顺带修一处隐患**：输入框的 `setOnFocusChangeListener` 此前被重复注册两次（后者覆盖前者），
  删掉注定失效的那个，避免以后改这里「以为改了却没生效」。

### 纯逻辑抽取

- 新增 `ui/chat/ChatLayoutOps`：把散落在 3000+ 行 `ChatActivity` 里的布局魔法数收口
  （气泡最大宽度比例、顶栏留白含状态栏、圆形按钮直径 36dp、气泡内边距），
  配 `ChatLayoutOpsTest` 9 例，含三条跨组件契约（36dp/10dp 须与 `UiKit.pageTopBar` 同步）。
- 既有聊天功能零删减；workbuddy PR #97，Closes #96。

## v1.152.0 (2026-10-04)

「仿 Operit AI」这条线的**收尾统一**：外壳（v1.150.0）与会话列表（v1.151.0）落地后，
把剩下**所有还各写一份顶栏**的页面一次性收口到同一套组件，消除「同一个 App 好几种顶栏」的割裂感。

### 主 Tab（发现 / 插件 / 设置）

- **设置页**：四处分组内容此前各铺一层 `operit_surface` 底色，与卡片自身面板同色，卡片边界糊掉；
  现统一改为透明分组，卡片圆角边界重新可见；根容器补 12dp 左右内边距，与其它页对齐。
- **设置页版本号**不再硬编码，改读 `UiKit.appVersion()`（避免再次出现「设置页显示旧版本」）。
- **插件页**：「卸载 / 删除」由带实心底的 `Button` 改为 Operit 描边 chip 胶囊，
  与全站次级操作语言统一；删除操作的危险色语义保留（`operit` 主题下用 `danger`）。
- **发现页**：小节标题色由旧的 `brand` 统一到 `operit_accent`。

### 二级页面（记忆 / 角色 / 主题 / 工具市场 / 关于 / 开发者 / 智能体控制台）

- 抽公共组件 **`UiKit.pageTopBar(Activity, title)`**：品牌紫渐隐背景 + 圆形返回按钮 + 标题，
  并**自动预留状态栏高度**——此前各页顶栏设置五花八门，部分页漏了状态栏内边距，标题会被状态栏压住。
- 上述 7 个二级页全部改用该组件，各页重复的顶栏代码与本地 `statusBarHeight()` 方法一并删除。
- 全量正文字号/字重/返回图标统一，返回按钮统一带涟漪与无障碍描述。
- 新增 `UiKit.statusBarHeight()` / `UiKit.appVersion()` 两个公共方法。

## v1.151.0 (2026-10-04)

继续推进「仿 Operit AI」这条线，这次优化顶栏下面的**会话列表**——旧观感残留最多的地方。

### 会话列表

- **头部**：`会话列表` 纯文字 + 大号实心「＋ 新建会话」按钮，改为强调色小节标题「最近对话」+ 右侧文字型 chip「＋ 新建」，与全站 Operit 小节标题统一。
- **卡片大幅瘦身**：移除每张卡片右下角常驻的红色「删除」按钮，删除统一移入**长按菜单**（重命名 / 置顶·取消置顶 / 删除），列表明显变干净。
- 卡片右侧只保留更新时间；标题与末条消息补 `ellipsize`，超长文案不再顶破布局。
- **修复置顶角标位置**：此前因 `addView` 顺序被挤到标题右侧，现按注释意图放到标题左侧。
- 会话头像由圆角色块改为**圆形**，贴合聊天列表惯例，也与旧版区分开。
- 空状态文案与入口对齐为「点上方「＋ 新建」开始」。

## v1.150.0 (2026-10-04)

本版把主界面外壳（顶栏 + 底部 5 Tab 导航）真正做成 **Operit AI 风格**。

### 为什么之前「改了却看不出变化」

上一轮的顶栏渐变、头像、新底部导航只停在**本地工作区、从未提交**；CI 只会从已提交的 main 分支出包，
所以用户装到的 v1.149.0 里只有 Android 16 闪退修复，**外壳仍是旧的纯色紫顶栏 + 旧导航条**——
表现就是「能进去了，但排版跟以前一样」。本版把这些改动补提交并发版。

### 顶栏（AppBar）

- 背景由「纯色紫 `#6D5BBA`」改为**自顶向下渐隐**的品牌紫（`#352C63 → #1A1A1A`），顶栏与内容区无缝衔接，不再是一块突兀色条。
- 左侧汉堡由会随字体渲染变化的 `☰` 字符改为**矢量图标** `ic_menu`，并套圆形涟漪热区（44dp）。
- 右侧新增**小汐头像 + 绿色在线点**，与侧栏头部同一视觉语言；标题 21sp 加粗、副标题半透明白。
- 顶栏延伸到状态栏下方，以内边距避让，保持沉浸式。

### 底部导航（仿 M3 NavigationBar）

- 新增 `ui/shell/OperitBottomNav`：仅承载 5 个主 Tab（对话 / 通讯录 / 发现 / 插件 / 设置），
  记忆 / 护理 / 控制台等高级路由仍走左侧侧栏。
- **选中态**＝图标胶囊底（`operit_nav_pill` 提亮为 `#463A8F`）+ 强调色图标 + 加粗标签；
  未选中＝次级灰。胶囊底**始终占位**（仅切换颜色），选中/未选中时图标与文字都不位移。
- 外观改为**四角圆角面板**，由 `MainActivity` 加左右 12dp、底部 10dp 边距，在深色页面上呈现为「浮起的一枚胶囊」。

## v1.149.0 (2026-10-04)

本版修复**上一轮 16 KB 对齐没修干净、Android 16 真机仍「点开即闪退」**的问题，并让启动崩溃不再无声消失。

### Android 16 仍闪退的真因：只对齐了 LOAD 段，没对齐 RELRO

**问题**：v1.141.0 给 native 库加了 `-Wl,-z,max-page-size=16384`，实测三个 ABI 的 LOAD 段 `Align` 都已是 `0x4000`（16 KB），但 Android 16 真机仍然一进就崩。

**原因**：本项目用 NDK r26（≤ r27）。按 [Google 官方文档](https://developer.android.com/guide/practices/page-sizes)，NDK r27 及更早**必须同时**给 `max-page-size` **和** `common-page-size` 两个链接参数：

```
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

只给前者时，链接器只把 **LOAD 段** 对齐到 16 KB，而 **RELRO / bss 等边界的取整仍按 `common-page-size`（默认 4 KB）** 计算，于是 `GNU_RELRO` 落在 4 KB 边界上（如 arm64 的 `0xf5000`）。16 KB 设备的动态链接器按 16 KB 粒度做 `mprotect`，保护范围会盖住本应可写的页，进程在重定位/首次写入时直接 `SIGSEGV`——表现就是「看起来对齐了，真机还是闪退」。

- **`app/src/main/cpp/CMakeLists.txt`**：补上 `-Wl,-z,common-page-size=16384`。
- 修复后实测发布包（`assembleRelease`）三个 ABI 的 `GNU_RELRO` 结束地址均为 16 KB 整数倍：arm64 `0xed010 + 0xaff0 = 0xf8000`、armeabi-v7a `0xb2100 + 0x5f00 = 0xb8000`、x86 `0xeb8d0 + 0x4730 = 0xf0000`（修复前分别是 `0xf5000` / `0xb6000` / `0xef740`，均非 16 KB 对齐）。

### 启动崩溃可见化（不再「点开就没了」）

**问题**：此前 native 库加载失败时，`System.loadLibrary` 在崩溃处理器安装**之前**且失败即 `return`，用户只看到静默闪退，什么线索都没有。

- `util/CrashHandler`：先装 `UncaughtExceptionHandler` 再 `loadLibrary`；加载失败写入含 `SDK` / `SUPPORTED_ABIS` / 堆栈的日志文件；新增 `previousCrash()` / `clearPreviousCrash()`。
- `render/Live2DNative`：静态块捕获 `loadLibrary` 失败，改由 `isLoaded()` / `loadError()` 暴露，避免类初始化直接抛错导致「首次触碰即闪退」。
- `ui/MainActivity`：本次启动若发现上次崩溃残留，先把崩溃原文弹出来（可滚动 / 可复制 / 可重试），暂不做任何可能再次触发的初始化，保证「进得去」且用户能把原因直接发给开发者。

## v1.148.0 (2026-10-04)

本版为 `ui/` 聊天与页面体验合集：新增**会话置顶**、**复制为 Markdown**、**运行环境自检**、**联系人搜索**四项功能（#82 / #83 / #87 / #88），并回补此前三项聊天修复（#72 / #75 / #78）。

### 会话列表支持置顶 / 取消置顶（#82）

**问题**：会话列表长按只有「重命名」，仅护理大脑会话恒置顶；自建会话一多，常用的钉不到顶部。

- `util/ChatStore`：`sessions` 表新增 `pinned` 列，**`DB_VERSION` 2 → 3**，`onUpgrade` 用 `ALTER TABLE` 补列；旧记录一律视为未置顶（**无需手动清理，自动迁移**）。新增 `setPinned()`；`getSession()` / `getSessions()` 读取该列；`SessionInfo` 增 `pinned` 字段并**保留旧 7 参构造**，兼容既有调用。
- 新增纯逻辑 `ui/chat/ConversationOrder`：排序规则统一收拢在此（**护理会话恒第一 → 置顶优先 → 组内按最近更新降序 → id 兜底稳定**），SQL 只负责取数，避免两处排序规则漂移。
- `ui/ConversationTabView`：长按菜单改为 `重命名 / 置顶·取消置顶`；置顶会话标题行前显示「置顶」角标。

### 消息长按菜单新增「复制为 Markdown」（#83）

**问题**：原「复制」拿到的是**渲染后的纯文本**，代码块围栏、表格 `|`、链接语法全丢；折叠态还只能拿到截断摘要。

- `ui/ChatActivity`：新增 `rawMarkdown` 登记（气泡 → 原始 Markdown），与既有 `fullTexts` 同生命周期。**用户气泡 / AI 气泡 / 历史恢复 / 流式增量回复四条路径全部登记原文**，折叠态同样可复制完整原文。
- `ui/chat/ChatTextOps`：新增 `toMarkdownForCopy()`——只剥离界面附加的折叠提示与中断角标、统一行尾为 LF，**不破坏 ``` / 表格 / 链接语法**。
- 两处长按菜单各加一项，其后索引顺延：用户 `朗读 / 复制 / 复制为 Markdown / 引用回复 / 重新发送 / 分享`；AI `朗读 / 复制 / 复制为 Markdown / 引用回复 / 分享 / 重新生成 / 删除`。普通「复制」行为不变。

### 「关于」页新增「运行环境自检」卡片（#87）

**问题**：用户报障（如 Android 16 上 16 KB 内存页与 4 KB 对齐 native 库不兼容）时，我们拿不到对方机型信息，只能靠猜。

- 新增纯逻辑 `util/EnvFacts`（不依赖 Android API）：Android 版本+API 级别、ABI 列表、内存页大小、16 KB 页面判定，以及供复制用的多行报告。
- `ui/AboutActivity`：版本信息下方新增卡片 +「复制环境信息」按钮；页大小取 `Os.sysconf(_SC_PAGESIZE)`（API 21+，取不到时显示「未知」）。

### 模型联系人页支持按名字搜索过滤（#88）

**问题**：导入的模型一多，联系人列表平铺后找不到。

- 新增纯逻辑 `ui/contacts/ContactFilter`：大小写不敏感（`Locale.ROOT`）、去首尾空白、空查询返回全部、按名字子串匹配。
- `ui/ContactsTabView`：头部新增搜索框，输入即过滤；无命中时显示「没有匹配的模型联系人」空态。

### 回补：聊天修复三项（#72 / #75 / #78）

上列三项此前已合并进 main 但未记入版本段，随本版一并回补：

- **#72 删除任意一条消息**：`ChatStore` 增加 `id`（rowid）与 `deleteMessage(sessionKey, msgId)`，`ChatActivity` 用 `msgIds` 关联气泡与行，长按可删除任意一条（原先只能删最后一条）。
- **#75 停止生成后丢弃迟到回调**：点「停止」后到达的 `onDelta` / `onDone` 直接丢弃，修复「幽灵助手气泡复活 / 残留文本入库」竞态。
- **#78 `onDestroy` 清理防抖回调**：新增 `handler.removeCallbacksAndMessages(null)`，修复 `suggestionDebounce` 在页面销毁后仍触发建议请求的泄漏。

### 验证

新增纯逻辑单测 32 例，全部通过：`ConversationOrderTest` 9 例、`EnvFactsTest` 12 例、`ContactFilterTest` 11 例；`ChatTextOpsTest` 扩充至 24 例（含「复制为 Markdown」5 例）。CI 单测门禁通过后合并；push main 后由 workflow 出包并发行 Release。

## v1.147.0 (2026-10-04)

本版补上**备份包的完整性校验**（#86）：v1.146.0 的恢复是边解压边写盘，包被截断或损坏时会写坏一半才失败，等于**静默损坏用户数据**；且恢复发生在下次启动、界面尚未起来，成功与失败用户都不可知。

### 恢复前整体校验，损坏包一个字节都不写

**问题**：`BackupArchive.restore` 边解压边覆盖 `shared_prefs/` 与 `databases/`，而截断的 zip 往往不报错、只是少了末尾几条。

- 新增纯逻辑 `storage/BackupManifest`：清单构建 / 解析 / 校验（格式版本、创建时间、条目数、逐条 `path` + `size` + `CRC32`），用 `org.json`。
- `storage/DataPort.exportZip`：写条目时同步算 `size`/`CRC32`，全部写完后追加最后一个 zip 条目 `dlc-manifest.json`；返回的文件数仍只算数据文件（不含清单）。
- `storage/BackupArchive.restore`：先做一遍**只读校验**（条目集合完全一致 + size/CRC 全对），任一不符抛 `IOException`、目标目录**不产生任何文件**；无清单的旧包走弱校验，保持向后兼容。

### 恢复结果回传

**问题**：恢复要重启后在 `Application.onCreate` 完成，此时界面还没起来，用户不知道到底成没成。

- `App` 应用待恢复后把结果写入 `Settings`（`ok|文件数` / `fail|原因`）；`ui/SettingsTabView` 首次打开时汇报一次并清除，弹出「恢复完成 / 恢复失败」。

### 验证

`./tools/verify.sh` 全绿（compile / test / lint / apk），单测总数 524、失败 0。新增覆盖：清单构建/解析往返、CRC 不符、缺条目、多条目、旧包兼容；`BackupArchiveTest` 断言篡改/截断包恢复时目标目录不落盘。

## v1.146.0 (2026-10-04)

本版补上**可移植的口令加密备份与恢复**（#81）：此前「数据与隐私」只能导出**明文** zip、且**没有导入入口**，而 `allowBackup=false` 之后跨机迁移等于断了路。

### 导出可选口令加密

**问题**：导出的是明文 zip，一旦经云盘/聊天工具转发就等同泄露全部对话、记忆与 API 配置；而想跨设备迁移时又没有任何导入入口。

- 新增 `util/PassphraseCrypto`：**PBKDF2WithHmacSHA1（120,000 次迭代）派生 + AES/GCM**，容器格式 `DLP1 | salt(16) | iv(12) | ct+tag`。与设备绑定的 `SecureStore`（Android Keystore）分工明确——本类用**用户口令**现场派生密钥，密文可拷到任意设备、用同一口令解开。为兼容 minSdk 21 未用 `PBKDF2WithHmacSHA256`（API 26+）；口令错误或密文被篡改时 GCM tag 校验失败，统一报「口令错误或备份已损坏」。
- `ui/SettingsTabView` 导出改为**两步**：SAF 选好位置后询问「设置口令 / 不加密 / 取消」，口令要求至少 6 位且不留存（忘记无法恢复，界面已明示）。不加口令时行为与旧版一致。

### 从备份恢复（两阶段落地）

**问题**：恢复必须覆盖 `shared_prefs/` 与 `databases/`，但这些文件在进程运行期间可能被 SharedPreferences / SQLite 占用，或写入后又被内存态覆盖。

- 新增 `storage/BackupArchive`：与 `DataPort.exportZip` 互逆，只认 `files/`、`shared_prefs/`、`databases/` 三个顶层前缀，其余一律忽略；逐条目做 **Zip Slip** 校验（拒绝绝对路径、`.`/`..` 段与反斜杠穿越）；`files/models/`（已导入模型）不覆盖。
- 新增 `storage/PendingRestore` + `App`（`Application` 入口，`AndroidManifest` 注册 `android:name=".App"`）：导入时**不立即写盘**，先把 zip 暂存到 cache；等进程**下次启动、任何存储打开之前**（`Application.onCreate`）再解压写入，避免被内存态或文件锁覆盖。无论成功失败都删除待恢复文件，防止每次启动反复失败。
- `ui/SettingsTabView` 新增「从备份恢复」：SAF 选择文件 → 自动识别加密容器（按魔数）并弹口令 → 二次确认 → 重启应用生效。

### 验证

`testDebugUnitTest --tests *PassphraseCryptoTest --tests *BackupArchiveTest --tests *PendingRestoreTest` 通过，新增 23 例（加解密往返 / 空串与 null / 错口令与篡改检测 / Zip Slip 边界 / 模型不覆盖 / 两阶段暂存与清理）。总数 511。CI 单测门禁通过后合并；push main 后由 workflow 出 `app-release.apk` 并发行 Release。

## v1.145.0 (2026-10-04)

本版为**工具审批防线加固**（#79）：把「外部/第三方工具」纳入默认审批，并修复带 namespace 前缀的危险工具漏判。

### 外部/第三方工具（MCP）一律需审批

**问题**：危险工具审批（v1.141.0 #40）只按「整名 + `delete_` / `forget_` / `remove_` 前缀」判定，存在两处漏网：MCP/插件工具带 namespace 前缀（如 `gh_delete_repo`、`fs_remove_file`）过不了整名比对，危险动词识别不到；远程 MCP 工具本身来自第三方、不可信，即便名字看似无害（`list_repos` 之类）也会被直接执行，用户没有拒绝机会。

- `tools/Tool` 新增 `isExternal()`（默认 `false`），`mcp/McpTool` 覆写为 `true`；
- `harness/ToolApprovalPolicy.requiresApproval(name, external)`：`external=true` 恒需审批；危险名判定改为按 `_` 边界逐段取后缀比对，覆盖带前缀的工具名；
- `harness/ToolPipeline` 从全局 `ToolRegistry` 按名取实例判定 `external`（查不到视为内部，走名字判定）；
- 单测 +3（`ToolApprovalPolicyTest`）：命名空间危险/只读工具、外部工具恒审批。

### 验证

`testDebugUnitTest --tests *ToolApprovalPolicyTest --tests *ToolPipelineTest` 通过（14 + 22）。CI 单测门禁通过后合并；push main 后由 workflow 出 `app-release.apk` 并发行 Release。

## v1.144.0 (2026-10-04)

本版为**工程化重构**：把 `care/CareTools` 里不依赖 Android 的纯文件/文本逻辑下沉到新类 `CareFileOps` 并补单测，行为不变。

### CareTools 上帝类下沉（护理包可测性）

**问题**：`care/CareTools` 是 1400+ 行的护理工具集，zip 解压 / 模型分析 / 动作编辑全混在一起；其中 `safeResolve`（Zip Slip 防护）、`stripModelJsonSuffix`、`sanitizeDirName`、`formatSize`、`relPath`、`firstFile`、`countFiles`、`readFile`、`deleteRecursive`、`copyRecursive`、`copyFile` 都是无状态纯逻辑，却埋在 Android 依赖里，安全相关的 Zip Slip 防护此前**零测试覆盖**。

- 新增 `care/CareFileOps`（纯 Java 工具类，不依赖 Android API），承接上述方法。
- `care/CareTools` 一律改走 `CareFileOps.*`，只保留业务编排，瘦身约 190 行。
- 新增 `CareFileOpsTest`（纯 JVM 单测，14 例）：覆盖 Zip Slip 边界（绝对路径 / `..` 穿越 / 空路径）、后缀剥离大小写、目录名净化、尺寸与相对路径格式化、递归统计/复制/删除。

### 验证

纯迁移，无行为变更。`testDebugUnitTest` 单测门禁通过后合并；push main 后由 workflow 出 `app-release.apk` 并发行 Release。

## v1.143.0 (2026-10-04)

本版为**发行与安全加固**（P0 打包）：发行包从 debug 构建切到正式 release 构建、MCP 鉴权令牌加密落盘、收紧组件导出。由发布负责人 trae 完成后 bump。

### 发行包改用 release 构建（非 debuggable + 正式签名）

**问题**：此前 CI 出的是 `assembleDebug`，且 `buildTypes` 里只有 `debug`——发行包是可调试构建（可 attach 调试器、`run-as` 读应用私有数据），且签名配置挂在 `debug` 名下，语义上就是个"调试包"。

- `app/build.gradle`：新增 `signingConfigs.release` 与 `release` buildType（`minifyEnabled false`）。签名沿用同一 `app/release-key.p12`（同 alias/密码），**证书与历史包一致，老用户可直接覆盖升级**；密码支持 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` 环境变量覆盖，便于日后接入自有密钥而不改代码。
- `.github/workflows/build.yml`：出包步骤改为 `assembleRelease`，上传 `app/build/outputs/apk/release/*.apk`；单测门禁仍跑 `testDebugUnitTest`。
- **R8 暂未开启**（`minifyEnabled false`）：native 侧 `JniBridgeC.cpp` 用 `FindClass("com/digitallife/render/Live2DNative")` + `GetStaticMethodID(..., "loadFile"/"moveTaskToBack")` 按名回调，`BuiltinTools.registerIfAbsent` 还用 `Tools.class.getDeclaredMethod("register", ...)` 反射。开启混淆必须补 keep 规则（至少 `-keep class com.digitallife.render.Live2DNative { *; }` 与 `-keep class com.digitallife.brain.Tools { *; }`）并做真机冒烟，而当前容器无真机/模拟器无法验证运行时，故留待后续有设备时单独开。

### MCP 鉴权令牌加密落盘

**问题**：`McpServerManager` 把 `headerValue`（多为 `Authorization: Bearer <token>`）以明文 JSON 写进 `shared_prefs/mcp_servers`，与 v1.140.0 已加密的 API Key 策略不一致，密钥裸奔。

- `mcp/McpServerManager`：落盘改走 `util/SecureStore`（Android Keystore + AES/GCM），读回时解密；历史明文（无 `enc:v1:` 前缀）原样返回并在下次保存时透明加密回写。设备不支持加密（API < 23）时降级明文，与 API Key 同一策略。

### 组件导出收紧

**问题**：除 `MainActivity` 外，Chat / Developer / Memory / AgentConsole / ToolMarket / Persona / Themes / CareModels 等 Activity 全部 `android:exported="true"` 且无权限校验，任意第三方 App 都可直接拉起；`ChatActivity` 还读取 intent extras（session/title/model），可被外部注入参数。

- `AndroidManifest.xml`：这些**无 intent-filter、仅由 App 内部显式 Intent 拉起**的页面统一改为 `android:exported="false"`；`MainActivity`（LAUNCHER）与 `BootReceiver`（系统广播）保持导出。

### 未纳入本版

- **明文流量（`usesCleartextTraffic="true"`）未收紧**：Android 的 `networkSecurityConfig` 只能按域名/IP 枚举，无法表达"私网网段"，而自建/局域网 LLM 端点（`http://192.168.x.x:port`）是本 App 的一等用例，收紧会直接破坏功能。判定为**已接受风险**，保留全局放开。
- 数据导出仍不可跨机迁移（密钥在 Keystore，`enc:v1:` 密文异机解不开）；无 i18n；`targetSdk` 仍为 34。

### 验证

无业务逻辑改动（签名/构建类型/清单/密钥存储路径）。CI 单测门禁通过后合并；push main 后由 workflow 出 `app-release.apk` 并发行 Release。

## v1.142.0 (2026-10-04)

本版汇总 v1.141.0 之后合并到 main 的聊天体验修复（#55 / #57 / #59 / #64 / #66）与 CI 降耗（#63），由发布负责人 trae 统一 bump 发版。

### 聊天体验修复

- **重开会话定位 / 未读计数 / 附件（#55）**：重开会话后滚动落到最新一条；「回到底部」未读计数不再虚高；普通会话也能发送附件。
- **删除 / 重新生成错位（#57）**：删除与重新生成只对最后一条回复生效，界面不再与数据库各删一条。
- **折叠态复制拿到截断文本（#59）**：长按复制优先取 `fullTexts` 保存的完整文本，不再复制折叠后的截断文案。
- **搜索高亮冲掉 Markdown（#64）**：`highlightText` 改为叠加 `BackgroundColorSpan` 不再重建文本，保留 AI 气泡的 Markdown 格式；新增浮动「上/下一条」导航条替换原来的占位 toast。
- **长消息折叠后退化为纯文本（#66）**：`appendCollapseHint` 改用 `SpannableStringBuilder` 拼接，保留加粗/代码/链接等 Markdown span，长消息展开后格式不再永久丢失。

### CI：降低 Actions 分钟消耗（#63）

- 同一分支连续 push 时取消旧 run（仅 PR 取消；main 的发布构建串行排队，不中途打断）。
- 纯文档改动（`**/*.md`、`docs/**`）不触发；PR 只跑单测门禁、不出包不发版，出包与发行只在 push 到 main 时做。

### 验证

本次为发版型变更（版本号 + 文档），未改动业务逻辑；单测总数维持 **471 / 38 个测试类**。CI 单测门禁通过后合并，push main 后由 workflow 出包并发行 Release。

## v1.141.0 (2026-10-04)

本版合并 PR #52（Android 16 兼容修复 + 危险工具确认 / 回到底部）与 PR #53（版本 bump），并含 workbuddy 的 PR #51（Markdown 行内链接 / 任务列表 / 水平线）。发布负责人 trae 统一 bump 发版。

### Android 16 兼容修复：native 库 16 KB 对齐

**问题**：Android 16（API 36）设备默认启用 16 KB 内存页，按 4 KB 对齐链接出的 native 库在这些设备上直接加载失败，表现为「装上打不开 / 启动即崩」。本 App 带 Live2D Cubism native 库，正好命中。

- **`app/src/main/cpp/CMakeLists.txt`**：新增 `-Wl,-z,max-page-size=16384`，强制 LOAD 段 16 KB 对齐。16 KB 对齐的库在 4 KB 页设备上同样可用（16 KB 是 4 KB 的整数倍，多出的对齐只是被忽略的填充），**一个包同时覆盖 Android 10~14（4 KB 页）与 Android 15/16（16 KB 页）**。
- **`app/build.gradle`**：`arguments '-DANDROID_STL=c++_static'`。项目用的 NDK r26 其预编译 `libc++_shared.so` 仍是 4 KB 对齐；改静态 STL 后不再打包该 .so，避免它在 16 KB 设备上拖后腿。
- 实测发布包 v1.141.0：arm64-v8a / armeabi-v7a / x86 三个 ABI 的 LOAD 段 Align 均为 `0x4000`（16 KB）；APK 内 native 库只剩 `libmaidendungeon.so`，不含 `libc++_shared.so`。

### 危险工具执行前确认（#40）

**问题**：对话大脑的工具调用循环此前无条件执行任何工具，「清除数据 / 删文件」这类破坏性操作没有任何确认环节。

- **`harness/ToolApprovalPolicy`**（纯逻辑，JVM 可测）：判定哪些工具需要审批、生成脱敏确认文案（敏感参数值显示 `***`）、维护会话级放行集、记录本轮拒绝。
- **`harness/ToolPipeline`**：在 `tools/pre-execute` waterfall 之后、宿主执行之前加**唯一审批埋点**；未装配策略或非危险工具零打扰；本轮已拒绝的同一工具直接短路，防模型重复试探造成死循环。
- **`harness/AgentLoop`**：每轮开始清空「本轮已拒绝」记账（会话放行集保留）。
- **`ui/ChatActivity`**：确认卡片三选一「仅这次允许 / 本会话始终允许 / 拒绝」；工具循环的后台线程阻塞等待用户决定，超时按拒绝处理。

### 回到底部 + 未读计数（#43）／ Markdown 三处渲染（#44）

- **`ui/chat/ScrollAnchor`**（纯逻辑）：距底部超过阈值时显示「回到底部」悬浮按钮，累计未读条数（超过上限显示 `N+`）。
- **`ui/ChatActivity`**：接入悬浮按钮与未读计数。
- **`ui/MarkdownRenderer`**（workbuddy PR #51）：补齐行内链接 `[文字](url)`（URLSpan + 链接色）、任务列表 `[ ]`/`[x]`（☐/☑）、水平线 `---`/`***`/`___`。本版合并时与 trae 分支的同名实现去重，保留 main 版。

### 验证

新增 47 单测（ToolApprovalPolicy 11 + ToolPipeline 22 + ScrollAnchor 12 + Markdown 渲染补测），单测总数 **471 / 38 个测试类**，失败 0、错误 0；`./gradlew testDebugUnitTest assembleDebug` 全绿，CI 单测门禁通过后合并。

## v1.140.0 (2026-10-03)

本轮合并两个 PR：#49（API Key 加密存储 + 收紧备份 + 数据导出/一键清除，Closes #47）与 #50（情绪外显——情绪驱动待机表情 + 自主小动作，Closes #48），均由 trae 提交，发布负责人 trae 统一 bump 发版。

### API Key 加密存储 + 收紧备份 + 数据导出 / 一键清除

**问题**：API Key 以明文躺在 `shared_prefs` 里，且 `allowBackup` 默认 `true`，会被系统云备份带出；用户也没有任何「把数据拿走 / 清干净」的手段。

- **`util/SecureCrypto`**（纯逻辑，JVM 可测）：AES/GCM/NoPadding，密文格式 `enc:v1:<hex(iv || ciphertext+tag)>`；hex 手写编解码，避开 `java.util.Base64`（API 26+）/`android.util.Base64`（JVM 不可用）的版本与可测性问题。无前缀的历史明文读取时原样返回，天然兼容旧数据。
- **`util/SecureStore`**：密钥生成/保存在 Android Keystore（不外泄、不随备份导出）；API < 23 无 Keystore AES 时降级明文并在设置页明确提示，不牺牲老机型可用性。密钥解析一次后静态缓存，避免每次读写都过 Keystore。
- **`util/Settings`**：`getApiKey` 首次读到明文即透明加密回写（迁移无感）；`setApiKey` 一律加密写入。新增 `isSecureStorageSupported()` 供 UI 展示状态。
- **`AndroidManifest`**：`android:allowBackup="false"`，含密文的 prefs 不再被云备份带出；数据迁移改由设置页的导出/清除提供。
- **`storage/DataPort`**（纯逻辑，传 `File` 根目录便于单测）：导出把 `shared_prefs/`、`databases/`、`files/` 打成 zip，**排除体积大的 `files/models/`**（否则包会被撑爆、清除后还要重新导入模型）；清除即删除同一范围并保留模型，供「一键清除」复用。
- **`ui/SettingsTabView`**：新增「数据与隐私」卡——加密状态说明、SAF `ACTION_CREATE_DOCUMENT` 导出、二次确认后清除、隐私说明弹窗。
- 新增 **18 单测**：`SecureCrypto`（往返 / 随机 IV / 换密钥 / 篡改 GCM tag / hex）、`DataPort`（导出条目 / 递归打包 / 清除保留模型 / 缺目录容错）。

### 情绪外显：情绪驱动待机表情 + 自主小动作（#48）

**问题**：`EmotionState` 一直在演化，但除触摸/对话瞬时设一下表情外，待机时桌宠始终是同一张脸——有内部情绪却不外显。

- **`ui/pet/EmotionExpression`**（纯逻辑，JVM 可测）：把情绪向量映射为 `F01`~`F06`，并做**两层防抖**避免边界抖动——最短停留 4s；切换迟滞要求目标情绪比「当前表情对应情绪」高出 `0.12`；平静值占优或主要情绪低于 `0.5` 时回落 `F01`。另提供 `expressionFor`/`dimOf` 双向映射与 `motionFor` 配套小动作。
- **`service/PetService`**：接入 1Hz vitals tick，仅在 L2 状态机为 `IDLE`、无动作在播、且距上次交互超过 6s 冷却时接管待机表情；切换时顺带播一个匹配的自主小动作。用户点击/长按/手动换表情会 `syncCurrent` 对齐内部状态，冷却期一过不抢回旧表情。
- 新增 **12 单测**：目标判定阈值、迟滞挡近似切换、最短停留、交互同步、静态映射。

### 验证

新增 30 单测（SecureCrypto 11 + DataPort 7 + EmotionExpression 12），单测总数 **424 / 35 个测试类**，失败 0、错误 0；本地 `verify.sh --quick` 三关全绿（compile / test / lint，lint 0 error）。

## v1.139.0 (2026-10-03)

形象管理页补上「从本地文件导入模型」（PR #46，Closes #45）。

### 从本地文件导入模型

**问题**：形象切换本身早已完整（导入 / 切换 / 设默认 / 删除 / 启动恢复，形象管理页与 AI 工具都有），但普通用户没有「添加模型」的入口——`ModelManager.importFromUri` **自诞生起就是死代码**，全仓库无人调用；唯一路径是把模型 zip 发到护理大脑对话里让 AI 装。开箱又只有内置 `huohuo` 一个模型，想换形象无从下手。

- **`care/CareModelsActivity`**：顶栏新增「导入」按钮 → SAF `ACTION_OPEN_DOCUMENT`（MIME 限 `application/zip` / `application/x-zip-compressed`，`*/*` 兜底）→ `onActivityResult` → 后台解压注册；结果用 `AlertDialog` 展示完整多行信息（含自动补动作提示，Toast 会截断）。
- **桌宠未启动也能导入**：先调一次幂等的 `Live2DNative.init` 保证 `files/models` 已就绪；解压与 `nativeAddModelDir` 注册都不依赖 GL，下次启动 `PetService` 自动加载。此前 `importFromUri` 若在桌宠未启动时被调用会直接报「模型目录未初始化」。
- **桌宠运行中**时再全量 `registerImportedModels` 对齐；`nativeAddModelDir` 已按目录名去重（`LAppLive2DManager.cpp`），覆盖导入同名目录不会产生重复条目。
- 空态文案从「可在护理大脑对话中发送 zip」改为指向新入口。

### 验证

单测 394（无新增——导入路径依赖 `Live2DNative`，JVM 加载不了 native 库，无法本地单测）。本地 `verify.sh --quick` 三关通过（compile / test / lint，lint 0 error）。

## v1.138.0 (2026-10-03)

本轮合并两个 PR：workbuddy 的 #39（Markdown 行级渲染补短板，Closes #36）与 trae 的 #42（应用内更新检查，Closes #41），由发布负责人 trae 统一 bump 发版。

### 应用内更新检查

侧载安装没有任何「有新版本」提示——装了 v1.137.0 之后，新版本只能自己去仓库翻。现在「关于」页手动点一下即可。

- **纯逻辑 `update/UpdateChecker`**（JVM 可测）：`normalize`（去 `v` 前缀、丢弃 `-`/`+` 后缀即预发布/构建元数据、超长数字段判非法）、`compare`（数值比较，`"1.10" > "1.9"`；字符串比较会错）、`isNewer`（任一侧无法解析一律 `false`，宁可漏报不误报）、`pickLatest`（跳过 `draft`、默认跳过 `prerelease`，按版本号取最高而非数组顺序）。缺 `html_url` 时兜底到 releases 页。新增 13 单测。
- **`update/UpdateClient`**：后台线程拉 `releases?per_page=15` → 比较本机 `versionName` → 回调主线程；15s 超时、512KB 上限；403/429（匿名 GitHub API 按 IP 限流 60 次/小时）给友好文案而不是把 `HTTP 403` 甩给用户。
- **`ui/AboutActivity`**：新增「检查更新」按钮 + 状态文案，检查中禁用防连点；发现新版按钮变「前往下载 vX.Y.Z」，用浏览器打开 Release 页；无新版显示「已是最新版本」。手动触发、不后台轮询（避免打扰与限流）。
- **实测要点**：仓库里有一批遗留的 `v1.1.0` **draft** release，不过滤 draft 会直接误报「有新版 v1.1.0」——`pickLatest` 已跳过，实测对当前版本判定为「已是最新」。

### Markdown 行级渲染补短板（workbuddy）

由协作者 **workbuddy** 提交（原 PR #39）。App 聊天里的 Markdown 是自研行级渲染，此前三处 LLM 常见写法渲染不出来。

- **缩进列表丢符号**：`UL_ITEM` / `OL_ITEM` / `BLOCKQUOTE` 正则原先锚死行首（`^[-*]`、`^>`），多级列表的二级行匹配不上，连着原始文本吐出、层级全丢。现在放开前导空白并按层级补缩进（用 **NBSP**——普通空格在行首会被 TextView 折行策略吃掉）。
- **`#标题` 漏识别**：`HEADING` 原要求 `#` 后至少一个空白，模型写中文标题常是 `#标题`，渲染出来是带井号的字面文本。放宽并加 `(?!#)` 护栏（`#### 四级` 仍是普通文本）。
- **表格截断劈 emoji**：`truncateCell` 逐 char 累加宽度，emoji 是两 char 代理对，边界落在高低代理之间会留下半个字符（豆腐块）。改成按**码点**推进，放不下就整个不要。
- 行级判定从 `appendLine` 下沉为纯静态 `parseBlockLine`（只识别、不拼 Span），新增 `MarkdownBlockTest` 30 条（不依赖 Robolectric），含三条防误判：`#### 四级` 仍是普通文本、`3.14 是圆周率` 不被当有序列表、任意宽度下表格都不留半个 emoji。

单测 351 → 394（#39 +30、#41 +13）。本地 `verify.sh --quick` 三关通过（compile/test/lint，lint 0 error）。

## v1.137.0 (2026-10-03)

桌宠触摸分区反应：摸头 / 戳身子给不同反馈（PR #38，Closes #37）。

### 触摸分区反应

**问题**：摸头和戳身子此前的反馈完全一样——同一句文案、同一个表情、同样的情绪增量，桌宠最该有的「摸头杀」没有差异化反馈。

- **纯逻辑 `ui/pet/PetTouchReaction`**：`zoneFor` 判定分区（落在人偶区域外或区域尺寸退化为 0 一律返回 `NONE`，不除零）、`reactionFor` 映射反馈——摸头害羞（`F06` + 亲密度 +0.02 + shy 0.18）、戳身子惊讶（`F05` + 亲密度 +0.01 + surprised 0.15）；**未知分区兜底**为「不改表情、不出气泡、不动情绪」，仅保留原有随机 `TapBody` 动作。分区值对齐 Cubism `Head`/`Body`，日后原生直出命中结果可直接接。新增 9 条单测覆盖分区边界与头身差异。
- **`ui/PetOverlayView`**：`Listener` 新增 `onTapZone(zone)`（`onTap()` 语义原样不动，避免波及语音分支）；`handleModelTap` 据此设表情、选动作，并把 zone 透出。
- **`service/PetService`**：`onTapZone` 只读调用 `EmotionState.addIntimacy/apply` 落气泡与情绪，不改 `brain/` 内部算法。

**为什么用 Java 近似而不是原生 HitArea**：`PetOverlayView.onInterceptTouchEvent` 在 ACTION_DOWN 命中人偶带时就拦截了事件，子 View `Live2DGLView` 只收到 ACTION_CANCEL，`nativeOnTouchesBegan` 从不触发——原生 `LAppModel::HitTest` 这条线在悬浮窗里是死的；跨线程去 GL 线程读 viewMatrix 做命中又会竞态。故按触摸点在人偶区域内的纵向位置切分（上 38% 头 / 其余身子）。**已知误差**：切分线固定，不随模型头身比例自适应，宁可偏「头」。

单测 342 → 351。本地 `verify.sh --quick` 三关通过（0 失败）。

## v1.136.0 (2026-10-03)

桌宠状态胶囊「点开详情」（Issue #27 第 1 条收尾；该条正文的「可点开详情」此前一直没落地）。

### 状态详情卡片

胶囊此前只能看到三个标量，点它还会被手势层当成「点了人偶」→ 直接弹输入框。现在胶囊本身可点，弹出详情卡片。

- **纯逻辑 `ui/pet/PetStatusText`**：新增 `DIMS`（六维顺序，与 `brain/EmotionState.DIMS` 对齐）、`detailTitle`（角色名可改，空名回落「我的状态」，超 8 字截断防顶破卡片）、`detailSubtitle`（`亲密度 62% · 亲密`）、`bondLevel`（陌生/眼熟/朋友/亲密/挚友，20% 一档）、`barWidth`（0~1 → 像素，NaN 视 0、越界夹取，**保证永不出现负数宽度**——负宽度在 View 上直接抛异常）。新增 10 条单测。
- **`ui/PetOverlayView`**：`statusView` 改为 clickable；详情卡片复用长按菜单的遮罩层套路（居中、点空白收起、卡片自身吃点击不误关）。`StatusProvider` 加 `name()` / `emotion(dim)` 两个 `default` 方法，不破坏既有匿名实现。
- **手势冲突修复**：`onInterceptTouchEvent` 在「状态胶囊命中」时放行给子 View。胶囊压在 `modelRect` 人偶带里，不放行就永远点不动，只会弹输入框。
- **`service/PetService`**：接线 `name()`（`Settings.pet_name`）与 `emotion(dim)`（只读 `EmotionState.get`）。不改 `brain/` 逻辑。

单测 332 → 342。本地 `testDebugUnitTest` 全绿（342 条，0 失败）。

## v1.135.0 (2026-10-03)

桌宠长按快捷菜单 + 位置锁定（PR #34，Closes #27 第 2 条；原 PR #33 由 workbuddy 提交）。

### 桌宠长按快捷菜单 + 位置锁定

主体由协作者 **workbuddy** 完成（原 PR #33，5 个提交原样保留在 #34 中）。长按桌宠不再直接跳设置，弹出轻量菜单，贴合桌宠该有的交互。

- **纯逻辑 `ui/pet/PetQuickMenu`**（JVM 可测）：菜单文案（锁/未锁两种措辞）、表情循环（6 档，未知值回落「平静」）、居中坐标（窗口比屏幕宽时夹回 0，绝不产生负坐标把桌宠推出屏外）。新增 10 条单测。
- **`ui/PetOverlayView`**：接回此前被改成空实现的 `GestureDetector.onLongPress`——`PetService.onLongPress()` 因此一直是**死代码**，不接上这根线菜单弹不出来；菜单居中悬浮，菜单期间 `onTouchEvent`/`onInterceptTouchEvent` 一律让位给菜单层，避免被原有手势拦截吃掉。
- **`service/PetService`**：菜单五项分发（换表情 / 一键贴边 / 回到中间 / 锁定位置 / 打开设置）。「一键贴边」直接复用 v1.132.0 的 `OverlayDock.dock()`，不另写一份几何；「回到中间」用 `PetQuickMenu.centerX/centerY`。
- **位置锁定**：`Settings.pet_locked`（默认不锁，落盘）。**只锁拖拽，不做 `FLAG_NOT_TOUCHABLE` 真穿透**——真穿透后窗口彻底不吃触摸，用户再也长按不出菜单，等于把自己锁死在外面。锁定时抬手也不再吸附，否则「锁定」形同虚设。

### 集成收尾（trae）

workbuddy 的 #33 基于 `766240a`（v1.133.0），**不含** #32，其 `setStatusProvider` 与已发版的 v1.134.0 重复。合并会自动产生两段 `setStatusProvider`（后者覆盖前者），多出的是永不生效的死代码。由发版负责人 trae 另开分支 `feat/pet-quick-menu` 集成（**不 force push 原分支**，遵 9.4）：删掉重复接线，恢复该分支误回退的三处 v1.133.0 胶囊注释，菜单本体一行未改。

单测 322 → 332。本地 verify.sh --quick 三关通过。

## v1.134.0 (2026-10-03)

单一改动：把 v1.133.0 留下的状态胶囊半成品接上线，胶囊真正可见（PR #32，Refs #27 第 1 条）。

### 桌宠状态胶囊：接线点亮

由 trae 提交（PR #32）。v1.133.0 里 workbuddy 的 PR #29 已建好胶囊视图与 `ui/pet/PetStatusText` 纯逻辑，但 `PetOverlayView.statusProvider` 恒为 `null`——**胶囊建好却永远隐藏**，是明确的半成品（#29 正文自己写了「第二步接线」）。

- `PetService.ensureRunning()` 在 `new AICore(...)` 之后调用 `overlayView.setStatusProvider(...)`：只读地把 `EmotionState` 的**亲密度 / 精力 / 主导情绪**喂进去。
- `StatusProvider` 接口由 #29 定义（只收三个标量），`ui/` 借此不反向依赖 `brain/`；不改 `brain/` 逻辑、不动手势。
- 接线即触发 `refreshStatus()`，胶囊立即显示，无需等下一个 5s tick；取数异常只隐藏这一轮，不崩桌宠。

**范围**：只改 `service/PetService.java`（trae 领地）。长按快捷菜单属 #27 第 2 条，@owner:workbuddy。

单测沿用 #29 的 7 条 `PetStatusText`（无新增纯逻辑）。单测 322。

## v1.133.0 (2026-10-03)

本轮合并两个 PR：workbuddy 的 #29（状态胶囊**底层**）与 trae 的 #31（开机自启），由发布负责人 trae 统一 bump 发版。

### 桌宠常驻：开机自启 + 划掉任务不杀

由 trae 提交（PR #31，Closes #30）。这是桌宠「常驻」缺失的两块。

**问题**：桌宠只在用户手动「开始」后存在——**全仓没有 `BOOT_COMPLETED` 接收器**，设备重启后桌宠不会自己回来；也没有 `stopWithTask=false`，从最近任务划掉 App 会连带停掉桌宠。

**改法**：

- **纯逻辑 `util/AutoStartPolicy.shouldStart(autoStart, wasRunning, overlayGranted)`**（JVM 可测）：只有「用户开着自启 + 关机前桌宠确实在跑 + 仍有悬浮窗权限」三者同时成立才拉起，**避免没启用过的用户被莫名启动**。
- **`service/BootReceiver`**：收 `BOOT_COMPLETED`（含部分 ROM 的 `QUICKBOOT_POWERON`）拉起 `PetService` 前台服务。
- **`Settings.auto_start`**（默认开）与 **`Settings.pet_enabled`**（用户意图）；`PetService` 在启动/停止时维护 `pet_enabled`，作为唯一事实来源，覆盖 UI 启停与通知停止两条路径。
- **`AndroidManifest`**：`RECEIVE_BOOT_COMPLETED` 权限 + 注册 receiver + `PetService` 加 `android:stopWithTask="false"`。
- **`SettingsTabView`** 功能设置新增「开机自动启动（重启后桌宠自己回来）」开关。

新增 5 条单测。

### 桌宠状态胶囊（底层就位，第二步接线后可见）

由协作者 workbuddy 提交（PR #29，Refs #27 第 1 条）。`brain/EmotionState`（亲密度 / 精力 / 6 维情绪）此前只在「发现」页展示。

- **`ui/pet/PetStatusText`**（纯逻辑）：把三个标量拼成 `亲密度 62% · 精力 71% · 开心`；只收标量、不认识 `EmotionState`（避免 `ui/` 反向依赖 `brain/`），百分比夹取、`NaN` 视作 0、未知情绪回退「平静」。
- **`ui/PetOverlayView`**：底部居中状态胶囊 + 5 秒 `ticker`（`onDetachedFromWindow` 摘回调防泄漏）、取数异常不崩桌宠。
- ⚠️ **尚未接线**：`statusProvider` 目前为 `null`，胶囊保持隐藏；接线 + 长按快捷菜单是 #27 的第二步，届时胶囊才可见。本版仅先把纯逻辑与视图就位。

新增 7 条单测。单测 310 → 322。

## v1.132.0 (2026-10-03)

### 桌宠体验：松手贴边停靠（左/右边缘吸附）+ 设置开关

由 trae 提交（PR #28，Closes #26）。补齐桌宠最基础的桌面惯例。

**问题**：桌宠拖动只有「不许出左/上边界」（`PetService.onDragged` 里 `x<0/y<0` 才夹），松手停在原地、还可能停在屏幕正中挡住内容；**没有任何贴边吸附**，也没有「拖出屏外后回落」的兜底。

**改法**：

- **纯逻辑 `util/OverlayDock`**（无 Android 依赖，JVM 可测）：给定窗口坐标/尺寸与屏幕尺寸，算出吸附到最近左/右边缘后的坐标与停靠侧。规则：纵向始终夹进屏内；`enabled=false` 时只做屏幕内约束；开启时以「窗口中心 vs 屏幕中心」判左右，留 8px 白边；窗口贴满或超宽时白边退化为 0，**绝不把窗口推出屏外**。
- **`Settings.edge_dock`**（默认开）；`SettingsTabView` 功能设置新增「贴边停靠（松手吸附到屏幕边缘）」开关。
- **`PetService`** 松手（`onDrag(0,0)`）走 `snapToEdgeIfNeeded()`：吸附并把坐标持久化；顺带兜住「拖拽期间越界、松手不回落」。

新增 7 条单测。单测 303 → 310。

## v1.131.0 (2026-10-03)

### 聊天体验：宽表格被 TextView 折行破坏 —— 整表宽度受控 + 列间不折行

由协作者 workbuddy 提交（PR #25，Closes #24），发布负责人 trae 合并并统一发版。这是 5.4 第 3 条「Markdown 表格增强」的落地。

**问题**：表格是等宽对齐纯文本，窄屏上两个问题叠加——单列上限 24、3 列就是 76 字符/行，而列间用的是**普通空格**（TextView 的合法折行点）。手机上气泡只放得下 30 出头的等宽字符，于是**列一多表格就被从列间空隙拦腰折断**，对齐全乱。

**改法**：

- **`MarkdownRenderer.fitColumns`**（纯逻辑，包内可见便于单测）：把各列自然宽度**等比压缩**进整表总宽预算（`MAX_TABLE_WIDTH - 列间距×(列数-1)`），单列不低于 `MIN_COL_WIDTH`（3）；下限保护导致总宽又超预算时，从最宽的列开始逐列让，直到塞下；列多到连下限都塞不下时**放弃压缩接受溢出**，也不把某列压成 0 让内容整列消失。
- **`MarkdownRenderer.noBreakSpaces`**：整行空格转 **NBSP**，列间空隙不再折行点，宽表格保持结构。
- 保留单列上限 24 与「先剥行内标记再算列宽」（v1.125.0）。

新增 8 条单测。单测 295 → 303。

## v1.130.0 (2026-10-03)

本轮合并两个独立 PR（互不冲突的文件），由发布负责人 trae 统一 bump 发版。

### 多智能体深化：台账按 agent 聚合（控制台「各子智能体」）

v1.128.0 的协作台账只有逐条明细，看不出**哪个子智能体最不可靠 / 最慢**。由 trae 提交（PR #22，Closes #23）。

- **`SubagentLedger.AgentStat` + `byAgent()`**：按 agent 聚合 `runs / ok / fail`、平均耗时、最后一次执行时间，并给出四舍五入的成功率；
- 排序固定为「执行次数降序，同次数按名字升序」，控制台展示稳定、测试可断言；
- 空 agent（preset 名没传对）单独成一条，控制台显示成 `?`，把这类别扭的委派暴露出来；
- **`AgentConsoleActivity` 第 5 个 Tab** 在总计行下新增「各子智能体」区块，先看汇总再看逐条明细。

新增 4 条单测。

### 聊天体验：每条气泡显示自己的 HH:mm 时间戳

由协作者 workbuddy 提交（PR #21，Closes #7）——这是 5.4 第 1 条的最后一块拼图。此前只知道「相邻消息间隔 >5 分钟才有时间分隔线」，单条消息几点发的要长按才看得到。

- **`ChatTextOps.formatBubbleTime`**（纯逻辑，可单测）：只给 `HH:mm`（不分日期，日期交给分隔线）；`ts<=0`（老数据无时间戳）回退为当前时间，不抛异常；
- **`ChatActivity.wrapBubble`**：把 AI 气泡包进「气泡 + 时间戳」的纵向容器，用户气泡改为纵向 row（气泡在上、时间在下，一起靠右）；历史与实时/流式两条路径都带上时间戳；
- 包一层会让气泡不再是 `listContainer` 的直接子视图，因此同步加固了三处遍历/移除：`bubbleOf()` / `removeBubble()` 统一取气泡与移除（否则**搜索匹配不到气泡、删除会留下孤儿时间条**），`doSearch` / `gotoHit` / `regenerateLast` / `deleteBubble` / 错误气泡重试均已改走这两个方法。

新增 5 条单测。单测 290 → 295。

## v1.129.0 (2026-10-03)

### 修复：会话列表空状态是死代码，补与聊天页一致的引导

由协作者 workbuddy 提交（PR #19），发布负责人 trae 合并并统一发版。

「对话」Tab 的空态此前一次都没显示过。`ConversationTabView#refresh()` 在取列表**之前**先 `ensureSession(SESSION_CARE, …)` 把内置护理会话落了地，列表因此至少有一条，导致 `sessions.isEmpty()` **恒为 false**——原来的空态分支是死代码。实际表现：新用户装完打开「对话」Tab，只看到孤零零一条「护理大脑」，没有任何「还没有对话 / 怎么开始」的引导。

改法：

- **`ConversationTabView.hasUserSession`**（新增静态纯逻辑，可单测）：判定改为「除内置护理会话外还有没有别的会话」，`null` / 空表 / `null` 元素都算「无自建会话」；
- **空态不再 `return`**：引导卡插在列表最上方，护理会话卡片照常渲染在下方，而不是把整张列表顶掉；
- **引导卡对齐聊天页空态**（`ChatActivity#appendEmptyGuide`）：同一套 `bg_chip_outline` chip，点了直接做事——「＋ 新建会话」拉起新建弹窗、「✦ 打开护理大脑」直接进护理会话；
- 顺带修一处文案不一致：空态原写「点右上角「＋ **新建对话**」」，与按钮实际文案「＋ **新建会话**」对不上。

新增 6 条单测（`ConversationTabViewTest`）。单测 280 → 286。

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
