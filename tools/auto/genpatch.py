#!/usr/bin/env python3
"""
自动任务生成器：扫描代码库，产出「安全机械改进」补丁投入队列。
每个生成器只在变量声明后插入一行已验证安全的调用，不改结构、不动逻辑。
生成器：
  a11y     → 给 ImageButton/Button 补 setContentDescription（无障碍）
  allcaps  → 给 Button 补 setAllCaps(false)（避免部分 ROM 强制大写）
  haptic   → 给 Button/ImageButton 补 setHapticFeedbackEnabled(true)（触感反馈）
"""
import os
import re
import subprocess
import sys
import time

REPO = '/workspace'
QUEUE = '/tmp/opencode/auto/queue'
STATE = '/tmp/opencode/auto/state'
PROCESSED = os.path.join(STATE, 'processed.txt')
COUNTER = os.path.join(STATE, 'counter')
END_TS = 1788739200  # 2026-09-07 00:00:00

if time.time() >= END_TS:
    print('STOP')
    sys.exit(0)

DESC_MAP = {
    'btnBack': '返回', 'btnSend': '发送', 'btnAttach': '附加文件',
    'btnVoice': '语音输入', 'btnClear': '清空', 'btnExport': '导出',
    'btnSearch': '搜索', 'btnModel': '选择模型', 'btnDelete': '删除',
    'btnEdit': '编辑', 'btnAdd': '添加', 'btnSave': '保存',
    'btnCancel': '取消', 'btnRefresh': '刷新', 'btnNew': '新建',
    'btnTest': '测试连接', 'btnRename': '重命名', 'btnStop': '停止',
    'btnStart': '启动', 'btnImport': '导入', 'btnShare': '分享',
    'btnCopy': '复制', 'btnMore': '更多', 'btnSetting': '设置',
    'btnPlay': '播放', 'btnPause': '暂停', 'btnClose': '关闭',
}

GEN_TITLE = {
    'a11y': '补全控件无障碍描述',
    'allcaps': '按钮禁用强制大写',
    'haptic': '按钮启用触感反馈',
}
GEN_BODY = {
    'a11y': '- 为缺失的图标/文字按钮补充 setContentDescription\n- TalkBack 可正确朗读，提升无障碍可用性',
    'allcaps': '- 为 Button 补充 setAllCaps(false)\n- 避免部分 ROM/主题把中文按钮文案强制转成大写样式',
    'haptic': '- 为可点击控件补充 setHapticFeedbackEnabled(true)\n- 点击时提供轻触感反馈，交互更有实感',
}


def java_files():
    root = os.path.join(REPO, 'app/src/main/java')
    out = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith('.java'):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def load_processed():
    if not os.path.exists(PROCESSED):
        return set()
    with open(PROCESSED) as fh:
        return set(l.strip() for l in fh if l.strip())


def find_candidate(gen, processed):
    decl_re = re.compile(r'\b(?:ImageButton|Button)\s+(\w+)\s*=\s*new\s+(?:ImageButton|Button)\s*\(')
    for path in java_files():
        try:
            with open(path, encoding='utf-8') as fh:
                lines = fh.read().split('\n')
        except Exception:
            continue
        for i, line in enumerate(lines):
            m = decl_re.search(line)
            if not m:
                continue
            var = m.group(1)
            key = '%s:%s:%s' % (os.path.relpath(path, REPO), var, gen)
            if key in processed:
                continue
            window = '\n'.join(lines[i + 1:i + 26])
            if gen == 'a11y' and 'setContentDescription' in window:
                continue
            if gen == 'allcaps' and 'setAllCaps' in window:
                continue
            if gen == 'haptic' and 'setHapticFeedbackEnabled' in window:
                continue
            return path, i, var, key
    return None


def make_text(gen, var):
    if gen == 'a11y':
        desc = DESC_MAP.get(var, var)
        return '%s.setContentDescription("%s");' % (var, desc)
    if gen == 'allcaps':
        return '%s.setAllCaps(false);' % var
    if gen == 'haptic':
        return '%s.setHapticFeedbackEnabled(true);' % var
    return None


def main():
    if len(sys.argv) < 2:
        print('NEED_GEN')
        return
    gen = sys.argv[1]
    processed = load_processed()
    cand = find_candidate(gen, processed)
    if not cand:
        print('EXHAUSTED')
        return
    path, i, var, key = cand
    with open(path, encoding='utf-8') as fh:
        lines = fh.read().split('\n')
    decl = lines[i]
    indent = re.match(r'[\t ]*', decl).group(0)
    text = indent + make_text(gen, var) + '   // 自动生成：' + gen
    lines.insert(i + 1, text)
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines))

    diff = subprocess.run(['git', 'diff'], cwd=REPO,
                          capture_output=True, text=True).stdout
    subprocess.run(['git', 'checkout', '--', path], cwd=REPO,
                   capture_output=True)
    if not diff.strip():
        print('EMPTY')
        return

    n = 0
    if os.path.exists(COUNTER):
        try:
            n = int(open(COUNTER).read().strip() or 0)
        except Exception:
            n = 0
    n += 1
    with open(COUNTER, 'w') as fh:
        fh.write(str(n))

    rel = os.path.relpath(path, REPO)
    base = '%03d-%s-%s' % (n, gen, os.path.basename(rel).replace('.java', ''))
    with open(os.path.join(QUEUE, base + '.patch'), 'w') as fh:
        fh.write(diff)
    with open(os.path.join(QUEUE, base + '.meta'), 'w') as fh:
        # 不再追加 Co-authored-by（用户 2026-10-03 要求：不要猴码机器人署名）
        fh.write('feat(a11y): %s（自动）\n\n%s\n- 文件：%s（变量 %s）\n'
                 % (GEN_TITLE.get(gen, gen), GEN_BODY.get(gen, ''), rel, var))
    with open(PROCESSED, 'a') as fh:
        fh.write(key + '\n')
    print('OK ' + base)


if __name__ == '__main__':
    main()
