#!/usr/bin/env bash
# 同时启动权限中心后端（demo profile）与管理前端（开发模式）
#
# 特性：
#   - 启动前检查端口占用（默认 8080 / 5173）
#   - 后端就绪后再启动前端（后端 JWT 接口返回 401/200 视为就绪）
#   - 日志输出到 logs/api.log、logs/front.log
#   - Ctrl+C / kill 时自动停止前后端并释放端口
#   - --init-db：启动前先执行 scripts/init-db.sh（需 MySQL 环境变量）
#
# 用法：
#   ./scripts/start-all.sh
#   API_PORT=18080 FRONT_PORT=5174 ./scripts/start-all.sh
#   MYSQL_USER=root MYSQL_PASSWORD='***' ./scripts/start-all.sh --init-db
#   RBAC_DB_URL='jdbc:mysql://127.0.0.1:3307/telbank_rbac?...' ./scripts/start-all.sh
#
# 环境变量：
#   API_PORT     后端端口，默认 8080
#   FRONT_PORT   前端端口，默认 5173
#   VITE_API_BASE_URL  前端请求的后端地址，默认 http://localhost:API_PORT
#   RBAC_*       透传给后端（数据库/JWT/AppSecret 等，见 README §7）
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

API_PORT="${API_PORT:-8080}"
FRONT_PORT="${FRONT_PORT:-5173}"
API_LOG="$ROOT/logs/api.log"
FRONT_LOG="$ROOT/logs/front.log"
INIT_DB=0
RUNNING=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --init-db) INIT_DB=1 ;;
    -h|--help)
      sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数：$1（可用：--init-db）" >&2
      exit 1
      ;;
  esac
  shift
done

mkdir -p "$ROOT/logs"

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

kill_port() {
  local port="$1" force="$2" pid
  lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null \
    | tail -n +2 | awk '{print $2}' | sort -u \
    | while read -r pid; do
        [ -n "$pid" ] || continue
        if [ "$force" = "1" ]; then kill -9 "$pid" 2>/dev/null || true; else kill "$pid" 2>/dev/null || true; fi
      done
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
  echo "已停止（端口 ${API_PORT}/${FRONT_PORT} 已释放）"
}
trap cleanup EXIT INT TERM

# ---------- 预检 ----------
if port_in_use "$API_PORT"; then
  echo "❌ 端口 ${API_PORT} 已被占用（可能是已启动的后端）。可设置 API_PORT=其他端口重试。" >&2
  lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN 2>/dev/null | head -3 >&2
  exit 1
fi
if port_in_use "$FRONT_PORT"; then
  echo "❌ 端口 ${FRONT_PORT} 已被占用（可能是已启动的前端）。可设置 FRONT_PORT=其他端口重试。" >&2
  exit 1
fi

if [ "$INIT_DB" = "1" ]; then
  echo "==> 初始化数据库（scripts/init-db.sh）"
  ./scripts/init-db.sh || { echo "❌ 数据库初始化失败，请检查 MySQL 连接配置" >&2; exit 1; }
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
    echo "常见原因：MySQL 未启动/未初始化（./scripts/init-db.sh 或 ./scripts/start-temp-mysql.sh start）、JDK 不是 21+、端口冲突。" >&2
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
 演示 JWT     : TOKEN=\$(./scripts/gen-demo-jwt.sh 13800138000)
 登录账号     : 13800138000（CRM/ERP 管理员 + 权限中心管理员 rbac-admin）
 停止服务     : 按 Ctrl+C（或 kill 本脚本）
============================================================

EOF

wait
