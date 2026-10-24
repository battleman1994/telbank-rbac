#!/usr/bin/env bash
# 同时启动权限中心后端（demo profile）与管理前端（开发模式）—— Linux 版
#
# 端口检测优先 lsof，其次 ss，最后 fuser（不同发行版安装情况不同）。
# Windows 请使用 scripts/start-all.cmd（或 start-all-windows.ps1）；macOS 用 start-all-macos.sh。
#
# 特性：
#   - --temp-db：自动启动一次性临时 MySQL（默认 3307）并创建 telbank_rbac 库，
#                后端 Flyway 自动建表/灌示例数据；脚本退出时自动停止并清理临时库
#   - --init-db：针对已有 MySQL 先执行 scripts/init-db.sh（需 MYSQL_* 环境变量）
#   - 后端就绪后再启动前端（后端 JWT 接口返回 401/200 视为就绪）
#   - 日志输出到 logs/api.log、logs/front.log、logs/temp-mysql.log
#   - Ctrl+C / kill 时自动停止前后端（及本脚本启动的临时库）并释放端口
#
# 前置：bash、JDK 21+、Maven、Node 22+（nvm 或 PATH）、可选 local MySQL（--temp-db 需 mysqld 可执行）
#
# 用法：
#   ./scripts/start-all.sh --temp-db                 # 推荐（Unix 分发器会自动识别 Linux）
#   ./scripts/start-all-linux.sh --temp-db           # 直接调用 Linux 脚本
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
    "$ROOT/scripts/start-temp-mysql.sh" stop >/dev/null 2>&1 || true
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
  if "$ROOT/scripts/start-temp-mysql.sh" status 2>/dev/null | grep -q '^running'; then
    echo "    检测到已有临时 MySQL 在运行，直接复用（退出时不会停止它）"
  else
    RBAC_TEMP_MYSQL_PORT="$TEMP_MYSQL_PORT" \
      "$ROOT/scripts/start-temp-mysql.sh" start >"$ROOT/logs/temp-mysql.log" 2>&1 \
      || { echo "❌ 临时 MySQL 启动失败，日志 logs/temp-mysql.log：" >&2; tail -n 20 "$ROOT/logs/temp-mysql.log" >&2; exit 1; }
    TEMP_DB_STARTED=1
    RUNNING=1
  fi
  export RBAC_DB_URL="jdbc:mysql://127.0.0.1:${TEMP_MYSQL_PORT}/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
  echo "    临时库已就绪：telbank_rbac @ 127.0.0.1:${TEMP_MYSQL_PORT}（表结构与示例数据由 Flyway 自动迁移）"
elif [ "$INIT_DB" = "1" ]; then
  echo "==> 初始化数据库（scripts/init-db.sh）"
  "$ROOT/scripts/init-db.sh" || { echo "❌ 数据库初始化失败，请检查 MySQL 连接配置" >&2; exit 1; }
fi

# ---------- 启动后端 ----------
echo "==> 启动权限中心后端（端口 ${API_PORT}，日志 logs/api.log）"
"$ROOT/scripts/start-api.sh" "-Dspring-boot.run.arguments=--server.port=${API_PORT}" >"$API_LOG" 2>&1 &
API_PID=$!
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
VITE_API_BASE_URL="${VITE_API_BASE_URL:-http://localhost:${API_PORT}}" \
  "$ROOT/scripts/start-front.sh" --port "$FRONT_PORT" >"$FRONT_LOG" 2>&1 &
FRONT_PID=$!

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
 演示 JWT     : TOKEN=\$(./scripts/gen-demo-jwt.sh 13800138000)
 登录账号     : 13800138000（CRM/ERP 管理员 + 权限中心管理员 rbac-admin）
 停止服务     : 按 Ctrl+C（或 kill 本脚本）
============================================================

EOF

wait
