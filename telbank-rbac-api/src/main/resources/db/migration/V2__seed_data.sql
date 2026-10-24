-- =====================================================================
-- telbank-rbac 权限管理系统 · V2 示例数据
-- 覆盖：CRM(region_a) / ERP(region_b) 两系统、同名角色编码、永久与临时授权、
--       公开权限码、页面操作元数据、SDK 端点上报、审批来源授权
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 业务系统 ------------------------------------------------------------
INSERT INTO sys_system (id, code, name, region, status, sort, remark) VALUES
(1, 'crm', 'CRM系统', 'region_a', 1, 10, '客户关系管理系统（示例）'),
(2, 'erp', 'ERP系统', 'region_b', 1, 20, '企业资源计划系统（示例）');

-- 角色（CRM 与 ERP 可同名 admin，互不影响） --------------------------
INSERT INTO sys_role (id, system_id, code, name, status, sort, remark) VALUES
(1, 1, 'admin',    'CRM管理员',  1, 10, 'CRM 全部权限'),
(2, 1, 'operator', 'CRM运营',    1, 20, '订单查看与导出'),
(3, 2, 'admin',    'ERP管理员',  1, 10, 'ERP 全部权限'),
(4, 2, 'purchaser','ERP采购员',  1, 20, '采购单相关');

-- 权限码 --------------------------------------------------------------
-- CRM：一个业务权限码前后端共用；method/api_pattern 非空才做接口拦截
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(1, 1, 0, 1, 'order:menu',        '订单管理',     NULL,     NULL,                10, 1, 0, NULL),
(2, 1, 1, 1, 'order:list',        '订单列表',     'GET',    '/api/order/**',     10, 1, 0, '{"icon":"ShoppingCart"}'),
(3, 1, 1, 1, 'order:detail',      '订单详情',     NULL,     NULL,                20, 1, 0, NULL),
(4, 1, 1, 2, 'order:delete',      '删除订单',     'DELETE', '/api/order/{id}',   10, 1, 0, NULL),
(5, 1, 1, 2, 'order:approve',     '审批订单',     NULL,     NULL,                20, 1, 0, NULL),
(6, 1, 1, 2, 'order:view:amount', '查看订单金额', NULL,     NULL,                30, 1, 0, NULL),
(7, 1, 1, 2, 'order:export',      '导出订单',     'POST',   '/api/order/export', 40, 1, 0, NULL),
(8, 1, 0, 1, 'common:profile',    '个人信息',     NULL,     NULL,                90, 1, 1, '{"public":true}');

-- ERP：同码 order:list / order:delete 与 CRM 重复，但属不同系统，互不影响
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(9,  2, 0, 1, 'order:menu',      '订单管理',   NULL,     NULL,            10, 1, 0, NULL),
(10, 2, 9, 1, 'order:list',      '订单列表',   'GET',    '/api/order/**', 10, 1, 0, NULL),
(11, 2, 9, 2, 'order:delete',    '删除订单',   'DELETE', '/api/order/{id}', 10, 1, 0, NULL),
(12, 2, 0, 2, 'purchase:create', '创建采购单', 'POST',   '/api/purchase/**', 20, 1, 0, NULL);

-- 管理端自身权限码（契约 §3 前置表：@perm.has(...) 使用；各系统内独立授权）
INSERT INTO sys_permission (id, system_id, parent_id, type, code, name, method, api_pattern, sort, status, is_public, meta) VALUES
(20, 1, 0, 2, 'rbac:system:manage',     '系统与用户系统管理', NULL, NULL, 80, 1, 0, NULL),
(21, 1, 0, 2, 'rbac:role:manage',       '角色与授权管理',     NULL, NULL, 81, 1, 0, NULL),
(22, 1, 0, 2, 'rbac:permission:manage', '权限码与端点管理',   NULL, NULL, 82, 1, 0, NULL),
(23, 1, 0, 2, 'rbac:page:manage',       '页面操作管理',       NULL, NULL, 83, 1, 0, NULL),
(24, 1, 0, 2, 'rbac:audit:view',        '审批与到期查询',     NULL, NULL, 84, 1, 0, NULL),
(30, 2, 0, 2, 'rbac:system:manage',     '系统与用户系统管理', NULL, NULL, 80, 1, 0, NULL),
(31, 2, 0, 2, 'rbac:role:manage',       '角色与授权管理',     NULL, NULL, 81, 1, 0, NULL),
(32, 2, 0, 2, 'rbac:permission:manage', '权限码与端点管理',   NULL, NULL, 82, 1, 0, NULL),
(33, 2, 0, 2, 'rbac:page:manage',       '页面操作管理',       NULL, NULL, 83, 1, 0, NULL),
(34, 2, 0, 2, 'rbac:audit:view',        '审批与到期查询',     NULL, NULL, 84, 1, 0, NULL);

-- 角色-权限授权 -------------------------------------------------------
-- 永久授权
INSERT INTO sys_role_permission (id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(1, 1, 1, 1, NULL, NULL, 1, NULL, NULL, 1),
(2, 1, 1, 2, NULL, NULL, 1, NULL, NULL, 1),
(3, 1, 1, 3, NULL, NULL, 1, NULL, NULL, 1),
(4, 1, 1, 4, NULL, NULL, 1, NULL, NULL, 1),
(5, 1, 1, 5, NULL, NULL, 1, NULL, NULL, 1),
(6, 1, 1, 6, NULL, NULL, 1, NULL, NULL, 1),
(7, 1, 1, 7, NULL, NULL, 1, NULL, NULL, 1),
(8, 1, 2, 1, NULL, NULL, 1, NULL, NULL, 1),
(9, 2, 3, 9, NULL, NULL, 1, NULL, NULL, 1),
(10, 2, 3, 10, NULL, NULL, 1, NULL, NULL, 1),
(11, 2, 3, 11, NULL, NULL, 1, NULL, NULL, 1),
(12, 2, 3, 12, NULL, NULL, 1, NULL, NULL, 1),
(13, 2, 4, 9, NULL, NULL, 1, NULL, NULL, 1),
(14, 2, 4, 10, NULL, NULL, 1, NULL, NULL, 1);

-- 管理端权限码授给各系统 admin 角色（CRM role 1 / ERP role 3）
INSERT INTO sys_role_permission (id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(20, 1, 1, 20, NULL, NULL, 1, NULL, NULL, 1),
(21, 1, 1, 21, NULL, NULL, 1, NULL, NULL, 1),
(22, 1, 1, 22, NULL, NULL, 1, NULL, NULL, 1),
(23, 1, 1, 23, NULL, NULL, 1, NULL, NULL, 1),
(24, 1, 1, 24, NULL, NULL, 1, NULL, NULL, 1),
(25, 2, 3, 30, NULL, NULL, 1, NULL, NULL, 1),
(26, 2, 3, 31, NULL, NULL, 1, NULL, NULL, 1),
(27, 2, 3, 32, NULL, NULL, 1, NULL, NULL, 1),
(28, 2, 3, 33, NULL, NULL, 1, NULL, NULL, 1),
(29, 2, 3, 34, NULL, NULL, 1, NULL, NULL, 1);

-- 临时授权（审批来源，到期自动收回）
INSERT INTO sys_role_permission (id, system_id, role_id, permission_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(15, 1, 2, 4, '2026-10-24 00:00:00', '2027-06-30 23:59:59', 1, '季度末批量清理订单', 'APV-20261024-001', 1),
(16, 1, 2, 7, '2026-10-24 00:00:00', '2027-06-30 23:59:59', 1, '季度末批量导出',     'APV-20261024-002', 1),
(17, 1, 2, 5, '2025-01-01 00:00:00', '2025-12-31 23:59:59', 1, '已过期审批（用于验证自动收回）', 'APV-20250101-001', 1);

-- 用户-角色授权（external_user_id 来自外部认证系统） -----------------
INSERT INTO sys_user_role (id, system_id, external_user_id, role_id, valid_from, valid_until, auto_revoke, grant_reason, source_approval_id, status) VALUES
(1, 1, '13800138000', 1, NULL, NULL, 1, '初始管理员', NULL, 1),
(2, 1, '13900139000', 2, '2026-10-24 00:00:00', '2027-06-30 23:59:59', 1, '临时运营权限', 'APV-20260901-001', 1),
(3, 2, '13800138000', 3, NULL, NULL, 1, 'ERP 管理员', NULL, 1),
(4, 1, '13700137000', 2, '2025-01-01 00:00:00', '2025-12-31 23:59:59', 1, '已过期授权（用于验证自动收回）', 'APV-20250101-002', 1),
-- 跨系统隔离验收用户：CRM 有 operator（含临时 order:delete），ERP 只有 purchaser（无 order:delete）
(10, 1, '13600136000', 2, NULL, NULL, 1, 'CRM 运营', NULL, 1),
(11, 2, '13600136000', 4, NULL, NULL, 1, 'ERP 采购员', NULL, 1);

-- 用户可访问系统 ------------------------------------------------------
INSERT INTO sys_user_system (id, system_id, external_user_id, status, is_default) VALUES
(1, 1, '13800138000', 1, 1),
(2, 2, '13800138000', 1, 0),
(3, 1, '13900139000', 1, 1),
-- 13900139000 未开通 ERP：用于验收跨系统 403
(10, 1, '13600136000', 1, 1),
(11, 2, '13600136000', 1, 0);

-- 页面操作元数据（模式 B：标准 CRUD 操作区） --------------------------
INSERT INTO sys_page_action (id, system_id, page_code, code, text, type, action, config, sort, status) VALUES
(1, 1, 'order:list', 'order:create',   '新增订单', 'primary', 'openDialog',         '{"dialog":"OrderCreateDialog","width":720}', 10, 1),
(2, 1, 'order:list', 'order:export',   '导出',     'default', 'callApi',            '{"api":"POST /api/order/export"}', 20, 1),
(3, 1, 'order:list', 'order:download', '下载模板', 'default', 'download',           '{"url":"/api/order/template"}', 30, 1),
(4, 1, 'order:list', 'order:detail',   '查看详情', 'link',    'navigate',           '{"path":"/order/detail"}', 40, 1),
(5, 1, 'order:list', 'order:delete',   '删除',     'danger',  'confirmThenCallApi', '{"confirm":"确认删除该订单？","api":"DELETE /api/order/{id}"}', 50, 1),
(6, 2, 'purchase:list', 'purchase:create', '新建采购单', 'primary', 'openDialog', '{"dialog":"PurchaseCreateDialog"}', 10, 1);

-- SDK 端点上报（示例：启动上报后由管理员关联权限码） ------------------
INSERT INTO sys_sdk_endpoint (id, system_id, method, path, candidate_code, source, permission_id, reported_at) VALUES
(1, 1, 'GET',    '/api/order/list',    'crm:order:list',    'GENERATED', 2, '2026-10-20 10:00:00'),
(2, 1, 'DELETE', '/api/order/{id}',    'crm:order:delete',  'GENERATED', 4, '2026-10-20 10:00:00'),
(3, 1, 'POST',   '/api/order/export',  NULL,                'GENERATED', NULL, '2026-10-20 10:00:00'),
(4, 1, 'GET',    '/api/common/profile',NULL,                'ANNOTATION', NULL, '2026-10-20 10:00:00'),
(5, 2, 'GET',    '/api/order/list',    'erp:order:list',    'GENERATED', 10, '2026-10-20 10:00:00');

-- 自增起点（示例数据占用后避免冲突）
ALTER TABLE sys_system AUTO_INCREMENT = 100;
ALTER TABLE sys_role AUTO_INCREMENT = 100;
ALTER TABLE sys_permission AUTO_INCREMENT = 100;
ALTER TABLE sys_user_role AUTO_INCREMENT = 100;
ALTER TABLE sys_role_permission AUTO_INCREMENT = 100;
ALTER TABLE sys_user_system AUTO_INCREMENT = 100;
ALTER TABLE sys_page_action AUTO_INCREMENT = 100;
ALTER TABLE sys_sdk_endpoint AUTO_INCREMENT = 100;
