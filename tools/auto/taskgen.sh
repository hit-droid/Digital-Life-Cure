#!/bin/bash
# 自动任务生成器调度：维持队列有货，让 autoloop 永不空转
# 三个生成器轮转；全部耗尽则拉长休眠等待新目标
set -u
AUTO=/tmp/opencode/auto
QUEUE=$AUTO/queue
LOGD=$AUTO/logs
END_TS=1788739200   # 2026-09-07 00:00:00

mkdir -p "$LOGD"
GENS=(a11y allcaps haptic)
gi=0
exhausted_rounds=0

log() { echo "[$(date '+%F %T')] $*" >> "$LOGD/taskgen.log"; }
log "taskgen 启动"

while true; do
  [ "$(date +%s)" -ge "$END_TS" ] && { log "到达 9/7，taskgen 停止"; break; }

  pending=$(ls "$QUEUE"/*.patch 2>/dev/null | wc -l)
  if [ "$pending" -lt 3 ]; then
    g="${GENS[$gi]}"
    out=$(python3 "$AUTO/genpatch.py" "$g" 2>&1 | tail -1)
    log "gen=$g pending=$pending → $out"
    if [ "$out" = "EXHAUSTED" ]; then
      gi=$(( (gi + 1) % ${#GENS[@]} ))
      exhausted_rounds=$((exhausted_rounds + 1))
    else
      exhausted_rounds=0
    fi
    # 三个生成器都耗尽：拉长休眠，避免空转刷屏
    if [ "$exhausted_rounds" -ge 3 ]; then
      log "生成器暂时耗尽，休眠 10 分钟"
      sleep 600
      exhausted_rounds=0
    fi
  fi
  sleep 75
done
