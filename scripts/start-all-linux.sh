#!/usr/bin/env bash
# 同时启动权限中心后端（demo profile）与管理前端（开发模式）—— Linux 版
#
# 端口检测优先 lsof，其次 ss，最后 fuser（不同发行版安装情况不同）。
# Windows 请使用 scripts/start-all-windows.ps1；本脚本为 Linux 自包含版本。
#
# 特性：
#   - --temp-db：自动启动一次性临时 MySQL（默认 3307）并创建 telbank_rbac 库，
#                由 scripts/init-db.sh 导入表结构/示例数据；脚本退出时自动停止并清理临时库
#   - --init-db：针对已有 MySQL 先执行 scripts/init-db.sh（需 MYSQL_* 环境变量）
#   - 后端就绪后再启动前端（后端 JWT 接口返回 401/200 视为就绪）
#   - 日志输出到 logs/api.log、logs/front.log、logs/temp-mysql.log
#   - Ctrl+C / kill 时自动停止前后端（及本脚本启动的临时库）并释放端口
#
# 前置：bash、JDK 21+、Maven、Node 22+（nvm 或 PATH）、可选 local MySQL（--temp-db 需 mysqld 可执行）
#
# 用法：
#   ./scripts/start-all-linux.sh --temp-db            # 推荐
#   ./scripts/start-all-linux.sh --temp-db
#   API_PORT=18080 FRONT_PORT=5174 ./scripts/start-all-linux.sh --temp-db
#   ./scripts/start-all-linux.sh                     # 使用已就绪的 MySQL
#   ./scripts/start-all-linux.sh --init-db           # 先建库（需 MYSQL_* 环境变量）
#
# 环境变量：
#   API_PORT        后端端口，默认 8080
#   FRONT_PORT      前端端口，默认 5173
#   TEMP_MYSQL_PORT 临时 MySQL 端口，默认 3307（仅 --temp-db）
#   VITE_API_BASE_URL 前端请求的后端地址，默认 http://localhost:API_PORT
#   RBAC_*          透传给后端（数据库/JWT/AppSecret 等，见 README §7）

set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

API_PORT="${API_PORT:-8080}"
FRONT_PORT="${FRONT_PORT:-5173}"
TEMP_MYSQL_PORT="${TEMP_MYSQL_PORT:-3307}"
API_LOG="$ROOT/logs/api.log"
FRONT_LOG="$ROOT/logs/front.log"
INIT_DB=0
TEMP_DB=0
TEMP_DB_STARTED=0
TEMP_DB_DATADIR="${RBAC_TEMP_MYSQL_DATADIR:-/tmp/telbank-rbac-mysql/data}"
TEMP_DB_DIR="$(dirname "$TEMP_DB_DATADIR")"
RUNNING=0

usage() {
  cat <<'USAGE'
用法：./scripts/start-all-linux.sh [--temp-db] [--init-db]

  --temp-db   自动启动一次性临时 MySQL（默认 3307）并创建 telbank_rbac，退出时清理
  --init-db   针对已有 MySQL 先执行 scripts/init-db.sh（需 MYSQL_* 环境变量）

环境变量：
  API_PORT=8080  FRONT_PORT=5173  TEMP_MYSQL_PORT=3307  VITE_API_BASE_URL=...
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --temp-db) TEMP_DB=1 ;;
    --init-db) INIT_DB=1 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "未知参数：$1（可用：--temp-db、--init-db）" >&2
      exit 1
      ;;
  esac
  shift
done

mkdir -p "$ROOT/logs"

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$"
    return $?
  fi
  if command -v fuser >/dev/null 2>&1; then
    fuser -n tcp "$port" >/dev/null 2>&1
    return $?
  fi
  return 1
}

kill_port() {
  local port="$1" force="$2" pid
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null \
      | tail -n +2 | awk '{print $2}' | sort -u \
      | while read -r pid; do
          [ -n "$pid" ] || continue
          if [ "$force" = "1" ]; then kill -9 "$pid" 2>/dev/null || true; else kill "$pid" 2>/dev/null || true; fi
        done
    return
  fi
  if command -v fuser >/dev/null 2>&1; then
    if [ "$force" = "1" ]; then fuser -k -9 -n tcp "$port" >/dev/null 2>&1 || true
    else fuser -k -n tcp "$port" >/dev/null 2>&1 || true; fi
    return
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltnp 2>/dev/null | awk -v p=":${port}" '$4 ~ p {print $6}' \
      | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u \
      | while read -r pid; do
          [ -n "$pid" ] || continue
          if [ "$force" = "1" ]; then kill -9 "$pid" 2>/dev/null || true; else kill "$pid" 2>/dev/null || true; fi
        done
  fi
}

# ---------- JDK 21 探测 ----------
find_jdk21() {
  local cands=() c ver
  [ -n "${JAVA_HOME_21:-}" ] && cands+=("${JAVA_HOME_21}")
  cands+=(
    "${HOME}/.sdkman/candidates/java/current"
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  )
  if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    cands+=("$(/usr/libexec/java_home -v 21)")
  fi
  for c in "${cands[@]}"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    ver="$("$c/bin/java" -version 2>&1 | head -n 1)"
    case "$ver" in
      *'"21.'*|*'"22.'*|*'"23.'*|*'"24.'*|*'"25.'*|*'"26.'*|*'"27.'*) echo "$c"; return 0 ;;
    esac
  done
  return 1
}

# ---------- 临时 MySQL（脚本内聚，无额外 helper） ----------
TEMP_MYSQL_DIR="$TEMP_DB_DIR"
TEMP_MYSQL_SOCKET="$TEMP_MYSQL_DIR/mysql.sock"
TEMP_MYSQL_PIDFILE="$TEMP_MYSQL_DIR/mysqld.pid"
TEMP_MYSQL_LOGFILE="$TEMP_MYSQL_DIR/mysqld.log"

find_mysqld() {
  if [ -n "${MYSQLD:-}" ]; then echo "$MYSQLD"; return 0; fi
  local c
  for c in /opt/homebrew/opt/mysql/bin/mysqld /usr/local/opt/mysql/bin/mysqld "$(command -v mysqld || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

temp_mysql_status() {
  if [ -f "$TEMP_MYSQL_PIDFILE" ] && kill -0 "$(cat "$TEMP_MYSQL_PIDFILE")" 2>/dev/null; then
    echo "running pid=$(cat "$TEMP_MYSQL_PIDFILE") port=${TEMP_MYSQL_PORT}"
  else
    echo "not running"
  fi
}

temp_mysql_start() {
  local mysqld client
  mysqld="$(find_mysqld)" || { echo "未找到 mysqld，请安装 MySQL 或设置 MYSQLD 环境变量" >&2; return 1; }
  mkdir -p "$TEMP_MYSQL_DIR"
  if [ ! -d "$TEMP_DB_DATADIR/mysql" ]; then
    "$mysqld" --initialize-insecure --datadir="$TEMP_DB_DATADIR" >/dev/null 2>&1 || return 1
  fi
  if [ -f "$TEMP_MYSQL_PIDFILE" ] && kill -0 "$(cat "$TEMP_MYSQL_PIDFILE")" 2>/dev/null; then
    echo "临时 MySQL 已在运行"
  else
    "$mysqld" --datadir="$TEMP_DB_DATADIR" --port="$TEMP_MYSQL_PORT" --socket="$TEMP_MYSQL_SOCKET" \
      --pid-file="$TEMP_MYSQL_PIDFILE" --log-error="$TEMP_MYSQL_LOGFILE" --skip-networking=0 \
      --bind-address=127.0.0.1 --mysqlx=0 >/dev/null 2>&1 &
    local i
    for i in $(seq 1 30); do [ -S "$TEMP_MYSQL_SOCKET" ] && break; sleep 1; done
  fi
  client="${MYSQL_BIN:-/opt/homebrew/opt/mysql/bin/mysql}"
  [ -x "$client" ] || client="$(command -v mysql || true)"
  if [ -n "$client" ]; then
    "$client" --socket="$TEMP_MYSQL_SOCKET" -uroot -e \
      "CREATE DATABASE IF NOT EXISTS telbank_rbac DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>/dev/null || true
  fi
}

temp_mysql_stop() {
  if [ -f "$TEMP_MYSQL_PIDFILE" ]; then
    kill "$(cat "$TEMP_MYSQL_PIDFILE")" 2>/dev/null || true
    sleep 2
    kill -9 "$(cat "$TEMP_MYSQL_PIDFILE")" 2>/dev/null || true
    rm -f "$TEMP_MYSQL_PIDFILE"
    echo "已停止临时 MySQL"
  fi
}

# ---------- 后端 / 前端 ----------
start_backend() {
  export JAVA_HOME="$JDK21"
  export PATH="$JDK21/bin:$PATH"
  if [ -n "${RBAC_M2_REPO:-}" ]; then
    ( cd "$ROOT" && exec mvn "-Dmaven.repo.local=${RBAC_M2_REPO}" \
        -pl telbank-rbac-api -am spring-boot:run \
        -Dspring-boot.run.profiles=demo \
        -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8" \
        "-Dspring-boot.run.arguments=--server.port=${API_PORT}" ) >"$API_LOG" 2>&1 &
  else
    ( cd "$ROOT" && exec mvn \
        -pl telbank-rbac-api -am spring-boot:run \
        -Dspring-boot.run.profiles=demo \
        -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8" \
        "-Dspring-boot.run.arguments=--server.port=${API_PORT}" ) >"$API_LOG" 2>&1 &
  fi
  BACKEND_PID=$!
}

start_frontend() {
  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [ -s "${NVM_DIR}/nvm.sh" ]; then
    # shellcheck disable=SC1091
    . "${NVM_DIR}/nvm.sh"
    nvm use 22 >/dev/null 2>&1 || true
  fi
  if [ ! -d "$ROOT/telbank-rbac-front/node_modules" ]; then
    ( cd "$ROOT/telbank-rbac-front" && npm install --no-audit --no-fund ) >>"$FRONT_LOG" 2>&1 || true
  fi
  ( cd "$ROOT/telbank-rbac-front" && \
    VITE_API_BASE_URL="${VITE_API_BASE_URL:-http://localhost:${API_PORT}}" \
    exec npm run dev -- --port "$FRONT_PORT" ) >"$FRONT_LOG" 2>&1 &
  FRONTEND_PID=$!
}


cleanup() {
  trap - EXIT INT TERM
  [ "$RUNNING" = "1" ] || return 0
  echo ""
  echo "正在停止前后端 ..."
  [ -n "${API_PID:-}" ] && kill "$API_PID" 2>/dev/null || true
  [ -n "${FRONT_PID:-}" ] && kill "$FRONT_PID" 2>/dev/null || true
  kill_port "$API_PORT" 0
  kill_port "$FRONT_PORT" 0
  sleep 1
  kill_port "$API_PORT" 1
  kill_port "$FRONT_PORT" 1
  if [ "$TEMP_DB_STARTED" = "1" ]; then
    echo "正在停止临时 MySQL（${TEMP_MYSQL_PORT}）并清理数据目录 ..."
    temp_mysql_stop >/dev/null 2>&1 || true
    # 兜底：即使 stop 被中断/进程已退出，也清掉临时数据目录
    rm -rf "$TEMP_DB_DIR" 2>/dev/null || true
  fi
  echo "已停止（端口 ${API_PORT}/${FRONT_PORT} 已释放）"
}
trap cleanup EXIT INT TERM

# ---------- 预检 ----------
if [ "$TEMP_DB" = "1" ] && [ -n "${RBAC_DB_URL:-}" ]; then
  echo "❌ --temp-db 与 RBAC_DB_URL 冲突：临时库由脚本自动配置，请二选一。" >&2
  exit 1
fi
if [ "$TEMP_DB" = "1" ] && [ "$INIT_DB" = "1" ]; then
  echo "提示：--temp-db 会自动创建 telbank_rbac 库，--init-db 将被忽略。"
  INIT_DB=0
fi

if port_in_use "$API_PORT"; then
  echo "❌ 端口 ${API_PORT} 已被占用（可能是已启动的后端）。可设置 API_PORT=其他端口重试。" >&2
  lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN 2>/dev/null | head -3 >&2
  exit 1
fi
if port_in_use "$FRONT_PORT"; then
  echo "❌ 端口 ${FRONT_PORT} 已被占用（可能是已启动的前端）。可设置 FRONT_PORT=其他端口重试。" >&2
  exit 1
fi

# ---------- 数据库准备 ----------
if [ "$TEMP_DB" = "1" ]; then
  echo "==> 准备临时 MySQL（端口 ${TEMP_MYSQL_PORT}，脚本退出时自动清理）"
  if temp_mysql_status | grep -q '^running'; then
    echo "    检测到已有临时 MySQL 在运行，直接复用（退出时不会停止它）"
  else
    temp_mysql_start >"$ROOT/logs/temp-mysql.log" 2>&1 \
      || { echo "❌ 临时 MySQL 启动失败，日志 logs/temp-mysql.log：" >&2; tail -n 20 "$ROOT/logs/temp-mysql.log" >&2; exit 1; }
    TEMP_DB_STARTED=1
    RUNNING=1
  fi
  export RBAC_DB_URL="jdbc:mysql://127.0.0.1:${TEMP_MYSQL_PORT}/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
  echo "    临时库已创建：telbank_rbac @ 127.0.0.1:${TEMP_MYSQL_PORT}，导入表结构/示例数据 ..."
  MYSQL_HOST=127.0.0.1 MYSQL_PORT="$TEMP_MYSQL_PORT" MYSQL_USER=root MYSQL_PASSWORD= \
    "$ROOT/scripts/init-db.sh" >"$ROOT/logs/temp-mysql-init.log" 2>&1 \
    || { echo "❌ 临时库初始化失败，日志 logs/temp-mysql-init.log：" >&2; tail -n 20 "$ROOT/logs/temp-mysql-init.log" >&2; exit 1; }
  echo "    表结构与示例数据已导入"
elif [ "$INIT_DB" = "1" ]; then
  echo "==> 初始化数据库（建库 + 导入表结构/示例数据）"
  "$ROOT/scripts/init-db.sh" || { echo "❌ 数据库初始化失败，请检查 MySQL 连接配置/SQL 文件" >&2; exit 1; }
fi

# 非临时库模式：把 MYSQL_* 映射给后端（否则只设 MYSQL_PASSWORD 会导致 Access denied）
if [ "$TEMP_DB" != "1" ]; then
  if [ -z "${RBAC_DB_USER:-}" ] && [ -n "${MYSQL_USER:-}" ]; then
    export RBAC_DB_USER="$MYSQL_USER"
  fi
  if [ -z "${RBAC_DB_PASSWORD:-}" ] && [ -n "${MYSQL_PASSWORD:-}" ]; then
    export RBAC_DB_PASSWORD="$MYSQL_PASSWORD"
  fi
  if [ -z "${RBAC_DB_URL:-}" ] && { [ -n "${MYSQL_HOST:-}" ] || [ -n "${MYSQL_PORT:-}" ] || [ -n "${RBAC_DB_NAME:-}" ]; }; then
    export RBAC_DB_URL="jdbc:mysql://${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}/${RBAC_DB_NAME:-telbank_rbac}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
  fi
  if [ -n "${RBAC_DB_URL:-}" ]; then
    echo "    数据源：${RBAC_DB_URL}"
  else
    echo "    数据源：默认 jdbc:mysql://localhost:3306/telbank_rbac（用户 ${RBAC_DB_USER:-root}）"
  fi
fi

JDK21="$(find_jdk21)" || { echo "❌ 未找到 JDK 21+（设置 JAVA_HOME_21 或安装 openjdk@21）" >&2; exit 1; }
echo "使用 JAVA_HOME=${JDK21}"

# ---------- 启动后端 ----------
echo "==> 启动权限中心后端（端口 ${API_PORT}，日志 logs/api.log）"
start_backend
API_PID=$BACKEND_PID
RUNNING=1

echo -n "    等待后端就绪"
API_READY=0
for _ in $(seq 1 180); do
  if ! kill -0 "$API_PID" 2>/dev/null; then
    echo ""
    echo "❌ 后端进程已退出，日志尾部：" >&2
    tail -n 40 "$API_LOG" >&2
    echo "" >&2
    echo "常见原因：MySQL 未启动/未初始化（--temp-db 或 ./scripts/init-db.sh）、JDK 不是 21+、端口冲突。" >&2
    exit 1
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:${API_PORT}/api/me" 2>/dev/null || true)"
  if [ "$code" = "401" ] || [ "$code" = "200" ]; then
    API_READY=1
    break
  fi
  echo -n "."
  sleep 1
done
if [ "$API_READY" != "1" ]; then
  echo ""
  echo "❌ 后端 180 秒内未就绪，日志尾部：" >&2
  tail -n 40 "$API_LOG" >&2
  exit 1
fi
echo " 就绪"

# ---------- 启动前端 ----------
echo "==> 启动管理前端（端口 ${FRONT_PORT}，日志 logs/front.log）"
start_frontend
FRONT_PID=$FRONTEND_PID

echo -n "    等待前端就绪"
FRONT_READY=0
for _ in $(seq 1 120); do
  if ! kill -0 "$FRONT_PID" 2>/dev/null; then
    echo ""
    echo "❌ 前端进程已退出，日志尾部：" >&2
    tail -n 40 "$FRONT_LOG" >&2
    exit 1
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:${FRONT_PORT}/" 2>/dev/null || true)"
  if [ "$code" = "200" ]; then
    FRONT_READY=1
    break
  fi
  echo -n "."
  sleep 1
done
if [ "$FRONT_READY" != "1" ]; then
  echo ""
  echo "❌ 前端 120 秒内未就绪，日志尾部：" >&2
  tail -n 40 "$FRONT_LOG" >&2
  exit 1
fi
echo " 就绪"

cat <<EOF

============================================================
 权限中心后端 : http://localhost:${API_PORT}     （日志 logs/api.log）
 管理前端     : http://localhost:${FRONT_PORT}   （日志 logs/front.log）
$([ "$TEMP_DB" = "1" ] && echo " 临时 MySQL   : 127.0.0.1:${TEMP_MYSQL_PORT}（telbank_rbac，脚本退出时自动清理）")
 演示 JWT     : TOKEN=\$(./scripts/gen-demo-jwt.sh superadmin)
 登录账号     : superadmin（平台超管）/ crm-admin-001 / erp-admin-001
 停止服务     : 按 Ctrl+C（或 kill 本脚本）
============================================================

EOF

wait
