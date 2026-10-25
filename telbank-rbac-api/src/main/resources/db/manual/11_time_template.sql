-- =====================================================================
-- 11_time_template.sql
-- 角色时间模板（A21）：时间语义从「权限 / 角色-权限」上移到「用户-角色」
--
--   用户 --(用户角色，带绝对生效窗口)--> 角色 --(角色权限，永久)--> 权限
--
-- 1. 新增全局时间模板表 sys_time_template（不按系统隔离）
-- 2. sys_role 增加 time_template_id（可空；NULL 表示永久角色，不可自助申请）
-- 3. sys_access_application_role 增加模板快照与审批时逐角色窗口
-- 4. sys_role_permission 去掉逐项时效（角色-权限永久有效，授权/撤销即生效）
-- 5. 预置 2 个演示模板（不自动绑定角色）
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 全局时间模板
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_time_template (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL COMMENT '模板名，全局唯一',
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区',
  start_day_offset INT NOT NULL DEFAULT 0 COMMENT '相对锚点日期的开始天数偏移（0–3650）',
  start_time TIME NOT NULL COMMENT '本地开始时间 HH:mm',
  end_day_offset INT NOT NULL DEFAULT 0 COMMENT '相对锚点日期的结束天数偏移（0–3650）',
  end_time TIME NOT NULL COMMENT '本地结束时间 HH:mm',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(255) NULL COMMENT '说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_time_template_name (name),
  KEY idx_time_template_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='角色时间模板（全局）';

-- ---------------------------------------------------------------------
-- 2. 角色绑定时间模板（可空 = 永久角色）
-- ---------------------------------------------------------------------
ALTER TABLE sys_role
  ADD COLUMN time_template_id BIGINT UNSIGNED NULL COMMENT '绑定的时间模板 id；NULL=永久角色' AFTER remark,
  ADD KEY idx_role_time_template (time_template_id);

-- ---------------------------------------------------------------------
-- 3. 申请单角色快照：创建时记录模板，审批时记录逐角色窗口（UTC）
-- ---------------------------------------------------------------------
ALTER TABLE sys_access_application_role
  ADD COLUMN time_template_id BIGINT UNSIGNED NULL COMMENT '创建申请时的模板 id 快照',
  ADD COLUMN time_template_name VARCHAR(100) NULL COMMENT '创建申请时的模板名快照',
  ADD COLUMN approved_valid_from DATETIME NULL COMMENT '审批通过时按模板计算的生效时间（UTC）',
  ADD COLUMN approved_valid_until DATETIME NULL COMMENT '审批通过时按模板计算的失效时间（UTC）';

-- ---------------------------------------------------------------------
-- 4. 角色-权限不再带时效：先删索引再删列
-- ---------------------------------------------------------------------
ALTER TABLE sys_role_permission DROP INDEX idx_valid_until;

ALTER TABLE sys_role_permission
  DROP COLUMN valid_from,
  DROP COLUMN valid_until;

-- ---------------------------------------------------------------------
-- 5. 演示模板（不绑定角色）
-- ---------------------------------------------------------------------
INSERT INTO sys_time_template
  (id, name, timezone, start_day_offset, start_time, end_day_offset, end_time, status, remark)
VALUES
  (1, '夜间窗口', 'Asia/Shanghai', 0, '19:00:00', 1, '07:00:00', 1,
   '审批后当天 19:00 至次日 07:00'),
  (2, '全天窗口', 'Asia/Shanghai', 0, '00:00:00', 0, '23:59:00', 1,
   '审批后当天全天生效')
ON DUPLICATE KEY UPDATE name = VALUES(name);

ALTER TABLE sys_time_template AUTO_INCREMENT = 100;
