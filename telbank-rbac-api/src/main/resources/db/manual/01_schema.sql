-- =====================================================================
-- telbank-rbac 权限管理系统 · V1 基础表结构
-- 需求基线：docs/telbank-rbac.md §4
-- 偏差：第 8 张表 sys_sdk_endpoint 为「SDK 端点上报列表」落地所需
--        （需求 §5.7 功能要求 vs §4.1 共 7 张表，见 docs/impl/contracts.md §9.2）
-- 数据库：MySQL 8.0+ · utf8mb4 · utf8mb4_0900_ai_ci · 时间统一 UTC
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------------------------------------------------------------------
-- 1. sys_system 业务系统
-- ---------------------------------------------------------------------
CREATE TABLE sys_system (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)     NOT NULL COMMENT '系统编码，全局唯一',
    name        VARCHAR(100)    NOT NULL COMMENT '系统名称',
    region      VARCHAR(32)     NOT NULL COMMENT '所属区域，决定 Redis 通知频道',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    sort        INT             NOT NULL DEFAULT 0,
    remark      VARCHAR(255)             DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_code (code),
    KEY idx_system_region (region)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务系统';

-- ---------------------------------------------------------------------
-- 2. sys_role 角色（系统内唯一）
-- ---------------------------------------------------------------------
CREATE TABLE sys_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id   BIGINT UNSIGNED NOT NULL,
    code        VARCHAR(64)     NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    status      TINYINT         NOT NULL DEFAULT 1,
    sort        INT             NOT NULL DEFAULT 0,
    remark      VARCHAR(255)             DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_system_code (system_id, code),
    KEY idx_role_system (system_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色';

-- ---------------------------------------------------------------------
-- 3. sys_permission 权限码统一资源表
--    method / api_pattern 非空才进入 SDK 映射表（接口拦截），否则仅前端展示控制
-- ---------------------------------------------------------------------
CREATE TABLE sys_permission (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id   BIGINT UNSIGNED NOT NULL,
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示根节点',
    type        TINYINT         NOT NULL COMMENT '1目录/页面级 2操作级 3纯接口',
    code        VARCHAR(128)    NOT NULL COMMENT '权限码，系统内唯一，如 order:delete',
    name        VARCHAR(100)    NOT NULL,
    method      VARCHAR(10)              DEFAULT NULL COMMENT 'HTTP 方法，可空',
    api_pattern VARCHAR(255)             DEFAULT NULL COMMENT 'Ant 风格接口模式，可空',
    sort        INT             NOT NULL DEFAULT 0,
    status      TINYINT         NOT NULL DEFAULT 1,
    is_public   TINYINT         NOT NULL DEFAULT 0 COMMENT '1 所有登录用户默认拥有',
    meta        JSON                     DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_system_code (system_id, code),
    KEY idx_perm_system_parent (system_id, parent_id),
    KEY idx_perm_api (system_id, method, api_pattern)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限码资源';

-- ---------------------------------------------------------------------
-- 4. sys_user_role 外部用户-角色关联（含时效）
-- ---------------------------------------------------------------------
CREATE TABLE sys_user_role (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id        BIGINT UNSIGNED NOT NULL,
    external_user_id VARCHAR(255)    NOT NULL,
    role_id          BIGINT UNSIGNED NOT NULL,
    valid_from       DATETIME                 DEFAULT NULL COMMENT 'NULL 立即生效',
    valid_until      DATETIME                 DEFAULT NULL COMMENT 'NULL 永久有效',
    auto_revoke      TINYINT         NOT NULL DEFAULT 1 COMMENT '1 到期由定时任务自动收回',
    grant_reason     VARCHAR(255)             DEFAULT NULL,
    source_approval_id VARCHAR(128)           DEFAULT NULL COMMENT '审批单号，用于幂等',
    status           TINYINT         NOT NULL DEFAULT 1,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (system_id, external_user_id, role_id),
    KEY idx_user (system_id, external_user_id),
    KEY idx_role (role_id),
    KEY idx_valid_until (valid_until, auto_revoke, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-角色授权';

-- ---------------------------------------------------------------------
-- 5. sys_role_permission 角色-权限关联（含时效）
-- ---------------------------------------------------------------------
CREATE TABLE sys_role_permission (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id        BIGINT UNSIGNED NOT NULL,
    role_id          BIGINT UNSIGNED NOT NULL,
    permission_id    BIGINT UNSIGNED NOT NULL,
    valid_from       DATETIME                 DEFAULT NULL,
    valid_until      DATETIME                 DEFAULT NULL,
    auto_revoke      TINYINT         NOT NULL DEFAULT 1,
    grant_reason     VARCHAR(255)             DEFAULT NULL,
    source_approval_id VARCHAR(128)           DEFAULT NULL,
    status           TINYINT         NOT NULL DEFAULT 1,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, permission_id),
    KEY idx_role_system (role_id, system_id),
    KEY idx_perm (permission_id),
    KEY idx_valid_until (valid_until, auto_revoke, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色-权限授权';

-- ---------------------------------------------------------------------
-- 6. sys_user_system 外部用户可访问系统
-- ---------------------------------------------------------------------
CREATE TABLE sys_user_system (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id        BIGINT UNSIGNED NOT NULL,
    external_user_id VARCHAR(255)    NOT NULL,
    status           TINYINT         NOT NULL DEFAULT 1,
    is_default       TINYINT         NOT NULL DEFAULT 0,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_system (system_id, external_user_id),
    KEY idx_user_system_user (external_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户可访问系统';

-- ---------------------------------------------------------------------
-- 7. sys_page_action 页面操作元数据（模式 B）
--    action: openDialog / callApi / download / navigate / confirmThenCallApi
-- ---------------------------------------------------------------------
CREATE TABLE sys_page_action (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id   BIGINT UNSIGNED NOT NULL,
    page_code   VARCHAR(128)    NOT NULL,
    code        VARCHAR(128)    NOT NULL,
    text        VARCHAR(64)     NOT NULL,
    type        VARCHAR(32)     NOT NULL DEFAULT 'default',
    action      VARCHAR(32)     NOT NULL,
    config      JSON                     DEFAULT NULL,
    sort        INT             NOT NULL DEFAULT 0,
    status      TINYINT         NOT NULL DEFAULT 1,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action (system_id, page_code, code),
    KEY idx_action_page (system_id, page_code, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '页面操作元数据';

-- ---------------------------------------------------------------------
-- 8. sys_sdk_endpoint SDK 端点上报（契约 §9.2：需求 §5.7 功能落地所需）
-- ---------------------------------------------------------------------
CREATE TABLE sys_sdk_endpoint (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id      BIGINT UNSIGNED NOT NULL,
    method         VARCHAR(10)     NOT NULL,
    path           VARCHAR(255)    NOT NULL,
    candidate_code VARCHAR(128)             DEFAULT NULL COMMENT 'SDK 生成的候选权限码',
    source         VARCHAR(32)     NOT NULL DEFAULT 'GENERATED' COMMENT 'GENERATED / ANNOTATION',
    permission_id  BIGINT UNSIGNED          DEFAULT NULL COMMENT '已关联的权限码',
    reported_at    DATETIME                 DEFAULT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_endpoint (system_id, method, path),
    KEY idx_endpoint_permission (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'SDK 上报端点';
