-- =====================================================================
-- 06_cleanup_admin_redundant_grants.sql
-- 清理系统管理员角色的冗余授权（A 方案补充）
--
-- 背景：
--   05 给每个业务系统的 admin 角色新增了一条通配权限 '*'，但 03/04 种子
--   里 admin 角色还保留着 order:* 等业务权限授权。'*' 已覆盖全部权限，
--   这些授权是冗余数据，会让「角色授权」页面/权限列表看起来不止一项。
--
-- 处理：
--   删除所有业务系统内 code='admin' 的角色中，权限码不为 '*' 的授权记录，
--   admin 角色只保留唯一一条 '*'。
--
-- 说明：
--   - 公共权限（is_public=1，如 common:profile）不属于角色授权，不在此清理范围；
--     它们仍会出现在所有登录用户的有效权限集合中，这是需求 §5.3 的公共权限语义。
--   - 平台超管角色（platform 系统 super-admin）本来就只有 '*'，无需处理。
--   - 其他业务角色（operator/purchaser 等）不受影响。
-- =====================================================================

SET NAMES utf8mb4;

DELETE rp
FROM sys_role_permission rp
JOIN sys_role r       ON r.id = rp.role_id AND r.system_id = rp.system_id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.code = 'admin'
  AND p.code <> '*';

-- 自增起点保持 200（与 05 一致）
ALTER TABLE sys_role_permission AUTO_INCREMENT = 200;
