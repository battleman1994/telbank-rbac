-- =====================================================================
-- 10_access_application.sql
-- 业务用户临时权限申请（只保存申请与审批结果，不实现审批流）
--
-- 只新增两张申请表，不修改既有权限表结构，不新增用户级权限表：
--   sys_access_application       申请单（主表）
--   sys_access_application_role  申请单角色快照
--
-- 状态：0=待审批(PENDING) 1=已通过(APPROVED) 2=已驳回(REJECTED)
-- pending_user_key 为 STORED 生成列：待审批时 = CONCAT(system_id, ':', external_user_id)，
-- 审批结束后为 NULL，配合唯一键保证「同系统同申请人最多一条待审批」，并自动释放约束。
-- =====================================================================

SET NAMES utf8mb4;

-- 业务用户临时权限申请单（权限中心内只存申请与结果，不实现审批流）
CREATE TABLE IF NOT EXISTS sys_access_application (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_no VARCHAR(40) NOT NULL COMMENT '申请单号，如 RA-20261025-A1B2C3',
  system_id BIGINT UNSIGNED NOT NULL COMMENT '业务系统 id',
  external_user_id VARCHAR(255) NOT NULL COMMENT '申请人（JWT sub）',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 待审批 / 1 已通过 / 2 已驳回',
  reason VARCHAR(200) NOT NULL COMMENT '申请原因',
  approved_valid_from DATETIME NULL COMMENT '审批通过后的统一生效时间（UTC）',
  approved_valid_until DATETIME NULL COMMENT '审批通过后的统一失效时间（UTC）',
  decision_comment VARCHAR(255) NULL COMMENT '审批意见',
  reviewed_by VARCHAR(128) NULL COMMENT '审批方标识',
  reviewed_at DATETIME NULL COMMENT '审批时间（UTC）',
  pending_user_key VARCHAR(300) GENERATED ALWAYS AS (
    CASE WHEN status = 0 THEN CONCAT(system_id, ':', external_user_id) ELSE NULL END
  ) STORED COMMENT '待审批唯一键：同系统同申请人最多一条 PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_access_application_no (application_no),
  UNIQUE KEY uk_access_application_pending (pending_user_key),
  KEY idx_access_application_user (system_id, external_user_id, created_at),
  KEY idx_access_application_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='业务用户临时权限申请单';

CREATE TABLE IF NOT EXISTS sys_access_application_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  role_code VARCHAR(64) NOT NULL COMMENT '创建时快照',
  role_name VARCHAR(100) NOT NULL COMMENT '创建时快照',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_access_application_role (application_id, role_id),
  KEY idx_access_application_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='申请单角色快照';
