# 业务用户临时权限申请 · 设计文档

- 状态：设计已确认（用户选择「按此设计，先写设计文档和计划」）
- 日期：2026-10-25
- 关联：需求 `docs/telbank-rbac.md`、契约 `docs/impl/contracts.md`、验收 `docs/acceptance.md`
- 范围：权限中心管理端（`telbank-rbac-front`）+ `telbank-rbac-api`；SDK 不改协议

## 1. 背景与问题

当前权限中心只有管理端授权入口：系统管理员在「角色授权 / 用户授权」页面给用户分配角色和时效。业务用户（例如 `13600136000`）只有业务权限码，没有管理权限短码，登录后只能看到「无管理权限」空态，无法自助申请临时权限。

需要增加一个**业务用户自助申请**入口：

1. 业务用户在当前系统内选择角色（可多选），查看每个角色的权限内容；
2. 提交后生成一张申请单，有申请单号、状态和申请原因；
3. 申请单跳转到外部审批平台审批；
4. 审批通过后，自动把申请的角色以**一个连续的临时有效期**授予申请人；审批驳回则只更新状态，不授权；
5. 不新建审批工作流，也不新增用户级权限表。

## 2. 目标与非目标

### 2.1 目标

- 任意已登录、且在**当前业务系统**拥有至少一个有效角色的用户，都能进入「我的申请」。
- 申请对象是当前系统的**非 admin 角色**，支持多选；申请页展示角色的权限预览。
- 申请单唯一编号、可查询列表/详情、状态可追踪、可跳转审批平台。
- 审批通过后复用现有 `sys_user_role.valid_from/valid_until` 落库：一个申请对应一个连续窗口，多角色共享同一窗口。
- 审批回调原子化（全成或全败）、按申请单号幂等，重复回调不重复授权。
- 通过后立即走现有 Redis 变更通知、SDK 缓存失效与过期扫描链路。
- 顺带完成角色授权页的 `*` 通配权限收回：非 admin 角色不能再被授予 `*`，前端也不展示 `*`。

### 2.2 非目标

- 不做审批流引擎、审批页面、审批人配置、会签/或签等能力。
- 不做按权限粒度的用户授权（不新增 `sys_user_permission`），不修改 `sys_permission` / `sys_role_permission` 表。
- 不做「按天重复的时段」（例如每天 19:00–次日 07:00）；审批只决定一个连续窗口。
- 不支持跨系统申请；申请单只在创建时的当前业务系统内有效。
- 不改变 SDK 的 `/rbac/me/*` 协议和既有审批回调 `/openapi/approval/grant`。
- 不提供申请撤销、修改、重新提交能力；驳回后可重新发起新申请。待审批期间重复提交由后端拦截。
- 不实现真实审批平台；提供可配置 URL + 本地模拟脚本。

## 3. 已锁定决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 审批集成方式 | 外部审批平台通过开放接口回调；权限中心只维护申请单与结果 |
| 2 | 申请单 | 有唯一申请单号和状态；点击申请单号跳外部审批平台 |
| 3 | 授权对象 | 角色，多选；申请页展示角色权限预览 |
| 4 | 时效模型 | 审批通过时确定**一个连续区间**；不按天重复，不由申请人填写期望时效 |
| 5 | 落库模型 | 复用 `sys_user_role` 的 `valid_from/valid_until`，不新增用户级权限表 |
| 6 | 可申请系统 | 仅当前系统，且申请人必须在该系统已有有效角色 |
| 7 | 可申请角色 | 当前系统内 `status=1`、`code != 'admin'` 的角色；后端强制校验 |
| 8 | 审批平台 | 当前没有真实审批平台；只做申请页 + `scripts/mock-access-approval.py` 模拟审批 |
| 9 | 回调接口 | 新增独立回调，不复用 `/openapi/approval/grant`；旧接口保持不变 |
| 10 | 角色授权页 `*` | 非 admin 角色禁止再授予 `*`；角色授权树不展示 `*`；admin 角色仍由现有 A18 规则保护 |

## 4. 用户流程

### 4.1 申请人

1. 登录管理前端，系统上下文为 `crm`（`X-System-Code: crm`）。
2. 看到顶级菜单「我的申请」；进入页面后加载可选角色。
3. 勾选一个或多个角色，右侧/下方展示角色权限预览，填写申请原因，提交。
4. 列表出现新申请：状态 `待审批`，显示申请单号，点击单号跳转审批平台。
5. 审批完成后刷新/重新进入：状态变为 `已通过` 或 `已驳回`；已通过显示统一授权窗口。
6. 已通过的角色在现有权限查询/SDK 中立即生效，过期后由现有 `ExpiryScanService` 自动收回。

### 4.2 审批方

外部审批平台（或本地模拟脚本）拿到申请单号后：

1. 审批人决定通过或驳回；
2. 通过时传入 `validFrom`、`validUntil`（UTC ISO-8601），可带审批意见；
3. 调用 `POST /openapi/approval/access-applications/decision`，使用审批专用 AppKey 签名；
4. 回调返回最新状态；同一申请单重复回调幂等。

## 5. 数据模型

新增迁移：`telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql`。**只新增两张申请表**，不修改既有权限表。

### 5.1 `sys_access_application`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK AUTO_INCREMENT | 主键 |
| `application_no` | VARCHAR(40) NOT NULL | 申请单号，如 `RA-20261025-A1B2C3`，全局唯一 |
| `system_id` | BIGINT UNSIGNED NOT NULL | 当前业务系统 |
| `external_user_id` | VARCHAR(255) NOT NULL | 申请人外部账号（JWT sub） |
| `status` | TINYINT NOT NULL DEFAULT 0 | 0 待审批 / 1 已通过 / 2 已驳回 |
| `reason` | VARCHAR(200) NOT NULL | 申请原因 |
| `approved_valid_from` | DATETIME NULL | 审批通过后的统一生效时间（UTC） |
| `approved_valid_until` | DATETIME NULL | 审批通过后的统一失效时间（UTC） |
| `decision_comment` | VARCHAR(255) NULL | 审批意见 |
| `reviewed_by` | VARCHAR(128) NULL | 审批方标识（外部审批平台传入） |
| `reviewed_at` | DATETIME NULL | 审批时间 |
| `created_at` / `updated_at` | DATETIME NOT NULL | 现有 `BaseEntity` 口径 |
| `pending_user_key` | VARCHAR(300) GENERATED ALWAYS AS (`status=0` 时 `CONCAT(system_id, ':', external_user_id)`，否则 NULL) STORED | 待审批唯一键 |

索引/约束：

- `UNIQUE KEY uk_access_application_no (application_no)`
- `UNIQUE KEY uk_access_application_pending (pending_user_key)`：同一系统 + 同一申请人最多一条待审批，避免并发重复提交；通过/驳回后生成列为 NULL，自动释放。
- `KEY idx_access_application_user (system_id, external_user_id, created_at)`
- `KEY idx_access_application_status (status, created_at)`

### 5.2 `sys_access_application_role`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK AUTO_INCREMENT | 主键 |
| `application_id` | BIGINT UNSIGNED NOT NULL | 关联申请单 |
| `role_id` | BIGINT UNSIGNED NOT NULL | 角色 id |
| `role_code` | VARCHAR(64) NOT NULL | 创建时快照 |
| `role_name` | VARCHAR(100) NOT NULL | 创建时快照 |
| `created_at` / `updated_at` | DATETIME NOT NULL | 现有 `BaseEntity` 口径（实现沿用公共实体基类） |

- `UNIQUE KEY uk_access_application_role (application_id, role_id)`
- `KEY idx_access_application_role_role (role_id)`

角色快照只用于展示申请时的角色名称；审批时仍以当前 `sys_role` 数据校验，角色被停用或删除则审批失败。

### 5.3 不新增/不修改

- 不新增 `sys_user_permission`。
- 不修改 `sys_user_role` 表结构；只写现有行。
- 不修改 `sys_permission` / `sys_role_permission` 的时效模板字段。
- 不新增权限码、不新增后台管理菜单。

## 6. 申请人 API（`/api/access-applications`）

所有接口都依赖 `ManagementContextFilter` 的 `X-System-Code`，服务层再调用 `ManagementContextService.requireContext()`，因此要求当前业务系统启用且申请人有有效角色。**不需要管理权限码**，也不加 `@PreAuthorize`。

### 6.1 可申请选项

`GET /api/access-applications/options`

```json
{
  "systemId": 1,
  "systemCode": "crm",
  "systemName": "CRM系统",
  "approvalUrlTemplate": "http://localhost:8090/approval/access?applicationNo={applicationNo}",
  "roles": [
    {
      "roleId": 2,
      "roleCode": "operator",
      "roleName": "CRM运营",
      "owned": true,
      "permissions": [
        {"id": 4, "code": "order:delete", "name": "删除订单", "type": 2}
      ]
    }
  ]
}
```

- 角色过滤：当前系统、`status=1`、`code != 'admin'`；平台系统不会进入（管理上下文只允许业务系统）。
- `owned=true` 表示申请人当前有效持有该角色（`sys_user_role.status=1` 且未过 `valid_until`）；前端禁止勾选，后端创建时二次校验。
- `permissions` 只返回当前有效角色-权限关系中的权限（`status=1`，且当前时间在 `valid_from/valid_until` 内），用于预览；不要求 `*`（正常不会出现）。
- `approvalUrlTemplate` 来自配置，前端把 `{applicationNo}` 替换为申请单号。

### 6.2 创建申请

`POST /api/access-applications`

```json
{
  "roleIds": [2, 5],
  "reason": "季度末需要批量导出订单"
}
```

校验：

- `roleIds` 非空、去重、最多 20 个；
- `reason` 必填，1–200 字；
- 每个角色属于当前系统、启用、`code != 'admin'`；
- 不允许申请人当前已有效持有该角色；
- 同一系统 + 同一申请人没有其他待审批申请（数据库唯一键兜底）。

成功返回 `201`（项目统一用 `ApiResponse`，HTTP 200 也可，契约按现有风格返回 200 + `data`）的申请详情 VO；失败：

- 400：参数/角色不合法、已持有角色；
- 409：已有待审批申请；
- 403：无系统上下文或申请人在当前系统无有效角色。

### 6.3 我的申请分页

`GET /api/access-applications?status=&page=&size=`

- 只查当前系统 + 当前 `external_user_id`；
- `status` 可选 `PENDING|APPROVED|REJECTED`；
- `page` 默认 1，`size` 默认 20，最大 200（复用 `PageQuery`）；
- 按 `id DESC` 排序。

返回 `PageResult<AccessApplicationVO>`，VO 至少包含：

```json
{
  "id": 12,
  "applicationNo": "RA-20261025-A1B2C3",
  "status": "PENDING",
  "roleCount": 2,
  "roles": [{"roleId": 2, "roleCode": "operator", "roleName": "CRM运营"}],
  "reason": "季度末需要批量导出订单",
  "approvedValidFrom": null,
  "approvedValidUntil": null,
  "decisionComment": null,
  "reviewedBy": null,
  "reviewedAt": null,
  "createdAt": "2026-10-25T01:20:00Z",
  "approvalUrl": "http://localhost:8090/approval/access?applicationNo=RA-20261025-A1B2C3"
}
```

### 6.4 申请详情

`GET /api/access-applications/{applicationNo}`

- 必须属于当前系统 + 当前用户；否则返回 404（避免泄露其他用户申请单是否存在）；
- 返回与分页相同的 VO。

### 6.5 申请单号

- 格式：`RA-yyyyMMdd-` + 6 位大写字母/数字随机串，例如 `RA-20261025-A1B2C3`；
- 服务端生成，`SecureRandom`；插入冲突最多重试 5 次，仍失败返回 500；
- 申请单号全局唯一，同时作为审批平台的业务主键。

## 7. 审批回调（外部审批平台 → api）

### 7.1 接口

```http
POST /openapi/approval/access-applications/decision
X-App-Key: approval-system
X-Timestamp / X-Nonce / X-Signature   # 与契约 §4.1 相同的签名算法
Content-Type: application/json
```

```json
{
  "applicationNo": "RA-20261025-A1B2C3",
  "decision": "APPROVED",
  "validFrom": "2026-10-25T01:30:00Z",
  "validUntil": "2026-10-26T01:30:00Z",
  "decisionComment": "同意，仅限本次月末处理",
  "reviewer": "approval-platform"
}
```

- `decision`：`APPROVED` | `REJECTED`；
- `APPROVED` 时 `validFrom`/`validUntil` 必填，且 `validUntil > validFrom`；允许 `validFrom` 早于当前时间（补录），但 `validUntil` 必须晚于当前时间（否则无实际授权意义，返回 400）；
- `REJECTED` 时两个时间字段忽略；
- `reviewer` 可选，默认 `external-approval`；`decisionComment` 可选，最大 255 字。

响应：

```json
{
  "applicationNo": "RA-20261025-A1B2C3",
  "status": "APPROVED",
  "idempotent": false,
  "grantedRoleIds": [2, 5],
  "approvedValidFrom": "2026-10-25T01:30:00Z",
  "approvedValidUntil": "2026-10-26T01:30:00Z"
}
```

- 验签失败：401；申请单不存在：404；状态冲突：409；参数错误：400。

### 7.2 处理规则

1. 使用 `rbac.approval` 的 AppKey/AppSecret 验签；路径位于 `/openapi/approval/` 前缀，由现有 `AppKeyAuthFilter` 的审批分支处理，不绑定 `systemCode`。
2. 按 `application_no` 锁定申请单（`SELECT ... FOR UPDATE`）。
3. 若已是 `APPROVED`：
   - 同一 `decision=APPROVED` 且 `validFrom/validUntil` 与申请单已保存值一致 → 返回成功、`idempotent=true`，不重复授权；
   - 其他情况 → 409「申请单已审批，不能重复决策」。
4. 若已是 `REJECTED`：仅同一 `decision=REJECTED` 返回幂等成功，否则 409。
5. 仅 `PENDING` 可决策。
6. `APPROVED` 事务内：
   - 重新校验角色仍属于申请单系统、`status=1`、`code != 'admin'`；
   - 重新校验申请人当前没有有效持有这些角色；
   - 对每个角色调用 `ApprovalGrantService.grant(...)`，参数：
     - `systemCode` = 申请单系统 code；
     - `externalUserId` = 申请人；
     - `grantType="role"`；
     - `roleId` = 角色 id；
     - `validFrom/validUntil` = 本次审批窗口；
     - `approvalId` = `applicationNo + ":" + roleId`（保证 128 字符内且可追溯）；
     - `reason` = 截断后的申请原因 + 申请单号（不超过 `grant_reason` 255 字符）。
   - 更新申请单：`status=APPROVED`、`approved_valid_from/until`、`decision_comment`、`reviewed_by`、`reviewed_at`；
   - 任一角色失败则整个事务回滚，申请单保持 `PENDING`。
7. `REJECTED` 事务内只更新状态、审批字段，不写 `sys_user_role`。
8. 通过后由 `ApprovalGrantService` 对每个角色发布 `PERMISSION_CHANGED`（事务提交后）；SDK 缓存失效与前端刷新走现有逻辑。
9. 旧 `POST /openapi/approval/grant` 不修改、不复用；它继续支持单个角色、旧审批系统的 `approvalId` 幂等。

### 7.3 与现有授权落库口径的关系

`ApprovalGrantService` 对同一 `(system_id, external_user_id, role_id)` 已存在记录的处理是**更新原行**（契约 §9.1）。因此本功能在创建申请和审批通过前都必须检查「当前是否已有效持有该角色」，避免审批覆盖用户已有的更长期限/更宽权限窗口。

已过期/已停用的旧记录可以被覆盖（复用原行），这是既有口径，不新增行。若审批窗口短于原记录时效，也因前置校验不会发生。

## 8. 后端设计

### 8.1 新增类

- `domain/entity/SysAccessApplication.java`
- `domain/entity/SysAccessApplicationRole.java`
- `domain/mapper/SysAccessApplicationMapper.java`
- `domain/mapper/SysAccessApplicationRoleMapper.java`
- `domain/mapper/row/AccessRolePermissionRow.java`
- `service/AccessApplicationService.java`
- `service/AccessDecisionService.java`
- `web/admin/AccessApplicationController.java`
- `web/openapi/OpenApiAccessApplicationController.java`
- `web/dto/AccessApplicationDtos.java`

### 8.2 Service 方法

```java
// options/create/page/detail 在 AccessApplicationService；decide 在 AccessDecisionService。
OptionsVO options();
ApplicationVO create(CreateRequest request);
PageResult<ApplicationVO> page(String status, PageQuery pageQuery);
ApplicationVO detail(String applicationNo);
DecisionResponse decide(DecisionRequest request);
```

关键实现点：

- `options/create/page/detail` 通过 `ManagementContextService.requireContext()` 取当前 `systemId/systemCode`，通过 `CurrentUserProvider.requireExternalUserId()` 取申请人；
- `create` 在事务内查重（含数据库唯一键兜底），生成申请单号，写主表 + 角色快照；
- `page/detail` 强制 `system_id + external_user_id` 过滤；
- `decide` 使用 `@Transactional` + 行锁；审批人与申请人不耦合；
- 所有错误使用 `ApiException`（400/403/404/409），由现有 `GlobalExceptionHandler` 输出统一 JSON；
- 时间统一 `Instant`（DTO）↔ `LocalDateTime UTC`（实体），与现有 `TimeUtils` 一致。

### 8.3 Mapper 关键 SQL

- `findByApplicationNo(String)`；
- `findByApplicationNoForUpdate(String)`：`SELECT * ... WHERE application_no = #{applicationNo} FOR UPDATE`；
- `search(Page, systemId, externalUserId, status)`：按当前系统 + 当前用户过滤；
- `findByApplicationId(Long)`：角色快照；
- 查用户是否已持有角色可复用 `SysUserRoleMapper.findBySystemIdAndExternalUserIdAndRoleId` 后在服务层判断状态/有效期，或新增 `countActiveBySystemIdAndExternalUserIdAndRoleIds`；推荐后者，避免 N+1 与时间判断分散。

### 8.4 控制器

```java
@RestController
@RequestMapping("/api/access-applications")
public class AccessApplicationController {
    // GET /options, GET, POST, GET /{applicationNo}
}
```

不加 `@PreAuthorize`；服务层做系统上下文与数据归属校验。

```java
@RestController
@RequestMapping("/openapi/approval/access-applications")
public class OpenApiAccessApplicationController {
    // POST /decision
}
```

`/openapi/**` 已在 `SecurityConfig` permitAll，由 `AppKeyAuthFilter` 负责审批 AppKey 验签。

### 8.5 配置

`RbacProperties` 新增：

```yaml
rbac:
  access-approval:
    url-template: ${RBAC_ACCESS_APPROVAL_URL_TEMPLATE:http://localhost:8090/approval/access?applicationNo={applicationNo}}
```

- 模板必须包含 `{applicationNo}`，否则启动时记录 WARN 并原样返回；
- 返回给前端的是替换后的绝对 URL；不存储到数据库，避免历史申请单因配置变更失效。

### 8.6 常量

在 `RbacConstants` 增加：

- `ACCESS_APPLICATION_STATUS_PENDING = 0`
- `ACCESS_APPLICATION_STATUS_APPROVED = 1`
- `ACCESS_APPLICATION_STATUS_REJECTED = 2`

状态字符串转换放在 `AccessApplicationService`，与 `SystemApplicationService` 风格一致。

## 9. 前端设计（`telbank-rbac-front`）

### 9.1 菜单与路由

- 新增页面 `src/pages/AccessApplicationsPage.tsx`，路由 `/access-applications`，不包 `PermissionGuard`。
- `src/config/menu.ts`：
  - `ManagementMenuItem` 增加可选 `alwaysVisible?: boolean`；
  - 新增顶级菜单项 `{ path: '/access-applications', label: '我的申请', alwaysVisible: true }`；
  - `isMenuItemVisible` 对 `alwaysVisible` 直接返回 true；
  - `getFirstPermittedPath` 优先返回非 `alwaysVisible` 的管理菜单，只有没有管理菜单时才返回 `alwaysVisible` 项（业务用户首页落到「我的申请」，管理员首页仍落到原管理页）。
- `src/layouts/AdminLayout.tsx`：
  - 用 `menuItems.length > 0`（任意可见菜单，含自服务）判断是否渲染 `<Outlet />`；业务用户有「我的申请」时不再提前渲染「无管理权限」空态；
  - 保留空态作为兜底（理论上不会触发）；
  - `hasManagementAccess` 若不再使用则删除，并清理 `NoPermission` import，避免 `tsc` 未使用导入报错。
- `HomeRedirect` 使用更新后的 `getFirstPermittedPath`。

### 9.2 页面结构

- `PageShell`：标题「我的申请」，描述说明当前系统、临时授权与审批流程。
- 顶部：状态筛选（全部/待审批/已通过/已驳回）+「新建申请」按钮 + 刷新。
- 列表：内容优先表格（A19）；
  - 申请单号：可点击链接，`approvalUrl` 新窗口打开；
  - 角色：Tag 展示，过多时折叠；
  - 状态：`待审批`（warning）/`已通过`（success）/`已驳回`（error）；
  - 授权有效期：`YYYY-MM-DD HH:mm`；未通过显示 `-`；
  - 申请时间；
  - 操作：查看详情。
- 新建申请 Modal：
  - 当前系统提示；
  - 角色多选（`Select mode="multiple"`）；`owned=true` 角色 disabled 并标注「已拥有」；
  - 角色权限预览：按角色分组展示权限名称/码；无权限时提示；
  - 申请原因 TextArea（max 200，showCount）；
  - 提交按钮 loading；
  - 后端错误逐字展示（尤其 409「已有待审批申请」/「角色已被授予」）。
- 详情 Drawer/Modal：
  - 申请单号、状态、角色、原因、审批时间、审批意见、统一授权窗口；
  - 审批平台按钮；
  - 不提供审批操作（审批在外部平台）。

### 9.3 API 模块

新增 `src/api/accessApplications.ts`：

```ts
getAccessApplicationOptions()
createAccessApplication(payload)
getAccessApplications({status,page,size})
getAccessApplication(applicationNo)
```

类型加到 `src/api/types.ts`，`src/api/index.ts` 导出。

### 9.4 跨系统切换

- 页面查询 key 携带 `currentSystemId`（现有约定）；
- 切换系统时只 `sessionQuery.refetch()`，页面自然按新系统重新查询（沿用 A18 修复口径，不能全局 invalidate 旧系统查询）；
- 申请列表/选项不跨系统缓存。

## 10. 模拟审批脚本与文档

### 10.1 `scripts/mock-access-approval.py`

- 纯 Python 标准库，复用契约 §4.1 的 canonical 签名逻辑（与 `acceptance.py`/`sign-request.py` 一致）；
- 参数：
  - `--application-no`（必填）
  - `--decision APPROVED|REJECTED`（默认 APPROVED）
  - `--valid-from` / `--valid-until`（ISO-8601；通过时默认 now / now+1 天）
  - `--comment`、`--reviewer`、`--base-url`
  - 环境变量 `RBAC_APPROVAL_APP_KEY` / `RBAC_APPROVAL_SECRET`，默认 `approval-system` / `approval-dev-secret`
- 为本地演示打印请求体、响应和最终状态；
- 失败时非 0 退出，便于脚本化验收。

### 10.2 文档

- `docs/telbank-rbac.md` 附录 A 增加本次修订项（自助申请、连续窗口、申请表、`*` 收回）；
- `docs/impl/contracts.md` 增加 §3.11（申请人 API）与 §5.1（新审批回调），更新目录/接口清单；
- `docs/acceptance.md` 增加 §9.17 自助申请验收记录；
- `README.md` 增加「业务用户申请临时权限」使用说明、审批脚本、配置项；demo 账号说明补充申请流程；
- `telbank-rbac-front/README.md` 增加「我的申请」页面说明；
- `docs/README.md` 更新版本/当前状态/修订记录。

## 11. 权限隔离与安全

- **系统隔离**：所有申请人 API 强制 `X-System-Code` + `ManagementContextService.requireContext()`；列表/详情只查 `system_id + external_user_id`；详情不存在与不属于当前用户/系统统一 404。
- **申请人准入**：管理上下文已要求当前系统有效角色；无任何角色时无法进入该系统的管理前端。
- **角色范围**：只允许当前系统、启用、非 admin 角色；不允许平台系统；不允许 `*` 角色（admin 角色被排除）。
- **管理权限**：申请页不需要管理权限码；管理端授权规则不变。
- **审批回调**：仅审批专用 AppKey + HMAC；不校验 `systemCode`（申请单号已绑定 system_id）；行锁保证并发决策幂等；审批内容不可信，服务端重新校验角色与归属。
- **防越权**：申请人不能通过改 roleIds 申请其他系统角色；审批回调不能给任意用户授权，只能处理已存在的申请单。
- **审计**：申请单保留原因、审批意见、审批人、审批时间；角色名快照防止角色改名后历史不可读。
- **不记录敏感信息**：申请原因不应包含密码/密钥；文档提示。

## 12. 边界与错误处理

| 场景 | 结果 |
|---|---|
| 当前系统无有效角色 | 管理上下文 403；前端不可进入该系统 |
| 申请 admin 角色 | 400「系统管理员角色不可自助申请」 |
| 申请其他系统角色 | 400「角色不属于当前系统」 |
| 已有效持有角色 | 400「已拥有该角色，无需申请」；`options` 中 `owned=true` 且 disabled |
| 已有待审批申请 | 409「已有待审批申请，请等待审批完成」 |
| 申请单号不存在 | 404 |
| 查看他人申请 | 404（不泄露存在性） |
| 审批时角色已停用/删除 | 409/400，申请保持 PENDING |
| 审批时申请人已持有角色 | 409，申请保持 PENDING，需管理员或重新申请 |
| 回调重复且内容一致 | 200 + `idempotent=true`，不重复授权 |
| 回调重复但内容不一致 | 409 |
| `validUntil <= validFrom` 或 `validUntil <= now` | 400 |
| 审批 AppKey 错误/签名失败 | 401 |
| 通过后授权已过期 | 申请状态仍 APPROVED；现有过期扫描将 `sys_user_role.status` 置 0，权限查询自动不返回 |

## 13. 迁移、兼容与回滚

- 迁移文件只新增两张表；不修改历史迁移。`scripts/init-db.sh` 按文件名顺序执行，自动包含 10。
- 已部署环境执行 `10_access_application.sql` 即可；无需回填数据。
- 新旧 API 并存：旧审批回调不受影响；申请功能未启用时仅多出空表。
- 回滚：停用前端菜单/路由并回滚后端版本即可；两张申请表可保留（无既有表引用），不做破坏性 DROP。
- SDK 不需要升级；通过结果通过现有 `/openapi/sdk/user-permissions` 与 Redis 通知生效。

## 14. 测试与验收

### 14.1 后端

- `AccessApplicationServiceTest`：
  - options 过滤 admin/停用角色、标注 owned、返回权限预览；
  - create 成功生成 PENDING + 角色快照 + 申请单号格式；
  - 重复待审批、已持有角色、跨系统角色、admin 角色、空 roleIds、超长 reason 均拒绝；
  - page/detail 只返回当前系统 + 当前用户；
  - approve 多角色写入统一窗口，`approvalId` 为 `applicationNo:roleId`，状态/审批字段正确；
  - 重复通过幂等；重复驳回幂等；通过后再驳回 409；驳回后再通过 409；
  - 审批窗口非法、角色已停用、申请人已持有角色 → 失败且不写任何授权；
  - reject 不调用授权。
- `OpenApiAccessApplicationControllerTest` / `AppKeyAuthFilterTest`：新回调路径使用 approval AppKey；错误 AppKey 401。
- `RolePermissionServiceTest`：非 admin 角色授予 `*` → 400；admin 角色原有 400 不变。
- `MapperContractTest`：新增 mapper 契约（表名、查询方法）。

### 14.2 前端

- `npm run build`（`tsc -b` + vite）通过；
- 业务用户（如 `13600136000`）登录 CRM：菜单只有「我的申请」+ 空态不再出现；新建申请可选到未拥有角色，已拥有角色 disabled；
- 管理用户（crm-admin-001）菜单仍为原管理菜单，首页仍落到 `/roles` 或首个管理页，不被「我的申请」抢占；
- 切换 CRM/ERP 后申请列表按当前系统刷新，无跨系统数据；
- 状态筛选、详情、外链、错误提示、loading 验收。

### 14.3 验收脚本

在 `scripts/acceptance.py` 增加 §9.17：

1. crm-admin 登录，创建一个临时测试角色（如 `apply-auditor`）并授予一个业务权限；
2. 136 用户 CRM 上下文调用 options，确认角色出现且未拥有；
3. 创建申请 → PENDING；重复创建 → 409；admin 角色申请 → 400；
4. approval AppKey 签名调用 decision（APPROVED + 1 小时窗口）→ 200；再次调用 → `idempotent=true`；
5. 136 的 `/api/me/bootstrap` 出现新角色对应的权限码；
6. 用 ERP 上下文查 CRM 申请单 → 404；
7. 新建第二张申请并驳回 → 状态 REJECTED，权限未增加；
8. 脚本方式调用一次（可选，手工步骤）验证 `mock-access-approval.py`；
9. 全部使用 `check(...)` 记录，注册到 `TESTS`。

### 14.4 一键验证

- `./scripts/verify-all.sh` 增加：
  - `10_access_application.sql` 存在且包含两张表；
  - 前端构建产物包含 `/access-applications` 页面；
- 后端测试数量、前端构建、SDK 测试保持全绿。

## 15. 风险与已知限制

1. **审批与授权之间的数据漂移**：申请时预览的权限可能因角色权限调整而变化；审批通过时按当时角色权限生效。申请单只快照角色名，不快照权限集合。
2. **角色已有有效授权**：为避免覆盖已有更优窗口，创建和审批都拒绝；用户需等旧授权过期或联系管理员。这是对既有「同关系覆盖」口径的收紧。
3. **待审批唯一性**：用 MySQL 生成列 + 唯一索引保证单系统单用户一条 PENDING；若未来支持多审批并行，需要调整。
4. **申请单不可修改/撤销**：当前版本只支持重新发起；驳回后重新申请会生成新单号。
5. **审批 URL 占位**：默认 `localhost:8090` 只是占位，生产必须配置 `RBAC_ACCESS_APPROVAL_URL_TEMPLATE`；无审批平台时可复制申请单号给脚本。
6. **多角色非严格同一权限窗口**：`sys_user_role` 窗口相同，但各角色权限自身 `sys_role_permission` 可能带不同时效；最终生效权限为「用户-角色窗口 ∩ 角色-权限窗口」，这是既有模型，不改变。
7. **`*` 收回的兼容**：如果历史非 admin 角色已经有 `*` 授权，前端不再展示，但现有授权仍会生效；本次不做数据清理。如需清理，另写迁移（本次不默认执行，避免影响未知环境）。

## 16. 涉及文件清单

后端：

- `telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql`（新增）
- `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/entity/SysAccessApplication.java`（新增）
- `.../domain/entity/SysAccessApplicationRole.java`（新增）
- `.../domain/mapper/SysAccessApplicationMapper.java`（新增）
- `.../domain/mapper/SysAccessApplicationRoleMapper.java`（新增）
- `.../service/AccessApplicationService.java`（新增）
- `.../web/admin/AccessApplicationController.java`（新增）
- `.../web/openapi/OpenApiAccessApplicationController.java`（新增）
- `.../web/dto/AccessApplicationDtos.java`（新增）
- `.../common/RbacConstants.java`（状态常量）
- `.../config/RbacProperties.java`、`application.yml`（审批 URL 模板）
- `.../service/RolePermissionService.java`（拒绝 `*`）
- 对应测试类（新增/修改）

前端：

- `src/pages/AccessApplicationsPage.tsx`（新增）
- `src/api/accessApplications.ts`（新增）
- `src/api/types.ts`、`src/api/index.ts`
- `src/router.tsx`、`src/config/menu.ts`、`src/layouts/AdminLayout.tsx`
- `src/pages/RolePermissionsPage.tsx`（过滤 `*`）
- `src/styles/global.css`（少量样式）
- `telbank-rbac-front/README.md`

脚本/文档：

- `scripts/mock-access-approval.py`（新增）
- `scripts/acceptance.py`、`scripts/verify-all.sh`
- `docs/telbank-rbac.md`、`docs/impl/contracts.md`、`docs/acceptance.md`、`README.md`、`docs/README.md`

## 17. 待实现顺序

1. 迁移 10 + 实体/Mapper + 常量；
2. `AccessApplicationService` + DTO + 两个 Controller + 配置；
3. 后端单测与 `RolePermissionService` `*` 守卫；
4. 前端 API、菜单/路由/布局、申请页、角色授权 `*` 过滤；
5. 模拟审批脚本；
6. 契约/验收/README 文档；
7. 验收脚本 §9.17、`verify-all.sh` 产物检查；
8. 全量验证、提交、CI。
