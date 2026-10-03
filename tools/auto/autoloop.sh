#!/bin/bash
# ============================================================
# 数字生命 · 自主流水线 (autoloop)
# 队列任务 → 应用补丁 → 提交 → bump 版本 → 推送 → 等 CI → 验证 release
# 队列空则休眠等待新任务；到达 2026-09-07 自动停止
# ============================================================
set -u
export LC_ALL=C

AUTO=/tmp/opencode/auto
QUEUE=$AUTO/queue
LOGD=$AUTO/logs
STATE=$AUTO/state
DONE=$STATE/done
FAILED=$STATE/failed
TOKEN_FILE=$AUTO/token
REPO_DIR=/workspace
REPO_API="https://api.github.com/repos/hit-droid/Digital-Life-Cure"

mkdir -p "$QUEUE" "$LOGD" "$STATE" "$DONE" "$FAILED"

TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null || echo "")
END_TS=$(date -d "2026-09-07 00:00:00" +%s 2>/dev/null || echo 0)

log() { echo "[$(date '+%F %T')] $*" >> "$LOGD/autoloop.log"; }

api() {  # api <path> [extra curl args...]
  local p="$1"; shift
  curl -s -m 20 -H "Authorization: token $TOKEN" \
       -H "Accept: application/vnd.github+json" \
       "$@" "https://api.github.com/repos/hit-droid/Digital-Life-Cure/$p"
}

# ---------- 版本读取 / 递增 ----------
read_version() {
  grep -oE 'versionName "[0-9.]+"' "$REPO_DIR/app/build.gradle" | head -1 \
    | grep -oE '[0-9.]+'
}
read_code() {
  grep -oE 'versionCode [0-9]+' "$REPO_DIR/app/build.gradle" | head -1 \
    | grep -oE '[0-9]+'
}
bump_version() {  # 次版本号 +1，修订号归零
  local cur="$1"
  local maj min
  maj=$(echo "$cur" | cut -d. -f1)
  min=$(echo "$cur" | cut -d. -f2)
  echo "$maj.$((min + 1)).0"
}

# ---------- CI 等待 ----------
wait_ci() {  # wait_ci <sha> → 输出 success/failure/timeout
  local sha="$1" runid="" st="" concl="" i=0
  if [ -z "$TOKEN" ]; then log "  ! 无 TOKEN，跳过 CI 验证"; echo "skipped"; return; fi
  # 找到匹配 sha 的 run
  for i in $(seq 1 40); do
    runid=$(api "actions/runs?branch=main&per_page=10" \
      | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    for r in d.get('workflow_runs',[]):
        if r.get('head_sha','')=='$sha' and r.get('name')=='Build APK':
            print(r['id']); break
except Exception: pass
")
    [ -n "$runid" ] && break
    sleep 10
  done
  if [ -z "$runid" ]; then log "  ! 未找到 CI run (sha=$sha)"; echo "notfound"; return; fi
  log "  CI run=$runid 等待中…"
  for i in $(seq 1 90); do
    st=$(api "actions/runs/$runid" | python3 -c "
import json,sys
try: print(json.load(sys.stdin).get('status',''))
except Exception: print('')
")
    if [ "$st" = "completed" ]; then
      concl=$(api "actions/runs/$runid" | python3 -c "
import json,sys
try: print(json.load(sys.stdin).get('conclusion',''))
except Exception: print('')
")
      log "  CI 结果: $concl"
      echo "$concl"; return
    fi
    sleep 20
  done
  echo "timeout"
}

# ---------- release 校验 ----------
check_release() {  # check_release <version>
  local v="$1"
  [ -z "$TOKEN" ] && { echo "skipped"; return; }
  api "releases/tags/v$v" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    n=len(d.get('assets',[]) or [])
    print('ok' if (d.get('tag_name') and n>0) else 'missing')
except Exception: print('missing')
"
}

# ---------- 单任务处理 ----------
process_task() {
  local patchf="$1"
  local base; base=$(basename "$patchf" .patch)
  local metaf="${patchf%.patch}.meta"
  local title="$base"
  local body="auto: $base"
  [ -f "$metaf" ] && { title=$(sed -n '1p' "$metaf"); body=$(sed -n '2,$p' "$metaf"); }

  log "===== 任务: $base ====="
  cd "$REPO_DIR" || return 1

  # 1) 同步远端（keepalive 心跳会插 commit）
  git pull --no-rebase origin main >/dev/null 2>&1

  # 2) 应用补丁
  if ! git apply --3way --whitespace=nowarn "$patchf" >/dev/null 2>&1; then
    if ! git apply -R --check "$patchf" >/dev/null 2>&1; then
      log "  ✗ 补丁应用失败（且非已应用）"
      mv "$patchf" "$FAILED/" 2>/dev/null
      [ -f "$metaf" ] && mv "$metaf" "$FAILED/" 2>/dev/null
      git checkout -- . 2>/dev/null; git reset --hard origin/main >/dev/null 2>&1
      return 1
    fi
    log "  · 补丁此前已应用，跳过内容改动"
  fi

  # 3) 提交功能改动
  local msg_file; msg_file=$(mktemp)
  # 不再追加 Co-authored-by（用户 2026-10-03 要求：不要猴码机器人署名）
  { echo "$title"; echo; echo "$body"; } > "$msg_file"
  git add -A
  if git diff --cached --quiet; then
    log "  · 无实际改动，跳过"
    rm -f "$msg_file"
    mv "$patchf" "$DONE/" 2>/dev/null; [ -f "$metaf" ] && mv "$metaf" "$DONE/" 2>/dev/null
    return 0
  fi
  GIT_COMMITTER_NAME="hit-droid" GIT_COMMITTER_EMAIL="hit-droid@users.noreply.github.com" \
    git commit -F "$msg_file" >/dev/null 2>&1
  rm -f "$msg_file"
  log "  ✓ 已提交功能改动"

  # 4) bump 版本
  local cur newv newc
  cur=$(read_version); newv=$(bump_version "$cur"); newc=$(( $(read_code) + 1 ))
  sed -i "s/versionCode .*/versionCode $newc/" "$REPO_DIR/app/build.gradle"
  sed -i "s/versionName \".*\"/versionName \"$newv\"/" "$REPO_DIR/app/build.gradle"
  git add -A
  GIT_COMMITTER_NAME="hit-droid" GIT_COMMITTER_EMAIL="hit-droid@users.noreply.github.com" \
    git commit -m "chore: bump version $cur → $newv" >/dev/null 2>&1
  log "  ✓ 版本 $cur → $newv (code $newc)"

  # 5) 推送
  local sha
  if ! GIT_COMMITTER_NAME="hit-droid" GIT_COMMITTER_EMAIL="hit-droid@users.noreply.github.com" \
      git push origin main >/dev/null 2>&1; then
    git pull --no-rebase origin main >/dev/null 2>&1
    if ! GIT_COMMITTER_NAME="hit-droid" GIT_COMMITTER_EMAIL="hit-droid@users.noreply.github.com" \
        git push origin main >/dev/null 2>&1; then
      log "  ✗ 推送失败，回滚"
      git reset --hard HEAD~2 >/dev/null 2>&1
      mv "$patchf" "$FAILED/" 2>/dev/null; [ -f "$metaf" ] && mv "$metaf" "$FAILED/" 2>/dev/null
      return 1
    fi
  fi
  sha=$(git rev-parse HEAD)
  log "  ✓ 已推送 sha=$sha"

  # 6) 等 CI
  local res; res=$(wait_ci "$sha")
  if [ "$res" != "success" ]; then
    log "  ✗ CI 未通过($res)，回滚这两个 commit"
    git reset --hard HEAD~2 >/dev/null 2>&1
    GIT_COMMITTER_NAME="hit-droid" GIT_COMMITTER_EMAIL="hit-droid@users.noreply.github.com" \
      git push --force origin main >/dev/null 2>&1
    mv "$patchf" "$FAILED/" 2>/dev/null; [ -f "$metaf" ] && mv "$metaf" "$FAILED/" 2>/dev/null
    return 1
  fi

  # 7) 校验 release
  local rel; rel=$(check_release "$newv")
  if [ "$rel" = "ok" ]; then
    log "  ✅ v$newv 已发行 (CI success + release 就位)"
  else
    log "  ⚠ CI 通过但 release 未就位($rel)"
  fi

  mv "$patchf" "$DONE/" 2>/dev/null
  [ -f "$metaf" ] && mv "$metaf" "$DONE/" 2>/dev/null
  echo "$newv|$base|$sha" >> "$STATE/releases.csv"
  return 0
}

# ---------- 主循环 ----------
log "########## autoloop 启动 (结束时间戳 $END_TS) ##########"
while true; do
  if [ "$END_TS" -gt 0 ] && [ "$(date +%s)" -ge "$END_TS" ]; then
    log "===== 已到达 2026-09-07，autoloop 正常停止 ====="
    break
  fi
  # 取队列中编号最小的一个
  nextp=$(ls "$QUEUE"/*.patch 2>/dev/null | sort | head -1)
  if [ -z "$nextp" ]; then
    sleep 30
    continue
  fi
  process_task "$nextp"
  sleep 15
done
log "########## autoloop 退出 ##########"
