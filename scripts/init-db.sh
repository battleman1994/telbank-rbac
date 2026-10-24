#!/usr/bin/env bash
# 初始化权限中心数据库（MySQL 8.0+）
#
# 作用：创建数据库 + 按顺序导入 db/manual/*.sql（表结构、示例数据、权限中心自接入）。
# 本项目 **不使用 Flyway**，升级数据库时按新的 SQL 文件手动执行；重复执行会自动跳过
# 已有表结构的库（不会重复灌数据）。
#
# 用法:
#   MYSQL_USER=root MYSQL_PASSWORD=secret ./scripts/init-db.sh
#   ./scripts/init-db.sh --drop     # 危险：删库重建（仅本地）
#   RBAC_DB_NAME=telbank_rbac_dev MYSQL_USER=root MYSQL_PASSWORD=secret ./scripts/init-db.sh
#
# 环境变量:
#   MYSQL_HOST     默认 127.0.0.1
#   MYSQL_PORT     默认 3306
#   MYSQL_USER     默认 root
#   MYSQL_PASSWORD 默认空
#   RBAC_DB_NAME   默认 telbank_rbac
#   RBAC_SQL_DIR   SQL 目录，默认 <repo>/telbank-rbac-api/src/main/resources/db/manual
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL_DIR="${RBAC_SQL_DIR:-$ROOT/telbank-rbac-api/src/main/resources/db/manual}"

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

HAS_SCHEMA="$(mysql "${MYSQL_ARGS[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name='sys_system';")"
if [ "$HAS_SCHEMA" -gt 0 ]; then
  echo "检测到 ${DB_NAME} 已有表结构，跳过 SQL 导入（如需重建：./scripts/init-db.sh --drop）"
  exit 0
fi

if [ ! -d "$SQL_DIR" ]; then
  echo "❌ SQL 目录不存在：$SQL_DIR" >&2
  exit 1
fi

echo "开始导入手动初始化 SQL（目录 ${SQL_DIR}）..."
for f in "$SQL_DIR"/*.sql; do
  if [ ! -e "$f" ]; then
    echo "❌ 未找到 SQL 文件：$SQL_DIR/*.sql" >&2
    exit 1
  fi
  echo "  导入 $(basename "$f") ..."
  mysql "${MYSQL_ARGS[@]}" "$DB_NAME" <"$f"
done

echo "数据库 ${DB_NAME} 初始化完成（表结构 + 示例数据，已导入 ${SQL_DIR}/*.sql）。"
