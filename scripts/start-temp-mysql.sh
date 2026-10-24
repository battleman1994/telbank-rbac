#!/usr/bin/env bash
# 启动/停止一次性本地 MySQL 实例（验收专用，不影响系统已有 MySQL）
#
# 用法:
#   ./scripts/start-temp-mysql.sh start   # 初始化并启动到 3307，打印连接信息
#   ./scripts/start-temp-mysql.sh stop    # 停止并清理
#   ./scripts/start-temp-mysql.sh status
#
# 用途：本机已有 MySQL 的 root 密码未知时，验收可在 3307 端口起一个临时实例：
#   MYSQLD=/opt/homebrew/opt/mysql/bin/mysqld
#   数据目录 /tmp/telbank-rbac-mysql
set -euo pipefail

DATADIR="${RBAC_TEMP_MYSQL_DATADIR:-/tmp/telbank-rbac-mysql/data}"
SOCKET="${RBAC_TEMP_MYSQL_SOCKET:-/tmp/telbank-rbac-mysql/mysql.sock}"
PORT="${RBAC_TEMP_MYSQL_PORT:-3307}"
PIDFILE="/tmp/telbank-rbac-mysql/mysqld.pid"
LOG="/tmp/telbank-rbac-mysql/mysqld.log"

find_mysqld() {
  if [ -n "${MYSQLD:-}" ]; then echo "$MYSQLD"; return; fi
  for c in /opt/homebrew/opt/mysql/bin/mysqld /usr/local/opt/mysql/bin/mysqld "$(command -v mysqld || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
  done
  return 1
}

stop_all() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    sleep 2
    kill -9 "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
    echo "已停止临时 MySQL"
  fi
}

case "${1:-start}" in
  start)
    MYSQLD_BIN="$(find_mysqld)" || { echo "未找到 mysqld，请安装 MySQL 或设置 MYSQLD 环境变量" >&2; exit 1; }
    mkdir -p "$(dirname "$DATADIR")"
    if [ ! -d "$DATADIR/mysql" ]; then
      echo "初始化临时数据目录：$DATADIR"
      "$MYSQLD_BIN" --initialize-insecure --datadir="$DATADIR" >/dev/null 2>&1
    fi
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "临时 MySQL 已在运行（pid $(cat "$PIDFILE")）"
    else
      "$MYSQLD_BIN" --datadir="$DATADIR" --port="$PORT" --socket="$SOCKET" \
        --pid-file="$PIDFILE" --log-error="$LOG" --skip-networking=0 \
        --bind-address=127.0.0.1 --mysqlx=0 >/dev/null 2>&1 &
      for _ in $(seq 1 30); do
        [ -S "$SOCKET" ] && break
        sleep 1
      done
    fi
    MYSQL_BIN="${MYSQL_BIN:-/opt/homebrew/opt/mysql/bin/mysql}"
    [ -x "$MYSQL_BIN" ] || MYSQL_BIN="$(command -v mysql)"
    "$MYSQL_BIN" --socket="$SOCKET" -uroot -e "
CREATE DATABASE IF NOT EXISTS telbank_rbac DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>/dev/null
    echo "临时 MySQL 已启动：127.0.0.1:${PORT}（root 无密码），库 telbank_rbac 已创建"
    echo "表结构/示例数据请执行：MYSQL_HOST=127.0.0.1 MYSQL_PORT=${PORT} MYSQL_USER=root ./scripts/init-db.sh"
    echo "启动权限中心时使用："
    echo "  export RBAC_DB_URL='jdbc:mysql://127.0.0.1:${PORT}/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'"
    echo "  ./scripts/start-api.sh"
    ;;
  stop)
    stop_all
    rm -rf "$(dirname "$DATADIR")"
    echo "已删除临时数据目录"
    ;;
  status)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "running pid=$(cat "$PIDFILE") port=${PORT}"
    else
      echo "not running"
    fi
    ;;
  *)
    echo "用法: $0 {start|stop|status}" >&2
    exit 1
    ;;
esac
