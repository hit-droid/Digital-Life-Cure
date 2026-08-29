# Requirements Document

## Introduction

「数字生命」AI 桌宠通过 MemoryStore（SQLite 三层记忆：原始对话、每日摘要、事实/画像/事件）记录与用户的长期互动。当前用户无法查看或修正桌宠记住的内容，导致错误记忆无法纠正、换机记忆无法备份。本功能在设置页提供「记忆管理」，支持分类查看、单条删除、单条编辑、导出与导入备份。

## Glossary

- **长期记忆**：MemoryStore 中持久保存的 facts（含 profile/event/preference/self_check 等分类）、daily_summaries（每日对话摘要）。
- **近期对话**：raw_messages 表中的上下文窗口内容（当前上限 CONTEXT_LIMIT=20 条），属于短期上下文。
- **记忆管理页**：本功能新增的设置子页面，用于浏览与维护长期记忆。
- **系统**：数字生命 App（含 MemoryStore 与设置页 SettingsTabView）。

## Requirements

### Requirement 1: 记忆分类总览

**User Story:** 作为用户，我希望在设置中进入记忆管理页看到记忆的分类概览，以便了解桌宠记住了哪些类型的信息。

#### Acceptance Criteria

1. WHEN 用户进入「记忆管理」页，系统 SHALL 展示长期记忆的分类列表，至少包含「事实/画像/事件」「每日摘要」两类。
2. WHEN 用户选择某一分类，系统 SHALL 以时间倒序列表展示该分类下的全部条目。
3. WHEN 某分类无条目，系统 SHALL 展示空状态提示而非报错。

### Requirement 2: 单条记忆详情查看

**User Story:** 作为用户，我希望点开某条记忆看到完整内容与记录时间，以便判断其是否需要修正。

#### Acceptance Criteria

1. WHEN 用户点击列表中的某一条目，系统 SHALL 展示该条目的完整文本内容与记录时间戳。
2. WHEN 条目属于 facts 分类，系统 SHALL 同时展示其分类标签（如 profile/event/preference）与置信度。

### Requirement 3: 删除单条记忆

**User Story:** 作为用户，我希望删除桌宠记错的某条记忆，以便错误记忆不再影响后续对话。

#### Acceptance Criteria

1. WHEN 用户在某条目上触发删除操作，系统 SHALL 弹出确认对话框展示待删除内容。
2. WHEN 用户确认删除，系统 SHALL 从对应数据表移除该条目并刷新列表。
3. WHEN 删除完成后，系统 SHALL 使后续对话请求使用更新后的记忆（不保留被删条目）。
4. IF 删除失败（如数据库异常），系统 SHALL 提示失败原因并保持原列表不变。

### Requirement 4: 编辑单条记忆

**User Story:** 作为用户，我希望修正某条记忆的文字内容，以便桌宠使用正确的事实。

#### Acceptance Criteria

1. WHEN 用户在某条目上触发编辑操作，系统 SHALL 展示预填原内容的文本输入框。
2. WHEN 用户保存编辑且内容非空，系统 SHALL 用新文本更新该条目并保留其原分类与时间戳。
3. IF 用户保存空内容，系统 SHALL 拒绝保存并提示内容不能为空。

### Requirement 5: 记忆导出备份

**User Story:** 作为用户，我希望把全部长期记忆导出为文件，以便换机或重装时不丢失。

#### Acceptance Criteria

1. WHEN 用户触发导出操作，系统 SHALL 将 facts 与 daily_summaries 序列化为 JSON 文件并写入应用可访问的存储位置。
2. WHEN 导出完成，系统 SHALL 提示文件保存路径。
3. IF 存储不可写，系统 SHALL 提示导出失败原因。

### Requirement 6: 记忆导入恢复

**User Story:** 作为用户，我希望从备份文件恢复记忆，以便在新设备上延续原有记忆。

#### Acceptance Criteria

1. WHEN 用户触发导入并选择有效的备份 JSON 文件，系统 SHALL 解析并将条目合并写入对应数据表。
2. IF 文件格式无效或解析失败，系统 SHALL 提示错误且不修改现有记忆。
3. WHEN 导入包含与现有条目相同的 id，系统 SHALL 以备份内容覆盖现有条目。

## Notes

- 近期对话（raw_messages）默认仅展示，不在本版本纳入逐条编辑；若需支持删除单条对话，将在设计阶段确认。
- 所有新增删除/编辑操作需在 MemoryStore 提供对应方法（当前仅有写入与读取，缺单条 delete/update）。
