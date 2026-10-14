#!/usr/bin/env bash
# LotusWallet Android 构建包装脚本
#
# 为什么需要它：
#   本机 C 盘（系统盘）剩余空间很小，而 Gradle / Kotlin / KSP 默认会把大量中间产物
#   和临时文件写到 C:\Users\<user>\AppData\Local\Temp 与 %USERPROFILE%\.gradle，
#   一旦写满会导致「页面文件太小」→ JVM 启动失败 / 构建中断。
#   因此这里把所有可配置的目录统一重定向到 D 盘。
#
# 用法：  ./tools/build.sh :app:assembleRelease
#         ./tools/build.sh :app:assembleDebug :app:lintDebug
set -euo pipefail

# --- 工具链（已从 C 盘迁至 D 盘）---
export JAVA_HOME="D:/android-toolchain/jdk-17.0.20.1+1"
export ANDROID_HOME="D:/android-toolchain/sdk"
export ANDROID_SDK_ROOT="D:/android-toolchain/sdk"

# --- Gradle 用户目录（缓存，可再生；必须在 D 盘）---
export GRADLE_USER_HOME="D:/gradle-home"

# --- 各级临时目录 ---
mkdir -p /d/tmp /d/tmp/build-tmp
export TMPDIR="D:/tmp"
export TMP="D:/tmp"
export TEMP="D:/tmp"
export _JAVA_OPTIONS="-Djava.io.tmpdir=D:/tmp"

cd "$(dirname "$0")/.."
exec ./gradlew "$@" --console=plain
