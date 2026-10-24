#!/usr/bin/env bash
# 演示「权限时效」：给指定账号授予一条 N 分钟（默认 1 分钟）临时权限
#
# 用途：管理前端登录后能看到对应菜单/按钮；过期后刷新页面即消失，
#       直接访问对应路由显示「无权限」（后端 API 也会返回 403）。
#
# 用法:
#   ./scripts/demo-temp-permission.sh                    # 默认 demo-1min / audit:view / crm / 1 分钟
#   ./scripts/demo-temp-permission.sh demo-1min audit:view crm 1
#   ./scripts/demo-temp-permission.sh 13700137000 order:delete crm 1   # 业务权限（需示例业务系统在跑）
#   ./scripts/demo-temp-permission.sh --clean            # 清理演示角色/授权
#
# 参数:
#   $1 账号 external_user_id（默认 demo-1min）
#   $2 权限码（默认 audit:view；系统内必须已存在）
#   $3 系统 code（默认 crm）
#   $4 有效分钟数（默认 1，建议 1）
#
# 环境变量: MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/RBAC_DB_NAME（同 init-db.sh）
set -euo pipefail
cd "$(dirname "$0")/.."

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
DB_NAME="${RBAC_DB_NAME:-telbank_rbac}"
ROLE_CODE="temp-1min"

MYSQL_ARGS=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -N -B)
if [ -n "$MYSQL_PASSWORD" ]; then
  MYSQL_ARGS+=("-p$MYSQL_PASSWORD")
fi
q() { mysql "${MYSQL_ARGS[@]}" "$DB_NAME" -e "$1"; }

if [ "${1:-}" = "--clean" ]; then
  q "DELETE ur FROM sys_user_role ur
       JOIN sys_role r ON r.id=ur.role_id
      WHERE r.code='${ROLE_CODE}';"
  q "DELETE rp FROM sys_role_permission rp
       JOIN sys_role r ON r.id=rp.role_id
      WHERE r.code='${ROLE_CODE}';"
  q "DELETE FROM sys_role WHERE code='${ROLE_CODE}';"
  echo "已清理演示角色 ${ROLE_CODE} 及其授权。"
  exit 0
fi

ACCOUNT="${1:-demo-1min}"
PERM_CODE="${2:-audit:view}"
SYSTEM_CODE="${3:-crm}"
MINUTES="${4:-1}"

for v in "$ACCOUNT" "$PERM_CODE" "$SYSTEM_CODE" "$MINUTES"; do
  case "$v" in
    *[!A-Za-z0-9_.:-]*) echo "❌ 参数包含非法字符：$v" >&2; exit 1 ;;
  esac
done
case "$MINUTES" in
  ''|*[!0-9]*) echo "❌ 分钟数必须是正整数：$MINUTES" >&2; exit 1 ;;
esac

SYS_ID="$(q "SELECT id FROM sys_system WHERE code='${SYSTEM_CODE}' AND status=1;" | head -1)"
[ -n "$SYS_ID" ] || { echo "❌ 系统不存在或已停用：${SYSTEM_CODE}" >&2; exit 1; }

PERM_ID="$(q "SELECT id FROM sys_permission WHERE system_id=${SYS_ID} AND code='${PERM_CODE}' AND status=1;" | head -1)"
[ -n "$PERM_ID" ] || { echo "❌ 权限码不存在或已停用：${SYSTEM_CODE}/${PERM_CODE}" >&2; exit 1; }

# 1) 演示角色（同系统内复用）
q "INSERT INTO sys_role (system_id, code, name, status, sort, remark)
   VALUES (${SYS_ID}, '${ROLE_CODE}', '临时权限演示角色', 1, 99, 'demo-temp-permission.sh 使用')
   ON DUPLICATE KEY UPDATE status=1, remark='demo-temp-permission.sh 使用';"
ROLE_ID="$(q "SELECT id FROM sys_role WHERE system_id=${SYS_ID} AND code='${ROLE_CODE}';" | head -1)"

# 2) 授权窗口：现在 ~ N 分钟后（数据库统一 UTC 时间）
q "DELETE FROM sys_role_permission WHERE role_id=${ROLE_ID};"
q "INSERT INTO sys_role_permission
     (system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, status)
   VALUES
     (${SYS_ID}, ${ROLE_ID}, ${PERM_ID},
      UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL ${MINUTES} MINUTE),
      1, '时间权限演示（${MINUTES} 分钟）', 1);"

# 3) 临时角色授权（系统访问由有效角色推导，无需用户-系统开通表）
q "DELETE FROM sys_user_role WHERE system_id=${SYS_ID} AND external_user_id='${ACCOUNT}' AND role_id=${ROLE_ID};"
q "INSERT INTO sys_user_role
     (system_id, external_user_id, role_id, valid_from, valid_until, auto_revoke, grant_reason, status)
   VALUES
     (${SYS_ID}, '${ACCOUNT}', ${ROLE_ID},
      UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL ${MINUTES} MINUTE),
      1, '时间权限演示（${MINUTES} 分钟）', 1);"

WINDOW="$(q "SELECT CONCAT(DATE_FORMAT(valid_from,'%H:%i:%s'), ' ~ ', DATE_FORMAT(valid_until,'%H:%i:%s'), ' UTC')
              FROM sys_user_role
             WHERE external_user_id='${ACCOUNT}' AND role_id=${ROLE_ID} AND system_id=${SYS_ID}
             ORDER BY id DESC LIMIT 1;")"

cat <<EOF

✅ 临时权限已下发（${MINUTES} 分钟后自动失效）
   账号      : ${ACCOUNT}
   系统/权限 : ${SYSTEM_CODE} / ${PERM_CODE}
   有效窗口  : ${WINDOW}
   本地时间  : $(date '+%H:%M:%S %Z')（数据库按 UTC 存储，窗口以 UTC 为准）

验证方式（管理前端，示例）：
  TOKEN=\$(./scripts/gen-demo-jwt.sh ${ACCOUNT})
  浏览器打开 http://localhost:5173 → 粘贴 TOKEN 登录
  ✅ ${MINUTES} 分钟内：能看到「授权记录 / 到期提醒」菜单（直接访问 /approval-grants 也是 200）
  ⏳ 等 ${MINUTES} 分钟后：刷新页面 → 菜单消失；直接访问 /approval-grants → 「无权限」页（后端返回 403）

重新计时（再跑一次）：
  MYSQL_USER=${MYSQL_USER} MYSQL_PASSWORD='***' ./scripts/demo-temp-permission.sh ${ACCOUNT} ${PERM_CODE} ${SYSTEM_CODE} ${MINUTES}

清理演示数据：
  ./scripts/demo-temp-permission.sh --clean
EOF
