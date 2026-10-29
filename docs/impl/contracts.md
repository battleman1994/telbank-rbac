# telbank-rbac 实现契约（内部开发基线）

> 本文件是三个交付工程之间的**接口契约**，与 `docs/telbank-rbac.md`（需求基线）配套使用。
> 需求文档未明确或内部有冲突处，以本文件「§9 偏差与实现口径」为准；实现不得再自行发明协议。
>
> 版本：v3.0（2026-10-25；实现口径 A22 更新于 2026-10-28、A23 更新于 2026-10-29）· 维护：Lead
> 修订：A23 声明式 Feign 接口（取代 A22 的 RestTemplate/RestClient 方案：两版 SDK 开放接口改为 `RbacOpenApiFeignApi`/`RbacOnboardingFeignApi` interface 声明，`RbacSigningClient` 装饰器注入签名头，响应经 Feign Jackson 解码器反序列化为 `RbacEnvelope<T>`；v1 依赖 feign-core/feign-jackson/feign-okhttp 11.10 + OkHttp 3.14.9（Jackson 2），v2 依赖 feign-core/feign-okhttp 11.10 + OkHttp 4.12.0 + 自带 `RbacJacksonEncoder`/`RbacJacksonDecoder`（Jackson 3）；删除 `RbacFeignDecoder`/`RbacResponseDecoder` 与 RestTemplate/RestClient 实现；协议/签名/配置键/公开 API 不变，见 §9）；A22 简化 SDK HTTP 客户端（已被 A23 取代，历史：JDK 8 版 Feign 11 + OkHttp 3 → Spring `RestTemplate` + Jackson 2；JDK 21 版 `RestClient` 改 typed `ParameterizedTypeReference<RbacEnvelope<T>>`；两版业务层只收发 POJO，移除 Feign/OkHttp 依赖与字节级解码器；协议/签名/配置键/公开 API 不变，见 §9）；v3.0 A21 角色时间模板（时间语义上移到「用户-角色」：全局 `sys_time_template`、角色可绑定模板、审批/分配时按模板计算绝对窗口、角色-权限永久；角色授权页去掉逐项时效，用户角色页与「我的申请」去掉日期选择；旧审批 `grant` 模板优先；契约 §3.3/§3.3.1/§3.5/§3.6/§4.3/§5/§5.1/§10 同步）；v2.7 A20 业务用户临时权限申请（申请单/审批 decision 回调/统一连续有效期/我的申请）+ 角色授权 `*` 过滤与非 admin 通配授权后端拒绝；v2.6 A18 接入凭据查看（系统管理员/超管）+ admin 角色授权保护；A19 管理端表格内容优先（auto/wrap、不用省略号）；v2.5 A16 系统接入双通道（SDK onboarding 上报 + 超管审核单条最新状态，手动录入保留）+ A17 系统 appKey/appSecret 分配与展示；v2.4 页面元素管理简化（只维护 pageCode/code/text/type 元素标识，type 改为语义类型 button/link/menu/icon/input，不再维护样式；action/config 仍保留在 API 与 SDK 契约中，页面不再编辑）+ 07 历史类型归一；v2.3 前端移除「到期提醒」菜单/路由/页面（`/api/expiring-grants` 后端接口保留）+ 管理台 100% 视口布局/表格自适应；v2.2 持久层 JPA/Hibernate → MyBatis-Plus 3.5.17（Boot4 starter），REST 契约/表结构不变；v2.1 A 方案通配权限（admin/超管只授一条 `*`，支持 `*`/`前缀:*` 匹配）+ 严格系统隔离（超管也必须按当前 X-System-Code 查询，禁止单请求跨系统）；v2.0 系统隔离模型重构（需求附录 A11）：删除用户-系统开通；系统访问由有效角色推导；数据驱动超管（platform 系统 + platform:admin，不再用配置文件）；管理上下文=X-System-Code 业务系统；管理权限改为系统内短码；系统接入仅超管且接入时录入系统管理员、自动创建默认 admin 角色；菜单 IA 重构。v1.3 移除 Flyway，数据库改为手动 SQL 初始化（需求附录 A10）；v1.2 增加权限中心自身接入（`rbac` 系统 + 管理上下文 + `/api/me/bootstrap`）与 JDK 21/Boot 4 SDK 变体；v1.1 补充实现期口径（SDK 构建工具链 JDK 17+ / `api_pattern` 支持 `{var}` / 新增 `/openapi/sdk/user-systems` / 真实 Redis 与 JDK 8 CI 验证）；v1.0 初版。

---

## 0. 工程坐标与包名

| 模块 | Maven 坐标 / 目录 | 运行时 | Java 包根 |
|---|---|---|---|
| 聚合工程 | `com.telbank:telbank-rbac:1.0.0`（根 `pom.xml`，packaging=pom） | — | — |
| 权限中心后端 | `com.telbank:telbank-rbac-api:1.0.0` → `telbank-rbac-api/` | JDK 21，Spring Boot 4.1.2 | `com.telbank.rbac` |
| SDK（JDK 8 业务系统） | `com.telbank:telbank-rbac-starter:1.0.0` → `telbank-rbac-starter/` | JDK 8 字节码（构建工具链 JDK 17+），Boot 2.7.18 | `com.telbank.rbac.sdk` |
| SDK（JDK 21 业务系统） | `com.telbank:telbank-rbac2-starter:1.0.0` → `telbank-rbac2-starter/` | JDK 21，Spring Boot 4.x（协议与 JDK 8 版一致） | `com.telbank.rbac.sdk` |
| 管理前端 | `telbank-rbac-front/`（npm 工程，非 Maven 模块） | Node 22.22+（nvm），Vite 8 | — |

- 三个 Maven 子模块的 `<parent>` 分别是各自 Spring Boot 的 `spring-boot-starter-parent`，`<relativePath/>` 为空；根 POM 只做 reactor 聚合。**不要修改根 POM 与模块 POM 的依赖版本段**（Lead 维护），需要新依赖先向 Lead 提出。
- 两个 SDK 变体使用**相同包名**（`com.telbank.rbac.sdk`）保证 API 兼容，但**不能同时出现在同一个 classpath**：JDK 8 业务系统用 `telbank-rbac-starter`，JDK 21 + Boot 4 业务系统用 `telbank-rbac2-starter`。
- 源码编码统一 UTF-8；API 工程语言级别 21（record/var/文本块可用），SDK 工程 `--release 8`（禁 Java 9+ 语法/API）。
- **SDK 构建口径（2026-10 调整）**：构建工具链 JDK 17+（推荐 17；JDK 21 亦可），使用 `--release 8` 产出 Java 8 字节码（major=52），运行时目标 JDK 8+；构建/校验入口 `scripts/build-sdk.sh`，CI 兼容性验证见 `.github/workflows/sdk-jdk8-compat.yml`（JDK 17 构建 + JDK 8 runtime 冒烟）。
- SDK 包名固定：`com.telbank.rbac.sdk`，业务系统扫描包示例 `com.telbank.crm`。

---

## 1. 通用约定（api 与 front 之间）

1. **前缀**：权限中心管理端接口统一 `/api/**`；SDK 开放接口统一 `/openapi/**`；审批回调 `/openapi/approval/grant`。
2. **响应信封**：所有 `/api/**` 返回

   ```json
   { "code": 0, "message": "ok", "data": <payload> }
   ```

   失败时 HTTP 状态码非 2xx，body 为 `{"code":<httpStatus>,"message":"<可读信息>","data":null}`。
   `/openapi/**` 使用同样的信封（SDK 只解析 `data`，非 2xx 视为失败）。
3. **分页**：请求 `?page=1&size=20`；`data = {"items":[..],"total":123,"page":1,"size":20}`。
4. **时间**：JSON 一律 ISO-8601 UTC 字符串（如 `2026-10-31T23:59:59Z`）；DB 用 `DATETIME`（UTC 存储，`serverTimezone=UTC`）。
5. **认证（管理端）**：`Authorization: Bearer <JWT>`；外部认证系统签发，`sub` = `external_user_id`。权限中心只校验不签发。
   - **管理端 `/api/**` 必须带 `X-System-Code: <业务系统 code>`**（`/api/me`、`/api/me/systems`、`/api/me/bootstrap` 除外）；服务端把它解析为当前管理上下文系统，并在该系统内校验管理权限码。请求中的 `systemId` 必须与上下文一致；**平台超管也不例外**（需要跨系统时先切换 `X-System-Code`），否则 403。
   - **平台超管由数据驱动**：用户在平台系统（`sys_system.is_platform=1`）内拥有有效角色且该角色授予 `platform:admin` 权限（种子账号 `superadmin`）；不再读取配置文件。超管跳过权限码校验、可切换任意业务系统，但仍要求合法 JWT。
6. **权限断言**：Controller 用 `@PreAuthorize("@perm.has('system:manage')")` 一类写法；`@perm` Bean 名固定 `perm`，方法签名 `boolean has(String... codes)`（单码时返回是否拥有；多码按 AND 语义，实现可扩展 OR 变体 `hasAny`）。
7. **权限码**：系统内唯一；菜单/按钮/接口共用一个码。`is_public=1` 默认所有登录用户拥有。
8. **CORS**：开发环境允许 `http://localhost:5173`（前端 dev server）与 `http://127.0.0.1:5173`；生产按配置。
9. **错误码约定**：401 未认证/验签失败；403 无权限；404 资源不存在；400 参数/语法错误；409 唯一键冲突；500 其他。
10. **演示 JWT（仅本地验收）**：`application-demo.yml` 启用 `rbac.security.jwt.mode=hs256`，用 `scripts/gen-demo-jwt.sh` 生成 HS256 token；生产必须 `mode=jwk`（`jwk-set-uri`）。SDK 不感知 JWT。

---

## 2. 数据库初始化（手动 SQL，无 Flyway）

- 本项目**不使用 Flyway**（用户确认移除，需求附录 A10）。数据库结构/种子数据由 `telbank-rbac-api/src/main/resources/db/manual/` 下的 SQL 维护，按文件名顺序执行：
  - `01_schema.sql`：8 张表（v2.0 经 `04_system_isolation.sql` 删除 `sys_user_system` 后为 **7 张**；`system_id` 隔离以 §9.2 偏差为准）
  - `02_seed_data.sql`：CRM/ERP 示例系统、角色、权限码、演示用户
  - `03_rbac_self_onboarding.sql`：权限中心自接入（`rbac` 系统、`rbac-admin` 角色、演示管理员接入）
- `10_access_application.sql`（A20）：`sys_access_application` / `sys_access_application_role`（申请单与角色快照）
- `11_time_template.sql`（A21）：新增全局 `sys_time_template`（预置「夜间窗口」「全天窗口」两个演示模板，不自动绑定角色）；`sys_role` 增加 `time_template_id`；`sys_access_application_role` 增加模板快照与审批窗口字段；`sys_role_permission` 删除 `valid_from/valid_until` 与 `idx_valid_until`
- 初始化入口：`scripts/init-db.sh`（建库 + 按序导入；库中已有 `sys_system` 表时自动跳过；`--drop` 删库重建）。平台一键脚本（`start-all-macos.sh` / `start-all-linux.sh` / `start-all-windows.ps1`）的 `--temp-db` 会在临时库启动后自动执行。
- `05_wildcard_permissions.sql`（v2.1）：业务系统 admin 角色与平台超管各只授一条 `*` 权限（删除 6 条短码与 `platform:admin`）。
- `09_system_appkey.sql`（A17）：`sys_system` 增加 `app_key/app_secret`，回填 crm/erp 凭据；系统接入时分配。
- `08_system_application.sql`（A16）：`sys_system_application`（system_code 唯一、status 0/1/2 最新状态、reviewed_*/system_id）。
- `07_page_action_semantic_type.sql`（v2.4）：把历史页面元素样式 type（primary/danger/default）归一为语义类型 button/link 等。
- `06_cleanup_admin_redundant_grants.sql`（v2.1 补充）：清理业务系统 `admin` 角色残留的 `order:*` 等冗余业务授权，使角色授权只剩唯一一条 `*`（公共权限 `is_public=1` 不属于角色授权，仍对所有登录用户生效）。
- `04_system_isolation.sql`（v2.0）：删除 `sys_user_system`；`sys_system` 增加 `is_platform/admin_external_user_id/admin_name`；新增平台系统、`super-admin` 角色、`platform:admin` 权限、种子超管 `superadmin`；新增各业务系统短管理权限码与两个系统管理员演示账号。
- 启动前提：数据库与表已经存在（持久层 MyBatis-Plus 不会自动建表）。
- 持久层：MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser` 分页），实体 `@TableName/@TableId`、`BaseMapper` + 注解 SQL，MP `Page` 分页；不使用 JPA/Hibernate/Spring Data Repository。
- SQL 文件由 Lead 维护；**新增变更不要改历史文件**，以 `06_xxx.sql` 递增并在 README/验收文档登记。

## 3. 权限中心管理端 API（api ↔ front）

> `systemId`/`systemCode` 二选一：本文统一用 `systemId`（雪花/自增 id）；前端在系统选择器里持有 id。
> 所有写接口成功后由服务端自动发布 Redis 变更通知（§6），无需前端调用通知接口。

**系统隔离模型（v2.0）**
- 业务系统是隔离边界：系统管理员只能看到/管理自己系统内的角色、权限、资源、页面元素、用户授权；跨系统上下文或 `systemId` → 403。
- 平台超管（数据驱动 `platform:admin`）可切换任意业务系统、跨系统查询，并独占「系统接入」能力。
- 用户可访问哪些系统由**有效角色**（`sys_user_role` status=1 且时效内、角色/系统启用）推导，不再有用户-系统开通表；平台系统 `platform` 不参与业务系统列表。

**权限匹配（A 方案，v2.1）**
- `*`：匹配任意权限码（系统内全部权限）；
- `前缀:*`（如 `order:*`）：匹配以 `前缀:` 开头的权限码；
- 其余精确匹配；多个必需权限按 AND 语义逐个匹配。
- 后端 `@perm`、前端 `hasPerm`、SDK 拦截器/权限缓存使用同一口径。

**管理端权限断言（语义码，系统 admin/平台超管只需一条 `*` 即可覆盖）：**

| 语义码 | 覆盖接口 |
|---|---|
| `role:manage` | `/api/roles/**`、`/api/roles/{id}/permissions/**` |
| `permission:manage` | `/api/permissions/**` |
| `resource:manage` | `/api/sdk-endpoints/**` |
| `page:manage` | `/api/page-actions/**` |
| `user:manage` | `/api/user-roles/**` |
| `audit:view` | `/api/approval-grants`、`/api/expiring-grants` |
| （超管专属） | `/api/systems/**`：`@perm.isPlatformAdmin()`，无业务权限码 |

- 除 `/api/me`、`/api/me/systems`、`/api/me/bootstrap` 仅要求已认证外，其余管理端接口都要求 `X-System-Code` + 对应权限码（平台超管免权限码但仍需合法业务系统上下文）。
- **严格隔离**：列表接口 `systemId` 可省略（=当前上下文），显式其他系统 403；所有 by-id 接口加载实体后校验 `entity.systemId == 上下文`，超管同样不允许单请求跨系统。
- 种子里 `crm-admin-001`（CRM admin）、`erp-admin-001`（ERP admin）各持有 **一条 `*`**；`superadmin` 在平台系统内持有 **一条 `*`**。

### 3.1 当前用户与系统

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/api/me` | — | `{"externalUserId":"...","platformAdmin":false}` |
| GET | `/api/me/systems` | — | `[{"id":1,"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}]`；普通用户=有有效角色的启用业务系统，超管=全部启用业务系统；**不含 platform 平台系统**；第一条 `isDefault=true` |
| GET | `/api/me/bootstrap` | 可选 `X-System-Code` | 管理端渲染：`{"externalUserId":"...","platformAdmin":false,"currentSystemCode":"crm","permissions":["role:manage",...],"systems":[...同上...]}`；`currentSystemCode`=合法请求头否则第一个可访问系统（无系统为 null）；`permissions`=当前系统内有效权限码（含 `*` 通配；超管 `["*"]`、无系统 `[]`） |

### 3.2 系统接入（仅平台超管）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/systems?keyword=&page=&size=` | 分页列表（仅业务系统，排除 platform），items = `{id,code,name,region,status,sort,remark,isPlatform,adminExternalUserId,adminName,appKey,appSecretMasked,createdAt,updatedAt}`；完整 appSecret 不在列表返回 |
| POST | `/api/systems` | 必填 `{code,name,region,adminExternalUserId}`，可选 `adminName,status,sort,remark`；事务内自动创建默认 `admin` 角色 + 一条 `*` 权限 + 角色授权 + 管理员 `user_role` + 写入 `admin_*`，并分配 `appKey=code`、随机 `appSecret`；响应额外返回一次性完整 `appSecret`；code 全局唯一（重复 409） |
| PUT | `/api/systems/{id}` | 必填 `{name,region}`，可选 `adminExternalUserId,adminName,status,sort,remark`；变更管理员时撤销旧管理员该 admin 角色授权并授权新管理员；code 不可改 |
| DELETE | — | **v2.0 移除**：用 PUT `status=0` 停用 |


> 非平台超管调用 → 403。系统接入必须录入系统管理员账号；新管理员登录后即可管理该系统的角色/权限。
> 每个业务系统接入时分配 `appKey=code` 与随机 `appSecret`；列表仅返回 `appKey/appSecretMasked`，创建/审批通过响应一次性返回完整 `appSecret`。开放接口验签优先使用 DB 凭据（见 §4.1）。

### 3.2.1 SDK 接入申请审核（A16，仅平台超管）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/system-applications?status=&page=&size=` | 分页申请单，status=`PENDING/APPROVED/REJECTED`（空=全部）；items=`SystemApplicationVO`：`{id,systemCode,name,region,source,appKey,sdkVersion,remark,status,rejectReason,reviewedBy,reviewedAt,systemId,createdAt}` |
| POST | `/api/system-applications/{id}/approve` | body `{adminExternalUserId,adminName?}`；仅 PENDING；调用系统接入创建逻辑（admin 角色+`*`+管理员授权+appKey/appSecret），回填 `APPROVED/systemId/reviewed_*`；响应在 VO 上追加 **一次性** `appSecret`（`id` 与 `applicationId` 相同） |
| POST | `/api/system-applications/{id}/reject` | body `{reason}`；仅 PENDING；回填 `REJECTED/rejectReason/reviewed_*`；返回 VO |

### 3.2.2 系统接入凭据查看（A18）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/system-credentials/current` | 当前 `X-System-Code` 系统的完整凭据；`@perm.has('system:credential')`（admin 的 `*`/平台超管通过，普通角色 403）；data=`{systemId,systemCode,systemName,region,appKey,appSecret}` |
| GET | `/api/system-credentials/{systemId}` | 仅平台超管；按 systemId 查看任意业务系统完整凭据（平台/不存在 404） |

> 「接入凭据」前端页面 `system:credential` 权限可见，默认隐藏 AppSecret，点显示后展示完整值；平台超管在「系统接入 → 查看密钥」也可查看任意系统完整密钥。

> 同一 `system_code` 只保留一条申请记录（唯一键），状态原地更新、不保留历史；驳回后 SDK 重新上报会重置为 PENDING。非平台超管 403、重复审批 409、不存在 404。外部审批系统可直接复用 approve/reject API。

### 3.3 角色管理（按系统隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/roles?systemId=&keyword=&page=&size=` | items: `{id,systemId,code,name,status,sort,remark,timeTemplateId,timeTemplateName}` |
| POST | `/api/roles` | `{systemId,code,name,status?,sort?,remark?,timeTemplateId?}`，`(systemId,code)` 唯一；`timeTemplateId` 非空时必须存在且启用（否则 400）；`admin` 角色必须不绑定模板（400） |
| PUT | `/api/roles/{id}` | `{name,status?,sort?,remark?,timeTemplateId?}`；`timeTemplateId` 传 null 表示改为永久角色 |
| DELETE | `/api/roles/{id}` | 停用 |

### 3.3.1 时间模板（A21，全局，平台超管维护）

> 时间语义：`用户 --(sys_user_role 绝对窗口)--> 角色 --(sys_role_permission 永久)--> 权限`。模板是全局配置（不按系统隔离），只决定「分配/审批通过那一刻」计算出的绝对窗口；一旦写入用户-角色，后续改/停用模板不影响已生效授权。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/time-templates?status=` | 列表，`role:manage` 或平台超管可读；items: `{id,name,timezone,startDayOffset,startTime,endDayOffset,endTime,status,remark,createdAt,updatedAt}`；`startTime/endTime` 为 `HH:mm` 字符串；按 id 倒序 |
| POST | `/api/time-templates` | **仅平台超管**（普通系统管理员即使拥有 `*` 也 403）；`{name,timezone?=Asia/Shanghai,startDayOffset,startTime,endDayOffset,endTime,status?,remark?}` |
| PUT | `/api/time-templates/{id}` | 仅平台超管，字段同上 |
| DELETE | `/api/time-templates/{id}` | 仅平台超管，逻辑停用（`status=0`） |

校验口径：`name` 1–100 字且全局唯一；`timezone` 必须是合法 IANA 时区；`startTime/endTime` 必须 `HH:mm`；`startDayOffset/endDayOffset` ∈ 0–3650；同一锚点日期下 `endLocal > startLocal`，否则 400。窗口计算见 §5.1。

### 3.4 权限资源管理（树形）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/permissions?systemId=` | 返回树：节点 `{id,systemId,parentId,type,code,name,method,apiPattern,sort,status,isPublic,meta,children[]}`；`type` 1目录/页面级、2操作级、3纯接口 |
| GET | `/api/permissions/{id}` | 单节点 |
| POST | `/api/permissions` | `{systemId,parentId?=0,type,code,name,method?,apiPattern?,sort?,status?,isPublic?,meta?}`；`(systemId,code)` 唯一 |
| PUT | `/api/permissions/{id}` | 同上（code 可改，但需保持系统内唯一） |
| DELETE | `/api/permissions/{id}` | 停用（status=0；有子节点时 400） |

**`apiPattern` 校验（统一口径）**：保存时校验接口模式语法，非法 → 400 并说明。
- 支持 Spring 模板变量 `{id}`（匹配单段，SDK 与 `*` 等价）与 Ant 通配 `*`（单段）；
- `**` 只允许出现在模式末尾，表示匹配剩余任意段；`**` 出现在中间/开头 → 非法；
- 必须以 `/` 开头；`method` 与 `apiPattern` 必须同时为空或同时非空（只填一个 → 400）。
- 例：`/api/order/{id}` ✔、`/api/order/**` ✔、`/api/order/*/items` ✔、`/api/**/x` ✘。

### 3.5 角色-权限授权

> **A18**：`admin` 角色默认拥有全部权限（`*`），`grant/revoke` 返回 400「系统管理员角色默认拥有全部权限，无需授权」；前端角色授权下拉排除 admin。
> **A21**：角色-权限为**永久静态绑定**，不再携带 `validFrom/validUntil`（迁移 11 删除对应列）；时间窗口只在「用户-角色」上体现。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/roles/{roleId}/permissions` | `{"roleId":1,"grants":[{"permissionId":10,"code":"order:delete","status":1}]}` |
| POST | `/api/roles/{roleId}/permissions/grant` | `{permissionIds:[...],grantReason?}`；已存在关系→更新 status=1，否则插入（无时效字段） |
| POST | `/api/roles/{roleId}/permissions/revoke` | `{permissionIds:[...]}`；status=0 |

### 3.6 用户-角色分配（窗口由角色时间模板计算）

> **A21**：管理端不再手工选择日期。分配时服务端按角色绑定的模板计算绝对窗口并写入 `sys_user_role.valid_from/valid_until`；角色无模板 = 永久（两列为 null）；角色绑定的模板不存在/已停用 → 409。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user-roles?systemId=&externalUserId=` | `[{"id":1,"systemId":1,"externalUserId":"13800138000","roleId":2,"roleCode":"admin","roleName":"管理员","validFrom":null,"validUntil":null,"status":1,"grantReason":null,"sourceApprovalId":null}]` |
| POST | `/api/user-roles` | `{systemId,externalUserId,roleId,grantReason?}`；窗口由服务端计算（见 §5.1 计算规则） |
| DELETE | `/api/user-roles/{id}` | 撤销（status=0） |

### 3.7 用户-系统开通（v2.0 已移除）

用户可访问系统由有效角色推导（见 §3.1）；系统接入见 §3.2。`sys_user_system` 表与 `/api/user-systems/**` 接口已删除，调用返回 404。

### 3.8 页面操作元数据（模式 B）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/page-actions?systemId=&pageCode=&page=&size=` | items: `{id,systemId,pageCode,code,text,type,action,config,sort,status}` |
| POST | `/api/page-actions` | `{systemId,pageCode,code,text,type?="button",action,config?,sort?}`；**管理端页面只维护 pageCode/code/text/type（语义类型 button/link/menu/icon/input）**；`action`/`config` 仅为 SDK/接口兼容保留，页面不再编辑 |
| PUT | `/api/page-actions/{id}` | 同字段更新 |
| DELETE | `/api/page-actions/{id}` | 停用 |

### 3.9 SDK 端点上报列表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sdk-endpoints?systemId=&keyword=&page=&size=` | items: `{id,systemId,method,path,candidateCode,source,permissionId,permissionCode,reportedAt}` |
| PUT | `/api/sdk-endpoints/{id}/link` | `{permissionId}` → 将 `sys_permission.method/api_pattern` 更新为该端点的 method/path，并把端点标记为已关联 |
| PUT | `/api/sdk-endpoints/{id}/unlink` | 解除关联（endpoint.permission_id=null；不改权限码） |

### 3.10 审批授权记录 / 即将到期

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/approval-grants?systemId=&externalUserId=&page=&size=` | 合并 `sys_user_role` 与 `sys_role_permission` 中 `source_approval_id IS NOT NULL` 的记录：`{kind:"USER_ROLE"\|"ROLE_PERMISSION",systemId,externalUserId?,roleId?,permissionId?,sourceApprovalId,grantReason,validFrom,validUntil,status,grantedAt}` |
| GET | `/api/expiring-grants?withinDays=7&systemId=` | 同上结构，条件是 `valid_until BETWEEN NOW() AND NOW()+withinDays` 且 `status=1`，按 `valid_until` 升序 |

---

## 4. SDK 开放接口（starter ↔ api）

### 4.1 鉴权：AppKey + HMAC-SHA256

请求头：

```
X-App-Key:   crm
X-Timestamp: 1792819200000          # epoch 毫秒
X-Nonce:     6f1c2c9a34b84b7f9a51   # 随机串，建议 ≥ 16 字符
X-Signature: <hex HMAC-SHA256>
```

规范化串（字段间 `\n`，无末尾换行）：

```
appKey + "\n" + timestamp + "\n" + nonce + "\n" + HTTP_METHOD + "\n" + path + "\n" + sortedQuery + "\n" + sha256Hex(body)
```

- `path`：不含 query 的请求路径（如 `/openapi/sdk/mappings`）。
- `sortedQuery`：把**原始 query 串**按 `&` 切成片段（保留发送时的原始 `k=v` 字节，不做解码/重编码），按片段中 `=` 之前的原始 key 字符串升序排序（同一 key 保持出现顺序），再用 `&` 拼回；无 query 时为 `""`。这样 SDK 与 api 都只对「实际发送的原始串」重排，不存在两次 URL 编码差异。
- `body`：原始请求体字节；GET 无 body 时对空串求 `sha256Hex("")`。
- `X-Signature` = `HMAC-SHA256(appSecret, canonical)` 的小写 hex。
- 服务端凭据解析：优先按 `sys_system.app_key` 查启用系统（A17），DB 命中即用其 `app_secret`；DB 无记录时才回退 `rbac.clients` 配置（兼容旧 demo）；系统停用/无 secret 直接 401。
- 服务端校验：`appKey` 存在且其绑定的 `systemCode` 与请求中的 `systemCode` 一致；时间戳窗口 ±5 分钟；`nonce` 5 分钟内不可重复（Redis 实现，Redis 禁用时用 Caffeine 兜底）；签名常量时间比较。
- 失败返回 401，`{"code":401,"message":"invalid signature"}`。

### 4.2 接口清单

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/openapi/sdk/mappings?systemCode=crm` | — | `{"systemCode":"crm","mappings":[{"method":"DELETE","pattern":"/api/order/{id}","code":"order:delete"},{"method":"GET","pattern":"/api/order/**","code":"order:list"}]}` |
| GET | `/openapi/sdk/user-permissions?systemCode=&externalUserId=` | — | `{"permissions":[{"code":"order:delete","validFrom":null,"validUntil":"2026-10-31T23:59:59Z"}],"serverTime":"2026-10-24T05:00:00Z"}`；平台超管返回 `[{"code":"*","validFrom":null,"validUntil":null}]` |
| GET | `/openapi/sdk/user-systems?externalUserId=` | — | `[{"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}]`；按有效角色推导业务系统（排除 platform；过期角色不返回；平台超管返回全部启用业务系统）。供 SDK 的 `/rbac/me/systems` 使用，失败时 SDK 可降级 |
| GET | `/openapi/sdk/page-actions?systemCode=&pageCode=` | `pageCode` 可省略 | `{"systemCode":"crm","pageActions":[{"id":1,"pageCode":"order:list","code":"order:export","text":"导出","type":"default","action":"callApi","config":"{}","sort":10}]}` |
| GET | `/openapi/sdk/connection?systemCode=` | — | `{"channel":"redis","region":"region_a","host":"127.0.0.1","port":6379,"password":"...","channelPrefix":"rbac:change:region_a:"}` |
| POST | `/openapi/sdk/endpoints/sync` | `{"systemCode":"crm","endpoints":[{"method":"GET","path":"/api/order/list","candidateCode":"crm:order:list","source":"GENERATED"}]}` | `{"received":12,"created":10,"updated":2}` |
| POST | `/openapi/approval/grant` | 见 §5 | `{"success":true,"grantId":123,"idempotent":false}` |
| POST | `/openapi/onboarding/systems` | 见 §4.5 | `{"applicationId":7,"status":"PENDING"}` |

映射表生成规则：`sys_permission` 中 `method` 与 `api_pattern` 均非空且 `status=1` 的记录，按 `system_id` 过滤后下发；`pattern` 为 Ant 风格（`**` 仅可结尾）。端点上报按 `(system_id, method, path)` 幂等 upsert，`candidateCode` 可为空。

### 4.3 开放接口数据口径（实现补充，已与 api/sdk 对齐）

- `user-permissions` 的每个 `code` 返回**有效窗口包络**：`validFrom` 取该码对应有效 `user_role` 的 `valid_from` 最小值（任一为 null 则 null）；`validUntil` 取 `valid_until` 最大值，**任一授权为永久（null）则该码 `validUntil=null`**。**A21 起窗口只来自 `user_role`**（角色-权限永久，不再参与窗口计算）。
- `page-actions` 的 `config` 字段为 **JSON 字符串**（管理端创建/更新时接受字符串或对象）；`permission.meta` 为 JSON 对象。
- `endpoints/sync` 对非法条目宽容跳过：`received` 为原始条数，`created/updated` 只统计有效条目。
- `approval-grants?externalUserId=` 过滤时，`ROLE_PERMISSION` 类记录（无 external_user_id 列）不参与该条件下的结果，只返回 `USER_ROLE` 记录。
- api 进程默认时区强制 UTC（`TimeZone.setDefault(UTC)` + Hikari `SET time_zone='+00:00'`），保证 `DATETIME` 读写与 `NOW()` 比较口径一致。

### 4.4 审批幂等的边界

同一关系（用户+角色）重新授权会**更新原行**并覆盖 `source_approval_id`（契约 §9.1），数据模型无审批历史表；因此「新审批覆盖旧审批后，再回放更早的 approvalId」无法再幂等，属数据模型固有限制。同一 `approvalId` 的重复回调幂等必须成立（已实测）。

---

### 4.5 系统接入上报（A16，onboarding 专用 AppKey）

`POST /openapi/onboarding/systems`，使用独立 `rbac.onboarding.app-key/app-secret` 验签（同一 canonical 签名算法，但**不绑定 systemCode**，不要求系统已存在）：

```json
{"systemCode":"oms","name":"OMS系统","region":"region_a","sdkVersion":"1.0.0","remark":"SDK 启动自动上报"}
```

- 只创建/更新 `sys_system_application`，状态 `PENDING`，**不会直接创建系统**；
- 同一 `systemCode` 只保留一条申请：PENDING 覆盖元数据；REJECTED 重新置 PENDING；APPROVED 幂等返回 `APPROVED`；
- `sys_system` 已存在但无申请记录 → 409；
- 校验失败 400；签名失败 401。

## 5. 审批回调（审批系统 → api）

```http
POST /openapi/approval/grant
X-App-Key: approval-system
X-Timestamp / X-Nonce / X-Signature   # 与 §4.1 同一套签名算法
Content-Type: application/json
```

```json
{
  "systemCode": "crm",
  "externalUserId": "13800138000",
  "grantType": "role",
  "roleId": 5,
  "permissionCodes": ["order:delete"],
  "validFrom": "2026-10-24T00:00:00Z",
  "validUntil": "2026-10-31T23:59:59Z",
  "approvalId": "APV-20261024-001",
  "reason": "季度末批量清理订单"
}
```

处理规则：

1. 验签（`rbac.approval` 配置的独立 AppKey/AppSecret），失败 401。
2. 以 `approvalId` 幂等：已成功处理过 → 直接返回 `{"success":true,"grantId":<原grantId>,"idempotent":true}`。
3. `grantType="role"`（必填且当前唯一支持）：校验 role 存在且属于 systemCode 对应系统；按 §9.1 口径写 `sys_user_role`（同用户+角色已存在则更新时效，否则插入），写入 `source_approval_id`、`grant_reason`、`valid_from/valid_until`。
4. `permissionCodes`：仅作为审批内容审计信息，必须全部是 `roleId` 已授权的权限码，否则 400（防止审批内容与角色不符）。`grantType="permission"` 返回 400：当前数据模型不支持绕过角色直接给用户授权（无 user_permission 表）。
5. 发布 Redis 通知；返回成功。
6. **A21 时间模板优先**：若 `roleId` 绑定了时间模板，忽略请求中的 `validFrom/validUntil`，由服务端按模板计算绝对窗口写入 `sys_user_role`；角色未绑定模板时继续使用请求窗口（可为空 = 永久）。模板不存在/已停用 → 409。

### 5.1 业务用户临时权限申请（A20）

权限中心只保存申请单与审批结果，不实现审批流。申请人接口要求 JWT，并由既有 `ManagementContextFilter` 要求当前系统上下文（`X-System-Code`）；**不要求管理权限码**，但申请人必须在当前系统有至少一个有效角色。所有查询强制限定 `system_id + external_user_id`，跨系统返回 404。

申请人 API（`/api/access-applications`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/options` | 返回当前系统可申请角色（**仅「启用 + 非 admin + 已绑定启用时间模板」**）、权限预览、`approvalUrlTemplate`；角色项含 `timeTemplateId/timeTemplateName/timeTemplateText`（如「审批后当天 19:00 ~ 次日 07:00」）；排除已有效持有的角色和 `*` 通配权限 |
| POST | `/` | 创建申请：`{"roleIds":[1,2],"reason":"..."}`，`roleIds` 1–20 个、`reason` 1–200 字；角色无模板/模板停用 → 400；同一系统同一申请人最多一条 `PENDING`，重复提交 409 |
| GET | `?status=PENDING\|APPROVED\|REJECTED&page=&size=` | 当前用户当前系统的申请分页，按创建时间倒序 |
| GET | `/{applicationNo}` | 当前用户的申请详情；申请单号非本人/非当前系统 → 404 |

申请单字段：`applicationNo`（`RA-yyyyMMdd-XXXXXX`）、`status`（PENDING/APPROVED/REJECTED）、`roleCount`、`roles`（申请时角色快照，含 `timeTemplateName` 与审批后逐角色 `approvedValidFrom/approvedValidUntil`）、`reason`、`approvedValidFrom/Until`（所有角色窗口的包络：最早开始、最晚结束）、`decisionComment`、`reviewedBy`、`reviewedAt`、`createdAt`、`approvalUrl`。申请单自身的 `approvedValidFrom/Until` 仅用于列表展示；实际授权以每个角色的 `sys_user_role` 窗口为准。

审批决策回调（审批系统 → api）：

```http
POST /openapi/approval/access-applications/decision
X-App-Key: approval-system
X-Timestamp / X-Nonce / X-Signature   # 与 §4.1 同一套签名算法
Content-Type: application/json
```

```json
{
  "applicationNo": "RA-20261025-A1B2C3",
  "decision": "APPROVED",
  "reviewer": "external-approval",
  "decisionComment": "同意，仅限本次"
}
```

处理规则：

1. 使用 `rbac.approval` 独立 AppKey/AppSecret 验签；`applicationNo` 不存在 → 404。
2. `decision` 仅接受 `APPROVED`/`REJECTED`（大小写不敏感）。**A21 起请求不再接收 `validFrom/validUntil`**（旧客户端传入也忽略），窗口由服务端按每个角色的时间模板分别计算，多角色可有不同窗口。
3. 只有 `PENDING` 申请单可决策；`REJECTED` 重放幂等返回 `idempotent=true`；已通过再回调 `APPROVED` 幂等返回 `idempotent=true` 且窗口不回滚；不同结果 → 409。
4. 通过时逐角色计算窗口并复用既有授权逻辑写 `sys_user_role`，`approvalId = applicationNo + ":" + roleId`；同时把模板快照（`time_template_id/time_template_name`）与逐角色窗口写入 `sys_access_application_role`，申请单级窗口写包络。任一角色失败整体回滚、申请保持 `PENDING`。已持有角色或 `admin` 角色 → 409；角色绑定的模板已停用/不存在 → 409。
5. 驳回只回写状态与审批字段，不写授权；通过/驳回均发布 Redis 变更通知。

**A21 窗口计算规则**（`startDayOffset/startTime`、`endDayOffset/endTime`，按模板时区）：以「当前时刻」为锚点转模板时区；`startLocal = 锚点日期 + startDayOffset 的 startTime`、`endLocal = 锚点日期 + endDayOffset 的 endTime`；要求 `endLocal > startLocal`；若 `endLocal <= now`（窗口已过期）→ 409/400 拒绝，不滚动到下一周期；通过时写 `validFrom = max(startInstant, now)`、`validUntil = endInstant`（UTC 落库）。角色无模板 = 永久（两列 null）。

申请人看到的审批外链由 `rbac.access-approval.url-template` 生成，前端替换 `{applicationNo}`；权限中心不代理审批平台页面。演示时可用 `scripts/mock-access-approval.py` 模拟外部审批。

---

## 6. Redis 变更通知（api → sdk）

- 频道：`rbac:change:{region}:{systemCode}`，如 `rbac:change:region_a:crm`。
- 消息体（JSON 字符串）：

  ```json
  {"systemCode":"crm","type":"PERMISSION_CHANGED","timestamp":"2026-10-24T05:00:00Z"}
  ```

- `type`：`PERMISSION_CHANGED`（角色-权限、用户-角色、角色/权限码/接口映射变更）、`PAGE_ACTION_CHANGED`（页面操作元数据）、`ENDPOINT_CHANGED`（端点上报）。
- 权限中心在以下时机发布（按 `sys_system.region` 路由）：角色-权限变更、用户-角色变更、权限码 method/api_pattern 变更、页面操作变更、审批授权写入、定时任务收回过期授权。
- SDK 收到任意消息：重新全量拉取 mappings + pageActions + 失效该用户权限集缓存（`PERMISSION_CHANGED` 时失效所有用户缓存；`PAGE_ACTION_CHANGED` 时只刷 pageActions）。
- Redis 不可用：api 发布失败只记 warn 日志，不影响主流程（SDK 有短轮询兜底）。

> 验证：已用 Redis 8.10.2 实测两区域链路（`rbac:change:region_a:crm` / `region_b:erp` 各 1 订阅者，变更后 API `receivers=1`，SDK 收到通知并刷新），见 `docs/acceptance.md` §9.11.1 执行记录。

---

## 7. SDK 行为契约

### 7.1 固定类名与职责（两个 SDK 变体，包名/类名/配置键/协议一致）

| 类 | 包 | 职责 |
|---|---|---|
| `RbacSdkAutoConfiguration` | `com.telbank.rbac.sdk` | 唯一自动配置入口，`@ConditionalOnProperty(rbac-sdk.enabled=true)`；注册下列全部组件；`spring.factories` 单轨注册 |
| `RbacSdkProperties` | `...sdk.config` | `@ConfigurationProperties("rbac-sdk")`，字段见需求 §6.13 |
| `RbacPermissionFilter` | `...sdk.filter` | `OncePerRequestFilter`；`FilterRegistrationBean` 注册 `/*`，order=`Ordered.LOWEST_PRECEDENCE-100` |
| `RbacController` | `...sdk.web` | `/rbac/me/systems`、`/rbac/me/bootstrap`（base-path 可配） |
| `RbacClient`（接口）/`DefaultRbacClient` | `...sdk.client` | 编程式查询；开放接口经声明式 Feign 代理（`RbacOpenApiFeignApi`/`RbacOnboardingFeignApi`，签名头由 `RbacSigningClient` 注入），响应经 Feign Jackson 解码器反序列化为内部 `RbacEnvelope<T>` POJO 后取 `data`，返回 `SystemInfo`、`PageAction`、权限码集合（A23） |
| `RbacPermission` | `...sdk.annotation` | `@Target({METHOD,TYPE})`，`value/name/isPublic` |
| `RbacEndpointScanner` | `...sdk.scan` | `ApplicationReadyEvent` 扫描 `RequestMappingHandlerMapping` 并上报 |
| `RbacMappingCache` | `...sdk.cache` | 映射表缓存 + Ant 匹配（精确优先，`**` 末尾） |
| `RbacUserPermissionCache` | `...sdk.cache` | `externalUserId -> systemCode -> code -> {validFrom,validUntil}`，无 TTL，通知驱动失效 |
| `RbacPageActionCache` | `...sdk.cache` | 页面操作元数据缓存 |
| `RbacChangeSubscriber` | `...sdk.notify` | Redis Pub/Sub 订阅（Lettuce/Jedis 均可，Boot 2.7 默认 Lettuce） |
| `RbacPollingFallback` | `...sdk.notify` | 兜底短轮询（默认 5 分钟，`cache.fallback-poll-interval`），SDK 自己持有 `ScheduledExecutorService`，不要求宿主 `@EnableScheduling` |
| `RbacSigner` | `...sdk.client` | §4.1 签名实现（SDK 与 api 必须逐字节一致） |

> **两个变体**：`telbank-rbac-starter`（JDK 8 + Boot 2.7，`META-INF/spring.factories`）与 `telbank-rbac2-starter`（JDK 21 + Boot 4，`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）。类名、包名、配置键、HTTP 协议一致；差异仅限 Servlet API（javax/jakarta）、JSON 库（Jackson 2/3）与 Feign 编解码器（A23：v1 `feign-core`/`feign-jackson`/`feign-okhttp` 11.10 + OkHttp 3.14.9 + Jackson 2 `JacksonEncoder`/`JacksonDecoder`；v2 `feign-core`/`feign-okhttp` 11.10 + OkHttp 4.12.0 + 自带 `RbacJacksonEncoder`/`RbacJacksonDecoder`）。两版开放接口声明（`RbacOpenApiFeignApi`/`RbacOnboardingFeignApi`）、签名注入（`RbacSigningClient`）与 `RbacEnvelope<T>` 解包方式一致，业务层不接触字节/JSON 树。

### 7.2 请求判定顺序（必须严格实现，需求 §6.10/§9.9）

1. 基础设施路径（默认 `/actuator/**`、`/error`、`/favicon.ico`，可被 `interceptor.exclude-paths` 覆盖）→ 放行，不查任何缓存。
2. 用 `HTTP Method + Path` 匹配映射表：
   - **未命中** → 直接放行，不查用户权限集、不回源；`unmatched-strategy=deny` 时返回 403。
   - **命中** → 得到 `code`。
3. 查用户权限集缓存：命中且时效有效 → 放行；命中但无该 code 或时效过期 → 403。
4. 缓存未命中 → 回源 `/openapi/sdk/user-permissions` → 写缓存 → 判定。
5. 回源失败：有旧缓存 → 用旧缓存 + 时钟偏移判定；无旧缓存 → 按 `unmatched-strategy`（默认 pass）。
6. 任何内部异常 → 记日志 + 放行（可用性优先）。
7. `/rbac/me/bootstrap` 调用后必须已预热用户权限集缓存。

### 7.3 端点扫描与上报

- `ApplicationReadyEvent` 后遍历 `RequestMappingHandlerMapping`；跳过方法/类上有 `@PreAuthorize`/`@Secured` 的端点。
- 有 `@RbacPermission` → 候选码 = `value`；否则按 `code-generator.pattern`（默认 `{systemCode}:{resource}:{action}`）生成：
  - `resource` = controller 简单类名去掉 `Controller` 后转 lower-kebab（`OrderController`→`order`）；
  - `action` = HTTP 方法映射：`GET→list`、`POST→create`、`PUT→update`、`DELETE→delete`、`PATCH→patch`（同一方法多 HTTP method 时逐个生成）。
- 全量上报（启动一次）：`POST /openapi/sdk/endpoints/sync`。
- 上报失败只告警，不得影响宿主启动。

### 7.4 用户权限集缓存与时钟偏移

- 回源响应里 `serverTime` 计算 `offsetMillis = serverTime - System.currentTimeMillis()`，每次回源重算。
- 判定时效用 `本地时间 + offsetMillis` 与 `validFrom/validUntil`（null 表示无限制）比较；缓存不设 TTL，仅由变更通知失效。
- `/rbac/me/bootstrap` 返回 `{"permissions":["order:menu",...],"pageActions":[...],"platformAdmin":false}`；`permissions` 为当前有效权限码字符串数组（`*` 表示平台超管）。

### 7.5 总开关

`rbac-sdk.enabled=true` → 所有组件注册；`false` → 所有组件不注册（`@ConditionalOnProperty`），不提供细粒度开关。`/rbac/me/bootstrap`、`/rbac/me/systems` 的路径前缀默认 `/rbac`，`endpoint.base-path` 可改。

### 7.6 当前用户身份（externalUserId）获取

SDK 不校验 JWT（认证属于业务系统/外部认证系统），按以下顺序取 `external_user_id`：

1. `HttpServletRequest.getUserPrincipal().getName()`（宿主 Spring Security / 认证过滤器已认证并写入 principal 时）；
2. 取不到 principal 时，解析请求头 `Authorization: Bearer <jwt>` 的 payload（仅 Base64URL 解码，**不验签**）中的 `sub`；
3. 都没有 → 视为 anonymous：filter 命中映射且需要权限时返回 403，`/rbac/me/bootstrap` 返回空权限（`permissions=[]`、`pageActions=[]`、`platformAdmin=false`）。

不引入 `X-External-User-Id` 一类可被客户端伪造的自定义头；SDK 不新增 spring-security 依赖。`RbacClient` 编程式方法由调用方显式传 `externalUserId`。

### 7.7 映射匹配口径（与 `apiPattern` 一致）

规则按优先级：**字面量精确** > **单段通配**（`*` 或 `{var}`，均匹配一段） > **末尾 `**`**（匹配剩余任意段，可为零段）；`**` 出现在非末尾的模式视为非法配置，SDK 加载时跳过并告警。`method` 必须精确匹配。

### 7.8 路径模式口径修订说明

本节定义覆盖前文任何“仅 Ant 风格”的描述：`{id}` 模板与 `*` 等价（单段），`**` 仅末尾。api 的保存校验与 SDK 的匹配实现必须按本口径一致。

### 7.9 JDK 21 / Boot 4 SDK 变体（telbank-rbac2-starter）

- 坐标：`com.telbank:telbank-rbac2-starter:1.0.0`；JDK 21，Spring Boot 4.x；Boot 4 自动配置通过 `AutoConfiguration.imports` 注册。
- 协议、类名、包名、配置键（`rbac-sdk.*`）与 JDK 8 版完全一致；同一业务系统只能选一个变体（同包名不可共存）。
- 实现差异：`jakarta.servlet`、Feign 客户端实现（A23：JDK 8 版 `feign-core`/`feign-jackson`/`feign-okhttp` 11.10 + OkHttp 3.14.9 + Jackson 2 `JacksonEncoder`/`JacksonDecoder`；JDK 21 版 `feign-core`/`feign-okhttp` 11.10 + OkHttp 4.12.0 + 自带 `RbacJacksonEncoder`/`RbacJacksonDecoder` 适配 Jackson 3；两版同一组 `RbacOpenApiFeignApi`/`RbacOnboardingFeignApi` 与 `RbacSigningClient` 签名装饰器）、Jackson 3（`tools.jackson`）；Redis Pub/Sub 同样支持连接失败降级轮询。
- 与权限中心之间仍走 §4.1 签名协议与 §6 Redis 频道，权限中心侧无需任何改动。

---

## 8. 配置键（api 与 sdk 共享口径）

### 8.1 权限中心 `application.yml`

```yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false
    username: ${RBAC_DB_USER:root}
    password: ${RBAC_DB_PASSWORD:}
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
    db-config:
      id-type: auto
rbac:
  security:
    jwt:
      mode: jwk            # jwk | hs256（demo）
      jwk-set-uri: ${RBAC_JWK_SET_URI:}
      issuer: ${RBAC_JWT_ISSUER:}
      hs256-secret: ${RBAC_JWT_HS256_SECRET:}
  clients:                 # SDK 系统级 AppKey
    - app-key: crm
      system-code: crm
      app-secret: ${RBAC_CRM_SECRET:crm-dev-secret}
    - app-key: erp
      system-code: erp
      app-secret: ${RBAC_ERP_SECRET:erp-dev-secret}
  approval:
    app-key: approval-system
    app-secret: ${RBAC_APPROVAL_SECRET:approval-dev-secret}
  access-approval:
    url-template: ${RBAC_ACCESS_APPROVAL_URL_TEMPLATE:http://localhost:8090/approval/access?applicationNo={applicationNo}}
  onboarding:
    enabled: ${RBAC_ONBOARDING_ENABLED:true}
    app-key: ${RBAC_ONBOARDING_APP_KEY:onboarding-app}
    app-secret: ${RBAC_ONBOARDING_SECRET:onboarding-secret}
  regions:                 # 区域 Redis 连接（SDK 通过 /openapi/sdk/connection 获取）
    region_a:
      host: ${RBAC_REDIS_REGION_A_HOST:127.0.0.1}
      port: 6379
      password: ${RBAC_REDIS_REGION_A_PASSWORD:}
      channel-prefix: "rbac:change:region_a:"
    region_b: { host: 127.0.0.1, port: 6379, password: "", channel-prefix: "rbac:change:region_b:" }
  redis:
    enabled: ${RBAC_REDIS_ENABLED:true}   # false 时 nonce 用本地 Caffeine、发布变更为 no-op（仅联调/测试）
  expiry-scan-cron: "0 * * * * *"
```

### 8.2 SDK `application.yml`（业务系统侧）

见需求 §6.13，字段必须一一对应；`RbacSdkProperties` 用松散绑定。A16 新增：`rbac-sdk.system-name`、`rbac-sdk.region`、`rbac-sdk.onboarding.{enabled,app-key,app-secret,report-on-startup}`（默认关闭；失败只 WARN，不影响业务启动）。

---

## 9. 偏差与实现口径（需求文档未明确/自相矛盾处，已锁定）

> 与需求文档「附录 A 实现修订记录」的对应：A1 = §9.2、A2 = §9.1、A3 = §9.3、A4 = §9.4、A5 = §3.4/§7.7、A6 = §4.2、A7 = §0；A8（权限中心自接入，v2.0 已被 A11 取代）= §1.5/§3；A9（JDK 21 SDK 变体）= §0/§7.9；A11（系统隔离重构）= §1.5/§3/§3.2/§10；A12（A 方案通配权限 + 严格上下文）= §1.5/§3/§10；A13（MyBatis-Plus 持久层）= §2；A14（前端移除到期提醒菜单 + 布局优化）= §10；A15（页面元素管理简化）= §3.8/§10；A16（系统接入双通道 + 审核）= §3.2.1/§4.5；A17（系统 appKey/appSecret）= §3.2/§4.1；A18（凭据查看 + admin 授权保护）= §3.2.2/§3.5/§10；A19（表格内容优先展示）= §10；A20（业务用户临时权限申请）= §5.1/§8.1/§10；A21（角色时间模板）= §3.3/§3.3.1/§3.5/§3.6/§4.3/§5/§5.1/§10；A22（简化 SDK HTTP 客户端：JDK 8 `RestTemplate` + Jackson 2 / JDK 21 typed `RestClient`，`RbacEnvelope<T>` POJO；已被 A23 取代）= §7.1/§7.9；A23（声明式 Feign 接口：`RbacOpenApiFeignApi`/`RbacOnboardingFeignApi` 接口声明 + `RbacSigningClient` 注入签名，v1 Feign 11.10 + OkHttp 3.14.9 + Jackson 2 / v2 Feign 11.10 + OkHttp 4.12.0 + 自带 Jackson 3 编解码器，响应 `RbacEnvelope<T>`）= §7.1/§7.9。

1. **重新提权的落库方式**：需求要求“旧记录标记 status=0，新记录插入”，但 `sys_user_role`/`sys_role_permission` 的唯一键 `(system_id,external_user_id,role_id)`/`(role_id,permission_id)` 不允许同一关系存在两条记录，两者不可同时满足。**实现口径：同关系重新授权时更新原行的 `valid_from/valid_until/source_approval_id/grant_reason/status=1`（不新增行）；已过期的旧授权保留 status=0 直到再次被授权时复用同一行。** 该偏差必须在 README「与需求文档的偏差」一节列明。
2. **SDK 端点上报需要持久化**：需求“共 7 张表”中没有存端点上报的表，但 §5.7 要求端点上报列表与关联权限码。**实现口径：新增第 8 张表 `sys_sdk_endpoint`**（见 `V1__schema.sql`），其余 7 张严格按需求。README 偏差清单列明。
3. **审批回调 `grantType=permission`**：数据模型没有 user_permission 表，无法绕过角色直接授权。**实现口径：仅支持 `grantType=role`，`permission` 返回 400 并说明原因**；`permissionCodes` 仅用于校验审批内容与角色授权一致。
4. **AppKey/AppSecret 与区域 Redis 连接配置的存放**：需求说 AppSecret/Redis 密码“不在配置文件明文、数据库加密存储”，但 7 张表中没有对应表。**实现口径：AppSecret 与 Redis 密码通过环境变量注入（配置文件中只写 `${ENV:默认值}`），本地 demo 默认值为开发用途；不新增密钥表。** README 说明生产注入方式。
5. **权限中心二级缓存**：管理端高频查询用 Caffeine 一级缓存；Redis 用于 Pub/Sub、nonce 防重放与（可选）权限集缓存，不额外实现复杂二级缓存协议。
6. **菜单结构**：权限中心不存储菜单/路由（需求 §2），管理前端的系统选择器/菜单由前端代码维护；不要给 `sys_permission` 加 path/component/icon 字段。
7. **业务前端数据获取**：管理前端（telbank-rbac-front）只调权限中心 `/api/**`；SDK 的 `/rbac/me/*` 属于业务系统后端（starter），两者不要混用。
8. **A21 时间模板口径**：时间语义从「角色-权限」上移到「用户-角色」。`sys_role_permission` 不再有时效（迁移 11 删列），旧审批 `grant` 的 `validFrom/validUntil` 仅在角色未绑定模板时生效；A20 决策回调不再接收窗口字段，收到也忽略。`sys_user_role.valid_from/valid_until` 保留，既有到期收回任务（§9.1 偏差 1）不变；已生效授权的窗口不因后续修改/停用模板而回滚。

---

## 10. 前端契约（telbank-rbac-front）

- 技术栈严格按需求 §3.3；包管理器 `npm`；Node 通过 nvm 使用 22.23.1+。
- 环境变量：`VITE_API_BASE_URL`（默认 `http://localhost:8080`）。
- 登录：`/login` 页面粘贴 JWT（外部认证系统签发；本地用 `scripts/gen-demo-jwt.sh` 生成），存 `localStorage["rbac.token"]`；请求头注入 `Authorization: Bearer`；当前系统存 `localStorage["rbac.systemCode"]`。
- 系统上下文：登录后 GET `/api/me` → `/api/me/systems` → 选择 `currentSystemCode`（本地缓存命中否则第一条）→ 带 `X-System-Code` 拉 `/api/me/bootstrap`；所有管理端查询都带当前业务系统头；超管可切换所有业务系统并看到「系统接入」，系统管理员只能看到自己的系统。
- 菜单 IA：`权限管理` 组（角色管理/角色权限/权限管理/资源管理/页面元素/用户角色/授权记录/接入凭据）+ 顶级「系统接入」（仅超管）+ 顶级「时间模板」（仅平台超管，A21）+ 顶级「我的申请」（所有已登录用户，A20）；`hasPerm` 支持 `*` 与 `前缀:*`；不再有「用户-系统开通」与「到期提醒」菜单（接口仍保留）。「角色授权」「用户授权」菜单文案分别改为「角色权限」「用户角色」。
- 页面（对应需求 §5.7，v3.0 IA）：系统接入（仅超管）：业务系统 Tab（手动录入 + AppKey/掩码密钥展示/查看密钥）与接入申请 Tab（SDK 上报待审核，审核/驳回，一次性返回新系统凭据）；时间模板（A21，仅平台超管）：列表 + 新建/编辑（名称/时区/开始天数偏移/开始时间/结束天数偏移/结束时间/备注/状态）+ 停用；权限管理组：角色管理（新增「时间模板」列与选择，绑定模板或留空=永久）、权限管理（树）、角色权限（只做静态勾选 + 一次 grant，**去掉逐项时效/RangePicker/分组提交**，admin 角色排除，可授权树不再展示 `*`）、用户角色（**去掉日期选择**，选中角色展示模板说明，窗口由服务端计算）、资源管理（SDK 端点）、页面元素（只维护元素标识：pageCode/code/text/type）、授权记录、接入凭据（A18）；「我的申请」（A20/A21）：业务用户选择当前系统角色（排除 admin/已持有/`*`，只展示绑定启用模板的角色及其模板摘要），提交原因；列表按状态筛选、显示申请单号/审批外链/逐角色窗口，不提供审批操作。**用户-系统开通、到期提醒菜单已移除**（`/api/expiring-grants` 接口保留）。
- 自服务入口：业务用户即使没有任何管理权限码，也能看到「我的申请」并进入页面；`AdminLayout` 以「是否有任意可见菜单」判断是否空态，而非只看管理权限；首页 `HomeRedirect` 优先管理菜单，无管理菜单时落到「我的申请」。
- 状态：TanStack Query 管理服务端状态；Zustand 管理当前用户/系统/权限码集合；不使用 Axios，使用封装的 `request()`（原生 fetch）。
- 表格内容优先（A19）：`table-layout:auto`，宽度足够不换行、不够换行完整展示，不用省略号；时间列 `YYYY-MM-DD HH:mm`；表格 100% 宽度、无横向滚动。
- 必须 `npm run build` 通过（含 `tsc` 类型检查）。

---

## 11. 验收与命令（已落地）

- 一键验证：`./scripts/verify-all.sh`（后端单测 + 两版 SDK 单测 + 前端类型检查/构建 + 产物核对，含 A21 迁移/时间模板页面检查；最新数字见 `docs/acceptance.md` 执行记录）
- 权限中心：`./scripts/start-all-macos.sh`（Linux 用 `start-all-linux.sh`；需要 JDK 21+；demo profile）；验收 `python3 scripts/acceptance.py`
- SDK（JDK 8）：`./scripts/build-sdk.sh`（JDK 17+ 工具链 → Java 8 字节码，校验 major=52）；CI 兼容性验证 `.github/workflows/sdk-jdk8-compat.yml`
- SDK（JDK 21）：`mvn -pl telbank-rbac2-starter test`（JDK 21，字节码 major=65）
- 系统隔离：`GET /api/me/systems`（有效角色推导）+ `GET /api/me/bootstrap`（当前系统权限）；管理端 `/api/**` 带 `X-System-Code=<业务系统>`；系统接入 `/api/systems/**` 仅超管
- 数据库初始化：`./scripts/init-db.sh`（手动 SQL，无 Flyway；`--temp-db` 自动执行）
- 管理前端：`cd telbank-rbac-front && npm install && npm run dev|build`（nvm Node 22）
- 示例业务系统：`samples/business-demo`（Boot 2.7.18，JDK 8+ 运行时）
- 验收矩阵与执行记录：`docs/acceptance.md`
