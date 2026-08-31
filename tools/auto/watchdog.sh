#!/bin/bash
# 看门狗：保证 autoloop 与 taskgen 永不下线，任何进程死掉立刻拉起
# 自身也以 setsid 脱离终端运行，直到 2026-09-07 停止
set -u
AUTO=/tmp/opencode/auto
LOGD=$AUTO/logs
END_TS=1788739200   # 2026-09-07 00:00:00

mkdir -p "$LOGD"
log() { echo "[$(date '+%F %T')] $*" >> "$LOGD/watchdog.log"; }
log "watchdog 启动 (pid $$)"

while true; do
  [ "$(date +%s)" -ge "$END_TS" ] && { log "到达 9/7，watchdog 停止"; break; }

  if ! pgrep -f "autoloop.sh" >/dev/null 2>&1; then
    cd "$AUTO" && setsid nohup "$AUTO/autoloop.sh" \
      >> "$LOGD/autoloop.out" 2>&1 < /dev/null &
    log "!! autoloop 掉线，已重新拉起"
  fi

  if ! pgrep -f "taskgen.sh" >/dev/null 2>&1; then
    cd "$AUTO" && setsid nohup "$AUTO/taskgen.sh" \
      >> "$LOGD/taskgen.out" 2>&1 < /dev/null &
    log "!! taskgen 掉线，已重新拉起"
  fi

  sleep 40
done
