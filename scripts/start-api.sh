#!/usr/bin/env bash
# 启动权限中心后端（demo profile：HS256 演示 JWT + Redis 可缺席）
#
# 前置条件: JDK 21（JAVA_HOME 指向 21 或 /usr/libexec/java_home -v 21 可用）
#           MySQL 已初始化（./scripts/init-db.sh）
#
# 用法: ./scripts/start-api.sh [额外 spring-boot 参数...]
# 环境变量: RBAC_DB_USER / RBAC_DB_PASSWORD / RBAC_JWT_HS256_SECRET ...
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME_21:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ] && "${JAVA_HOME}/bin/java" -version 2>&1 | grep -Eq '"(21|2[2-9]|3[0-9])'; then
    JAVA_HOME_21="${JAVA_HOME}"
  elif [ -x "${HOME}/.sdkman/candidates/java/current/bin/java" ] \
       && "${HOME}/.sdkman/candidates/java/current/bin/java" -version 2>&1 | grep -Eq '"(21|2[2-9]|3[0-9])'; then
    # SDKMAN 当前指向 JDK 21+（用户环境）
    JAVA_HOME_21="${HOME}/.sdkman/candidates/java/current"
  elif [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    # Homebrew keg-only JDK 21（不受 /usr/libexec/java_home 管理）
    JAVA_HOME_21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  elif [ -x /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    JAVA_HOME_21=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME_21="$(/usr/libexec/java_home -v 21)"
  elif command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq '"(21|2[2-9]|3[0-9])'; then
    JAVA_HOME_21="$(dirname "$(dirname "$(command -v java)")")"
  fi
fi

if [ -z "${JAVA_HOME_21:-}" ]; then
  echo "未找到 JDK 21+。请先安装 JDK 21（或设置 JAVA_HOME_21 指向 JDK 21+）。" >&2
  exit 1
fi

echo "使用 JAVA_HOME=${JAVA_HOME_21}"
export JAVA_HOME="${JAVA_HOME_21}"
export PATH="${JAVA_HOME}/bin:${PATH}"

exec mvn -pl telbank-rbac-api -am spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8" \
  "$@"
