-- =====================================================================
-- 05_wildcard_permissions.sql
-- A 方案：管理员角色只授一条通配权限 '*'
--
-- 变更：
--   1) 业务系统（CRM/ERP）：新增 '*' 权限（系统内全部权限），授予该系统 admin 角色；
--      删除原来的 6 条短码（role:manage 等）及授权 —— 通配 * 覆盖它们
--   2) 平台系统（platform）：把 platform:admin 替换为 '*'（平台超管只保留一条记录）
--   3) 系统接入新建系统时也只需创建 1 条 '*' 权限（后端逻辑同步改）
--
-- 通配匹配口径（后端 @perm / 前端 hasPerm / SDK 拦截器统一）：
--   - `*`：匹配任意权限码
--   - `前缀:*`（如 `order:*`）：匹配以 `前缀:` 开头的权限码
--   - 其余按精确匹配；多个必需权限按 AND 语义逐个匹配
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 1) 删除 04 中添加的 6 条系统内短管理权限码及其授权 --------------------
DELETE FROM sys_role_permission WHERE permission_id IN (50,51,52,53,54,55,60,61,62,63,64,65);
DELETE FROM sys_permission      WHERE id IN (50,51,52,53,54,55,60,61,62,63,64,65);

-- 2) 业务系统：新增一条 '*' 权限并授予各自 admin 角色 -------------------
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(100, 1, 0, 2, '*', '全部权限（通配）', NULL, NULL, 100, 1, 0, NULL),
(101, 2, 0, 2, '*', '全部权限（通配）', NULL, NULL, 100, 1, 0, NULL);

INSERT INTO sys_role_permission
(id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(100, 1, 1, 100, NULL, NULL, 1, '系统管理员通配权限', NULL, 1),
(101, 2, 3, 101, NULL, NULL, 1, '系统管理员通配权限', NULL, 1);

-- 3) 平台系统：platform:admin -> '*' ------------------------------------
DELETE FROM sys_role_permission WHERE system_id = 4 AND permission_id = 90;
DELETE FROM sys_permission      WHERE id = 90;

INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(102, 4, 0, 2, '*', '平台全部权限（通配）', NULL, NULL, 100, 1, 0, NULL);

INSERT INTO sys_role_permission
(id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(102, 4, 90, 102, NULL, NULL, 1, '平台超管通配权限', NULL, 1);

-- 4) 自增起点 -----------------------------------------------------------
ALTER TABLE sys_permission        AUTO_INCREMENT = 200;
ALTER TABLE sys_role_permission   AUTO_INCREMENT = 200;
