#!/bin/sh
# 本地构建环境（免 Android Studio）。用法：
#   ./tools/local-build.sh                # 编译 Java
#   ./tools/local-build.sh assembleDebug  # 出 APK
#   ./tools/local-build.sh test           # 跑单测
set -e
export JAVA_HOME=/tmp/opencode/toolchain/jdk-17.0.20.1+1
export ANDROID_HOME=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
TASK="${1:-:app:compileDebugJavaWithJavac}"
exec ./gradlew "$TASK" --no-daemon --console=plain
