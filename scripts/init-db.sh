#!/usr/bin/env bash
# 初始化权限中心数据库（MySQL 8.0+）
#
# 用法:
#   MYSQL_USER=root MYSQL_PASSWORD=secret ./scripts/init-db.sh
#   ./scripts/init-db.sh --drop     # 危险：删库重建（仅本地）
#
# 环境变量:
#   MYSQL_HOST     默认 127.0.0.1
#   MYSQL_PORT     默认 3306
#   MYSQL_USER     默认 root
#   MYSQL_PASSWORD 默认空
#   RBAC_DB_NAME   默认 telbank_rbac
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
DB_NAME="${RBAC_DB_NAME:-telbank_rbac}"

MYSQL_ARGS=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER")
if [ -n "$MYSQL_PASSWORD" ]; then
  MYSQL_ARGS+=("-p$MYSQL_PASSWORD")
fi

if [ "${1:-}" = "--drop" ]; then
  echo "!! 即将删除数据库 ${DB_NAME}，5 秒后执行（Ctrl+C 取消）"
  sleep 5
  mysql "${MYSQL_ARGS[@]}" -e "DROP DATABASE IF EXISTS \`${DB_NAME}\`;"
  echo "已删除 ${DB_NAME}"
fi

mysql "${MYSQL_ARGS[@]}" -e "
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SELECT 'database ready: ${DB_NAME}' AS msg;
"

echo "数据库 ${DB_NAME} 已就绪；表结构与示例数据由权限中心启动时 Flyway 自动迁移。"
