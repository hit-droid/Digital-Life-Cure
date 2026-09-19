#!/bin/sh
# 全量自动验证：编译 → 单测 → lint → APK。
# 任一环节失败即中止并打印失败环节，成功时输出各环节耗时与测试计数。
#
# 用法：
#   ./tools/verify.sh          # 全量
#   ./tools/verify.sh --quick  # 跳过 APK 打包（省约 15 秒）
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

if [ -x /tmp/opencode/toolchain/jdk-17.0.20.1+1/bin/java ]; then
  export JAVA_HOME=/tmp/opencode/toolchain/jdk-17.0.20.1+1
else
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
export ANDROID_HOME=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "!! JDK 缺失：$JAVA_HOME"
  echo "   容器重启会清空 /tmp，重装方式见 AGENTS.md 第 2.1.1 节"
  exit 2
fi

QUICK=0
[ "$1" = "--quick" ] && QUICK=1

run_stage() {
  name="$1"
  shift
  start=$(date +%s)
  if ./gradlew "$@" --no-daemon --console=plain > "/tmp/verify-$name.log" 2>&1; then
    end=$(date +%s)
    echo "  [OK]   $name  ($((end - start))s)"
  else
    end=$(date +%s)
    echo "  [FAIL] $name  ($((end - start))s)"
    echo "  ---- 失败详情（最后 40 行，完整日志 /tmp/verify-$name.log）----"
    tail -40 "/tmp/verify-$name.log"
    exit 1
  fi
}

echo "== 自动验证开始 =="
run_stage compile :app:compileDebugJavaWithJavac
run_stage test    :app:testDebugUnitTest
run_stage lint    :app:lintDebug
if [ "$QUICK" = "0" ]; then
  run_stage apk   :app:assembleDebug
fi

# 汇总单测计数
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
total = fail = err = 0
classes = 0
for p in glob.glob('app/build/test-results/**/*.xml', recursive=True):
    r = ET.parse(p).getroot()
    total += int(r.get('tests', 0)); fail += int(r.get('failures', 0)); err += int(r.get('errors', 0))
    classes += 1
print(f"  单测: {total} 个 / {classes} 个测试类，失败 {fail}，错误 {err}")
if fail or err:
    raise SystemExit(1)
PY

# 汇总 lint error 数（warning 不阻塞）
if [ -f app/build/reports/lint-results-debug.xml ]; then
  n=$(grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml 2>/dev/null | head -1)
  [ -z "$n" ] && n=0
  echo "  lint: Error $n 个（warning 不阻塞）"
fi

if [ "$QUICK" = "0" ] && [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
  echo "  APK:  $(ls -l app/build/outputs/apk/debug/app-debug.apk | awk '{print $5}') 字节"
fi

echo "== 全部通过 =="
