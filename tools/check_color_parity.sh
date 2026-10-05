#!/usr/bin/env bash
# check_color_parity.sh —— 色彩体系护栏（workbuddy 在 issue #98 派给 trae 的活）
#
# 背景：v1.155.0 的「浅色模式一半黑一半白」根因是**两套色板长期共存且无人守门**——
#   res/values/      （系统浅色模式）：旧色 card_bg=#FFFFFF 等
#   res/values-night/（系统深色模式）：旧色 card_bg=#1C1C28 等
# 新组件用 operit_*（两套同值），旧组件用旧色（两套完全不同）→ 系统浅色时旧组件翻白，
# 与恒深色的 operit_* 混在一起。修复方案 A：把深色那套提为唯一色板、删除 values-night/。
#
# 本脚本把「第二套色板重新长出来」这件事钉死在 CI 里：
#   1) values-night/ 必须不存在（有人再加一套就红）；
#   2) 若将来确实要恢复双套色，则 values/ 与 values-night/ 的同名 color 必须逐一同值；
#   3) styles.xml 里 AppTheme 的 parent 不得带 Light（Light 壳会让默认控件走亮色分支撞色）。
#
# 用法：bash tools/check_color_parity.sh   （退出码 0 = 通过）
set -euo pipefail

# 仓库根目录（脚本位于 tools/ 下，向上一级）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="$ROOT/app/src/main/res"
VALUES="$RES/values"
NIGHT="$RES/values-night"
STYLES="$VALUES/styles.xml"

fail=0

echo "== check_color_parity =="
echo "res: $RES"

# ---------- 1) values-night/ 不得重现 ----------
if [ -d "$NIGHT" ]; then
    echo "FAIL: $NIGHT 存在——项目已收敛为单套深色色板，禁止再建 values-night/。" >&2
    echo "      若确需双套色，请连同本脚本的判据一起改（并在 PR 里说明）。" >&2
    fail=1
else
    echo "OK  : values-night/ 不存在（单套色板）"
fi

# ---------- 2) 同名 color 一致性 ----------
# 抽取 <color name="x">VALUE</color> 为 "x=VALUE"，按名排序。
extract_colors() {
    # $1 = 目录；拼接该目录下所有 colors 定义（*.xml 中凡是 <color> 都算）
    local dir="$1"
    [ -d "$dir" ] || return 0
    grep -rhoE '<color[[:space:]]+name="[^"]+"[^>]*>[^<]*</color>' "$dir" 2>/dev/null \
        | sed -E 's/.*name="([^"]+)"[^>]*>([^<]*)<.*/\1=\2/' \
        | sort
}

if [ -d "$NIGHT" ]; then
    tmp_values="$(mktemp)"
    tmp_night="$(mktemp)"
    trap 'rm -f "$tmp_values" "$tmp_night"' EXIT
    extract_colors "$VALUES" > "$tmp_values"
    extract_colors "$NIGHT"  > "$tmp_night"

    # 只比对两套都定义了的同名 color；值不同即 fail。
    mismatch=0
    while IFS='=' read -r name value; do
        [ -n "$name" ] || continue
        night_value="$(awk -F= -v n="$name" '$1==n{sub(/^[^=]*=/,"");print;exit}' "$tmp_night")"
        if [ -n "$night_value" ] && [ "$value" != "$night_value" ]; then
            echo "FAIL: color '$name' 两套值不同：values/=$value  values-night/=$night_value" >&2
            mismatch=1
        fi
    done < "$tmp_values"
    if [ "$mismatch" -eq 0 ]; then
        echo "OK  : values/ 与 values-night/ 同名 color 全部一致"
    else
        fail=1
    fi
else
    echo "SKIP: 无 values-night/，同名一致性检查跳过（单套色板下恒等）"
fi

# ---------- 3) AppTheme 的 parent 不得含 Light ----------
if [ ! -f "$STYLES" ]; then
    echo "FAIL: 找不到 $STYLES" >&2
    fail=1
else
    apptheme_line="$(grep -E '<style[^>]*name="AppTheme"' "$STYLES" | head -n 1 || true)"
    if [ -z "$apptheme_line" ]; then
        echo "FAIL: styles.xml 中没有 AppTheme 定义" >&2
        fail=1
    elif printf '%s' "$apptheme_line" | grep -q 'Light'; then
        echo "FAIL: AppTheme parent 含 Light —— 默认控件会走亮色分支撞色。" >&2
        echo "      $apptheme_line" >&2
        fail=1
    else
        echo "OK  : AppTheme parent 不含 Light"
    fi
fi

# ---------- 4) drawable 内不得写死白透明度，必须引用 glass_* token ----------
# 判据：只查「形/描边/渐变」的颜色属性 android:color / android:*Color，
#       豁免矢量图标的 android:fillColor / android:tint（图标用纯白是正常设计）。
#       v1.157.1：存量 7 处已由 trae 迁移到 glass_ripple / glass_sheen，
#       基线清零、规则转严 —— 此后任何写死的白透明度都会直接 FAIL。
# 基线：tools/color_guard_baseline.txt 保留作将来存量的缓释口，
#       当前为空（0 条），即不豁免任何东西；新增存量时应同步登记并限期清理。
DRAWABLE_DIR="$RES/drawable"
BASELINE="$ROOT/tools/color_guard_baseline.txt"

if [ -d "$DRAWABLE_DIR" ]; then
    found="$(
        grep -rEn 'android:(color|startColor|endColor|centerColor)="#([0-9A-Fa-f]{2})?[Ff]{6}"' "$DRAWABLE_DIR" 2>/dev/null \
        | grep -vE 'android:(fillColor|tint|strokeColor)' \
        || true
    )"
    if [ -n "$found" ]; then
        new_cnt=0; old_cnt=0
        while IFS= read -r line; do
            [ -z "$line" ] && continue
            sig="$(printf '%s' "$line" | sed -E 's/^[^:]+:[0-9]+://' | tr -d ' \t')"
            if [ -f "$BASELINE" ] && grep -Fqx "$sig" "$BASELINE"; then
                old_cnt=$((old_cnt+1)); echo "WARN: [基线内] $line"
            else
                new_cnt=$((new_cnt+1)); echo "FAIL: drawable 写死白透明度，请改用 glass_* token —— $line"
            fi
        done <<< "$found"
        echo "      （第4条：基线内 $old_cnt 处，新增违规 $new_cnt 处）"
        [ "$new_cnt" -gt 0 ] && fail=1
    else
        echo "OK  : drawable 内无写死的白透明度（全部引用 glass_* token）"
    fi
fi

echo "--------------------------"
if [ "$fail" -ne 0 ]; then
    echo "结果：FAIL（见上）" >&2
    exit 1
fi
echo "结果：PASS"
exit 0
