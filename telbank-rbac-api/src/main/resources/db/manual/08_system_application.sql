-- =====================================================================
-- 08_system_application.sql
-- 系统接入申请（A16）：SDK 自动上报 + 平台超管审核
--
-- 流程：
--   业务系统启动后，SDK 用 onboarding AppKey 签名上报
--   POST /openapi/onboarding/systems {systemCode,name,region,sdkVersion,remark}
--   → 写入本表 status=0（待审核），不直接创建 sys_system；
--   平台超管在「系统接入 → 接入申请」审核：
--     approve → 调用现有系统接入逻辑创建 sys_system/admin 角色/管理员授权，
--               回填 system_id、status=1、reviewed_*；
--     reject  → status=2，记录 reject_reason。
--
-- 状态：0=待审核(PENDING) 1=已通过(APPROVED) 2=已驳回(REJECTED)
-- system_code 唯一：同一系统只保留一条申请记录；驳回后 SDK 可重新上报覆盖为待审核。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_system_application (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_code    VARCHAR(64)     NOT NULL COMMENT '业务系统编码（全局唯一申请）',
    name           VARCHAR(100)    NOT NULL COMMENT '业务系统名称',
    region         VARCHAR(32)     NOT NULL COMMENT '区域',
    source         VARCHAR(16)     NOT NULL DEFAULT 'SDK' COMMENT '来源：SDK/MANUAL',
    app_key        VARCHAR(64)     NULL COMMENT '上报所用 onboarding AppKey',
    sdk_version    VARCHAR(32)     NULL COMMENT 'SDK 版本',
    remark         VARCHAR(255)    NULL COMMENT 'SDK 上报备注',
    status         TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审核 1已通过 2已驳回',
    reject_reason  VARCHAR(255)    NULL COMMENT '驳回原因',
    reviewed_by    VARCHAR(255)    NULL COMMENT '审核人 external_user_id',
    reviewed_at    DATETIME        NULL COMMENT '审核时间',
    system_id      BIGINT UNSIGNED NULL COMMENT '通过后创建的业务系统 id',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_application_system_code (system_code),
    KEY idx_application_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统接入申请（SDK 上报待审核）';

-- 演示一条 SDK 待审核申请（不影响业务系统列表；可在「接入申请」页审核/驳回）
INSERT INTO sys_system_application
(system_code, name, region, source, app_key, sdk_version, remark, status)
VALUES
('finance', '财务系统', 'region_a', 'SDK', 'onboarding-app', '1.0.0', 'SDK 自动上报示例，等待平台超管审核', 0);
