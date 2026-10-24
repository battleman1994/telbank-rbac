# telbank-rbac 实现契约（内部开发基线）

> 本文件是三个交付工程之间的**接口契约**，与 `docs/telbank-rbac.md`（需求基线）配套使用。
> 需求文档未明确或内部有冲突处，以本文件「§9 偏差与实现口径」为准；实现不得再自行发明协议。
>
> 版本：v1.1（2026-10-24）· 维护：Lead
> 修订：v1.1 补充实现期口径（SDK 构建工具链 JDK 17+ / `api_pattern` 支持 `{var}` / 新增 `/openapi/sdk/user-systems` / 真实 Redis 与 JDK 8 CI 验证）；v1.0 初版。

---

## 0. 工程坐标与包名

| 模块 | Maven 坐标 / 目录 | 运行时 | Java 包根 |
|---|---|---|---|
| 聚合工程 | `com.telbank:telbank-rbac:1.0.0`（根 `pom.xml`，packaging=pom） | — | — |
| 权限中心后端 | `com.telbank:telbank-rbac-api:1.0.0` → `telbank-rbac-api/` | JDK 21，Spring Boot 4.1.2 | `com.telbank.rbac` |
| SDK | `com.telbank:telbank-rbac-starter:1.0.0` → `telbank-rbac-starter/` | JDK 8 字节码（构建工具链 JDK 17+），Boot 2.7.18 | `com.telbank.rbac.sdk` |
| 管理前端 | `telbank-rbac-front/`（npm 工程，非 Maven 模块） | Node 22.22+（nvm），Vite 8 | — |

- 两个 Maven 子模块的 `<parent>` 分别是各自 Spring Boot 的 `spring-boot-starter-parent`，`<relativePath/>` 为空；根 POM 只做 reactor 聚合。**不要修改根 POM 与两个模块 POM 的依赖版本段**（Lead 维护），需要新依赖先向 Lead 提出。
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
   - 系统级接口还必须带 `X-System-Code: <code>`；服务端把它解析成 `system_id` 并做隔离。
   - 平台超管（`rbac.platform-admin-ids` 命中 `sub`）跳过权限码校验，但仍要求合法 JWT。
6. **权限断言**：Controller 用 `@PreAuthorize("@perm.has('system:manage')")` 一类写法；`@perm` Bean 名固定 `perm`，方法签名 `boolean has(String... codes)`（单码时返回是否拥有；多码按 AND 语义，实现可扩展 OR 变体 `hasAny`）。
7. **权限码**：系统内唯一；菜单/按钮/接口共用一个码。`is_public=1` 默认所有登录用户拥有。
8. **CORS**：开发环境允许 `http://localhost:5173`（前端 dev server）与 `http://127.0.0.1:5173`；生产按配置。
9. **错误码约定**：401 未认证/验签失败；403 无权限；404 资源不存在；400 参数/语法错误；409 唯一键冲突；500 其他。
10. **演示 JWT（仅本地验收）**：`application-demo.yml` 启用 `rbac.security.jwt.mode=hs256`，用 `scripts/gen-demo-jwt.sh` 生成 HS256 token；生产必须 `mode=jwk`（`jwk-set-uri`）。SDK 不感知 JWT。

---

## 2. 数据库与 Flyway

- 库名 `telbank_rbac`，MySQL 8.0+，utf8mb4 / utf8mb4_0900_ai_ci，时区 UTC。
- Flyway 脚本归 Lead 维护，位于 `telbank-rbac-api/src/main/resources/db/migration/`：`V1__schema.sql`（8 张表，见 §9.2 偏差）、`V2__seed_data.sql`（CRM/ERP 示例数据）。**其他人不要改这两个文件**；需要字段/种子数据变更，向 Lead 提。
- 权限中心**不使用** `spring-boot-starter-flyway`：由 `FlywayConfig` 手动构造 `Flyway` Bean 并 `migrate()`（`baselineOnMigrate=true`，locations=`classpath:db/migration`）。
- 所有权限查询必须带时效过滤（需求 §5.3）：`(valid_from IS NULL OR valid_from <= NOW()) AND (valid_until IS NULL OR valid_until > NOW()) AND status = 1`。
- Primary key 均为 `BIGINT UNSIGNED`，Java 侧用 `Long`。表字段以 `V1__schema.sql` 为准。

---

## 3. 权限中心管理端 API（api ↔ front）

> `systemId`/`systemCode` 二选一：本文统一用 `systemId`（雪花/自增 id）；前端在系统选择器里持有 id。
> 所有写接口成功后由服务端自动发布 Redis 变更通知（§6），无需前端调用通知接口。

**管理端权限码（`@perm.has(...)` 使用，种子数据已授权给各系统 admin 角色）：**

| 权限码 | 覆盖接口 |
|---|---|
| `rbac:system:manage` | `/api/systems/**`、`/api/user-systems/**` |
| `rbac:role:manage` | `/api/roles/**`、`/api/user-roles/**`、`/api/roles/{id}/permissions/**` |
| `rbac:permission:manage` | `/api/permissions/**`、`/api/sdk-endpoints/**` |
| `rbac:page:manage` | `/api/page-actions/**` |
| `rbac:audit:view` | `/api/approval-grants`、`/api/expiring-grants` |

- 除 `/api/me`、`/api/me/systems` 仅要求已认证外，其余管理端接口都要求对应权限码（平台超管命中即放行）。
- 权限码在 `X-System-Code` 指向的系统内查询；缺 `X-System-Code` 且非平台超管 → 403。前端登录后总会带当前系统头。
- Seed 中 CRM admin（role 1）与 ERP admin（role 3）已获得各自系统内的上述 5 个权限码；operator/purchaser 没有，用于验收 403。

### 3.1 当前用户与系统

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/api/me` | — | `{"externalUserId":"...","platformAdmin":false}` |
| GET | `/api/me/systems` | — | `[{"id":1,"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}]`（平台超管返回全部启用系统） |

### 3.2 系统管理

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/systems?keyword=&page=&size=` | 分页列表，`data.items[]` = SysSystemVO `{id,code,name,region,status,sort,remark,createdAt,updatedAt}` |
| POST | `/api/systems` | body `{code,name,region,status?=1,sort?=0,remark?}`，`code` 全局唯一 |
| PUT | `/api/systems/{id}` | body `{name,region,status?,sort?,remark?}`（code 不可改） |
| DELETE | `/api/systems/{id}` | 停用（status=0），不物理删除 |

### 3.3 角色管理（按系统隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/roles?systemId=&keyword=&page=&size=` | items: `{id,systemId,code,name,status,sort,remark}` |
| POST | `/api/roles` | `{systemId,code,name,status?,sort?,remark?}`，`(systemId,code)` 唯一 |
| PUT | `/api/roles/{id}` | `{name,status?,sort?,remark?}` |
| DELETE | `/api/roles/{id}` | 停用 |

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

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/roles/{roleId}/permissions` | `{"roleId":1,"grants":[{"permissionId":10,"code":"order:delete","validFrom":null,"validUntil":"2027-12-31T23:59:59Z","status":1}]}` |
| POST | `/api/roles/{roleId}/permissions/grant` | `{permissionIds:[...],validFrom?,validUntil?,grantReason?}`；已存在关系→更新时效（见 §9.1），否则插入 |
| POST | `/api/roles/{roleId}/permissions/revoke` | `{permissionIds:[...]}`；status=0 |

### 3.6 用户-角色分配（含时效）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user-roles?systemId=&externalUserId=` | `[{"id":1,"systemId":1,"externalUserId":"13800138000","roleId":2,"roleCode":"admin","roleName":"管理员","validFrom":null,"validUntil":null,"status":1,"grantReason":null,"sourceApprovalId":null}]` |
| POST | `/api/user-roles` | `{systemId,externalUserId,roleId,validFrom?,validUntil?,grantReason?}` |
| DELETE | `/api/user-roles/{id}` | 撤销（status=0） |

### 3.7 用户-系统开通

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user-systems?externalUserId=` | `[{"id":1,"systemId":1,"systemCode":"crm","externalUserId":"13800138000","isDefault":true,"status":1}]` |
| POST | `/api/user-systems` | `{systemId,externalUserId,isDefault?=false}` |
| DELETE | `/api/user-systems/{id}` | 停用 |

### 3.8 页面操作元数据（模式 B）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/page-actions?systemId=&pageCode=&page=&size=` | items: `{id,systemId,pageCode,code,text,type,action,config,sort,status}` |
| POST | `/api/page-actions` | `{systemId,pageCode,code,text,type?="default",action,config?,sort?}`，`action ∈ {openDialog,callApi,download,navigate,confirmThenCallApi}` |
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
- 服务端校验：`appKey` 存在且其绑定的 `systemCode` 与请求中的 `systemCode` 一致；时间戳窗口 ±5 分钟；`nonce` 5 分钟内不可重复（Redis 实现，Redis 禁用时用 Caffeine 兜底）；签名常量时间比较。
- 失败返回 401，`{"code":401,"message":"invalid signature"}`。

### 4.2 接口清单

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/openapi/sdk/mappings?systemCode=crm` | — | `{"systemCode":"crm","mappings":[{"method":"DELETE","pattern":"/api/order/{id}","code":"order:delete"},{"method":"GET","pattern":"/api/order/**","code":"order:list"}]}` |
| GET | `/openapi/sdk/user-permissions?systemCode=&externalUserId=` | — | `{"permissions":[{"code":"order:delete","validFrom":null,"validUntil":"2026-10-31T23:59:59Z"}],"serverTime":"2026-10-24T05:00:00Z"}`；平台超管返回 `[{"code":"*","validFrom":null,"validUntil":null}]` |
| GET | `/openapi/sdk/user-systems?externalUserId=` | — | `[{"code":"crm","name":"CRM系统","region":"region_a","isDefault":true}]`；按该 AppKey 对应系统的权限上下文验签，返回该用户 `sys_user_system` 中有效且系统启用的列表（`is_default` 降序）；平台超管返回全部启用系统。供 SDK 的 `/rbac/me/systems` 使用，失败时 SDK 可降级为配置的系统单例 |
| GET | `/openapi/sdk/page-actions?systemCode=&pageCode=` | `pageCode` 可省略 | `{"systemCode":"crm","pageActions":[{"id":1,"pageCode":"order:list","code":"order:export","text":"导出","type":"default","action":"callApi","config":"{}","sort":10}]}` |
| GET | `/openapi/sdk/connection?systemCode=` | — | `{"channel":"redis","region":"region_a","host":"127.0.0.1","port":6379,"password":"...","channelPrefix":"rbac:change:region_a:"}` |
| POST | `/openapi/sdk/endpoints/sync` | `{"systemCode":"crm","endpoints":[{"method":"GET","path":"/api/order/list","candidateCode":"crm:order:list","source":"GENERATED"}]}` | `{"received":12,"created":10,"updated":2}` |
| POST | `/openapi/approval/grant` | 见 §5 | `{"success":true,"grantId":123,"idempotent":false}` |

映射表生成规则：`sys_permission` 中 `method` 与 `api_pattern` 均非空且 `status=1` 的记录，按 `system_id` 过滤后下发；`pattern` 为 Ant 风格（`**` 仅可结尾）。端点上报按 `(system_id, method, path)` 幂等 upsert，`candidateCode` 可为空。

### 4.3 开放接口数据口径（实现补充，已与 api/sdk 对齐）

- `user-permissions` 的每个 `code` 返回**有效窗口包络**：`validFrom` 取该码所有生效授权的 `valid_from` 最小值（任一为 null 则 null）；`validUntil` 取 `valid_until` 最大值，**任一授权为永久（null）则该码 `validUntil=null`**。窗口由 `user_role` 与 `role_permission` 两段时段的交集再聚合。
- `page-actions` 的 `config` 字段为 **JSON 字符串**（管理端创建/更新时接受字符串或对象）；`permission.meta` 为 JSON 对象。
- `endpoints/sync` 对非法条目宽容跳过：`received` 为原始条数，`created/updated` 只统计有效条目。
- `approval-grants?externalUserId=` 过滤时，`ROLE_PERMISSION` 类记录（无 external_user_id 列）不参与该条件下的结果，只返回 `USER_ROLE` 记录。
- api 进程默认时区强制 UTC（`TimeZone.setDefault(UTC)` + Hikari `SET time_zone='+00:00'`），保证 `DATETIME` 读写与 `NOW()` 比较口径一致。

### 4.4 审批幂等的边界

同一关系（用户+角色）重新授权会**更新原行**并覆盖 `source_approval_id`（契约 §9.1），数据模型无审批历史表；因此「新审批覆盖旧审批后，再回放更早的 approvalId」无法再幂等，属数据模型固有限制。同一 `approvalId` 的重复回调幂等必须成立（已实测）。

---

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

### 7.1 固定类名与职责（starter）

| 类 | 包 | 职责 |
|---|---|---|
| `RbacSdkAutoConfiguration` | `com.telbank.rbac.sdk` | 唯一自动配置入口，`@ConditionalOnProperty(rbac-sdk.enabled=true)`；注册下列全部组件；`spring.factories` 单轨注册 |
| `RbacSdkProperties` | `...sdk.config` | `@ConfigurationProperties("rbac-sdk")`，字段见需求 §6.13 |
| `RbacPermissionFilter` | `...sdk.filter` | `OncePerRequestFilter`；`FilterRegistrationBean` 注册 `/*`，order=`Ordered.LOWEST_PRECEDENCE-100` |
| `RbacController` | `...sdk.web` | `/rbac/me/systems`、`/rbac/me/bootstrap`（base-path 可配） |
| `RbacClient`（接口）/`DefaultRbacClient` | `...sdk.client` | 编程式查询；Feign + OkHttp 实现，返回 `SystemInfo`、`PageAction`、权限码集合 |
| `RbacPermission` | `...sdk.annotation` | `@Target({METHOD,TYPE})`，`value/name/isPublic` |
| `RbacEndpointScanner` | `...sdk.scan` | `ApplicationReadyEvent` 扫描 `RequestMappingHandlerMapping` 并上报 |
| `RbacMappingCache` | `...sdk.cache` | 映射表缓存 + Ant 匹配（精确优先，`**` 末尾） |
| `RbacUserPermissionCache` | `...sdk.cache` | `externalUserId -> systemCode -> code -> {validFrom,validUntil}`，无 TTL，通知驱动失效 |
| `RbacPageActionCache` | `...sdk.cache` | 页面操作元数据缓存 |
| `RbacChangeSubscriber` | `...sdk.notify` | Redis Pub/Sub 订阅（Lettuce/Jedis 均可，Boot 2.7 默认 Lettuce） |
| `RbacPollingFallback` | `...sdk.notify` | 兜底短轮询（默认 5 分钟，`cache.fallback-poll-interval`），SDK 自己持有 `ScheduledExecutorService`，不要求宿主 `@EnableScheduling` |
| `RbacSigner` | `...sdk.client` | §4.1 签名实现（SDK 与 api 必须逐字节一致） |

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
  jpa:
    hibernate.ddl-auto: none
    open-in-view: false
rbac:
  platform-admin-ids: [platform-admin-001]
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

见需求 §6.13，字段必须一一对应；`RbacSdkProperties` 用松散绑定。

---

## 9. 偏差与实现口径（需求文档未明确/自相矛盾处，已锁定）

> 与需求文档「附录 A 实现修订记录」的对应：A1 = §9.2、A2 = §9.1、A3 = §9.3、A4 = §9.4、A5 = §3.4/§7.7、A6 = §4.2、A7 = §0。

1. **重新提权的落库方式**：需求要求“旧记录标记 status=0，新记录插入”，但 `sys_user_role`/`sys_role_permission` 的唯一键 `(system_id,external_user_id,role_id)`/`(role_id,permission_id)` 不允许同一关系存在两条记录，两者不可同时满足。**实现口径：同关系重新授权时更新原行的 `valid_from/valid_until/source_approval_id/grant_reason/status=1`（不新增行）；已过期的旧授权保留 status=0 直到再次被授权时复用同一行。** 该偏差必须在 README「与需求文档的偏差」一节列明。
2. **SDK 端点上报需要持久化**：需求“共 7 张表”中没有存端点上报的表，但 §5.7 要求端点上报列表与关联权限码。**实现口径：新增第 8 张表 `sys_sdk_endpoint`**（见 `V1__schema.sql`），其余 7 张严格按需求。README 偏差清单列明。
3. **审批回调 `grantType=permission`**：数据模型没有 user_permission 表，无法绕过角色直接授权。**实现口径：仅支持 `grantType=role`，`permission` 返回 400 并说明原因**；`permissionCodes` 仅用于校验审批内容与角色授权一致。
4. **AppKey/AppSecret 与区域 Redis 连接配置的存放**：需求说 AppSecret/Redis 密码“不在配置文件明文、数据库加密存储”，但 7 张表中没有对应表。**实现口径：AppSecret 与 Redis 密码通过环境变量注入（配置文件中只写 `${ENV:默认值}`），本地 demo 默认值为开发用途；不新增密钥表。** README 说明生产注入方式。
5. **权限中心二级缓存**：管理端高频查询用 Caffeine 一级缓存；Redis 用于 Pub/Sub、nonce 防重放与（可选）权限集缓存，不额外实现复杂二级缓存协议。
6. **菜单结构**：权限中心不存储菜单/路由（需求 §2），管理前端的系统选择器/菜单由前端代码维护；不要给 `sys_permission` 加 path/component/icon 字段。
7. **业务前端数据获取**：管理前端（telbank-rbac-front）只调权限中心 `/api/**`；SDK 的 `/rbac/me/*` 属于业务系统后端（starter），两者不要混用。

---

## 10. 前端契约（telbank-rbac-front）

- 技术栈严格按需求 §3.3；包管理器 `npm`；Node 通过 nvm 使用 22.23.1+。
- 环境变量：`VITE_API_BASE_URL`（默认 `http://localhost:8080`）。
- 登录：`/login` 页面粘贴 JWT（外部认证系统签发；本地用 `scripts/gen-demo-jwt.sh` 生成），存 `localStorage["rbac.token"]`；请求头注入 `Authorization: Bearer`；当前系统存 `localStorage["rbac.systemCode"]`。
- 系统上下文：登录后 GET `/api/me` + `/api/me/systems`；顶部系统切换器；切换后所有查询带 `X-System-Code`（SDK 开放接口不用这个头，管理端用）。
- 页面（对应需求 §5.7）：系统管理、角色管理、权限资源管理（树）、角色-权限授权（树勾选 + 时效）、用户-角色分配（含时效）、用户-系统开通、页面操作元数据、SDK 端点上报、审批授权记录、即将到期授权。
- 状态：TanStack Query 管理服务端状态；Zustand 管理当前用户/系统/权限码集合；不使用 Axios，使用封装的 `request()`（原生 fetch）。
- 必须 `npm run build` 通过（含 `tsc` 类型检查）。

---

## 11. 验收与命令（已落地）

- 一键验证：`./scripts/verify-all.sh`（后端 46 单测 + SDK 90 单测 + 前端类型检查/构建 + 产物核对）
- 权限中心：`./scripts/start-api.sh`（需要 JDK 21+；demo profile）；验收 `python3 scripts/acceptance.py`
- SDK：`./scripts/build-sdk.sh`（JDK 17+ 工具链 → Java 8 字节码，校验 major=52）；CI 兼容性验证 `.github/workflows/sdk-jdk8-compat.yml`
- 管理前端：`cd telbank-rbac-front && npm install && npm run dev|build`（nvm Node 22）
- 示例业务系统：`samples/business-demo`（Boot 2.7.18，JDK 8+ 运行时）
- 验收矩阵与执行记录：`docs/acceptance.md`
