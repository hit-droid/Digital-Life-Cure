# AGENTS.md — 数字生命（Digital-Life-Cure）项目交接文档

> 本文件写给**一个完全不了解情况的新会话**。请先完整读一遍再动手。
> 最后更新：2026-08-31（版本 v1.55.0，已发行 17 个版本）

---

## 0. 三十秒速览

这是一个 Android 智能体 App「数字生命」，AI 角色叫**小汐**。
2026-08-31 我搭了一套**无人值守自治流水线**：三个后台进程自动产出版本、跑 CI、发行 GitHub Release，一直跑到 **2026-09-07** 自动停止。

**如果你接手时流水线还在跑**：别动手改 `/workspace`，先看第 6 节确认进程状态。
**如果流水线已经死了**：第 7 节有完整的重建步骤（`tools/auto/` 里已经存了全部脚本）。

---

## 1. 项目目标与完成程度

### 1.1 目标
把「数字生命」App 持续完善为智能体产品。2026-08-31 起进入无人值守自主开发模式：自建流水线持续产出版本并发行，用户要求**一直运行到 2026-09-07**。

### 1.2 技术约束（硬约束，别违反）
| 约束 | 说明 |
|---|---|
| 无 AndroidX / 无 Compose | 纯 Java + View 系统 |
| **无 XML 布局** | **整个 UI 全部用 Java 代码构建**（`res/` 下只有 drawable/values/xml，没有 layout/）。改界面必须改 Java |
| 包名 | `com.digitallife` |
| minSdk / targetSdk | 21 / 34，compileSdk 34，JDK 17 |
| commit 格式 | 中文一行式 + `Co-authored-by: monkeycode-ai <monkeycode-ai@chaitin.com>` |
| push 身份 | committer 必须设为 `hit-droid@users.noreply.github.com` |

### 1.3 完成程度

- **已完成**：v1.0 → v1.55.0，其中 v1.37.0 ~ v1.55.0 是本轮自治流水线产出的（17 个已发行版本）
- **自动化本轮手工功能**（我亲自写的，非机械生成）：
  | 版本 | 功能 | commit |
  |---|---|---|
  | v1.44.0 | 顶栏溢出菜单（搜索/导出/清空收进「⋯」） | `701849a` |
  | v1.46.0 | 代码块点击复制 | `6f2dc36` |
  | v1.50.0 | 新会话引导卡片（「试试这样问我」+ 3 枚快捷入口） | `a7c327d` |
  | v1.51.0 | Markdown 表格渲染 | `e04507d` |
- **待发行**：v1.47 多行输入（补丁 `051-multiline-input.patch` 已在队列，还没轮到）
- **流水线寿命**：2026-09-07 00:00（时间戳 `1788739200`）自动停止

---

## 2. 目录结构与关键文件

### 2.1 仓库结构
```
/workspace                          ← 主工作区（autoloop 独占，见第 4 节警告）
├── app/
│   ├── build.gradle                ← 版本发版入口：versionCode / versionName
│   └── src/main/
│       ├── java/com/digitallife/   ← 全部源码（77 个 java 文件）
│       ├── res/
│       │   ├── drawable/           ← 只有 drawable，没有 layout！
│       │   ├── values/ colors.xml  ← 配色定义
│       │   └── xml/
│       └── AndroidManifest.xml
├── .github/workflows/build.yml     ← CI：push main 自动编译 + 自动发 Release
├── tools/auto/                     ← 【本轮新增】流水线脚本备份（重装用）
│   ├── autoloop.sh                 ← 流水线主脚本
│   ├── taskgen.sh                  ← 任务生成调度
│   ├── watchdog.sh                 ← 看门狗自愈
│   ├── genpatch.py                 ← 机械改进补丁生成器
│   └── mkpatch.py                  ← 安全补丁生成助手（隔离区工作流）
└── AGENTS.md                       ← 本文件
```

### 2.2 最关键的几个源文件

| 文件 | 作用 | 行数 |
|---|---|---|
| `ui/ChatActivity.java` | **主体**，聊天界面全部逻辑（气泡、折叠、搜索、语音、建议、表格…） | ~2400 |
| `ui/MarkdownRenderer.java` | Markdown → Spannable 渲染（标题/粗斜体/代码/列表/**表格**） | ~350 |
| `ui/UiKit.java` | UI 工具（按压动效等） | |
| `util/ChatStore.java` | 消息持久化（SQLite） | |
| `brain/LLMClient.java` | LLM 调用，密钥池 | |
| `brain/AICore.java` | 事实摘要压缩 | |
| `brain/PlanExecutor.java` | 计划执行 + 自纠错 | |
| `care/CareAI.java` | 护理大脑（医疗模式） | |

**ChatActivity 里的关键常量**（改之前先看这里，别重复定义）：
```
COLLAPSE_MAX_LINES    = 10     长消息折叠阈值
TOOL_COLLAPSE_CHARS   = 300    工具结果折叠阈值
TOOL_BRIEF_CHARS      = 150
CTX_BUDGET_CHARS      = 6000   上下文字符预算
CTX_KEEP_RECENT       = 6
SUGGESTION_COOLDOWN_MS= 30s
SUGGESTION_DEBOUNCE_MS= 1s
REQ_AUDIO_PERMISSION  = 4001
INPUT_MAX_LINES       = 5      输入框最大行数（v1.47）
```

**关键字段**：`listContainer`（气泡容器）、`etInput`（输入框）、`curAssistantBubble`、`curToolBubble`、`speechRecognizer`、`tvTitleRef`、`chipScrollRef`、`suggestionBar`、`lastUserText` / `lastAttachContext`（失败重试用）

### 2.3 气泡体系（改配色前必须分清，否则会改错）
| 类型 | 方法 | 背景 drawable |
|---|---|---|
| AI 对话气泡 | `newTextViewBubble()` | `bg_bubble_ai`（isCare 时切 `bg_bubble_care`） |
| 用户气泡 | `appendUserBubble()` | `bg_bubble_user` |
| 工具调用气泡 | `appendToolBubble()` | `bg_tool`（**独立体系，用户明确要求配色不可动**） |
| 错误气泡 | `appendErrorBubble()` | 复用 AI 气泡 + 红色原因 + accent 色重试提示 |

---

## 3. 技术决策与原因

### 3.1 自治流水线三件套
| 脚本 | 职责 |
|---|---|
| `autoloop.sh` | 消费队列：应用补丁 → 提交 → bump 版本 → 推送 → 按 head_sha 轮询 CI → 校验 release 资产 → 成功归档 `state/done`，失败 `reset --hard HEAD~2` + force push 回滚 |
| `taskgen.sh` + `genpatch.py` | 生产任务：扫描 Java 找 `ImageButton/Button` 声明，在其后插入一行安全调用；维持队列 ≥3 个待办 |
| `watchdog.sh` | 每 40s 巡检，任一进程掉线立即 `setsid` 拉起（**已实测：pkill 后 40s 内自动复活**） |

**为什么这么设计**：
- **补丁队列**而非直接写代码：让「改代码」和「发版」解耦，脚本可以稳定重放，失败可回滚
- **taskgen 只做机械改进**（不改结构不动逻辑）：保证 CI 高通过率，17 版全部一次通过
- **失败自愈 + force push 回滚**：保证流水线永不中断，坏版本不会留在 main 上

### 3.2 `setsid` 是必须的
`nohup ... &` 启动的进程会随终端/会话退出被杀。**必须** `setsid nohup ... < /dev/null &` 才能真正常驻。watchdog 自身也要这样启动。

### 3.3 隔离工作区（血泪换来的，见第 4 节）
所有手工编辑必须在 `/tmp/opencode/work`（独立克隆）里做，**绝不能**在 `/workspace` 里改。

### 3.4 记忆文档单独推送
`.monkeycode/MEMORY.md` 用 `[skip ci]` 单独推送，不触发 CI、不发版（已验证有效）。

---

## 4. 踩过的坑（最重要的一节，别重走）

### 4.1 ⚠️ 工作树争用事故（代价：两次 CI 失败 + 一个坏版本）
**症状**：v1.43.0 编译失败，报 `cannot find symbol`。

**根因**：我和 autoloop 共用 `/workspace` 工作树。
- autoloop 回滚时执行 `git reset --hard`，**抹掉了我未完成的编辑**
- 反过来，我未完成的编辑被 autoloop 的 `git add -A` **一起提交**，导致只有 `appendEmptyGuide()` 调用、没有方法定义的半成品混进了发行版本

**解法（必须遵守）**：
```bash
# 1. 建立隔离工作区（只需一次）
git clone /workspace /tmp/opencode/work
cd /tmp/opencode/work && git remote set-url origin https://github.com/hit-droid/Digital-Life-Cure.git

# 2. 每次开工前同步到最新
python3 /tmp/opencode/mkpatch.py sync

# 3. 在隔离区改代码（随便改，不会污染流水线）

# 4. 生成补丁入队，隔离区自动还原
python3 /tmp/opencode/mkpatch.py finish <名字> "<标题>" "<正文>"
```

### 4.2 ⚠️ `block` 非 effectively final（代价：一次 CI 失败）
`MarkdownRenderer.appendCodeBlock()` 里 `block` 被重新赋值过（去掉尾部换行），不是 effectively final，两个匿名 `ClickableSpan` 捕获它直接编译失败：
```
error: local variables referenced from an inner class must be final or effectively final
```
**解法**：取一份 `final String codeToCopy = block;` 再捕获，并抽出静态 `copyCodeToClipboard()` 供两处共用。
**通用规则**：匿名内部类/lambda 捕获的局部变量，若被重复赋值，必须先提 final 副本。

### 4.3 ⚠️ 绝不在 `/workspace` 手动 `git pull`
autoloop 每轮开头自己会 `git pull --no-rebase`（脚本 119 行），push 失败时还会再 pull 重试（164 行），**具备自愈能力**。我手动 pull 反而制造出 merge conflict 状态。
**解法**：记忆类改动一律到隔离区提交，用 `git pull --rebase` 再 push。

**这个坑的真实代价**（2026-08-31 亲历）：我在 `/workspace` 手贱 pull 了一次，仓库进入 `.git/MERGE_HEAD` 冲突态。紧接着 autoloop 开始下一个任务，它开头的 `git pull` 在这个状态下直接失败 → `git apply` 也失败 → 任务被误判成补丁问题，一路丢进 `failed/`。我精心写好并验证过的 `051-multiline-input.patch` 就这么被误杀，只能重新入队（`054-multiline-input.patch`）。

**如果已经弄出了冲突态**，立刻修复（autoloop 不会自己修复，只会持续误杀队列任务）：
```bash
cd /workspace
git merge --abort 2>/dev/null           # 先尝试中止合并
git fetch origin
git reset --hard origin/main            # 强行复位到远端
git status --porcelain | wc -l          # 必须是 0
```
修完记得去 `state/failed/` 看看有没有被误杀的补丁，捞出来重新入队（先 `git apply --check` 复核一遍）。

### 4.4 取 CI 日志必须带 token
`gh` 默认报 `gh auth login`。正确姿势：
```bash
GH_TOKEN=$(cat /tmp/opencode/auto/token) gh run view <run_id> --log-failed
```

### 4.5 补丁应用失败 ≠ 漂移
先确认功能是否已随更早的 commit 落地：
```bash
git show HEAD:<文件> | grep <关键符号>
```
（v1.42 重试功能其实已完整落地，042 补丁是重复应用才失败的，白折腾一轮。）

### 4.6 与自动生成器的冲突要提前规避
`genpatch.py` 会扫描所有 `Button`/`ImageButton` 声明。如果我删掉了某个按钮变量，生成器后续仍会选中它 → 补丁失效 → 流水线回滚。
**解法**：把即将删除的变量按 `文件:变量:生成器` 格式预先写进 `state/processed.txt`。
```bash
# 已做的预标记示例
app/src/main/java/com/digitallife/ui/ChatActivity.java:btnClear:a11y
```
**注意**：如果以后要恢复那三个顶栏按钮，记得把 processed.txt 里这 9 行删掉，否则它们永远不会再被 a11y 生成器处理。

### 4.7 已发行的补丁要从队列清掉
autoloop 靠 `--3way` 应用，已落地的补丁会再次入队重试。虽然有「已应用则跳过」判定，但仍应主动 `rm` 掉 queue 里对应的 `.patch`/`.meta`。

### 4.8 ⚠️ mkpatch.py 的 `sh().strip()` 会损坏补丁（已修复，2026-09-05）
`sh()` 原来对所有输出 `strip()`，`finish()` 里的 `git diff` 尾部若含纯空格上下文行（`' '`）会被剥掉 → 补丁报 `corrupt patch`（077/078/082/083 四个补丁曾被误杀进 failed/）。
**已修复**：`sh()` 增加 `strip` 参数，`finish()` 取 diff 用 `strip=False`。修好后把 failed/ 里能 `git apply --check` 通过的补丁直接捞回 queue，损坏的（如 083）在隔离区重写后重新入队。
**教训**：生成补丁前永远 `git apply --check` 验证；`mkpatch.py finish` 之后养成再 check 一次的习惯。

### 4.9 其他小坑
- **zsh 会截断 commit message**：`\`` + `#` 组合会被截断，复杂 message 用 `git commit -F /tmp/xxx.txt`
- **Edit 工具陷阱**：`oldString` 不能包含与保留代码完全相同的子串；`case MotionEvent.CANCEL` 不会被自动补 `ACTION_` 前缀
- **模型不支持读图**：read 图片返回成功但模型侧报 `this model does not support image input`；`image_analysis` MCP 报 `insufficient balance`。截图问题只能请用户文字描述
- **气泡最大宽度**：`maxBubbleWidth` = 屏幕宽 82%

---

## 5. 还没做完的事 / 下一步

### 5.1 立即可做
- 051 多行输入已发行（v1.59.0）；077 朗读 / 102 中断角标 / 103 模型组降级 / 104 web_fetch 已于 2026-09-05 全部入队发行（v1.102~v1.105）
- failed/ 里若再有补丁，先 `git apply --check`，损坏的按 4.8 流程重写

### 5.2 流水线自身的改进方向
- 生成器现在只做 3 种机械改进（a11y / allcaps / haptic），**会耗尽**。耗尽后 taskgen 每 10 分钟才轮询一次。可以考虑增加新的安全生成器（如 `setImportantForAutofill`、给 EditText 补 `setSingleLine` 提示等）
- autoloop 目前对每个任务都等完整 CI（3~4 分钟），是吞吐瓶颈

### 5.3 OpenMinis 对标（2026-09-05 用户指定参考对象）
用户要求参考 GitHub 项目 **OpenMinis**（OpenMinis/OpenMinis，本地优先的设备端 AI Agent，iSH/PRoot 沙箱 + Skills + MCP + 模型组）。已落地的对标项：
| 版本 | 对标能力 | 说明 |
|---|---|---|
| v1.104.0 | **模型组自动降级** | 同 scope 多配置构成降级链，网络错/401/403/408/409/429/5xx 自动切备用配置重发（ChatActivity.tryModelFailover） |
| v1.105.0 | **web_fetch 网页抓取** | BuiltinTools 新工具，URL → 纯文本正文（去标签/折叠空白/截断） |

后续可继续对标的（按可行性排序）：
1. **定时任务调度器**：OpenMinis 支持一次性/周期任务；我们已有 Heartbeat/ProactiveEngine，可加用户可配置的 `schedule_task` 工具 + AlarmManager 唤醒
2. **SKILL.md 技能包**：PluginManager 已有插件机制，可兼容加载声明式技能文件夹
3. **备份恢复**：OpenMinis 有 .minisbak 加密导出；我们的 ChatStore/MemoryStore/Settings 可打包导出
4. **真实 web_search**：当前 web_search 还是返回 Bing URL 的桩，可接搜索 API

### 5.4 App 功能方向（其他候选，按推荐度排序）
1. **消息时间戳**：气泡上没有时间显示，长按才知道
2. **引用回复**：消息菜单现在只有「复制/重新生成/删除」
3. **Markdown 表格增强**：当前是等宽文本对齐，可考虑真表格布局
4. **会话列表空状态**：与聊天页空态一致的引导
5. 表格单元格内的行内 Markdown（**加粗**等）目前不解析，只显示原始文本

### 5.5 到期后
2026-09-07 三个脚本会自动退出。届时向用户汇报：完整发行记录见 `state/releases.csv`（格式 `版本|任务名|sha`）。

---

## 6. 怎么确认流水线还活着

```bash
# 进程数应该 ≥3（autoloop / taskgen / watchdog，每个可能有子进程）
pgrep -f "autoloop.sh|taskgen.sh|watchdog.sh" | wc -l

# 看最新日志
tail -20 /tmp/opencode/auto/logs/autoloop.log

# 看发行了多少版
wc -l < /tmp/opencode/auto/state/releases.csv

# 看队列
ls /tmp/opencode/auto/queue/*.patch
```

日志目录：`/tmp/opencode/auto/logs/`（autoloop.log / taskgen.log / watchdog.log）
状态目录：`/tmp/opencode/auto/state/`（releases.csv / done/ / failed/ / processed.txt / counter）

**预期节奏**：约 3~4 分钟一版（含 CI 等待）。看到 `✅ vX.Y.Z 已发行` 就是正常。

---

## 7. 装了什么、在哪、怎么重装

### 7.1 我下载/安装的东西
**没有通过包管理器安装任何东西。** 只有：

| 东西 | 位置 | 说明 |
|---|---|---|
| 流水线脚本 | `/tmp/opencode/auto/` | **在 /tmp，机器重启就没了** |
| 脚本备份 | **`/workspace/tools/auto/`** | 【本轮新加】已提交进仓库，重装用这份 |
| 隔离工作区 | `/tmp/opencode/work/` | 独立 git 克隆 |
| GitHub Token | `/tmp/opencode/auto/token` | chmod 600，**PAT（repo 权限）** |
| 项目记忆 | `/workspace/.monkeycode/MEMORY.md` | 已提交 |

> ⚠️ **Token 安全提示**：token 明文未写入仓库（避免泄露到公开仓库被 GitHub 扫描吊销）。如果 token 掉了/过期了，需要用户重新给一个 PAT（需 `repo` 权限），写进 `/tmp/opencode/auto/token` 并 `chmod 600`。

### 7.2 完整重装步骤

```bash
# ── 1. 建目录、放 token ─────────────────────────────
mkdir -p /tmp/opencode/auto/{queue,logs,state/done,state/failed}
echo '你的GitHub PAT（repo权限）' > /tmp/opencode/auto/token
chmod 600 /tmp/opencode/auto/token

# ── 2. 从仓库恢复脚本（不用重新写！）────────────────
cd /workspace && git pull
cp tools/auto/*.sh tools/auto/*.py /tmp/opencode/auto/
cp tools/auto/mkpatch.py /tmp/opencode/
chmod +x /tmp/opencode/auto/*.sh /tmp/opencode/auto/*.py

# ── 3. 建隔离工作区 ────────────────────────────────
rm -rf /tmp/opencode/work
git clone /workspace /tmp/opencode/work
cd /tmp/opencode/work && git remote set-url origin https://github.com/hit-droid/Digital-Life-Cure.git

# ── 4. 拉起三个守护进程（setsid 是关键！）──────────
cd /tmp/opencode/auto
setsid nohup ./autoloop.sh >> logs/autoloop.out 2>&1 < /dev/null &
setsid nohup ./taskgen.sh >> logs/taskgen.out 2>&1 < /dev/null &
setsid nohup ./watchdog.sh >> logs/watchdog.out 2>&1 < /dev/null &

# ── 5. 验证 ───────────────────────────────────────
sleep 5
pgrep -f "autoloop.sh|taskgen.sh|watchdog.sh" | wc -l   # 应该 ≥3
tail -5 /tmp/opencode/auto/logs/autoloop.log
```

**注意**：`REPO=/workspace` 是硬编码在脚本里的。如果仓库路径变了，需要改 `genpatch.py` 第 16 行、`autoloop.sh` 第 17 行、`mkpatch.py` 的 `WORK` 常量。

**停止时间**：三个脚本都以 `END_TS=1788739200`（2026-09-07 00:00:00）为终止条件，到点自动退出。要改就改各脚本里的 `END_TS`。

### 7.3 手工加一个功能的标准流程
```bash
python3 /tmp/opencode/mkpatch.py sync                    # 1. 同步
# 2. 在 /tmp/opencode/work 里改代码（用 Edit 工具）
python3 /tmp/opencode/mkpatch.py finish 052-my-feature \
  "feat(chat): 我的功能（v1.56.0）" \
  "- 改动点一
- 改动点二"                                              # 3. 自检 + 入队 + 还原
# 4. 等几分钟，看 autoloop.log 出现 ✅ 就成功了
```

`mkpatch.py finish` 会自动做：静态自检（内部类捕获 effectively final）、生成 patch/meta、还原隔离区。
`meta` 文件第 1 行是 commit 标题，其余是正文，会自动追加 `Co-authored-by`。

---

## 8. 关键速查

| 想做什么 | 命令 |
|---|---|
| 看流水线状态 | `tail -20 /tmp/opencode/auto/logs/autoloop.log` |
| 看已发行版本 | `cat /tmp/opencode/auto/state/releases.csv` |
| 看队列 | `ls /tmp/opencode/auto/queue/*.patch` |
| 看失败任务 | `ls /tmp/opencode/auto/state/failed/` |
| 取 CI 错误日志 | `GH_TOKEN=$(cat /tmp/opencode/auto/token) gh run view <run_id> --log-failed` |
| 当前版本 | `grep versionName /workspace/app/build.gradle` |
| 手工加功能 | 见 7.3 |
| 重建流水线 | 见 7.2 |

**仓库**：https://github.com/hit-droid/Digital-Life-Cure
**分支**：main（push main 会自动编译 + 发 Release，tag = `v{versionName}`）
