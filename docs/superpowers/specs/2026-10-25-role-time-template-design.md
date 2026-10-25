# 角色时间模板（A21）设计

日期：2026-10-25  
状态：用户已确认；已实现并通过（2026-10-25，全量验收 159/159、后端 219 单测、verify-all 17 项）  
范围：只改 telbank-rbac-api / telbank-rbac-front / 脚本与文档；SDK 无需改动。

## 1. 目标与不变量

把时间语义从「权限」和「角色-权限」上移到「用户-角色」：

```text
用户 --(用户角色，带绝对生效窗口)--> 角色 --(角色权限，永久)--> 权限
```

- 只有 `sys_user_role` 保留 `valid_from / valid_until`（UTC 绝对时间）。
- `sys_role_permission` 不再带时效；权限授予/撤销即生效/失效。
- 一个角色绑定最多一个**全局**时间模板（`sys_role.time_template_id`，可空）。
- 模板不按系统隔离，不实现循环/定时规则；它只在授权写入时计算一个具体绝对窗口。
- 未绑定模板的角色是永久角色：只能由管理员直接分配，不能自助申请。
- 是否跨夜、是否包含周末完全由模板计算结果决定，不做额外工作日过滤。

## 2. 模板模型

表 `sys_time_template`（全局，无 system_id）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint pk | |
| name | varchar(100) unique | 模板名 |
| timezone | varchar(64) | IANA 时区，默认 `Asia/Shanghai` |
| start_day_offset | int | 相对锚点日期的天数偏移，0–3650 |
| start_time | time | 本地开始时间，`HH:mm` |
| end_day_offset | int | 相对锚点日期的天数偏移，0–3650 |
| end_time | time | 本地结束时间，`HH:mm` |
| status | tinyint | 1 启用 / 0 停用 |
| remark | varchar(255) | 可空 |
| created_at / updated_at | datetime | 数据库维护 |

模板示例：

- 当天 19:00 ~ 次日 07:00：`start=0/19:00`，`end=1/07:00`
- 审批后第 3 天 08:00 ~ 第 5 天 18:00：`start=3/08:00`，`end=5/18:00`
- 周五 20:00 ~ 周一 08:00：`start=0/20:00`，`end=3/08:00`（是否周末由结果自然覆盖）

校验：`name` 唯一且 1–100 字；`startTime/endTime` 匹配 `HH:mm`；`end_day_offset/end_time` 计算出的本地结束必须晚于开始；`timezone` 必须能被 `ZoneId.of` 解析。

## 3. 窗口计算规则

由 `RoleTimeWindowService` 统一计算，锚点 = 写入授权的当前时刻（审批通过时间 / 管理员直接分配时间）：

1. `anchor = Instant.now()`，转模板 `timezone`；
2. `startLocal = anchor日期 + start_day_offset 天，at start_time`；
3. `endLocal = anchor日期 + end_day_offset 天，at end_time`；
4. 校验 `endLocal > startLocal`，否则 400；
5. 若 `endInstant <= now`：409/400「模板计算的时间窗口已失效」，不自动滚动到下一次；
6. `effectiveFrom = max(startInstant, now)`（开始时间已过但结束未过时立即生效）；
7. `effectiveUntil = endInstant`；
8. 写入 `sys_user_role.valid_from/valid_until`（UTC），`auto_revoke=1`。

模板停用或缺失时，审批/分配返回 409「时间模板不存在或已停用」。

## 4. API 契约增量

### 4.1 时间模板

- `GET /api/time-templates?status=`：返回 `TimeTemplateVO[]`，按 id DESC；需要 `role:manage`（系统管理员角色页要读取；平台超管 `*` 也通过）。
- `POST /api/time-templates`：平台超管专属。
- `PUT /api/time-templates/{id}`：平台超管专属。
- `DELETE /api/time-templates/{id}`：逻辑停用，平台超管专属。

`TimeTemplateVO`：

```json
{
  "id": 1,
  "name": "夜间窗口",
  "timezone": "Asia/Shanghai",
  "startDayOffset": 0,
  "startTime": "19:00",
  "endDayOffset": 1,
  "endTime": "07:00",
  "status": 1,
  "remark": "当天19:00至次日07:00"
}
```

创建/更新请求同字段（不含 id/status 可选）。

### 4.2 角色

`RoleVO` 增加：

```json
"timeTemplateId": 1,
"timeTemplateName": "夜间窗口"
```

`RoleCreateRequest` / `RoleUpdateRequest` 增加可空 `timeTemplateId`：

- 非空时模板必须存在且启用；
- `admin` 角色必须为 null（400）；
- null 表示永久角色。

### 4.3 角色-权限

`RolePermissionGrantRequest` 改为：

```json
{ "permissionIds": [1,2], "grantReason": "可选" }
```

`PermissionGrant` 改为：

```json
{ "permissionId": 1, "code": "order:menu", "status": 1 }
```

移除 validFrom/validUntil（后台 DTO/服务/定时扫描/前端逐项时效全部移除）。

### 4.4 用户-角色

`UserRoleCreateRequest` 改为：

```json
{ "systemId": 1, "externalUserId": "13600136000", "roleId": 2, "grantReason": "可选" }
```

服务层按角色模板计算窗口：

- 角色有模板：写入计算出的绝对窗口；
- 角色无模板：永久（valid_from/valid_until 为 null）；
- 管理端不再手工选择日期。

`UserRoleVO` 保留 validFrom/validUntil（供列表/详情展示），不回传模板计算过程。

### 4.5 我的申请

- `GET /api/access-applications/options`：只返回「启用 + 非 admin + 已绑定启用模板」的角色；`RoleOptionVO` 增加：
  - `timeTemplateId`、`timeTemplateName`、`timeTemplateText`（如「审批后当天 19:00 ~ 次日 07:00」）
- 创建申请时，角色必须绑定启用模板，否则 400。
- 申请单角色快照 `sys_access_application_role` 增加：`time_template_id`、`time_template_name`、`approved_valid_from`、`approved_valid_until`。
- 审批决策请求不再接收 `validFrom/validUntil`，由服务端按每个角色模板分别计算；多角色可以有不同的窗口。
- `sys_access_application.approved_valid_from/until` 存所有角色窗口的包络（最早开始、最晚结束），用于列表展示；详情中的每个角色返回自己的窗口。
- `ApplicationVO.RoleVO` 增加 `timeTemplateName`、`approvedValidFrom`、`approvedValidUntil`。
- 审批幂等：已 APPROVED 再回调 APPROVED 返回 idempotent=true；窗口不回滚。

### 4.6 旧审批授权接口

`POST /openapi/approval/grant` 保持兼容：

- 角色有模板时，以模板计算窗口为准，忽略请求里的 validFrom/validUntil；
- 角色无模板时，继续使用请求窗口（可为空 = 永久）；
- 其他字段/幂等规则不变。

## 5. 前端 IA / 菜单

- `/role-permissions` 菜单「角色授权」→ **角色权限**（只给角色分配静态权限）。
- `/user-roles` 菜单「用户授权」→ **用户角色**（只给用户分配角色；时间由模板计算）。
- 新增 `/time-templates` 菜单 **时间模板**，平台超管专属，放在顶级（建议在「系统接入」之后）。
- 角色管理页增加「时间模板」列；新建/编辑角色选择模板，可空（永久角色）。
- 角色权限页去掉 RangePicker、逐项时效、按窗口分组提交；改为静态勾选 + 一次 grant。
- 用户角色页去掉日期选择；选中角色后展示其模板说明，并提示窗口由审批/分配时刻计算。
- 「我的申请」页：可申请角色展示其时间模板摘要；申请说明改为“审批通过后按角色时间模板计算生效窗口”，不再说统一窗口；已通过申请展示各角色窗口。
- 管理端所有页面标题/菜单/README 统一使用新名称。

## 6. 迁移与验收

新增 `db/manual/11_time_template.sql`：

- 创建 `sys_time_template`；
- `sys_role` 增加 `time_template_id` + 索引；
- `sys_access_application_role` 增加模板快照与逐角色窗口字段；
- 删除 `sys_role_permission.valid_from/valid_until` 及 `idx_valid_until`；
- 预置 2 个演示模板（夜间窗口、全天窗口），不自动绑定角色。

验收要求：

- 后端单元测试覆盖模板校验/窗口计算（含跨夜、未来偏移、窗口已过期、开始已过立即生效、停用模板拒绝）。
- 角色 CRUD 模板绑定校验；角色权限无时效；用户角色按模板写入窗口；申请审批逐角色写入窗口。
- §9.17 改为模板化审批；新增 §9.18 时间模板 CRUD + 模板窗口审批 + 不可申请无模板角色。
- `verify-all.sh` 增加 11 迁移/时间模板页面/新菜单产物检查。
- 文档同步：contracts v3.0、README/docs/README、验收记录、前端 README。
