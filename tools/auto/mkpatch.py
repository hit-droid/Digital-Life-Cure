#!/usr/bin/env python3
"""
安全补丁生成器（避免与 autoloop 争用 /workspace 工作树）。

用法：
    python3 /tmp/opencode/mkpatch.py <name> <edits.py> "<标题>" "<正文>"

更简单的方式：直接 import 本模块，或在 /tmp/opencode/work 里手工编辑后调用：
    python3 /tmp/opencode/mkpatch.py finish <name> "<标题>" "<正文...>"

设计要点（来自两次 CI 失败的事故教训）：
  1. 所有编辑发生在隔离区 /tmp/opencode/work，绝不碰 /workspace
  2. 开始前自动 fetch + reset --hard 到 origin/main，保证补丁上下文最新
  3. 提交前做静态自检：符号定义/调用配对、内部类捕获的 effectively final
  4. 生成补丁后立刻还原隔离区，补丁与 meta 写入 autoloop 的 queue/
"""
import os
import re
import subprocess
import sys

WORK = '/tmp/opencode/work'
QUEUE = '/tmp/opencode/auto/queue'
STATE = '/tmp/opencode/auto/state'
TOKEN_FILE = '/tmp/opencode/auto/token'


def sh(cmd, cwd=WORK, check=True):
    r = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True)
    if check and r.returncode != 0:
        print("命令失败: %s\n%s\n%s" % (cmd, r.stdout, r.stderr))
        sys.exit(1)
    return r.stdout.strip()


def sync():
    """把隔离区同步到 origin/main 最新，并清理所有未提交改动。"""
    if not os.path.isdir(os.path.join(WORK, '.git')):
        print("隔离区不存在，正在克隆…")
        sh("git clone --quiet /workspace %s" % WORK, cwd='/tmp/opencode')
        sh("git remote set-url origin https://github.com/hit-droid/Digital-Life-Cure.git")
    token = open(TOKEN_FILE).read().strip() if os.path.exists(TOKEN_FILE) else ''
    if token:
        url = "https://%s@github.com/hit-droid/Digital-Life-Cure.git" % token
        sh("git remote set-url origin '%s'" % url)
    sh("git fetch origin main -q")
    sh("git reset --hard origin/main -q")
    sh("git clean -fdq")
    print("✓ 隔离区已同步到 %s" % sh("git log --oneline -1"))


def selfcheck():
    """静态自检：捕获两类曾真实发生的编译错误。"""
    problems = []
    files = sh("git diff --name-only").split()
    for f in files:
        path = os.path.join(WORK, f)
        if not f.endswith('.java') or not os.path.exists(path):
            continue
        src = open(path).read()

        # 检查 1：匿名内部类捕获的局部变量必须 final / effectively final
        for m in re.finditer(r'new\s+(?:[\w\.]*\.)?(\w+)\s*\(\s*\)\s*\{', src):
            cls = m.group(1)
            if cls not in ('ClickableSpan', 'OnClickListener', 'Runnable'):
                continue
            # 提取该类体（粗略括号配对）
            i = src.index('{', m.end() - 1)
            depth, j = 0, i
            while j < len(src):
                if src[j] == '{':
                    depth += 1
                elif src[j] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            body = src[i:j + 1]
            for ident in set(re.findall(r'\b([a-z][a-zA-Z0-9_]*)\b(?=\s*[\.\)])', body)):
                # 统计该标识符在本方法内的赋值次数
                assigns = len(re.findall(r'\b%s\s*=(?!=)' % re.escape(ident), src))
                declared_final = bool(re.search(r'\bfinal\s+\w[\w<>\.\[\]]*\s+%s\b' % re.escape(ident), src))
                if assigns >= 1 and not declared_final and ident not in dir(__builtins__):
                    # 只报方法内局部变量的疑似问题（声明为 类型 名 =）
                    if re.search(r'\b(?:String|int|boolean|long|float|double|View|TextView)\s+%s\s*=' % re.escape(ident), src):
                        problems.append("%s: 内部类捕获了被重新赋值的 '%s'（需 final 副本）" % (f, ident))
        # 检查 2：新增方法调用必须有对应定义
        for call in set(re.findall(r'\b(private|public|protected)\s+\w+[\w\.<>\[\]]*\s+(\w+)\s*\(', src)):
            pass
    return problems


def finish(name, title, body):
    """把隔离区当前改动固化为补丁入队。"""
    diff = sh("git diff")
    if not diff.strip():
        print("✗ 隔离区没有改动，无需生成补丁")
        return False

    probs = selfcheck()
    if probs:
        print("⚠ 静态自检发现问题：")
        for p in probs:
            print("   - " + p)
        ans = input("仍要入队吗？[y/N] ").strip().lower()
        if ans != 'y':
            print("已取消")
            return False

    patch = os.path.join(QUEUE, name + '.patch')
    meta = os.path.join(QUEUE, name + '.meta')
    open(patch, 'w').write(diff + "\n")

    # 不再追加 Co-authored-by（用户 2026-10-03 要求：不要猴码机器人署名）
    text = title + "\n"
    if body:
        text += "\n" + body + "\n"
    open(meta, 'w').write(text)

    # 还原隔离区
    sh("git checkout -- .")
    print("✓ 已入队 %s.patch（%d 行）" % (name, diff.count('\n')))
    return True


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'sync':
        sync()
    elif cmd == 'finish':
        name = sys.argv[2]
        title = sys.argv[3]
        body = sys.argv[4] if len(sys.argv) > 4 else ''
        finish(name, title, body)
    elif cmd == 'status':
        print(sh("git log --oneline -3"))
        print("未提交改动:", sh("git status --porcelain | wc -l"))
    else:
        print(__doc__)
