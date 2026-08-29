# Changelog

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
