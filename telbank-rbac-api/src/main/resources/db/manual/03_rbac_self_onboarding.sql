-- =====================================================================
-- telbank-rbac 权限管理系统 · V3 权限中心自身接入（方案 A）
-- 内容：
--   1) 新增“权限中心自身”系统 rbac（管理上下文）
--   2) 将原本挂在 CRM/ERP 下的管理权限码 rbac:* 迁入 rbac 系统
--   3) 新增 rbac-admin 角色并授权，演示管理员 13800138000 接入
-- 说明：管理端 /api/** 的 X-System-Code 固定为 rbac（管理上下文），
--       业务系统筛选通过 query/body 的 systemId 表达。
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 1) 权限中心自身系统 ------------------------------------------------
INSERT INTO sys_system (id, code, name, region, status, sort, remark) VALUES
(3, 'rbac', '权限中心', 'region_a', 1, 0, 'RBAC 权限中心自身（管理端接入）');

-- 2) 移除挂在业务系统下的管理权限码（先授权关系、后权限码） ----------
DELETE FROM sys_role_permission
 WHERE permission_id IN (20, 21, 22, 23, 24, 30, 31, 32, 33, 34);
DELETE FROM sys_permission
 WHERE id IN (20, 21, 22, 23, 24, 30, 31, 32, 33, 34);

-- 3) 管理权限码迁入 rbac 系统 ----------------------------------------
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(40, 3, 0, 2, 'rbac:system:manage',     '系统与用户系统管理', NULL, NULL, 80, 1, 0, NULL),
(41, 3, 0, 2, 'rbac:role:manage',       '角色与授权管理',     NULL, NULL, 81, 1, 0, NULL),
(42, 3, 0, 2, 'rbac:permission:manage', '权限码与端点管理',   NULL, NULL, 82, 1, 0, NULL),
(43, 3, 0, 2, 'rbac:page:manage',       '页面操作管理',       NULL, NULL, 83, 1, 0, NULL),
(44, 3, 0, 2, 'rbac:audit:view',        '审批与到期查询',     NULL, NULL, 84, 1, 0, NULL);

-- 4) 权限中心管理员角色与授权 ----------------------------------------
INSERT INTO sys_role (id, system_id, code, name, status, sort, remark) VALUES
(40, 3, 'rbac-admin', '权限中心管理员', 1, 10, '管理端全部管理权限（rbac:*）');

INSERT INTO sys_role_permission (id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(40, 3, 40, 40, NULL, NULL, 1, NULL, NULL, 1),
(41, 3, 40, 41, NULL, NULL, 1, NULL, NULL, 1),
(42, 3, 40, 42, NULL, NULL, 1, NULL, NULL, 1),
(43, 3, 40, 43, NULL, NULL, 1, NULL, NULL, 1),
(44, 3, 40, 44, NULL, NULL, 1, NULL, NULL, 1);

-- 5) 演示管理员接入权限中心系统（13800138000） ------------------------
INSERT INTO sys_user_system (id, system_id, external_user_id, status, is_default) VALUES
(40, 3, '13800138000', 1, 0);

INSERT INTO sys_user_role (id, system_id, external_user_id, role_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(40, 3, '13800138000', 40, NULL, NULL, 1, '权限中心管理员（演示）', NULL, 1);

-- 6) 自增起点保持后续新增空间 ----------------------------------------
ALTER TABLE sys_system AUTO_INCREMENT = 100;
ALTER TABLE sys_role AUTO_INCREMENT = 100;
ALTER TABLE sys_permission AUTO_INCREMENT = 100;
ALTER TABLE sys_user_role AUTO_INCREMENT = 100;
ALTER TABLE sys_role_permission AUTO_INCREMENT = 100;
ALTER TABLE sys_user_system AUTO_INCREMENT = 100;
