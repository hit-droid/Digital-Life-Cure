# Changelog

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
