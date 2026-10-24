#!/usr/bin/env bash
# 构建 SDK（JDK 17 工具链 → Java 8 字节码）并做兼容性校验
#
# 设计口径（2026-10 调整）：
#   - 构建工具链：JDK 17+（推荐 JDK 17；JDK 21 亦可）
#   - 字节码目标：Java 8（--release 8），运行时可跑在 JDK 8+ 业务系统
#   - 本脚本额外校验产物 class 文件 major version == 52（Java 8）
#
# 用法:
#   ./scripts/build-sdk.sh                 # 使用自动探测到的 JDK 17
#   JAVA_HOME_17=/path/to/jdk17 ./scripts/build-sdk.sh
#   RBAC_M2_REPO=/tmp/rbac-m2repo ./scripts/build-sdk.sh   # 自定义 Maven 本地仓库
set -euo pipefail
cd "$(dirname "$0")/.."

find_jdk17() {
  local cands=()
  [ -n "${JAVA_HOME_17:-}" ] && cands+=("${JAVA_HOME_17}")
  cands+=(
    "${HOME}/.sdkman/candidates/java/17.0.19-oracle-open"
    "${HOME}/.sdkman/candidates/java/17.0.20.1-oracle-open"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  )
  local c ver
  for c in "${cands[@]}"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    ver="$("$c/bin/java" -version 2>&1 | head -n 1)"
    case "$ver" in
      *'"17.'*) echo "$c"; return 0 ;;
    esac
  done
  return 1
}

if ! JDK17="$(find_jdk17)"; then
  echo "未找到 JDK 17。请安装 JDK 17（brew install openjdk@17）或设置 JAVA_HOME_17。" >&2
  exit 1
fi
echo "构建工具链：${JDK17}"
"${JDK17}/bin/java" -version 2>&1 | head -n 2
export JAVA_HOME="${JDK17}"
export PATH="${JAVA_HOME}/bin:${PATH}"

MVN_ARGS=(-B -pl telbank-rbac-starter)
if [ -n "${RBAC_M2_REPO:-}" ]; then
  MVN_ARGS+=("-Dmaven.repo.local=${RBAC_M2_REPO}")
fi

mvn "${MVN_ARGS[@]}" clean test

echo
echo "== 校验字节码版本 =="
major="$(javap -verbose -cp telbank-rbac-starter/target/classes \
  com.telbank.rbac.sdk.RbacSdkAutoConfiguration 2>/dev/null | awk '/major version/{print $3}')"
echo "class file major version = ${major}（期望 52 = Java 8）"
if [ "${major}" != "52" ]; then
  echo "ERROR: 产物不是 Java 8 字节码，已破坏 JDK 8 兼容性！" >&2
  exit 1
fi
echo "OK：JDK 17 构建、单测通过、产物为 Java 8 字节码（major=52）。"
