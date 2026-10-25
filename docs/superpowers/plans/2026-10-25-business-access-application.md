# 业务用户临时权限申请 · 实现计划

> **给执行代理：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现，每完成一个任务做一次检查点。步骤使用复选框跟踪。

**目标：** 让只有业务权限、没有管理权限短码的用户，在当前系统内自助申请非 admin 角色；生成可追踪的申请单，外部审批通过后以统一连续有效期自动授予角色。

**架构：** 新增两张申请表和申请人 API；申请人接口复用 `ManagementContextFilter` 的系统上下文；审批回调走独立 `/openapi/approval/access-applications/decision`，校验审批 AppKey 后，在事务中逐个调用既有 `ApprovalGrantService.grant(...)` 写 `sys_user_role`。审批通过/驳回由外部系统或 `scripts/mock-access-approval.py` 决定，权限中心不实现审批工作流。

**技术栈：** JDK 21、Spring Boot 4.1.2、Spring Security 7、MyBatis-Plus 3.5.17、MySQL 8；React 19、Vite 8、antd 6、React Query；Python 3 标准库验收/模拟脚本。

## 全局约束

- 设计唯一来源：`docs/superpowers/specs/2026-10-25-business-access-application-design.md`。本文与设计冲突时以设计为准，先修正本文再写代码。
- 只新增迁移 `10_access_application.sql`；**不新增 Flyway**，不修改 01–09，不修改 `sys_user_role` / `sys_permission` / `sys_role_permission` 表结构。
- **不新增用户级权限表**；不实现审批页面/审批工作流；不改变 SDK 协议。
- 申请人 API 不加 `@PreAuthorize`；必须走 `ManagementContextService.requireContext()`，系统隔离和数据归属校验在服务层完成。
- 审批回调只接受审批专用 AppKey（`rbac.approval`），路径必须在 `/openapi/approval/` 前缀下。
- 中文 UI、中文文档；不新增前后端依赖；保持现有 3 个平台启动脚本、无分发器。
- 所有错误用 `ApiException` 和统一 `ApiResponse`；时间 DTO 用 `Instant`，实体用 UTC `LocalDateTime`。
- 先写失败测试，再实现；每个任务结束跑指定验证并提交。

## 文件地图

| 路径 | 责任 |
|---|---|
| `telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql` | 两张申请表、索引、PENDING 唯一键 |
| `.../domain/entity/SysAccessApplication.java` | 申请单实体 |
| `.../domain/entity/SysAccessApplicationRole.java` | 申请单角色快照实体 |
| `.../domain/mapper/SysAccessApplicationMapper.java` | 申请单查询、分页、行锁、待审批查重 |
| `.../domain/mapper/SysAccessApplicationRoleMapper.java` | 角色快照按申请单查询 |
| `.../domain/mapper/SysUserRoleMapper.java` | 增加当前有效角色 id 查询 |
| `.../domain/mapper/row/AccessRolePermissionRow.java` | options 的角色权限预览查询行 |
| `.../web/dto/AccessApplicationDtos.java` | options / create / page / detail / decision DTO |
| `.../service/AccessApplicationService.java` | 申请人 options/create/page/detail |
| `.../service/AccessDecisionService.java` | 审批回调决策、幂等、授权 |
| `.../web/admin/AccessApplicationController.java` | `/api/access-applications` 四个接口 |
| `.../web/openapi/OpenApiAccessApplicationController.java` | `/openapi/approval/access-applications/decision` |
| `.../config/RbacProperties.java` | `accessApproval.urlTemplate` |
| `.../common/RbacConstants.java` | 申请状态常量 |
| `telbank-rbac-api/src/test/java/.../AccessApplicationServiceTest.java` | options/create/page/detail 单元测试 |
| `telbank-rbac-api/src/test/java/.../AccessDecisionServiceTest.java` | 审批决策/幂等/回滚单元测试 |
| `telbank-rbac-api/src/test/java/.../RolePermissionServiceTest.java` | 非 admin 授予 `*` 拒绝 |
| `telbank-rbac-front/src/api/accessApplications.ts` | 申请 API 封装 |
| `telbank-rbac-front/src/api/types.ts` | 申请相关类型 |
| `telbank-rbac-front/src/pages/AccessApplicationsPage.tsx` | 我的申请页面、创建 Modal、详情 Drawer |
| `telbank-rbac-front/src/config/menu.ts` | 顶级「我的申请」、alwaysVisible、首页优先管理菜单 |
| `telbank-rbac-front/src/layouts/AdminLayout.tsx` | 业务用户不再被空态拦截 |
| `telbank-rbac-front/src/router.tsx` | `/access-applications` 自服务路由 |
| `telbank-rbac-front/src/pages/RolePermissionsPage.tsx` | 授权树过滤 `*` |
| `scripts/mock-access-approval.py` | 模拟外部审批平台回调 |
| `scripts/acceptance.py` | 新增 §9.17 自助申请验收 |
| `scripts/verify-all.sh` | 新迁移/前端页面/模拟脚本产物核对 |
| `docs/impl/contracts.md` | 申请人 API 与审批回调契约 |
| `docs/acceptance.md` / `README.md` / `docs/README.md` / `telbank-rbac-front/README.md` / `docs/telbank-rbac.md` | 使用说明与验收记录 |

---

## Task 1：迁移、实体、Mapper 与常量

**Files:**
- Create: `telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql`
- Create: `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/entity/SysAccessApplication.java`
- Create: `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/entity/SysAccessApplicationRole.java`
- Create: `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysAccessApplicationMapper.java`
- Create: `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysAccessApplicationRoleMapper.java`
- Modify: `telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysUserRoleMapper.java`
- Modify: `telbank-rbac-api/src/main/java/com/telbank/rbac/common/RbacConstants.java`

- [ ] **Step 1：写迁移 SQL**

写入以下完整内容：

```sql
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
```

- [ ] **Step 2：加常量**

在 `RbacConstants` 增加（放在系统接入申请状态常量附近）：

```java
public static final int ACCESS_APPLICATION_STATUS_PENDING = 0;
public static final int ACCESS_APPLICATION_STATUS_APPROVED = 1;
public static final int ACCESS_APPLICATION_STATUS_REJECTED = 2;
```

- [ ] **Step 3：实体**

`SysAccessApplication` 继承 `BaseEntity`，`@TableName("sys_access_application")`，字段：

```java
private String applicationNo;
private Long systemId;
private String externalUserId;
private Integer status;
private String reason;

@TableField(updateStrategy = FieldStrategy.ALWAYS)
private LocalDateTime approvedValidFrom;
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private LocalDateTime approvedValidUntil;
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private String decisionComment;
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private String reviewedBy;
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private LocalDateTime reviewedAt;
```

`SysAccessApplicationRole` 继承 `BaseEntity`，`@TableName("sys_access_application_role")`，字段：`Long applicationId`、`Long roleId`、`String roleCode`、`String roleName`。两个实体都生成标准 getter/setter，不映射 `pending_user_key`。

- [ ] **Step 4：Mapper**

`SysAccessApplicationMapper extends RbacMapper<SysAccessApplication>`：

```java
default Optional<SysAccessApplication> findByApplicationNo(String applicationNo) {
    return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
            .eq(SysAccessApplication::getApplicationNo, applicationNo)));
}

default Optional<SysAccessApplication> findByApplicationNoForUpdate(String applicationNo) {
    return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
            .eq(SysAccessApplication::getApplicationNo, applicationNo)
            .last("FOR UPDATE")));
}

default boolean existsByApplicationNo(String applicationNo) {
    return selectCount(Wrappers.<SysAccessApplication>lambdaQuery()
            .eq(SysAccessApplication::getApplicationNo, applicationNo)) > 0;
}

default Optional<SysAccessApplication> findPending(Long systemId, String externalUserId) {
    return Optional.ofNullable(selectOne(Wrappers.<SysAccessApplication>lambdaQuery()
            .eq(SysAccessApplication::getSystemId, systemId)
            .eq(SysAccessApplication::getExternalUserId, externalUserId)
            .eq(SysAccessApplication::getStatus, RbacConstants.ACCESS_APPLICATION_STATUS_PENDING)
            .orderByDesc(SysAccessApplication::getId)
            .last("LIMIT 1")));
}

@Select("""
        <script>
        SELECT * FROM sys_access_application
        WHERE system_id = #{systemId}
          AND external_user_id = #{externalUserId}
          <if test="status != null">AND status = #{status}</if>
        ORDER BY id DESC
        </script>
        """)
List<SysAccessApplication> search(Page<SysAccessApplication> page,
                                  @Param("systemId") Long systemId,
                                  @Param("externalUserId") String externalUserId,
                                  @Param("status") Integer status);
```

`SysAccessApplicationRoleMapper extends RbacMapper<SysAccessApplicationRole>`：

```java
default List<SysAccessApplicationRole> findByApplicationIdOrderByIdAsc(Long applicationId) {
    return selectList(Wrappers.<SysAccessApplicationRole>lambdaQuery()
            .eq(SysAccessApplicationRole::getApplicationId, applicationId)
            .orderByAsc(SysAccessApplicationRole::getId));
}

default List<SysAccessApplicationRole> findByApplicationIdInOrderByIdAsc(Collection<Long> applicationIds) {
    if (applicationIds == null || applicationIds.isEmpty()) {
        return List.of();
    }
    return selectList(Wrappers.<SysAccessApplicationRole>lambdaQuery()
            .in(SysAccessApplicationRole::getApplicationId, applicationIds)
            .orderByAsc(SysAccessApplicationRole::getApplicationId)
            .orderByAsc(SysAccessApplicationRole::getId));
}
```

`SysUserRoleMapper` 增加当前有效角色 id：

```java
@Select("""
        SELECT ur.role_id
        FROM sys_user_role ur
        JOIN sys_role r ON r.id = ur.role_id AND r.system_id = ur.system_id
        WHERE ur.system_id = #{systemId}
          AND ur.external_user_id = #{externalUserId}
          AND ur.status = 1
          AND r.status = 1
          AND (ur.valid_from IS NULL OR ur.valid_from <= NOW())
          AND (ur.valid_until IS NULL OR ur.valid_until > NOW())
        ORDER BY ur.role_id
        """)
List<Long> findActiveRoleIds(@Param("systemId") Long systemId,
                             @Param("externalUserId") String externalUserId);
```

- [ ] **Step 5：验证迁移与编译**

```bash
RBAC_DB_NAME=telbank_rbac_access_plan MYSQL_USER=root MYSQL_PASSWORD="${MYSQL_PASSWORD:-}" \
  ./scripts/init-db.sh --drop
mysql -h 127.0.0.1 -u root ${MYSQL_PASSWORD:+-p"$MYSQL_PASSWORD"} telbank_rbac_access_plan \
  -e "SHOW CREATE TABLE sys_access_application\G SHOW CREATE TABLE sys_access_application_role\G"
mvn -q -pl telbank-rbac-api -am test -DskipTests
```

人工确认：两张表都存在；`uk_access_application_pending` 存在；`pending_user_key` 为 STORED 生成列。再用临时数据库做一次 PENDING 唯一键验证：插入两条同 `system_id`、`external_user_id`、`status=0` 的记录，第二条必须失败；把第一条状态改为 1 后，第二条可插入。

- [ ] **Step 6：提交**

```bash
git add telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/entity/SysAccessApplication.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/entity/SysAccessApplicationRole.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysAccessApplicationMapper.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysAccessApplicationRoleMapper.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysUserRoleMapper.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/common/RbacConstants.java
git commit -m "feat(db): 新增业务用户临时权限申请表"
```

---

## Task 2：DTO、申请人 options / page / detail API

**Files:**
- Create: `.../web/dto/AccessApplicationDtos.java`
- Create: `.../domain/mapper/row/AccessRolePermissionRow.java`
- Create: `.../service/AccessApplicationService.java`（本任务只做 options/page/detail；create 在 Task 3 追加）
- Create: `.../web/admin/AccessApplicationController.java`（本任务 GET options/page/detail；POST 在 Task 3 追加）
- Modify: `.../config/RbacProperties.java`
- Modify: `telbank-rbac-api/src/main/resources/application.yml`
- Test: `.../service/AccessApplicationServiceTest.java`

- [ ] **Step 1：写失败测试**

新建 `AccessApplicationServiceTest`，覆盖：

- `options` 过滤 `admin` 和停用角色；
- `options` 对当前有效角色返回 `owned=true`，对其他角色 `owned=false`；
- `options` 权限预览只包含当前有效角色-权限关系；
- `page` 只调用 `search(ctx.systemId, currentUser, status, page)`；
- `detail` 跨用户/跨系统返回 404；
- 状态字符串 `PENDING/APPROVED/REJECTED` 转 0/1/2，未知值返回 400。

测试使用 `TestProxies.proxy(...)` 构造 mapper/context/currentUser 假实现（参考 `ApprovalGrantServiceTest`）。先运行：

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=AccessApplicationServiceTest
```

期望编译失败或断言失败。

- [ ] **Step 2：DTO**

`AccessApplicationDtos` 全部使用 record：

```java
public record RolePermissionVO(Long id, String code, String name, Integer type) {}
public record RoleOptionVO(Long roleId, String roleCode, String roleName,
                           boolean owned, List<RolePermissionVO> permissions) {}
public record OptionsVO(Long systemId, String systemCode, String systemName,
                        String approvalUrlTemplate, List<RoleOptionVO> roles) {}
public record CreateRequest(@NotEmpty @Size(max = 20) List<Long> roleIds,
                            @NotBlank @Size(max = 200) String reason) {}
public record RoleVO(Long roleId, String roleCode, String roleName) {}
public record ApplicationVO(Long id, String applicationNo, Long systemId, String systemCode,
                            String status, int roleCount, List<RoleVO> roles, String reason,
                            Instant approvedValidFrom, Instant approvedValidUntil,
                            String decisionComment, String reviewedBy, Instant reviewedAt,
                            Instant createdAt, String approvalUrl) {}
public record DecisionRequest(@NotBlank String applicationNo,
                              @NotBlank String decision,
                              Instant validFrom, Instant validUntil,
                              @Size(max = 128) String reviewer,
                              @Size(max = 255) String decisionComment) {}
public record DecisionResponse(String applicationNo, String status, boolean idempotent,
                               List<Long> grantedRoleIds,
                               Instant approvedValidFrom, Instant approvedValidUntil) {}
```

`ApplicationVO.roleCount = roles.size()`，不另存列。

- [ ] **Step 3：配置**

`RbacProperties` 增加：

```java
private final AccessApproval accessApproval = new AccessApproval();

public AccessApproval getAccessApproval() {
    return accessApproval;
}

public static class AccessApproval {
    private String urlTemplate = "http://localhost:8090/approval/access?applicationNo={applicationNo}";

    public String getUrlTemplate() { return urlTemplate; }
    public void setUrlTemplate(String urlTemplate) { this.urlTemplate = urlTemplate; }
}
```

`application.yml` 的 `rbac` 下、`approval` 后增加：

```yaml
  access-approval:
    url-template: ${RBAC_ACCESS_APPROVAL_URL_TEMPLATE:http://localhost:8090/approval/access?applicationNo={applicationNo}}
```

模板必须包含 `{applicationNo}`；若配置值不含该占位符，启动/读取时记录 WARN（不阻断启动），前端仍只显示申请单号。实现可在 `RbacProperties.AccessApproval` 的 `setUrlTemplate` 或 `@PostConstruct` 中校验，避免引入新的配置框架。

- [ ] **Step 4：Service 查询逻辑**

`AccessApplicationService` 构造注入：

```java
SysAccessApplicationMapper applicationMapper
SysAccessApplicationRoleMapper applicationRoleMapper
SysRoleMapper roleMapper
SysUserRoleMapper userRoleMapper
SysRolePermissionMapper rolePermissionMapper
SysSystemMapper systemMapper
ManagementContextService contextService
CurrentUserProvider currentUserProvider
RbacProperties properties
```

实现：

```java
public OptionsVO options() {
    ManagementContext ctx = contextService.requireContext();
    String user = currentUserProvider.requireExternalUserId();
    List<Long> ownedIds = userRoleMapper.findActiveRoleIds(ctx.systemId(), user);
    Set<Long> owned = new HashSet<>(ownedIds);

    List<SysRole> roles = roleMapper.findAllBySystemIdAndStatusOrderBySortAscIdAsc(ctx.systemId(), 1)
            .stream()
            .filter(role -> !"admin".equals(role.getCode()))
            .toList();
    Map<Long, List<RolePermissionVO>> permissionMap =
            loadRolePermissions(ctx.systemId(), roles.stream().map(SysRole::getId).toList());

    SysSystem system = systemMapper.findById(ctx.systemId())
            .orElseThrow(() -> ApiException.notFound("业务系统不存在"));
    return new OptionsVO(ctx.systemId(), ctx.systemCode(), system.getName(),
            properties.getAccessApproval().getUrlTemplate(),
            roles.stream().map(role -> new RoleOptionVO(role.getId(), role.getCode(), role.getName(),
                    owned.contains(role.getId()),
                    permissionMap.getOrDefault(role.getId(), List.of()))).toList());
}

public PageResult<ApplicationVO> page(String status, PageQuery pageQuery) {
    ManagementContext ctx = contextService.requireContext();
    String user = currentUserProvider.requireExternalUserId();
    Integer statusCode = parseStatus(status, true);
    Page<SysAccessApplication> page = pageQuery.toMpPage();
    List<SysAccessApplication> records =
            applicationMapper.search(page, ctx.systemId(), user, statusCode);
    return PageResult.of(toVOs(records, ctx), page.getTotal(), pageQuery);
}

public ApplicationVO detail(String applicationNo) {
    ManagementContext ctx = contextService.requireContext();
    String user = currentUserProvider.requireExternalUserId();
    SysAccessApplication app = applicationMapper.findByApplicationNo(applicationNo)
            .filter(a -> a.getSystemId().equals(ctx.systemId()))
            .filter(a -> a.getExternalUserId().equals(user))
            .orElseThrow(() -> ApiException.notFound("申请单不存在"));
    return toVOs(List.of(app), ctx).get(0);
}
```

`loadRolePermissions` 用新 mapper 查询（放在 `SysRolePermissionMapper`，避免 N+1）：

```java
@Select("""
        <script>
        SELECT rp.role_id AS roleId, p.id AS id, p.code AS code, p.name AS name, p.type AS type
        FROM sys_role_permission rp
        JOIN sys_permission p ON p.id = rp.permission_id AND p.system_id = #{systemId} AND p.status = 1
        WHERE rp.role_id IN
        <foreach collection="roleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          AND rp.status = 1
          AND (rp.valid_from IS NULL OR rp.valid_from <= NOW())
          AND (rp.valid_until IS NULL OR rp.valid_until > NOW())
        ORDER BY rp.role_id, p.sort, p.id
        </script>
        """)
List<AccessRolePermissionRow> findActivePermissionRows(@Param("systemId") Long systemId,
                                                       @Param("roleIds") List<Long> roleIds);
```

空角色列表不要执行 IN 查询。`AccessRolePermissionRow` 用普通 JavaBean（字段 `Long roleId, Long id, String code, String name, Integer type`，标准 getter/setter），放在 `domain/mapper/row/`；不要用 record，避免 MyBatis 构造器映射的不确定性。

`toVOs` 按 `applicationId` 批量查角色快照；`approvalUrl` 只在 `status=PENDING` 且模板包含 `{applicationNo}` 时替换，否则为 null。`parseStatus`：`null/blank` 返回 null；`PENDING→0`、`APPROVED→1`、`REJECTED→2`；其他 `ApiException.badRequest("非法申请状态")`。

- [ ] **Step 5：Controller**

```java
@RestController
@RequestMapping("/api/access-applications")
public class AccessApplicationController {

    private final AccessApplicationService service;

    public AccessApplicationController(AccessApplicationService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public ApiResponse<OptionsVO> options() {
        return ApiResponse.ok(service.options());
    }

    @GetMapping
    public ApiResponse<PageResult<ApplicationVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.page(status, PageQuery.of(page, size)));
    }

    @GetMapping("/{applicationNo}")
    public ApiResponse<ApplicationVO> detail(@PathVariable String applicationNo) {
        return ApiResponse.ok(service.detail(applicationNo));
    }
}
```

- [ ] **Step 6：跑测试并提交**

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=AccessApplicationServiceTest
```

预期全部通过。若失败，先改实现，不改断言意图。

```bash
git add telbank-rbac-api/src/main/java/com/telbank/rbac/web/dto/AccessApplicationDtos.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/row/AccessRolePermissionRow.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/service/AccessApplicationService.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/web/admin/AccessApplicationController.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/config/RbacProperties.java \
  telbank-rbac-api/src/main/resources/application.yml \
  telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper/SysRolePermissionMapper.java
git add telbank-rbac-api/src/test/java/com/telbank/rbac/service/AccessApplicationServiceTest.java
git commit -m "feat(api): 增加自助申请选项/列表/详情接口"
```

---

## Task 3：创建申请（PENDING 唯一、角色校验、单号生成）

**Files:**
- Modify: `.../service/AccessApplicationService.java`
- Modify: `.../web/admin/AccessApplicationController.java`
- Test: `.../service/AccessApplicationServiceTest.java`

- [ ] **Step 1：写失败测试**

补充测试：

- 正常创建：返回 `status=PENDING`、`applicationNo` 匹配 `^RA-\d{8}-[A-Z0-9]{6}$`、角色快照数量等于请求；
- roleIds 为空/超过 20：400；
- reason 为空白或超 200：400；
- 申请 admin 角色：400「系统管理员角色不可自助申请」；
- 申请其他系统角色：400「角色不属于当前系统」；
- 已有效持有角色：400「已拥有该角色，无需申请」；
- 已有 PENDING：409「已有待审批申请，请等待审批完成」；
- 数据库唯一键并发冲突：409（模拟 `DataIntegrityViolationException`）。

先运行测试确认失败。

- [ ] **Step 2：实现 create**

```java
@Transactional
public ApplicationVO create(CreateRequest request) {
    ManagementContext ctx = contextService.requireContext();
    String user = currentUserProvider.requireExternalUserId();

    if (request.roleIds() == null || request.roleIds().isEmpty()) {
        throw ApiException.badRequest("roleIds 不能为空");
    }
    if (request.roleIds().size() > 20) {
        throw ApiException.badRequest("一次最多申请 20 个角色");
    }
    if (request.roleIds().stream().anyMatch(id -> id == null)) {
        throw ApiException.badRequest("roleIds 不能包含空值");
    }
    List<Long> roleIds = request.roleIds().stream().distinct().toList();
    if (request.reason() == null || request.reason().trim().isEmpty()) {
        throw ApiException.badRequest("申请原因不能为空");
    }
    String reason = request.reason().trim();
    if (reason.length() > 200) {
        throw ApiException.badRequest("申请原因不能超过 200 字");
    }
    List<SysRole> roles = roleIds.stream()
            .map(id -> roleMapper.findByIdAndSystemId(id, ctx.systemId())
                    .orElseThrow(() -> ApiException.badRequest("角色不属于当前系统")))
            .toList();
    if (roles.stream().anyMatch(role -> role.getStatus() == null || role.getStatus() != 1)) {
        throw ApiException.badRequest("角色已停用，不能申请");
    }
    if (roles.stream().anyMatch(role -> "admin".equals(role.getCode()))) {
        throw ApiException.badRequest("系统管理员角色不可自助申请");
    }
    Set<Long> owned = new HashSet<>(userRoleMapper.findActiveRoleIds(ctx.systemId(), user));
    if (roles.stream().anyMatch(role -> owned.contains(role.getId()))) {
        throw ApiException.badRequest("已拥有该角色，无需申请");
    }
    applicationMapper.findPending(ctx.systemId(), user).ifPresent(pending -> {
        throw ApiException.conflict("已有待审批申请，请等待审批完成");
    });

    SysAccessApplication app = new SysAccessApplication();
    app.setSystemId(ctx.systemId());
    app.setExternalUserId(user);
    app.setStatus(RbacConstants.ACCESS_APPLICATION_STATUS_PENDING);
    app.setReason(reason);
    app.setApplicationNo(nextApplicationNo());

    try {
        applicationMapper.save(app);
    } catch (DataIntegrityViolationException e) {
        throw ApiException.conflict("已有待审批申请，请等待审批完成");
    }

    for (SysRole role : roles) {
        SysAccessApplicationRole snapshot = new SysAccessApplicationRole();
        snapshot.setApplicationId(app.getId());
        snapshot.setRoleId(role.getId());
        snapshot.setRoleCode(role.getCode());
        snapshot.setRoleName(role.getName());
        applicationRoleMapper.save(snapshot);
    }
    return detailByEntity(app, ctx);
}
```

`nextApplicationNo()`：`"RA-" + UTC yyyyMMdd + "-" + 6 位大写字母/数字`，用 `SecureRandom`，最多 5 次查重；仍冲突抛 `new ApiException(500, "申请单号生成失败，请重试")`（`ApiException` 没有 `internalError` 工厂）。

`detailByEntity` 复用 Task 2 的单条 VO 转换。`@Transactional` 保证主表+快照原子。

- [ ] **Step 3：Controller 增加 POST**

```java
@PostMapping
public ApiResponse<ApplicationVO> create(@Valid @RequestBody CreateRequest request) {
    return ApiResponse.ok(service.create(request));
}
```

- [ ] **Step 4：测试与提交**

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=AccessApplicationServiceTest
git add telbank-rbac-api/src/main/java/com/telbank/rbac/service/AccessApplicationService.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/web/admin/AccessApplicationController.java \
  telbank-rbac-api/src/test/java/com/telbank/rbac/service/AccessApplicationServiceTest.java
git commit -m "feat(api): 业务用户创建临时权限申请"
```

---

## Task 4：审批决策服务与开放回调

**Files:**
- Create: `.../service/AccessDecisionService.java`
- Create: `.../web/openapi/OpenApiAccessApplicationController.java`
- Modify: `.../web/dto/AccessApplicationDtos.java`（若响应 DTO 未完成）
- Test: `.../service/AccessDecisionServiceTest.java`

- [ ] **Step 1：写失败测试**

覆盖：

- `APPROVED`：多角色各调用一次 `ApprovalGrantService.grant`，`approvalId` 为 `applicationNo:roleId`，统一 `validFrom/validUntil`，申请状态/审批字段/授权窗口正确；
- 同一通过回调再次调用：`idempotent=true`，不再调用 grant；
- 已通过后改窗口/驳回：409；
- `REJECTED`：只更新状态，不调用 grant；重复驳回幂等；
- `validUntil <= validFrom` 或 `validUntil <= now`：400；
- 角色已停用/删除：409，未写任何 `sys_user_role`；
- 申请人已有效持有角色：409，未写授权；申请保持 PENDING；
- 非法 decision：400；申请单不存在：404。

先运行测试确认失败。

- [ ] **Step 2：实现 decide**

```java
@Transactional
public DecisionResponse decide(DecisionRequest request) {
    String decision = request.decision().trim().toUpperCase(Locale.ROOT);
    if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
        throw ApiException.badRequest("decision 只能是 APPROVED 或 REJECTED");
    }
    SysAccessApplication app = applicationMapper.findByApplicationNoForUpdate(request.applicationNo())
            .orElseThrow(() -> ApiException.notFound("申请单不存在"));

    if ("APPROVED".equals(decision)) {
        Instant from = requireWindow(request.validFrom(), request.validUntil());
        if (app.getStatus() == RbacConstants.ACCESS_APPLICATION_STATUS_APPROVED
                && sameWindow(app, from, request.validUntil())) {
            return response(app, true);
        }
        if (app.getStatus() != RbacConstants.ACCESS_APPLICATION_STATUS_PENDING) {
            throw ApiException.conflict("申请单已审批，不能重复决策");
        }
        approve(app, from, request.validUntil(), request);
        return response(app, false);
    }

    if (app.getStatus() == RbacConstants.ACCESS_APPLICATION_STATUS_REJECTED) {
        return response(app, true);
    }
    if (app.getStatus() != RbacConstants.ACCESS_APPLICATION_STATUS_PENDING) {
        throw ApiException.conflict("申请单已审批，不能重复决策");
    }
    reject(app, request);
    return response(app, false);
}
```

`approve` 关键步骤：

1. `SysSystem system = systemMapper.findById(app.getSystemId())...`，系统不存在/停用 → 409；
2. `List<SysAccessApplicationRole> snapshots = applicationRoleMapper.findByApplicationIdOrderByIdAsc(app.getId())`，空 → 409；
3. 逐角色 `roleMapper.findByIdAndSystemId(roleId, app.getSystemId())`，不存在/停用/`admin` → 409；
4. `Set<Long> activeRoleIds = new HashSet<>(userRoleMapper.findActiveRoleIds(app.getSystemId(), app.getExternalUserId()))`；任一申请角色已在其中 → 409「申请人已拥有该角色，不能重复授权」；
5. 逐角色调用：

```java
ApprovalGrantResponse granted = approvalGrantService.grant(new ApprovalGrantRequest(
        system.getCode(), app.getExternalUserId(), "role", roleId, null,
        from, until,
        app.getApplicationNo() + ":" + roleId,
        truncate("申请单 " + app.getApplicationNo() + "：" + app.getReason(), 255)));
```

6. 收集 `grantId`；更新 `app.status=APPROVED`、`approvedValidFrom/Until`（用 `TimeUtils.toUtcLocalDateTime(...)`）、`decisionComment`、`reviewedBy`（`reviewer` 空则 `"external-approval"`）、`reviewedAt=TimeUtils.toUtcLocalDateTime(Instant.now())`，`applicationMapper.updateById(app)`。

`requireWindow`：`validFrom`、`validUntil` 都非空，`validUntil.isAfter(validFrom)`，`validUntil.isAfter(Instant.now())`；失败 400。`sameWindow` 比较 UTC 时间值。

`reject`：状态置 REJECTED、comment/reviewer/reviewedAt，不改 approved 字段。

- [ ] **Step 3：开放回调 Controller**

```java
@RestController
@RequestMapping("/openapi/approval/access-applications")
public class OpenApiAccessApplicationController {

    private final AccessDecisionService decisionService;

    public OpenApiAccessApplicationController(AccessDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping("/decision")
    public ApiResponse<DecisionResponse> decide(@Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(decisionService.decide(request));
    }
}
```

路径必须以 `/openapi/approval/` 开头，确保命中现有 `AppKeyAuthFilter` 审批 AppKey 分支；不添加 JWT/`@PreAuthorize`。

- [ ] **Step 4：测试与提交**

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=AccessDecisionServiceTest
git add telbank-rbac-api/src/main/java/com/telbank/rbac/service/AccessDecisionService.java \
  telbank-rbac-api/src/main/java/com/telbank/rbac/web/openapi/OpenApiAccessApplicationController.java \
  telbank-rbac-api/src/test/java/com/telbank/rbac/service/AccessDecisionServiceTest.java
git commit -m "feat(api): 新增自助申请审批决策回调"
```

---

## Task 5：收回非 admin 角色的 `*` 通配权限

**Files:**
- Modify: `telbank-rbac-api/src/main/java/com/telbank/rbac/service/RolePermissionService.java`
- Test: `telbank-rbac-api/src/test/java/com/telbank/rbac/service/RolePermissionServiceTest.java`
- Modify: `telbank-rbac-front/src/pages/RolePermissionsPage.tsx`

- [ ] **Step 1：后端失败测试**

在 `RolePermissionServiceTest` 增加：

- 非 admin 角色 grant 权限列表含 `*` → `ApiException` 400，消息包含「通配」或「*」，不写 `sys_role_permission`；
- 同一请求不含 `*` 仍成功（回归）。

运行：

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=RolePermissionServiceTest
```

- [ ] **Step 2：后端守卫**

在 `RolePermissionService.grant` 的权限循环中，`permissionRepository.findByIdAndSystemId(...)` 成功后、写入前：

```java
if (RbacConstants.PERM_WILDCARD.equals(permission.getCode())) {
    throw ApiException.badRequest("通配权限 * 只能由系统管理员角色默认拥有，不能手工授权");
}
```

admin 角色已由 `rejectAdminRoleChanges` 先拦截，保留原行为。

- [ ] **Step 3：前端过滤**

`RolePermissionsPage.tsx`：

- 保留 `flatPermissions` 作为“全部权限（含 `*`）”，供已授权列表显示名称；
- 新增 `grantablePermissions = flatPermissions.filter(node => node.code !== '*')`；如果 `*` 节点存在子节点，必须连同子树递归过滤（不能只删父节点），避免留下无法挂到根上的孤立节点；
- 树 `treeData={buildTreeData(grantablePermissions)}`（如 `buildTreeData` 接收扁平列表则先按树结构过滤；保持现有函数签名不变更 API）；
- 展开 keys、`selectedPermissions`、`selectAll` 都基于 `grantablePermissions`；
- 已授权列表仍显示 `*` 的黄色 Tag 和现有 `hasWildcardGrant` Alert；提示文案改为「历史 `*` 授权仅剩展示，通配权限只属于系统管理员角色」。

- [ ] **Step 4：构建与提交**

```bash
mvn -q -pl telbank-rbac-api -am test -Dtest=RolePermissionServiceTest
( cd telbank-rbac-front && npm run build )
git add telbank-rbac-api/src/main/java/com/telbank/rbac/service/RolePermissionService.java \
  telbank-rbac-api/src/test/java/com/telbank/rbac/service/RolePermissionServiceTest.java \
  telbank-rbac-front/src/pages/RolePermissionsPage.tsx
git commit -m "fix(rbac): 非 admin 角色禁止手工授予通配权限"
```

---

## Task 6：前端「我的申请」页面、菜单与路由

**Files:**
- Modify: `telbank-rbac-front/src/api/types.ts`
- Create: `telbank-rbac-front/src/api/accessApplications.ts`
- Modify: `telbank-rbac-front/src/api/index.ts`
- Create: `telbank-rbac-front/src/pages/AccessApplicationsPage.tsx`
- Modify: `telbank-rbac-front/src/config/menu.ts`
- Modify: `telbank-rbac-front/src/layouts/AdminLayout.tsx`
- Modify: `telbank-rbac-front/src/router.tsx`
- Optional CSS: `telbank-rbac-front/src/styles/global.css`

- [ ] **Step 1：类型与 API**

`types.ts` 增加：

```ts
export type AccessApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export interface AccessRolePermissionVO { id: number; code: string; name: string; type: number }
export interface AccessRoleOptionVO {
  roleId: number
  roleCode: string
  roleName: string
  owned: boolean
  permissions: AccessRolePermissionVO[]
}
export interface AccessApplicationOptionsVO {
  systemId: number
  systemCode: string
  systemName: string
  approvalUrlTemplate: string
  roles: AccessRoleOptionVO[]
}
export interface AccessApplicationRoleVO { roleId: number; roleCode: string; roleName: string }
export interface AccessApplicationVO {
  id: number
  applicationNo: string
  systemId: number
  systemCode: string
  status: AccessApplicationStatus
  roleCount: number
  roles: AccessApplicationRoleVO[]
  reason: string
  approvedValidFrom: string | null
  approvedValidUntil: string | null
  decisionComment: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  createdAt: string
  approvalUrl: string | null
}
export interface AccessApplicationCreatePayload { roleIds: number[]; reason: string }
```

`src/api/accessApplications.ts`：

```ts
import { request, requestPage } from './request'
import type {
  AccessApplicationCreatePayload, AccessApplicationOptionsVO,
  AccessApplicationVO, AccessApplicationStatus, PageResult,
} from './types'

export function getAccessApplicationOptions() {
  return request<AccessApplicationOptionsVO>('/api/access-applications/options')
}

export function createAccessApplication(payload: AccessApplicationCreatePayload) {
  return request<AccessApplicationVO>('/api/access-applications', { method: 'POST', body: payload })
}

export function getAccessApplications(query: {
  status?: AccessApplicationStatus; page?: number; size?: number
}) {
  return requestPage<AccessApplicationVO>('/api/access-applications', { query })
}

export function getAccessApplication(applicationNo: string) {
  return request<AccessApplicationVO>(`/api/access-applications/${applicationNo}`)
}
```

在 `src/api/index.ts` 增加 `export * from './accessApplications'`。

- [ ] **Step 2：菜单与路由**

`menu.ts`：

```ts
export interface ManagementMenuItem {
  path: string
  label: string
  permission?: ManagementPermissionCode
  platformAdminOnly?: boolean
  group?: string
  alwaysVisible?: boolean
}
```

`MANAGEMENT_MENU_ITEMS` 最前面加：

```ts
{ path: '/access-applications', label: '我的申请', alwaysVisible: true },
```

`isMenuItemVisible` 最前面：

```ts
if (item.alwaysVisible) return true
```

`getFirstPermittedPath`：

```ts
const visible = getVisibleMenuItems(visibility)
const preferred = visible.find((item) => !item.alwaysVisible)
return (preferred ?? visible[0])?.path ?? null
```

`AdminLayout.tsx`：

- `MENU_ICON_BY_PATH['/access-applications'] = <FormOutlined />`（从 `@ant-design/icons` 导入）；
- 计算 `const hasAnyVisibleMenu = menuItems.length > 0`；`menuItems` 由现有 `buildMenuItems(visibility)` 生成，已按权限过滤，且「我的申请」是无权限的顶级项；
- 提前返回空态的条件从 `!hasManagementAccess` 改为 `!hasAnyVisibleMenu`；业务用户可见「我的申请」时必须渲染 `<Outlet />`，不能再返回「无管理权限」；
- 若 `hasManagementAccess` 变量没有其他用途则删除，避免留下未使用变量导致 `tsc` 失败；同时删除不再使用的 `NoPermission` import（改完后 `AdminLayout.tsx` 不应再引用它）。

`router.tsx` 增加（放在管理路由之外）：

```tsx
<Route path="/access-applications" element={<AccessApplicationsPage />} />
```

不包 `PermissionGuard` / `PlatformAdminGuard`。

- [ ] **Step 3：页面**

`AccessApplicationsPage.tsx` 用 `PageShell` + React Query：

```tsx
const currentSystemId = useCurrentSystemId()
const [status, setStatus] = useState<AccessApplicationStatus | undefined>()
const [page, setPage] = useState(1)
const listQuery = useQuery({
  queryKey: ['access-applications', currentSystemId, status, page],
  queryFn: () => getAccessApplications({ status, page, size: 20 }),
  enabled: currentSystemId !== undefined,
})
const optionsQuery = useQuery({
  queryKey: ['access-application-options', currentSystemId],
  queryFn: getAccessApplicationOptions,
  enabled: currentSystemId !== undefined,
})
```

页面必须包含：

1. `PageShell` 标题「我的申请」，描述说明“审批通过后按统一连续有效期授予角色，过期自动失效；本页不提供审批操作”。
2. 顶部筛选 `<Segmented>` 或 `Select`：全部/待审批/已通过/已驳回；右侧「新建申请」按钮；当前系统名称/编码提示。
3. 内容优先 `Table`（沿用 A19 表格样式），列：申请单号（PENDING 且有 `approvalUrl` 时 `<a target="_blank" rel="noreferrer">`）、状态 `Tag`（待审批 warning / 已通过 success / 已驳回 error）、申请角色 Tag、授权有效期（`formatDateTime` 或 `-`）、申请原因、申请时间、操作「详情」。
4. 新建申请 `Modal`（width 760）：
   - `Select mode="multiple"`，options 来自 `optionsQuery.data.roles`；`owned` 角色 `disabled`，label 后加「已拥有」；
   - 选中角色下按角色分组展示权限预览（名称 + code Tag）；无权限显示「该角色暂无有效权限」；
   - 申请原因 `Input.TextArea maxLength={200} showCount`；
   - 提交按钮 loading，前端校验 roleIds 非空、reason.trim() 非空；`createAccessApplication` 成功后关闭、重置、`message.success('申请已提交：' + applicationNo)`，并 invalidate 列表/options；
   - 错误直接展示后端 `message`（尤其 409「已有待审批申请」）。
5. 详情 `Drawer` 或 `Modal`：申请单号、状态、角色、原因、提交时间、审批人/时间、审批意见、统一授权窗口（`approvedValidFrom ~ approvedValidUntil`）、审批平台按钮（仅 PENDING 且 URL 存在）。
6. 空态/加载/错误态：无当前系统时 `Alert` 提示「当前账号在该系统没有有效角色，无法申请」；无申请单显示 `Empty`，不显示审批操作。

- [ ] **Step 4：构建**

```bash
( cd telbank-rbac-front && npm run build )
```

预期 `tsc -b && vite build` 通过。人工/无头检查：

- 业务用户 `13600136000` CRM：侧栏只有「我的申请」，能进入页面，不再显示「无管理权限」；
- 管理用户 `crm-admin-001`：首页仍落到管理页，菜单多出「我的申请」；
- 切换系统后列表 query key 变化，不串系统。

- [ ] **Step 5：提交**

```bash
git add telbank-rbac-front/src/api/types.ts telbank-rbac-front/src/api/accessApplications.ts \
  telbank-rbac-front/src/api/index.ts telbank-rbac-front/src/pages/AccessApplicationsPage.tsx \
  telbank-rbac-front/src/config/menu.ts telbank-rbac-front/src/layouts/AdminLayout.tsx \
  telbank-rbac-front/src/router.tsx telbank-rbac-front/src/styles/global.css
git commit -m "feat(front): 我的申请页面与业务用户自助入口"
```

---

## Task 7：模拟审批平台脚本

**Files:**
- Create: `scripts/mock-access-approval.py`（可执行）

- [ ] **Step 1：实现脚本**

```python
#!/usr/bin/env python3
"""模拟外部审批平台：对权限中心的自助申请单做出通过/驳回决策。

用法：
  ./scripts/mock-access-approval.py --application-no RA-20261025-A1B2C3
  ./scripts/mock-access-approval.py --application-no RA-... --decision REJECTED --comment 不符合规定
"""
import argparse, datetime as dt, hashlib, hmac, json, os, secrets, sys, time, urllib.request, urllib.error

APP_KEY = os.environ.get("RBAC_APPROVAL_APP_KEY", "approval-system")
APP_SECRET = os.environ.get("RBAC_APPROVAL_SECRET", "approval-dev-secret")
PATH = "/openapi/approval/access-applications/decision"
BASE_URL = os.environ.get("RBAC_BASE_URL", "http://localhost:8080").rstrip("/")


def iso(d: dt.datetime) -> str:
    return d.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def signed_headers(method: str, path: str, raw: bytes) -> dict:
    ts = str(int(time.time() * 1000))
    nonce = secrets.token_hex(12)
    canonical = "\n".join([APP_KEY, ts, nonce, method, path, "", hashlib.sha256(raw).hexdigest()])
    sig = hmac.new(APP_SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {"X-App-Key": APP_KEY, "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig}


def main() -> int:
    p = argparse.ArgumentParser(description="模拟审批平台回调权限中心")
    p.add_argument("--application-no", required=True)
    p.add_argument("--decision", choices=["APPROVED", "REJECTED"], default="APPROVED")
    p.add_argument("--valid-from", default="")
    p.add_argument("--valid-until", default="")
    p.add_argument("--comment", default="")
    p.add_argument("--reviewer", default="mock-approval-platform")
    p.add_argument("--base-url", default=BASE_URL)
    p.add_argument("--app-key", default=APP_KEY)
    p.add_argument("--secret", default=APP_SECRET)
    a = p.parse_args()
    global APP_KEY, APP_SECRET
    APP_KEY, APP_SECRET = a.app_key, a.secret

    now = dt.datetime.now(dt.timezone.utc)
    payload = {"applicationNo": a.application_no, "decision": a.decision,
               "decisionComment": a.comment, "reviewer": a.reviewer}
    if a.decision == "APPROVED":
        payload["validFrom"] = a.valid_from or iso(now)
        payload["validUntil"] = a.valid_until or iso(now + dt.timedelta(days=1))
    raw = json.dumps(payload, ensure_ascii=False).encode()
    headers = signed_headers("POST", PATH, raw)
    headers["Content-Type"] = "application/json"
    req = urllib.request.Request(a.base_url.rstrip("/") + PATH, data=raw, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            print(json.dumps(json.loads(resp.read().decode()), ensure_ascii=False, indent=2))
        return 0
    except urllib.error.HTTPError as e:
        print(e.read().decode(), file=sys.stderr)
        return 1
    except Exception as e:
        print("调用审批回调失败:", e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
```

注意：`--app-key`/`--secret` 参数用于本地测试；`main` 已在 `parse_args()` 后覆盖模块级 `APP_KEY/APP_SECRET`，`signed_headers` 使用覆盖后的值。

- [ ] **Step 2：可执行与自检**

```bash
chmod +x scripts/mock-access-approval.py
python3 scripts/mock-access-approval.py --help
```

- [ ] **Step 3：接触发接口验证**

先通过页面/验收脚本创建一张 PENDING 申请，再执行：

```bash
./scripts/mock-access-approval.py --application-no <申请单号> --comment "同意，仅限本次"
```

预期返回 `"status": "APPROVED"` 或 `"REJECTED"`；再次执行同一单号通过，预期 `"idempotent": true`。

- [ ] **Step 4：提交**

```bash
git add scripts/mock-access-approval.py
git commit -m "feat(script): 增加模拟外部审批回调脚本"
```

---

## Task 8：契约、验收、文档与全量验证

**Files:**
- Modify: `scripts/acceptance.py`
- Modify: `scripts/verify-all.sh`
- Modify: `docs/impl/contracts.md`
- Modify: `docs/acceptance.md`
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `docs/telbank-rbac.md`
- Modify: `telbank-rbac-front/README.md`

- [ ] **Step 1：验收脚本新增 §9.17**

在 `scripts/acceptance.py` 增加 `test_access_application()`，并在 `TESTS` 末尾注册 `("9.17", test_access_application)`。流程：

1. crm-admin token 创建两个临时非 admin 角色（`applyauditor<suffix>` / `applyreject<suffix>`），取得 `roleId`；
2. 查 `/api/permissions` 树，找 `order:approve`（避免和 136 已有 `order:export` 混淆），给审计角色 grant 该权限；
3. 136 CRM token 调 `GET /api/access-applications/options`：审计角色出现且 `owned=false`，当前已持有的 `operator` 角色 `owned=true`（ERP 采购角色不属于 CRM，不应出现在本系统选项）；
4. `POST /api/access-applications` 创建申请：PENDING、单号格式、角色快照；再创建一次 → 409；
5. 用 admin 角色 id 创建 → 400；用 ERP 角色 id 创建 → 400；
6. 用 `openapi(..., app_key=APPROVAL_APP_KEY, secret=APPROVAL_SECRET)` 调 decision（APPROVED，now-1min ~ now+60min；`validFrom` 必须在过去，否则立即查 `/api/me/bootstrap` 看不到新权限）：200、状态 APPROVED、`grantedRoleIds` 正确；再次相同参数 → `idempotent=true`；
7. 136 的 `/api/me/bootstrap` CRM 权限包含 `order:approve`；
8. 136 用 ERP 上下文 `GET /api/access-applications/{no}` → 404；
9. 创建第二张申请（驳回角色，不授权）→ approval decision REJECTED → 状态 REJECTED，再驳回幂等；
10. 检查所有申请单列表只包含当前用户/当前系统。

关键辅助函数（加到现有 helper 附近）：

```python
def access_decision(payload, secret=None):
    raw = json.dumps(payload).encode("utf-8")
    headers = signed_headers(APPROVAL_APP_KEY, secret or APPROVAL_SECRET,
                             "POST", "/openapi/approval/access-applications/decision", "", raw)
    headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        BASE_URL.rstrip("/") + "/openapi/approval/access-applications/decision",
        data=raw, headers=headers, method="POST")
    return _send(req)
```

- [ ] **Step 2：verify-all 产物核对**

在 `scripts/verify-all.sh` 的产物核对段增加：

```bash
[ -f telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql ] \
  || die "缺少 10_access_application.sql"
grep -q 'sys_access_application' telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql \
  || die "10_access_application.sql 未包含申请表"
grep -q 'sys_access_application_role' telbank-rbac-api/src/main/resources/db/manual/10_access_application.sql \
  || die "10_access_application.sql 未包含申请角色表"
[ -f telbank-rbac-front/src/pages/AccessApplicationsPage.tsx ] \
  || die "缺少我的申请页面"
grep -q '/access-applications' telbank-rbac-front/src/router.tsx \
  || die "前端路由缺少 /access-applications"
[ -x scripts/mock-access-approval.py ] \
  || die "缺少可执行模拟审批脚本 scripts/mock-access-approval.py"
```

- [ ] **Step 3：契约与文档**

- `docs/impl/contracts.md`：版本升到 v2.7；新增 §3.11「业务用户自助申请」（options/page/detail/create、字段、状态、错误码）；新增 §5.1「自助申请审批回调」（路径、签名、请求/响应、幂等/并发规则），保留旧 `/openapi/approval/grant` 契约不删；更新目录。
- `docs/acceptance.md`：新增 §9.17 验收记录，写清前置数据、执行命令、PASS 数、截图/脚本输出摘要。
- `README.md`：增加「业务用户申请临时权限」一节：菜单位置、申请流程、`RBAC_ACCESS_APPROVAL_URL_TEMPLATE` 配置、`mock-access-approval.py` 用法；demo 账号说明补充 136 可自助申请；明确待审批不可重复提交。
- `telbank-rbac-front/README.md`：增加「我的申请」页面、角色权限预览、审批外链说明。
- `docs/telbank-rbac.md` 附录 A：增加本次修订（申请表、自助入口、连续窗口、`*` 收回）。
- `docs/README.md`：版本/修订记录/当前状态更新，偏差计数相应 +1。

- [ ] **Step 4：全量验证**

```bash
# 1) 后端单测 + 前端构建 + 产物核对
./scripts/verify-all.sh
```

预期所有检查通过（脚本会跑后端、两个 SDK、前端 build、产物核对）。

```bash
# 2) 全新数据库 + 启动 API + 验收
RBAC_DB_NAME=telbank_rbac_v10 MYSQL_USER=root MYSQL_PASSWORD="${MYSQL_PASSWORD:-}" \
  ./scripts/init-db.sh --drop
# 按现有 README 用 8080 启动后端（或 ./scripts/start-all-macos.sh --temp-db）
python3 scripts/acceptance.py
```

预期验收全绿，至少包含 §9.17 的新检查；再单独跑：

```bash
python3 scripts/acceptance.py --only 9.17
./scripts/mock-access-approval.py --help
```

- [ ] **Step 5：提交**

```bash
git add scripts/acceptance.py scripts/verify-all.sh docs/impl/contracts.md docs/acceptance.md \
  README.md docs/README.md docs/telbank-rbac.md telbank-rbac-front/README.md
git commit -m "docs: 补充自助申请契约、验收与使用说明"
```

---

## 用户验收步骤（交付时直接给用户）

1. 启动数据库、后端和前端（现有 3 个平台脚本之一；或已有环境）。
2. 用业务账号 `13600136000` 登录前端，系统上下文选 CRM：
   - 侧栏出现「我的申请」，不再出现「无管理权限」空白页；
   - 新建申请，选择 `apply-auditor` 角色，能看到该角色的权限预览，填写原因提交；
   - 列表出现 PENDING 申请单号；再次提交应提示「已有待审批申请」。
3. 用管理账号 `crm-admin-001` 登录：确认管理菜单和首页不变；在「角色授权」页确认 `*` 不再出现在可授权树中。
4. 模拟审批：
   ```bash
   ./scripts/mock-access-approval.py --application-no <申请单号> --comment "同意"
   ```
5. 回到 136 账号刷新：
   - 申请单状态变为「已通过」，显示统一授权窗口；
   - 该角色权限立即生效（现有权限查询/SDK 一致）；
   - 用 ERP 上下文访问该申请单应 404（系统隔离）。
6. 再创建一张申请并用 `--decision REJECTED` 驳回：状态为「已驳回」，权限不增加。
7. 运行 `./scripts/verify-all.sh` 和 `python3 scripts/acceptance.py`，确认全绿。
8. 审批 URL 未配置时，页面只显示申请单号；配置 `RBAC_ACCESS_APPROVAL_URL_TEMPLATE` 后，PENDING 单号可外链到审批平台。

## 完成标准

- 两张申请表由 `10_access_application.sql` 创建，旧表无结构变更。
- 业务用户能 options/create/page/detail；非 admin 角色、当前系统、未拥有角色校验全部在后端。
- 回调通过/驳回原子、幂等；通过后复用 `ApprovalGrantService` 写 `sys_user_role`，过期由现有扫描器处理。
- 角色授权树和非 admin 后端都禁止 `*`。
- 前端菜单/首页/路由对业务用户和管理用户都正确。
- 验收 §9.17、`verify-all.sh`、文档、模拟脚本全部通过。
