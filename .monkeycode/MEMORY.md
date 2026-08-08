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
