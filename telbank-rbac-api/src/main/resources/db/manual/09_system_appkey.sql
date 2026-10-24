-- =====================================================================
-- 09_system_appkey.sql
-- 系统接入凭据（A17）：每个业务系统在接入时分配 appKey/appSecret
--
-- 背景：
--   原实现 appKey/appSecret 只存在于 application.yml 的 rbac.clients，
--   系统接入页无法展示，也没有“接入时分配”的动作。
--
-- 变更：
--   sys_system 增加 app_key / app_secret：
--     - app_key：全局唯一，等于系统编码（系统编码本身已全局唯一，便于识别）；
--     - app_secret：HMAC 密钥，新建系统时随机生成；
--     - platform 平台系统不接入开放接口，保持 NULL。
--   现有 demo 系统回填与 yaml 一致的凭据：
--     crm → crm / crm-dev-secret
--     erp → erp / erp-dev-secret
--
-- 说明：
--   - 管理端列表只返回 appKey 与掩码后的 appSecret；
--   - 完整 appSecret 只在创建/审批通过响应中返回一次（或后续“重置密钥”时），
--     当前 demo 为明文存储，生产应改为加密存储/KMS。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE sys_system
    ADD COLUMN app_key    VARCHAR(64)  NULL COMMENT '开放接口 AppKey（全局唯一，业务系统=system code）' AFTER admin_name,
    ADD COLUMN app_secret VARCHAR(128) NULL COMMENT '开放接口 HMAC Secret（生产应加密存储）' AFTER app_key;

UPDATE sys_system
SET app_key = code,
    app_secret = CASE code
        WHEN 'crm' THEN 'crm-dev-secret'
        WHEN 'erp' THEN 'erp-dev-secret'
        ELSE app_secret
    END
WHERE is_platform = 0;

CREATE UNIQUE INDEX uk_system_app_key ON sys_system (app_key);

-- 接入申请审核通过后，新系统会由 SystemService.create 生成 appKey/appSecret。
