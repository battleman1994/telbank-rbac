-- =====================================================================
-- 04_system_isolation.sql
-- 权限中心改为「以系统为隔离边界 + 数据驱动超管」的权限管理模型
--
-- 变更：
--   1) 删除「用户-系统开通」表：用户可访问系统 = 在该系统有有效角色（由角色推导）
--   2) 删除 V3 的权限中心自身系统 rbac（自接入数据，管理权限改为系统内短码）
--   3) sys_system 增加平台标记与系统管理员信息字段
--   4) 新增平台系统 platform + super-admin 角色 + platform:admin 权限，
--      种子超管账号 superadmin（不需要 rbac.platform-admin-ids 配置文件）
--   5) 为 CRM / ERP 增加系统内管理权限码，并授予各自 admin 角色；
--      登记两个系统的系统管理员账号
--   6) 新增演示系统管理员账号：crm-admin-001 / erp-admin-001（仅管理本系统）
--
-- 说明：
--   - 超管判定：用户在某系统内拥有有效角色、且该角色授予 platform:admin 权限
--     （平台系统内）；后端据此做跨系统旁路，不再读取配置文件；
--   - 系统接入（/api/systems/**）仅超管可调用；接入时必须录入系统管理员账号，
--     服务端自动创建默认 admin 角色（6 个短权限码）并授权；
--   - 历史文件 01/02/03 不修改，本文件为增量迁移；已有库手动执行本文件一次。
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 1) 删除用户-系统开通表（用户可访问系统由有效角色推导） ----------------
DROP TABLE IF EXISTS sys_user_system;

-- 2) 删除权限中心自身系统 rbac（V3 自接入） -----------------------------
DELETE FROM sys_role_permission WHERE system_id = 3;
DELETE FROM sys_user_role       WHERE system_id = 3;
DELETE FROM sys_role            WHERE system_id = 3;
DELETE FROM sys_permission      WHERE system_id = 3;
DELETE FROM sys_system          WHERE id = 3 OR code = 'rbac';

-- 3) 系统表增加：平台标记 + 系统管理员信息 ------------------------------
ALTER TABLE sys_system
  ADD COLUMN is_platform TINYINT NOT NULL DEFAULT 0 COMMENT '1=权限中心平台系统（不参与业务系统选择）',
  ADD COLUMN admin_external_user_id VARCHAR(255) DEFAULT NULL COMMENT '系统管理员外部账号',
  ADD COLUMN admin_name VARCHAR(100) DEFAULT NULL COMMENT '系统管理员姓名';

-- 4) 平台系统 + 超管账号（数据驱动，不依赖配置文件） --------------------
INSERT INTO sys_system (id, code, name, region, status, sort, is_platform) VALUES
(4, 'platform', '权限中心平台', 'region_a', 1, 0, 1);

INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(90, 4, 0, 2, 'platform:admin', '平台超级管理员', NULL, NULL, 1, 1, 0, NULL);

INSERT INTO sys_role (id, system_id, code, name, status, sort, remark) VALUES
(90, 4, 'super-admin', '平台超级管理员', 1, 1, '系统接入 + 跨系统角色权限管理');

INSERT INTO sys_role_permission
(id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(90, 4, 90, 90, NULL, NULL, 1, '平台超管权限', NULL, 1);

INSERT INTO sys_user_role
(id, system_id, external_user_id, role_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(52, 4, 'superadmin', 90, NULL, NULL, 1, '默认平台超管（mock 账号）', NULL, 1);

-- 5) 业务系统登记系统管理员信息 ----------------------------------------
UPDATE sys_system SET admin_external_user_id = 'crm-admin-001', admin_name = 'CRM 系统管理员' WHERE code = 'crm';
UPDATE sys_system SET admin_external_user_id = 'erp-admin-001', admin_name = 'ERP 系统管理员' WHERE code = 'erp';
UPDATE sys_system SET admin_external_user_id = 'superadmin',   admin_name = '平台超级管理员' WHERE code = 'platform';

-- 6) 各系统内管理权限码（短码，系统内唯一） -----------------------------
-- CRM（system_id = 1）
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(50, 1, 0, 2, 'role:manage',       '角色管理',     NULL, NULL, 80, 1, 0, NULL),
(51, 1, 0, 2, 'permission:manage', '权限管理',     NULL, NULL, 81, 1, 0, NULL),
(52, 1, 0, 2, 'resource:manage',   '资源管理',     NULL, NULL, 82, 1, 0, NULL),
(53, 1, 0, 2, 'page:manage',       '页面元素管理', NULL, NULL, 83, 1, 0, NULL),
(54, 1, 0, 2, 'user:manage',       '用户授权管理', NULL, NULL, 84, 1, 0, NULL),
(55, 1, 0, 2, 'audit:view',        '授权记录查看', NULL, NULL, 85, 1, 0, NULL);

-- ERP（system_id = 2）
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(60, 2, 0, 2, 'role:manage',       '角色管理',     NULL, NULL, 80, 1, 0, NULL),
(61, 2, 0, 2, 'permission:manage', '权限管理',     NULL, NULL, 81, 1, 0, NULL),
(62, 2, 0, 2, 'resource:manage',   '资源管理',     NULL, NULL, 82, 1, 0, NULL),
(63, 2, 0, 2, 'page:manage',       '页面元素管理', NULL, NULL, 83, 1, 0, NULL),
(64, 2, 0, 2, 'user:manage',       '用户授权管理', NULL, NULL, 84, 1, 0, NULL),
(65, 2, 0, 2, 'audit:view',        '授权记录查看', NULL, NULL, 85, 1, 0, NULL);

-- 7) 授予各系统 admin 角色（CRM role 1 / ERP role 3） -------------------
INSERT INTO sys_role_permission
(id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(60, 1, 1, 50, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(61, 1, 1, 51, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(62, 1, 1, 52, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(63, 1, 1, 53, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(64, 1, 1, 54, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(65, 1, 1, 55, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(66, 2, 3, 60, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(67, 2, 3, 61, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(68, 2, 3, 62, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(69, 2, 3, 63, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(70, 2, 3, 64, NULL, NULL, 1, '系统管理员权限', NULL, 1),
(71, 2, 3, 65, NULL, NULL, 1, '系统管理员权限', NULL, 1);

-- 8) 演示系统管理员账号（只管理各自系统；不跨系统） --------------------
INSERT INTO sys_user_role
(id, system_id, external_user_id, role_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(50, 1, 'crm-admin-001', 1, NULL, NULL, 1, 'CRM 系统管理员（演示）', NULL, 1),
(51, 2, 'erp-admin-001', 3, NULL, NULL, 1, 'ERP 系统管理员（演示）', NULL, 1);

-- 9) 自增起点（显式 id 不冲突，保持后续空间） ---------------------------
ALTER TABLE sys_system          AUTO_INCREMENT = 100;
ALTER TABLE sys_role            AUTO_INCREMENT = 100;
ALTER TABLE sys_permission      AUTO_INCREMENT = 200;
ALTER TABLE sys_user_role       AUTO_INCREMENT = 100;
ALTER TABLE sys_role_permission AUTO_INCREMENT = 100;
