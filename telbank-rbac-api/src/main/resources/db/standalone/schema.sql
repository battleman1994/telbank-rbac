-- ============================================================================
-- TelBank RBAC — standalone（H2 内存数据库）初始化 schema
--
-- 来源：由 db/manual/01~11_*.sql 依次在 MySQL 8 执行后的最终结构
--       （mysqldump --no-data，库 telbank_rbac_h2seed）等价转换而来，
--       共 11 张表，表结构/字段/约束与 MySQL 最终态尽量保持 1:1。
-- 用途：仅用于 standalone 模式（H2 内存库）启动时由 Spring spring.sql.init 执行；
--       MySQL 部署仍以 db/manual/*.sql 为权威 schema，请勿用本文件替代。
-- 环境：H2 2.4.240，URL 固定为
--       jdbc:h2:mem:<name>;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
--
-- 转换差异（均为 H2 MySQL 模式下的最小化调整，不改变逻辑结构）：
--   1. 去掉 MySQL 专用表选项 ENGINE / DEFAULT CHARSET / COLLATE，
--      以及 AUTO_INCREMENT=N 自增起始值（行数据由 data.sql 显式插入）。
--   2. 生成列去掉 STORED 关键字（H2 不支持），保留 GENERATED ALWAYS AS (...)。
--   3. 生成列表达式内的 _utf8mb4 字符集引导符去掉（H2 字符串一律 Unicode）。
--   4. 保留表/列 COMMENT、UNSIGNED、行内 UNIQUE KEY / KEY（H2 MySQL 模式均支持）。
--   5. H2 的 JSON 列语义与 MySQL 不同：MySQL 的 JSON 列与 String 互转时按 JSON 文档
--      解析/规范化；而 H2 的 JSON 类型把传入的 String 存为“JSON 字符串”，读回带
--      引号与反斜杠转义，会让实体里按 String 映射的 config/meta 解析结果从对象退化为
--      字符串。因此下面 sys_page_action.config、sys_permission.meta 两列在 standalone
--      下声明为 varchar（H2 中不限长度），保持“Java String 原样存取”的 MySQL 可观测行为；
--      其余 9 张表均无该问题，列类型与 MySQL 最终态一致。
--   6. H2 的索引名是 schema 全局唯一的（MySQL 是表内唯一）；sys_role 与
--      sys_role_permission 在 MySQL 中同名索引 idx_role_system，这里将后者改名为
--      idx_role_perm_system，索引列与语义不变。
-- ============================================================================

CREATE TABLE `sys_access_application` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `application_no` varchar(40) NOT NULL COMMENT '申请单号，如 RA-20261025-A1B2C3',
  `system_id` bigint unsigned NOT NULL COMMENT '业务系统 id',
  `external_user_id` varchar(255) NOT NULL COMMENT '申请人（JWT sub）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0 待审批 / 1 已通过 / 2 已驳回',
  `reason` varchar(200) NOT NULL COMMENT '申请原因',
  `approved_valid_from` datetime DEFAULT NULL COMMENT '审批通过后的统一生效时间（UTC）',
  `approved_valid_until` datetime DEFAULT NULL COMMENT '审批通过后的统一失效时间（UTC）',
  `decision_comment` varchar(255) DEFAULT NULL COMMENT '审批意见',
  `reviewed_by` varchar(128) DEFAULT NULL COMMENT '审批方标识',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审批时间（UTC）',
  `pending_user_key` varchar(300) GENERATED ALWAYS AS ((case when (`status` = 0) then concat(`system_id`,':',`external_user_id`) else NULL end)) COMMENT '待审批唯一键：同系统同申请人最多一条 PENDING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_application_no` (`application_no`),
  UNIQUE KEY `uk_access_application_pending` (`pending_user_key`),
  KEY `idx_access_application_user` (`system_id`,`external_user_id`,`created_at`),
  KEY `idx_access_application_status` (`status`,`created_at`)
) COMMENT='业务用户临时权限申请单';

CREATE TABLE `sys_access_application_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `application_id` bigint unsigned NOT NULL,
  `role_id` bigint unsigned NOT NULL,
  `role_code` varchar(64) NOT NULL COMMENT '创建时快照',
  `role_name` varchar(100) NOT NULL COMMENT '创建时快照',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `time_template_id` bigint unsigned DEFAULT NULL COMMENT '创建申请时的模板 id 快照',
  `time_template_name` varchar(100) DEFAULT NULL COMMENT '创建申请时的模板名快照',
  `approved_valid_from` datetime DEFAULT NULL COMMENT '审批通过时按模板计算的生效时间（UTC）',
  `approved_valid_until` datetime DEFAULT NULL COMMENT '审批通过时按模板计算的失效时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_application_role` (`application_id`,`role_id`),
  KEY `idx_access_application_role_role` (`role_id`)
) COMMENT='申请单角色快照';

CREATE TABLE `sys_page_action` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `page_code` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `text` varchar(64) NOT NULL,
  `type` varchar(32) NOT NULL DEFAULT 'default',
  `action` varchar(32) NOT NULL,
  `config` varchar DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action` (`system_id`,`page_code`,`code`),
  KEY `idx_action_page` (`system_id`,`page_code`,`status`)
) COMMENT='页面操作元数据';

CREATE TABLE `sys_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `parent_id` bigint unsigned NOT NULL DEFAULT '0' COMMENT '0 表示根节点',
  `type` tinyint NOT NULL COMMENT '1目录/页面级 2操作级 3纯接口',
  `code` varchar(128) NOT NULL COMMENT '权限码，系统内唯一，如 order:delete',
  `name` varchar(100) NOT NULL,
  `method` varchar(10) DEFAULT NULL COMMENT 'HTTP 方法，可空',
  `api_pattern` varchar(255) DEFAULT NULL COMMENT 'Ant 风格接口模式，可空',
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1',
  `is_public` tinyint NOT NULL DEFAULT '0' COMMENT '1 所有登录用户默认拥有',
  `meta` varchar DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_system_code` (`system_id`,`code`),
  KEY `idx_perm_system_parent` (`system_id`,`parent_id`),
  KEY `idx_perm_api` (`system_id`,`method`,`api_pattern`)
) COMMENT='权限码资源';

CREATE TABLE `sys_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `code` varchar(64) NOT NULL,
  `name` varchar(100) NOT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `sort` int NOT NULL DEFAULT '0',
  `remark` varchar(255) DEFAULT NULL,
  `time_template_id` bigint unsigned DEFAULT NULL COMMENT '绑定的时间模板 id；NULL=永久角色',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_system_code` (`system_id`,`code`),
  KEY `idx_role_system` (`system_id`),
  KEY `idx_role_time_template` (`time_template_id`)
) COMMENT='角色';

CREATE TABLE `sys_role_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `role_id` bigint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  `auto_revoke` tinyint NOT NULL DEFAULT '1',
  `grant_reason` varchar(255) DEFAULT NULL,
  `source_approval_id` varchar(128) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`,`permission_id`),
  KEY `idx_role_perm_system` (`role_id`,`system_id`),
  KEY `idx_perm` (`permission_id`)
) COMMENT='角色-权限授权';

CREATE TABLE `sys_sdk_endpoint` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `method` varchar(10) NOT NULL,
  `path` varchar(255) NOT NULL,
  `candidate_code` varchar(128) DEFAULT NULL COMMENT 'SDK 生成的候选权限码',
  `source` varchar(32) NOT NULL DEFAULT 'GENERATED' COMMENT 'GENERATED / ANNOTATION',
  `permission_id` bigint unsigned DEFAULT NULL COMMENT '已关联的权限码',
  `reported_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_endpoint` (`system_id`,`method`,`path`),
  KEY `idx_endpoint_permission` (`permission_id`)
) COMMENT='SDK 上报端点';

CREATE TABLE `sys_system` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL COMMENT '系统编码，全局唯一',
  `name` varchar(100) NOT NULL COMMENT '系统名称',
  `region` varchar(32) NOT NULL COMMENT '所属区域，决定 Redis 通知频道',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `sort` int NOT NULL DEFAULT '0',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_platform` tinyint NOT NULL DEFAULT '0' COMMENT '1=权限中心平台系统（不参与业务系统选择）',
  `admin_external_user_id` varchar(255) DEFAULT NULL COMMENT '系统管理员外部账号',
  `admin_name` varchar(100) DEFAULT NULL COMMENT '系统管理员姓名',
  `app_key` varchar(64) DEFAULT NULL COMMENT '开放接口 AppKey（全局唯一，业务系统=system code）',
  `app_secret` varchar(128) DEFAULT NULL COMMENT '开放接口 HMAC Secret（生产应加密存储）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_code` (`code`),
  UNIQUE KEY `uk_system_app_key` (`app_key`),
  KEY `idx_system_region` (`region`)
) COMMENT='业务系统';

CREATE TABLE `sys_system_application` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_code` varchar(64) NOT NULL COMMENT '业务系统编码（全局唯一申请）',
  `name` varchar(100) NOT NULL COMMENT '业务系统名称',
  `region` varchar(32) NOT NULL COMMENT '区域',
  `source` varchar(16) NOT NULL DEFAULT 'SDK' COMMENT '来源：SDK/MANUAL',
  `app_key` varchar(64) DEFAULT NULL COMMENT '上报所用 onboarding AppKey',
  `sdk_version` varchar(32) DEFAULT NULL COMMENT 'SDK 版本',
  `remark` varchar(255) DEFAULT NULL COMMENT 'SDK 上报备注',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审核 1已通过 2已驳回',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT '驳回原因',
  `reviewed_by` varchar(255) DEFAULT NULL COMMENT '审核人 external_user_id',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `system_id` bigint unsigned DEFAULT NULL COMMENT '通过后创建的业务系统 id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_application_system_code` (`system_code`),
  KEY `idx_application_status` (`status`)
) COMMENT='系统接入申请（SDK 上报待审核）';

CREATE TABLE `sys_time_template` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '模板名，全局唯一',
  `timezone` varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区',
  `start_day_offset` int NOT NULL DEFAULT '0' COMMENT '相对锚点日期的开始天数偏移（0–3650）',
  `start_time` time NOT NULL COMMENT '本地开始时间 HH:mm',
  `end_day_offset` int NOT NULL DEFAULT '0' COMMENT '相对锚点日期的结束天数偏移（0–3650）',
  `end_time` time NOT NULL COMMENT '本地结束时间 HH:mm',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `remark` varchar(255) DEFAULT NULL COMMENT '说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_time_template_name` (`name`),
  KEY `idx_time_template_status` (`status`)
) COMMENT='角色时间模板（全局）';

CREATE TABLE `sys_user_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `system_id` bigint unsigned NOT NULL,
  `external_user_id` varchar(255) NOT NULL,
  `role_id` bigint unsigned NOT NULL,
  `valid_from` datetime DEFAULT NULL COMMENT 'NULL 立即生效',
  `valid_until` datetime DEFAULT NULL COMMENT 'NULL 永久有效',
  `auto_revoke` tinyint NOT NULL DEFAULT '1' COMMENT '1 到期由定时任务自动收回',
  `grant_reason` varchar(255) DEFAULT NULL,
  `source_approval_id` varchar(128) DEFAULT NULL COMMENT '审批单号，用于幂等',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`system_id`,`external_user_id`,`role_id`),
  KEY `idx_user` (`system_id`,`external_user_id`),
  KEY `idx_role` (`role_id`),
  KEY `idx_valid_until` (`valid_until`,`auto_revoke`,`status`)
) COMMENT='用户-角色授权';
