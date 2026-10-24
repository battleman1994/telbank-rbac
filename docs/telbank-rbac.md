# 多系统 RBAC 权限管理平台 — 最终需求文档（DeepSeek Harness 执行版）

> 本文档为最终执行基线。所有技术栈和需求范围已拍板。权限中心为独立 JDK 21 进程；SDK 以 JDK 8 字节码编译，运行于业务系统后端；两者通过 HTTPS 和区域化 Redis Pub/Sub 通信，运行时完全隔离。业务系统前端只调业务系统后端或 SDK 自动注册的 `/rbac/me/*`，不直接访问权限中心。本文档不包含 OAuth2.0 服务间调用。
>
> **实现修订（2026-10-24）**：交付阶段确认的 11 项偏差/新增口径见文末「附录 A 实现修订记录」，与 `docs/impl/contracts.md` §9、`README.md` §9 保持一致；原始需求条目保留以保持可追溯。

**工程名称**：`telbank-rbac` 权限管理系统（多系统 RBAC 权限管理平台）

| 端 | 工程名 | 说明 |
|---|---|---|
| 权限中心管理前端 | `telbank-rbac-front` | React 19.2 + Vite 8 管理界面 |
| 权限中心后端 | `telbank-rbac-api` | JDK 21 + Spring Boot 4.1.x 权限中心 |
| SDK | `telbank-rbac-starter` | JDK 8 字节码，业务系统引入的 Spring Boot Starter |

> **Maven 坐标**：`groupId` 统一为 `com.telbank`，父工程（聚合 POM）`artifactId` 为 `telbank-rbac`；`telbank-rbac-api`、`telbank-rbac-starter` 为父工程子模块，Java 包名前缀统一为 `com.telbank`；`telbank-rbac-front` 为独立 npm 工程，不参与 Maven 坐标。工程名为唯一交付名，后续目录结构、构建脚本、README 中的模块名均以此为准。

---

## 1. 整体架构

### 1.1 参与角色

| 角色 | 说明 | 是否由本系统实现 |
|---|---|---|
| **权限中心** | 权限码、角色、授权关系、页面操作元数据、接口映射的唯一数据源；提供 SDK 开放接口和审批回调接口 | 是，核心交付 |
| **权限中心管理前端** | 权限中心自己的 Web 管理界面 | 是，核心交付 |
| **业务系统后端（含 SDK）** | 运行 SDK 的业务系统后端；SDK 提供整套权限校验逻辑 | 业务系统自有，SDK 由本系统交付 |
| **业务系统前端** | 业务系统自己的前端；只调业务系统后端或 SDK 自动注册的 `/rbac/me/*`；路由和菜单由前端代码维护 | 业务系统自有 |
| **外部认证系统** | 签发用户 JWT，`sub` 为 `external_user_id` | 外部已有 |
| **审批系统** | 审批通过后回调权限中心授权接口 | 外部已有 |
| **Redis（按区域）** | 权限变更通知通道；权限中心发布，SDK 订阅 | 基础设施 |
| **MySQL** | 权限中心数据存储 | 基础设施 |

### 1.2 职责边界

| 内容 | 归属 |
|---|---|
| 权限码定义、角色、授权关系、时效 | **权限中心** |
| 页面操作元数据（模式 B 按钮配置） | **权限中心** |
| 接口映射（`method + api_pattern → code`） | **权限中心** |
| 用户可访问系统列表 | **权限中心** |
| 用户认证（登录、JWT 签发） | **外部认证系统** |
| 审批流程、审批规则、审批通知 | **审批系统** |
| 菜单结构、路由树、侧边栏、页面组件 | **业务系统前端** |
| 前端路由与权限码绑定（`meta.code`） | **业务系统前端** |
| 业务系统自身的业务接口 | **业务系统后端** |
| 接口权限拦截执行 | **SDK（运行于业务系统后端）** |
| 权限数据 HTTP 查询接口（默认） | **SDK 自动注册的 `RbacController`** |
| 权限数据 Java 查询 API（可选） | **SDK 提供的 `RbacClient`** |

**关键原则**：

- 权限中心只做授权，不做认证，不管理菜单结构，不直接服务业务系统前端。
- 业务系统前端只调业务系统后端或 SDK 自动注册的 `/rbac/me/*`，不感知权限中心的存在。
- SDK 提供**整套权限校验逻辑**，只保留 `rbac-sdk.enabled` 总开关。业务系统要么全用 SDK，要么不用。
- 业务系统后端可零代码（直接用 SDK 自动注册的 `RbacController`），也可注入 `RbacClient` 自己封装接口。

### 1.3 部署拓扑

```text
┌─────────────────────────────────────────────────────────────────┐
│  外部认证系统（已有）— 签发用户 JWT                                │
└──────────────────────────────┬──────────────────────────────────┘
                               │ JWT
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  业务系统前端 — 路由表由前端代码维护，调 /rbac/me/* 或业务后端接口  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTPS + JWT + X-System-Code
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  业务系统后端 JVM（JDK 8 + Spring Boot 2.7.x）                    │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  rbac-sdk（JDK 8 字节码）                                   │  │
│  │  - RbacPermissionFilter 自动注册，拦截业务接口做权限校验      │  │
│  │  - RbacController 自动注册，暴露 /rbac/me/*                  │  │
│  │  - RbacClient：供业务后端编程式查询（可选）                  │  │
│  │  - 端点扫描上报                                             │  │
│  │  - 映射表缓存、用户权限集缓存（带时效）、页面操作元数据缓存     │  │
│  │  - Redis Pub/Sub 订阅、兜底短轮询                            │  │
│  │  - Feign + OkHttp 客户端、AppKey + HMAC-SHA256 签名          │  │
│  └───────────────────────────────────────────────────────────┘  │
└───────────────┬─────────────────────────────┬───────────────────┘
                │ HTTPS + 签名                 │ 订阅
                ▼                             ▼
┌───────────────────────────────┐  ┌───────────────────────────────┐
│  权限中心（JDK 21 + Boot 4.1） │  │  Redis（按区域部署）            │
│  - 权限码、角色、授权、时效     │  │  - rbac:change:{region}:{sys} │
│  - 页面操作元数据               │  │  - 权限中心发布，SDK 订阅       │
│  - 接口映射表                   │  └───────────────────────────────┘
│  - /openapi/sdk/* 开放接口      │
│  - /openapi/approval/grant      │
│  - 管理前端                     │
└───────────────┬───────────────┘
                │ HTTPS + 签名
                ▼
┌───────────────────────────────┐
│  审批系统（已有）— 审批通过后回调 │
└───────────────────────────────┘
```

### 1.4 核心数据流

**数据流 A：用户请求**

```text
1. 外部认证系统签发 JWT
2. 业务系统前端调 /rbac/me/bootstrap（SDK 自动注册）或业务系统自己的接口
3. SDK 的 RbacController 通过 RbacClient 取权限集（本地缓存，未命中则回源）
4. 返回 permissions + pageActions 给前端
5. 前端用 hasPerm 过滤路由、菜单、按钮
6. 用户操作 → 前端调业务接口
7. SDK RbacPermissionFilter 拦截：
   映射表未命中 → 放行；命中 → 查用户权限集 + 时效 → 放行/403
8. 进入业务 Controller
```

**数据流 B：权限变更**

```text
1. 管理员在权限中心修改角色权限或接口映射
2. 权限中心按 sys_system.region 向对应区域 Redis 发布消息
3. SDK 订阅到消息 → 拉取最新映射表/页面操作元数据 → 更新缓存
                    → 失效相关用户权限集缓存
4. Redis 不可用时，SDK 兜底短轮询全量拉取
```

**数据流 C：审批授权与到期收回**

```text
1. 用户在审批系统提交权限申请（权限码/角色 + 期望有效期 + 原因）
2. 审批通过
3. 审批系统调权限中心 POST /openapi/approval/grant
4. 权限中心写入 sys_user_role / sys_role_permission，携带 valid_from、valid_until、source_approval_id
5. 权限中心发布 Redis Pub/Sub 通知
6. SDK 收到通知，失效相关用户权限集缓存，下次请求回源拉取
7. 权限查询 SQL 带时效过滤：到期即视为无权限
8. 定时任务扫描过期授权，标记失效，再次发布通知
```

---

## 2. 项目目标

构建**统一权限中心 + 多业务系统权限隔离 + 零侵入 SDK** 的 RBAC 权限管理平台。

- **多系统隔离**：角色、权限、映射关系均属于某个业务系统（`system_id` 隔离），权限码系统内唯一，跨系统可重复。
- **用户来自外部系统**：不建用户表、不维护用户主数据。关联表中存 `external_user_id VARCHAR(255)`。
- **认证外部化**：外部系统签发 JWT，权限中心作为 OAuth2 Resource Server 只校验，无登录页。
- **权限中心只做授权**：不管理菜单结构、路由树、页面组件。
- **业务前端不直连权限中心**：只调业务系统后端或 SDK 自动注册的 `/rbac/me/*`。
- **SDK 整套逻辑 + 总开关**：完整权限校验逻辑，只保留 `rbac-sdk.enabled` 总开关，不提供细粒度扩展点。
- **一个业务权限码前后端共用**：`method`/`api_pattern` 可选，填了进映射表做后端拦截，不填只做前端展示控制。
- **权限时效性**：授权支持 `valid_from`/`valid_until`，审批通过后授予，到期自动收回。
- **安全底线**：前端隐藏按钮只是体验优化，后端接口强校验才是安全边界。

---

## 3. 技术栈（锁定）

### 3.1 权限中心后端

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21 LTS** | Spring Boot 4.1.x 要求 Java 17+ |
| Spring Boot | **4.1.x**（当前 4.1.2） | 当前最新稳定版 |
| Spring Security | **7.1.x**（随 Boot 管理） | OAuth2 Resource Server + 方法级授权 |
| 构建工具 | **Maven 3.6.3+** 或 **Gradle 8.14+** | 默认 Maven |
| 数据库 | **MySQL 8.0+** | utf8mb4 / utf8mb4_0900_ai_ci |
| 数据库迁移 | **flyway-core + flyway-mysql** | 不使用 `spring-boot-starter-flyway`；在配置类中显式声明 `Flyway` Bean 并执行迁移 |
| 缓存 | **Caffeine（一级）+ Redis（二级）** | 高频鉴权优先 Caffeine；Redis 用于区域化 Pub/Sub |
| 认证 | **外部 JWT + OAuth2 Resource Server** | 只校验，不签发，无登录接口 |
| Servlet 容器 | **Tomcat 11.0.x**（内嵌） | Spring Boot 4 默认 |

### 3.2 SDK（运行于业务系统后端）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK 字节码目标 | **Java 8**（`--release 8`） | 业务系统为 JDK 8 + Spring Boot 2.x |
| 目标框架 | **Spring Boot 2.7.x** | 使用 `META-INF/spring.factories` 单轨自动配置；不兼容 Boot 3/4 |
| Servlet | **Servlet 4.0**（Tomcat 9） | 拦截器用 `OncePerRequestFilter` |
| HTTP 客户端 | **Feign + OkHttp** | `feign.okhttp.enabled=true`；Feign 10.x/11.x，OkHttp 3.x/4.x |
| Redis 客户端 | Jedis 或 Lettuce（JDK 8 兼容版本） | 订阅 Redis Pub/Sub |
| 禁止使用 | `var`、`record`、`List.of()`、文本块等 Java 9+ 特性 | 全模块 `--release 8` 编译 |

### 3.3 权限中心管理前端

| 组件 | 版本 | 说明 |
|---|---|---|
| Node.js | **22.22.0+** | React Router 8.x 要求；Vite 8 要求 Node 20.19+ 或 22.12+ |
| React | **19.2.x** | 当前稳定版 |
| Vite | **8.x** | 当前稳定大版本 |
| @vitejs/plugin-react | **6.x** | Vite 官方 React 插件 |
| React Router | **8.x** | 要求 React 19.2.7+、Vite 7+、Node 22.22+ |
| TypeScript | **5.x** | 全前端 TypeScript |
| UI 组件库 | **Ant Design 6.x** | 仅 antd 主题 |
| 服务端状态 | **TanStack Query v5** | 权限、角色、授权、映射等 |
| 客户端状态 | **Zustand v5** | 当前用户、当前 systemCode、权限码集合 |
| HTTP 客户端 | **原生 fetch** | 不使用 Axios；封装 `request()` 注入 JWT 和 `X-System-Code` |

### 3.4 部署

- **不容器化**。交付源码 + 启动脚本。
- README 说明：MySQL、Redis（按区域）、权限中心后端 `mvn spring-boot:run`、管理前端 `npm install && npm run dev`。
- 业务系统前端和后端自行部署；SDK 以 Maven 依赖交付。

### 3.5 全链路安全

- SDK 与权限中心之间强制 HTTPS。
- SDK 调权限中心开放接口采用 **AppKey + AppSecret + Timestamp + Nonce + HMAC-SHA256 签名**。
- 审批系统回调同样采用 AppKey + HMAC-SHA256 签名。
- `AppSecret` 不在配置文件中明文存储，通过环境变量注入。
- Redis 连接配置（含密码）在 HTTPS 通道内传输，密码在数据库中加密存储。

---

## 4. 数据模型

### 4.1 表清单（共 7 张，无用户主数据表）

| 表 | 作用 | 关键约束 |
|---|---|---|
| `sys_system` | 业务系统 | `code` 全局唯一；`region` 标识所属区域 |
| `sys_role` | 角色 | `(system_id, code)` 唯一 |
| `sys_permission` | 权限码统一资源表 | `(system_id, code)` 唯一 |
| `sys_user_role` | 外部用户-角色关联（含时效） | `(system_id, external_user_id, role_id)` 唯一 |
| `sys_role_permission` | 角色-权限关联（含时效） | `(role_id, permission_id)` 唯一 |
| `sys_user_system` | 外部用户可访问系统 | `(system_id, external_user_id)` 唯一 |
| `sys_page_action` | 页面操作元数据 | `(system_id, page_code, code)` 唯一 |

### 4.2 核心表字段

#### `sys_system`

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
code VARCHAR(64) NOT NULL,
name VARCHAR(100) NOT NULL,
region VARCHAR(32) NOT NULL,
status TINYINT NOT NULL DEFAULT 1,
sort INT NOT NULL DEFAULT 0,
remark VARCHAR(255),
created_at, updated_at,
UNIQUE KEY uk_system_code (code),
KEY idx_system_region (region)
```

#### `sys_role`

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
code VARCHAR(64) NOT NULL,
name VARCHAR(100) NOT NULL,
status TINYINT NOT NULL DEFAULT 1,
sort INT NOT NULL DEFAULT 0,
remark VARCHAR(255),
created_at, updated_at,
UNIQUE KEY uk_role_system_code (system_id, code),
KEY idx_role_system (system_id)
```

#### `sys_permission`

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
type TINYINT NOT NULL,              -- 1目录/页面级 2操作级 3纯接口
code VARCHAR(128) NOT NULL,
name VARCHAR(100) NOT NULL,
method VARCHAR(10),                 -- 可选，非空表示关联后端接口
api_pattern VARCHAR(255),           -- 可选，非空表示关联后端接口，Ant 风格
sort INT NOT NULL DEFAULT 0,
status TINYINT NOT NULL DEFAULT 1,
is_public TINYINT NOT NULL DEFAULT 0,
meta JSON,
created_at, updated_at,
UNIQUE KEY uk_perm_system_code (system_id, code),
KEY idx_perm_system_parent (system_id, parent_id),
KEY idx_perm_api (system_id, method, api_pattern)
```

说明：
- 一个权限码表示一个业务能力。`method`/`api_pattern` 填了才进映射表做后端拦截；不填只做前端展示控制。
- 不含 `path`、`component`、`icon`、`visible` 字段。菜单结构和路由由业务系统前端维护。

#### `sys_user_role`（含时效）

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
external_user_id VARCHAR(255) NOT NULL,
role_id BIGINT UNSIGNED NOT NULL,
valid_from DATETIME NULL,           -- NULL 表示立即生效
valid_until DATETIME NULL,          -- NULL 表示永久有效
auto_revoke TINYINT NOT NULL DEFAULT 1,
grant_reason VARCHAR(255),
source_approval_id VARCHAR(128),
status TINYINT NOT NULL DEFAULT 1,
created_at, updated_at,
UNIQUE KEY uk_user_role (system_id, external_user_id, role_id),
KEY idx_user (system_id, external_user_id),
KEY idx_role (role_id),
KEY idx_valid_until (valid_until, auto_revoke, status)
```

#### `sys_role_permission`（含时效）

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
role_id BIGINT UNSIGNED NOT NULL,
permission_id BIGINT UNSIGNED NOT NULL,
valid_from DATETIME NULL,
valid_until DATETIME NULL,
auto_revoke TINYINT NOT NULL DEFAULT 1,
grant_reason VARCHAR(255),
source_approval_id VARCHAR(128),
status TINYINT NOT NULL DEFAULT 1,
created_at, updated_at,
UNIQUE KEY uk_role_perm (role_id, permission_id),
KEY idx_role_system (role_id, system_id),
KEY idx_perm (permission_id),
KEY idx_valid_until (valid_until, auto_revoke, status)
```

#### `sys_user_system`

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
external_user_id VARCHAR(255) NOT NULL,
status TINYINT NOT NULL DEFAULT 1,
is_default TINYINT NOT NULL DEFAULT 0,
created_at, updated_at,
UNIQUE KEY uk_user_system (system_id, external_user_id)
```

#### `sys_page_action`

```sql
id BIGINT UNSIGNED AUTO_INCREMENT,
system_id BIGINT UNSIGNED NOT NULL,
page_code VARCHAR(128) NOT NULL,
code VARCHAR(128) NOT NULL,
text VARCHAR(64) NOT NULL,
type VARCHAR(32) NOT NULL DEFAULT 'default',
action VARCHAR(32) NOT NULL,         -- openDialog / callApi / download / navigate / confirmThenCallApi
config JSON,
sort INT NOT NULL DEFAULT 0,
status TINYINT NOT NULL DEFAULT 1,
created_at, updated_at,
UNIQUE KEY uk_action (system_id, page_code, code)
```

### 4.3 权限码规范

- 命名：`模块:资源:操作`，如 `order:delete`。
- 唯一源头是 `sys_permission`。业务前端不维护权限码常量列表。
- `is_public=1` 表示所有登录用户默认拥有。
- 菜单、按钮、接口共用同一个业务权限码；`method`/`api_pattern` 可选。

### 4.4 平台超管

配置文件维护：

```yaml
rbac:
  platform-admin-ids:
    - platform-admin-001
```

命中则鉴权短路放行。

---

## 5. 权限中心核心功能

### 5.1 认证与系统上下文

1. 外部系统签发 JWT，`sub` 为 `external_user_id`。
2. 权限中心作为 OAuth2 Resource Server 校验 JWT 签名和有效期。
3. 权限中心管理前端由权限中心自己的登录体系保护（首期复用外部 JWT + 平台超管配置）。
4. 请求带 `Authorization: Bearer <token>` 和 `X-System-Code: <code>`。

### 5.2 权限中心自身接口强校验

- 启用 `@EnableMethodSecurity`。
- 权限中心业务接口使用 `@PreAuthorize` 或等价实现。
- `@perm` Bean 查询逻辑：JWT `sub` → `external_user_id`；命中平台超管则放行；否则查 `sys_user_role` 按 `(system_id, external_user_id)` 匹配权限码，带时效过滤。
- 无权限返回 403。

### 5.3 权限查询的时效过滤

所有权限查询 SQL 必须带：

```sql
AND (valid_from IS NULL OR valid_from <= NOW())
AND (valid_until IS NULL OR valid_until > NOW())
AND status = 1
```

**到期即视为无权限**，不依赖定时任务。

### 5.4 权限变更通知

以下操作后向对应区域 Redis 发布消息：

- 角色-权限关联变更
- 用户-角色关联变更
- 权限码的 `method`/`api_pattern` 变更
- 页面操作元数据变更
- 审批授权写入
- 定时任务自动收回过期授权

消息格式：

```text
频道：rbac:change:{region}:{systemCode}
消息：{"systemCode":"crm","type":"PERMISSION_CHANGED"}
```

### 5.5 自动收回定时任务

```text
定时任务（默认每分钟一次）：
  UPDATE sys_user_role SET status = 0
  WHERE valid_until IS NOT NULL AND valid_until <= NOW()
    AND auto_revoke = 1 AND status = 1

  UPDATE sys_role_permission 同理

  若有记录被更新，按 system_id 分组发布 Redis Pub/Sub 通知
```

### 5.6 审批回调接口

```http
POST /openapi/approval/grant
Header: X-App-Key: approval-system
Header: X-Timestamp: ...
Header: X-Nonce: ...
Header: X-Signature: HMAC-SHA256(...)

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

处理逻辑：

1. 验签。
2. 按 `approvalId` 幂等：已处理过直接返回成功。
3. **重新提权时，旧记录标记 `status=0`，新记录插入，不修改原记录**。
4. 写入 `valid_from`、`valid_until`、`source_approval_id`、`grant_reason`。
5. 发布 Redis Pub/Sub 通知。
6. 返回 `{"success": true, "grantId": 123}`。

权限中心不实现审批流程本身，只提供授权/收回的编程接口。

### 5.7 权限中心管理前端

功能包括：

- 系统管理（增/改/停用，配置 `region`）
- 角色管理（按系统筛选）
- 权限资源管理（树形，支持 `type`、`is_public`、`code`、`method`、`api_pattern` 编辑；`api_pattern` 保存时校验 Ant 风格语法）
- 角色-权限授权（树形勾选，按系统隔离，支持设置时效）
- 用户-角色分配（输入 `external_user_id` 精确添加，按 `system_id` 筛选角色，支持设置时效）
- 用户-系统开通
- 页面操作元数据管理
- SDK 端点上报列表（展示上报端点，管理员可为端点创建/关联权限码，配置 `method`/`api_pattern`）
- 审批授权记录查看
- 即将到期的授权列表

---

## 6. SDK 设计

### 6.1 设计原则

- **整套逻辑 + 总开关**：只保留 `rbac-sdk.enabled` 总开关，不提供细粒度自定义扩展点。
- **接入方零代码**：只加依赖 + 两行配置。
- **全量上报**：所有接口都上报（带 `@PreAuthorize`/`@Secured` 的除外）。
- **默认 public**：未配置权限码的接口默认公开，放行。
- **可用性优先**：映射表未命中默认放行；保留 `deny` 配置项。
- **区域化消息通道**：SDK 自动获取本区 Redis 配置。
- **全链路加密**：HTTPS + AppKey + HMAC-SHA256。
- **HTTP 客户端用 Feign + OkHttp**。

### 6.2 接入方式

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
rbac-sdk:
  enabled: true
  system-code: crm
  platform-base-url: https://rbac.example.com
  app-key: crm
  app-secret: ${RBAC_SDK_APP_SECRET}
```

- `enabled: true`：整套逻辑生效，业务系统零代码。
- `enabled: false`：整套逻辑不注册，SDK 不介入，业务系统自己负责权限校验。
- 没有中间态。

### 6.3 SDK 自动注册的组件

| 组件 | 作用 |
|---|---|
| `RbacPermissionFilter` | 拦截所有请求，做接口权限校验 |
| `RbacController` | 暴露 `/rbac/me/systems`、`/rbac/me/bootstrap` |
| `RbacEndpointScanner` | 启动时扫描端点并上报 |
| `RbacMappingCache` | 本地缓存映射表 |
| `RbacUserPermissionCache` | 本地缓存用户权限集（带时效） |
| `RbacChangeSubscriber` | 订阅 Redis Pub/Sub + 兜底短轮询 |

全部随总开关启停。

### 6.4 `RbacController` 默认接口

```http
GET /rbac/me/systems
→ [{"code":"crm","name":"CRM系统","isDefault":true}, ...]

GET /rbac/me/bootstrap
Header: X-System-Code: crm
→ {
    "permissions": ["order:menu","order:list","order:delete", ...],
    "pageActions": [ ... ],
    "platformAdmin": false
  }
```

- 路径前缀默认 `/rbac`，可通过 `rbac-sdk.endpoint.base-path` 修改。
- 不返回菜单树。
- **调用 `/rbac/me/bootstrap` 会预热用户权限集缓存**。

### 6.5 `RbacClient` Java API（可选）

```java
public interface RbacClient {
    List<SystemInfo> getSystems(String externalUserId);
    Set<String> getPermissions(String externalUserId, String systemCode);
    List<PageAction> getPageActions(String systemCode, String pageCode);
    boolean isPlatformAdmin(String externalUserId);
}
```

业务系统后端可注入使用，自行封装接口。不是 SDK 扩展点。

### 6.6 自定义注解 `@RbacPermission`

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RbacPermission {
    String value();
    String name() default "";
    boolean isPublic() default false;
}
```

- 方法级优先于类级。
- 存在时覆盖自动生成的候选码。
- 这是 SDK 唯一正式承诺解析的注解；不解析 Spring Security 注解。

### 6.7 端点扫描与上报

- 监听 `ApplicationReadyEvent`。
- 遍历 `RequestMappingHandlerMapping`。
- 跳过规则：方法或类上有 `@PreAuthorize` 或 `@Secured` → 跳过。
- 有 `@RbacPermission` → 用其 `value` 作为候选码。
- 无注解 → 按规则自动生成候选码：

```text
OrderController.deleteOrder + DELETE  →  crm:order:delete
```

规则可配置：

```yaml
rbac-sdk:
  code-generator:
    pattern: "{systemCode}:{resource}:{action}"
    resource-from: controller-class
    action-from: http-method
```

- 全量上报：

```http
POST /openapi/sdk/endpoints/sync
X-App-Key: crm
X-Timestamp: ...
X-Nonce: ...
X-Signature: ...
```

- 权限中心默认将未配置端点视为 public，不阻塞流程。

### 6.8 映射表下发与本地缓存

- 权限中心将 `method` 和 `api_pattern` 非空且 `status=1` 的记录按 `systemCode` 生成映射表：

```json
{
  "systemCode": "crm",
  "mappings": [
    { "method": "DELETE", "pattern": "/api/order/{id}", "code": "order:delete" },
    { "method": "GET",    "pattern": "/api/order/**",  "code": "order:list" }
  ]
}
```

- SDK 启动时全量拉取，预编译 Ant 风格规则，写入本地缓存。
- 不做版本号。变更通知或兜底轮询触发时全量拉取。

### 6.9 用户权限集获取与缓存（带时效 + 时钟偏移）

**接口返回结构**：

```json
{
  "permissions": [
    { "code": "order:delete", "validFrom": null, "validUntil": "2026-10-31T23:59:59Z" },
    { "code": "order:list",   "validFrom": null, "validUntil": null }
  ],
  "serverTime": "2026-10-24T10:00:00Z"
}
```

- `validFrom`/`validUntil` 为 null 表示无时效限制。
- `serverTime` 用于计算时钟偏移量。

**缓存结构**：

```java
class CachedPermission {
    String code;
    Instant validFrom;
    Instant validUntil;
}

// externalUserId -> systemCode -> code -> CachedPermission
Map<String, Map<String, Map<String, CachedPermission>>> cache;
```

**时钟偏移**：

```java
long offsetMillis = serverTime.toEpochMilli() - System.currentTimeMillis();
```

每次回源拉取权限数据时重新计算，抵消业务系统时钟漂移。

**缓存刷新策略**：

- **不设置缓存 TTL**。权限一旦授予，`validUntil` 不可修改；需要变更时重新提权，产生新记录。
- 缓存仅由**权限变更通知**驱动失效：SDK 收到 Redis Pub/Sub 消息后，失效相关用户权限集缓存，下次请求回源拉取。
- 缓存未命中时回源 `/openapi/sdk/user-permissions`，写入缓存。
- `/rbac/me/bootstrap` 调用会预热缓存，降低业务请求回源概率。

**回源接口**：

```http
GET /openapi/sdk/user-permissions?systemCode=crm&externalUserId=13800138000
X-App-Key: crm
X-Timestamp: ...
X-Nonce: ...
X-Signature: ...
```

### 6.10 动态拦截器（判断顺序明确）

**拦截器决策树**：

```text
请求进入 RbacPermissionFilter
  │
  ├─ 基础设施路径（/actuator/**、/error 等）→ 放行
  │
  ├─ 用 HTTP Method + Path 匹配本地映射表
  │    │
  │    ├─ 接口未命中映射表（该接口未配置权限控制）
  │    │    → 直接放行，不查用户权限集，不回源
  │    │
  │    └─ 接口命中映射表（该接口配置了权限码）
  │         → 得到 code
  │         │
  │         ├─ 查本地用户权限集缓存
  │         │    ├─ 缓存命中 → 判断 code 是否存在 + 时效是否有效
  │         │    │    ├─ 有权限且时效有效 → 放行
  │         │    │    └─ 无权限或时效已过 → 403
  │         │    └─ 缓存未命中 → 回源拉取 → 写入缓存 → 判断 code + 时效
  │         │         ├─ 有权限且时效有效 → 放行
  │         │         └─ 无权限或时效已过 → 403
  │         │
  │         └─ 回源失败（权限中心不可用）
  │              ├─ 有旧缓存 → 用旧缓存 + 时钟偏移判断
  │              └─ 无旧缓存 → 按 unmatched-strategy 处理（默认 pass）
  │
  └─ 异常 → 告警 + 放行
```

**关键术语**：

- **命中** = 请求的 `HTTP Method + Path` 在映射表里有记录，即该接口配置了权限控制。
- **未命中** = 映射表里没有该接口的记录，即该接口未配置权限控制，直接放行。
- 映射表命中后，才查用户权限集；映射表未命中，不查用户权限集、不回源。

**拦截器配置**：

- 自动注册 `FilterRegistrationBean`，拦截路径 `/*`。
- 过滤器顺序在 Spring Security 之后（`Ordered.LOWEST_PRECEDENCE - 100`）。
- 默认跳过 `/actuator/**`、`/error`、`/favicon.ico`，可通过 `exclude-paths` 覆盖。
- 匹配逻辑：精确匹配优先，通配符次之（Ant 风格，`**` 仅末尾）。
- `unmatched-strategy` 默认 `pass`，可配 `deny`。

### 6.11 变更通知机制（区域化 Redis Pub/Sub）

**SDK 获取连接配置**：

```http
GET /openapi/sdk/connection?systemCode=crm
X-App-Key: crm
X-Timestamp: ...
X-Nonce: ...
X-Signature: ...
```

返回：

```json
{
  "channel": "redis",
  "region": "region_a",
  "host": "redis-a.internal",
  "port": 6379,
  "password": "******",
  "channelPrefix": "rbac:change:region_a:"
}
```

**SDK 内部逻辑**：

```text
启动
  → 拉取全量映射表和页面操作元数据，写入本地缓存
  → 调 /openapi/sdk/connection 获取本区 Redis 配置
  → 连接本区 Redis，订阅 rbac:change:{region}:{systemCode}
  → 收到变更消息 → 拉取最新映射表/页面操作元数据 → 更新缓存
                    → 失效相关用户权限集缓存
  → 连接失败 → 告警 + 降级短轮询
```

**兜底短轮询**：SDK 内部低频定时任务（默认 5 分钟），全量拉取映射表和页面操作元数据。

全部封装在 SDK 内部。

### 6.12 SDK 调权限中心开放接口的鉴权

- 权限中心为每个系统分配 `AppKey` 和 `AppSecret`。
- SDK 请求头携带 `X-App-Key`、`X-Timestamp`、`X-Nonce`、`X-Signature`。
- 签名算法：HMAC-SHA256(AppSecret, 规范化参数 + timestamp + nonce + appKey)。
- 权限中心验签：查 `AppSecret`、校验时间戳窗口（5 分钟）、Redis 校验 `Nonce` 防重放、比对签名。
- `AppSecret` 仅存储在双方本地，不参与网络传输。
- 所有通信强制 HTTPS。

### 6.13 配置项

```yaml
rbac-sdk:
  enabled: true                  # 总开关
  system-code: crm
  platform-base-url: https://rbac.example.com
  app-key: crm
  app-secret: ${RBAC_SDK_APP_SECRET}
  app-version: ${project.version}

  endpoint:
    base-path: /rbac

  scan:
    base-packages: com.telbank.crm
    report-on-startup: true

  interceptor:
    unmatched-strategy: pass      # pass / deny
    exclude-paths:
      - /actuator/**
      - /error
      - /favicon.ico

  cache:
    fallback-poll-interval: 300s

  code-generator:
    pattern: "{systemCode}:{resource}:{action}"
    resource-from: controller-class
    action-from: http-method
```

### 6.14 渐进式接入路径

| 阶段 | 业务方动作 | SDK 行为 | 风险 |
|---|---|---|---|
| 1 观察 | 加依赖和配置 | 扫描上报，拦截器查不到即放行 | 零风险 |
| 2 配置 | 权限中心为接口创建/关联权限码，填写 `method`/`api_pattern`，授权 | 映射表下发，SDK 拦截器按映射校验 | 低风险 |
| 3 稳定 | 无需额外动作 | 受控接口无权限返回 403，未配置接口继续放行 | 安全边界明确 |

---

## 7. 业务系统前端设计

### 7.1 设计原则

- 前端只调业务系统后端或 SDK 自动注册的 `/rbac/me/*`，不直接访问权限中心。
- 路由和菜单由前端代码维护，权限中心不参与。
- 前端通过 `hasPerm(code)` 过滤路由、菜单、按钮。

### 7.2 路由模型

```tsx
const routes = [
  {
    path: '/order',
    element: <OrderLayout />,
    meta: { code: 'order:menu', title: '订单管理', icon: 'ShoppingCart' },
    children: [
      {
        path: '/order/list',
        element: <OrderList />,
        meta: { code: 'order:list', title: '订单列表' },
      },
      {
        path: '/order/detail/:id',
        element: <OrderDetail />,
        meta: { code: 'order:detail', title: '订单详情', hideInMenu: true },
      },
    ],
  },
]
```

- 路由结构、组件、层级全部在前端代码里。
- 每个路由的 `meta.code` 绑定一个权限码。
- `hideInMenu` 控制是否出现在侧边栏。
- 无 `meta.code` 的路由视为公开路由。

### 7.3 运行时过滤

```tsx
const visibleRoutes = filterRoutesByPermission(routes, hasPerm)
const menuItems = buildMenuFromRoutes(visibleRoutes)
```

- `hasPerm(route.meta.code)` 为 false 的路由，不注册、不出现在侧边栏。
- 用户手动输入无权限路由 → 匹配不到路由 → 404 或跳 403。

### 7.4 数据获取

业务系统前端可调用：
- SDK 自动注册的 `GET /rbac/me/bootstrap` 和 `GET /rbac/me/systems`；
- 或业务系统后端自己封装的接口（通过 `RbacClient` 获取数据）。

返回结构包含 `permissions`、`pageActions`、`platformAdmin`。

### 7.5 状态管理

```ts
interface AppState {
  systems: SystemInfo[]
  currentSystemCode: string
  permissions: Set<string>
  pageActions: Map<string, PageAction[]>
  platformAdmin: boolean
}
```

- `permissions` 用 `Set`，`hasPerm(code)` 直接 `set.has(code)`。
- 平台超管返回 `permissions: ["*"]`，`hasPerm` 直接返回 true。

### 7.6 按钮控制

```tsx
// 模式 A：复杂页面特殊按钮
{hasPerm('order:approve') && <Button onClick={openApproveFlow}>审批</Button>}

// 模式 B：标准 CRUD 操作区
<PageActions pageCode="order:list" />
```

### 7.7 权限变更后的刷新

- 前端不做定时轮询。
- 用户刷新页面、切换系统时重新拉取权限数据。
- 权限变更后前端什么时候看到，取决于用户是否刷新页面或切换路由。

---

## 8. 交付物

1. **权限中心后端源码**（`telbank-rbac-api`）：Spring Boot 4.1.x + JDK 21，含 Flyway 手动配置、OAuth2 Resource Server 配置、`@perm` Bean、Caffeine + Redis 缓存、区域化 Redis Pub/Sub、自动收回定时任务、SDK 开放接口、审批回调接口、AppKey 签名校验。
2. **SDK 源码**（`telbank-rbac-starter`）：JDK 8 字节码，Spring Boot 2.7.x `spring.factories` 自动配置，含 `RbacPermissionFilter`、`RbacController`、`RbacClient`、`@RbacPermission`、端点扫描、全量上报、映射表缓存、用户权限集缓存（带时效 + 时钟偏移）、页面操作元数据缓存、Redis Pub/Sub 订阅、降级短轮询、Feign + OkHttp 客户端、AppKey 签名。只保留 `rbac-sdk.enabled` 总开关。
3. **权限中心管理前端源码**（`telbank-rbac-front`）：React 19.2 + Vite 8 + TypeScript + Ant Design 6 + TanStack Query v5 + Zustand v5 + 原生 fetch，含系统管理、角色管理、权限资源管理、角色-权限授权、用户-角色分配（含时效）、用户-系统开通、页面操作元数据管理、SDK 端点上报列表、审批授权记录、即将到期授权列表。
4. **数据库脚本**：Flyway 管理，含示例系统（CRM/ERP，分属不同 region）、角色、权限码（含 `method`/`api_pattern` 示例和 `is_public` 示例）、页面操作元数据、示例授权记录（含永久和临时授权）、平台超管配置示例。
5. **README**：环境要求（JDK 21、JDK 8、Node 22.22+、MySQL 8.0+、Redis 多区域部署）、JWT 校验配置、AppKey/AppSecret 配置、启动步骤、SDK 接入示例、审批回调示例、验收操作步骤。
6. **验收证据**：逐条对照第 9 节，提供测试记录或自动化测试用例。

---

## 9. 验收场景（逐条必须通过）

### 9.1 多系统隔离

1. 外部用户 A 在 CRM 有 `order:delete`，在 ERP 没有；切换系统后，CRM 删除按钮可见，ERP 同码按钮不可见。
2. 用户 A 调 ERP 的 `order:delete` 接口返回 403，即使持有 CRM 同码权限。
3. CRM 和 ERP 可以有同名角色编码（如 `admin`），互不影响。
4. 同一 `external_user_id` 字符串在不同系统下互不干扰。

### 9.2 权限码模型

1. 配置了 `method + api_pattern` 的权限码（如 `order:delete`），前端按钮和后端接口同时受控。
2. 未配置 `method + api_pattern` 的权限码（如 `order:view:amount`），只控制前端展示，SDK 不拦截。
3. `is_public=1` 的权限码，所有主体默认拥有。

### 9.3 SDK 整套逻辑与总开关

1. `rbac-sdk.enabled: true` 时，`RbacPermissionFilter` 和 `RbacController` 自动注册，业务系统后端零代码。
2. `rbac-sdk.enabled: false` 时，整套逻辑不注册，SDK 不介入。
3. 不提供细粒度组件开关。

### 9.4 业务前端数据获取

1. 业务系统前端调 `/rbac/me/bootstrap` 获取 `permissions` 和 `pageActions`。
2. 业务系统前端调 `/rbac/me/systems` 获取系统列表。
3. `/rbac/me/bootstrap` 不返回菜单树。
4. 业务系统后端可注入 `RbacClient` 自行封装接口。
5. 前端路由表由代码维护，`hasPerm` 过滤后无权限路由不注册、不出现在侧边栏。

### 9.5 模式 B 元数据驱动

1. 订单列表页操作列按钮由 `sys_page_action` 配置驱动，后端新增 `order:export` 配置并授权后，前端刷新按钮出现，不改前端代码。
2. 后端删除配置或移除角色权限后，按钮消失，不改前端代码。
3. `callApi`、`confirmThenCallApi`、`openDialog`、`download`、`navigate` 至少各验证一种。

### 9.6 模式 A 手写按钮

1. 订单详情页“审批”按钮用 `hasPerm('order:approve')` 控制；移除权限后按钮消失。
2. 前端隐藏后直接调接口返回 403。

### 9.7 公共权限与平台超管

1. `is_public=1` 的权限码，所有登录用户 `hasPerm` 返回 true。
2. 配置在 `rbac.platform-admin-ids` 中的 ID，在任意系统下可访问全部菜单和接口。
3. 平台超管不受 `sys_user_role` 限制。

### 9.8 SDK 扫描与上报

1. 业务系统引入 SDK 并配置 `systemCode`，启动后权限中心收到全量端点上报。
2. 带 `@PreAuthorize` 的接口不出现上报列表中，SDK 不拦截。
3. 带 `@RbacPermission` 的接口，候选码使用注解 `value`。
4. 无注解接口按规则生成候选码。
5. 未配置权限码的接口默认视为 public，不阻塞任何流程。

### 9.9 SDK 拦截器判断顺序

1. 请求的 `HTTP Method + Path` **未命中映射表** → 直接放行，不查用户权限集，不回源。
2. 请求**命中映射表** → 查用户权限集缓存：
   - 缓存命中，有权限且时效有效 → 放行。
   - 缓存命中，无权限或时效已过 → 403。
   - 缓存未命中 → 回源拉取 → 写入缓存 → 判断 → 放行或 403。
3. 回源失败且有旧缓存 → 用旧缓存 + 时钟偏移判断。
4. 回源失败且无旧缓存 → 按 `unmatched-strategy` 处理（默认 pass）。
5. 未配置权限码的接口，永远不触发回源。
6. `/rbac/me/bootstrap` 调用后，用户权限集缓存已预热，后续业务请求命中缓存。

### 9.10 映射表与通配符

1. 受控接口无权限用户请求返回 403；有权限用户放行。
2. `unmatched-strategy: deny` 配置下，未匹配请求返回 403。
3. 通配符 `api_pattern`（如 `/api/order/**`）保存时校验 Ant 风格语法，非法语法拒绝保存；SDK 按通配符匹配请求。
4. 带 `@PreAuthorize` 的接口，SDK 拦截器不介入，原有安全逻辑生效。
5. SDK 拦截器自动注册，接入方不需要编写 Filter；过滤器顺序在 Spring Security 之后。
6. `/actuator/**`、`/error` 等基础设施路径默认放行。

### 9.11 区域化消息通道

1. 系统 A 在 region_a、系统 B 在 region_b，权限变更后各自区域的 SDK 均能通过 Redis Pub/Sub 收到通知并刷新缓存。
2. `GET /openapi/sdk/connection?systemCode=crm` 返回 CRM 所在区域的 Redis 配置。
3. 区域 Redis 不可用时，SDK 降级为短轮询，业务请求不受影响，告警可见。

### 9.12 权限时效与审批集成

1. 审批系统调 `POST /openapi/approval/grant` 授予临时角色（`valid_until` 为未来时间），用户在有效期内 `hasPerm` 返回 true。
2. 超过 `valid_until` 后，即使用户未重新登录，缓存中的 `validUntil` 与当前时间（本地时间 + 时钟偏移）比对使 `hasPerm` 返回 false，接口返回 403。
3. 定时任务扫描到过期授权后标记 `status=0`，并发布 Redis Pub/Sub 通知，SDK 失效相关用户权限集缓存。
4. 相同 `approvalId` 重复回调，权限中心幂等处理，不重复授权。
5. 永久授权（`valid_until` 为 NULL）不受时效过滤影响。
6. 重新提权时，旧记录标记 `status=0`，新记录插入，不修改原记录。
7. 权限中心返回 `serverTime`，SDK 计算时钟偏移量；业务系统时钟偏差在容忍窗口内不影响时效判断。
8. 审批回调签名错误、时间戳过期、Nonce 重放均返回 401。

### 9.13 开放接口鉴权与加密

1. SDK 调 `/openapi/sdk/*` 时均携带 AppKey + HMAC-SHA256 签名。
2. 签名错误、时间戳过期、Nonce 重放均返回 401。
3. 所有通信强制 HTTPS；Redis 配置和权限数据在 HTTPS 通道内传输。

### 9.14 安全底线

1. 未配置权限码默认 false，按钮不显示。
2. 前端隐藏按钮后直接调接口返回 403。
3. 无有效 JWT 或 JWT 过期，所有业务接口返回 401。

---

## 10. 已拍板项汇总（锁定）

| 编号 | 拍板结果 |
|---|---|
| Q1 | JDK 21 LTS（权限中心后端）；SDK 为 JDK 8 字节码 |
| Q2 | Spring Boot 4.1.x（权限中心后端）；SDK 适配 Spring Boot 2.7.x |
| Q3 | MySQL 8.0+ |
| Q4 | Caffeine + Redis 两级缓存；Redis 同时用于区域化 Pub/Sub |
| Q5 | 外部 JWT + OAuth2 Resource Server 校验，无登录页 |
| Q6 | Ant Design 6.x，仅 antd 主题 |
| Q7 | 仅源码 + 启动脚本，不容器化 |
| Q8 | Header `X-System-Code` |
| Q9 | A+B 混合：B 限标准 CRUD 操作区，A 用于复杂页面特殊按钮 |
| Q10 | 登录/刷新/切换系统时拉取权限，不做前端轮询和版本号 |
| Q11 | 仅功能权限，不做数据权限 |
| Q12 | 业务系统前端不维护权限码常量列表，数据库为唯一源头 |
| Q13 | 用户来自外部系统，无用户表，`external_user_id VARCHAR(255)` |
| Q14 | 平台超管用 `rbac.platform-admin-ids` 配置 |
| Q15 | SDK 只支持 JDK 8，后续高版本另开发 |
| Q16 | SDK 不解析 Spring Security 注解，只支持 `@RbacPermission` + 自动生成候选码；带 Spring Security 注解的接口完全跳过 |
| Q17 | 未匹配请求默认 `pass`，保留 `deny` 配置项 |
| Q18 | 通配符由权限中心配置并校验 Ant 风格语法，SDK 扫描单个 API |
| Q19 | 变更通知用区域化 Redis Pub/Sub，SDK 自动获取本区连接配置，失败降级短轮询 |
| Q20 | 一个业务权限码前后端共用，`method`/`api_pattern` 可选；映射表只取非空记录 |
| Q21 | SDK 全量上报，不排除业务接口；未配置权限码默认 public |
| Q22 | 映射表直接全量拉取，不做版本号 |
| Q23 | SDK 自动注册拦截器，接入方零 Filter 代码 |
| Q24 | SDK 调权限中心开放接口用 AppKey + HMAC-SHA256 签名，全链路 HTTPS |
| Q25 | 不纳入 OAuth2.0 服务间调用 |
| Q26 | 不使用 `spring-boot-starter-flyway`，Flyway 手动配置 |
| Q27 | SDK 使用 Feign + OkHttp；前端使用原生 fetch，不用 Axios |
| Q28 | 权限支持时效性，审批系统回调授权，到期自动收回 |
| Q29 | 权限中心不管理菜单结构；菜单和路由由业务系统前端维护 |
| Q30 | 业务系统前端只调业务系统后端或 SDK 自动注册的 `/rbac/me/*` |
| Q31 | SDK 提供 `RbacClient` Java API，供业务系统后端可选使用 |
| Q32 | SDK 只保留 `rbac-sdk.enabled` 总开关，整套逻辑一起启停 |
| Q33 | 用户权限集缓存带时效信息（`validFrom`/`validUntil`），不设 TTL，仅由权限变更通知驱动刷新 |
| Q34 | 权限中心返回 `serverTime`，SDK 计算时钟偏移量用于时效判断 |
| Q35 | 重新提权时旧记录标记 `status=0`，新记录插入，不修改原记录 |
| Q36 | 拦截器判断顺序：映射表未命中直接放行，不查用户权限集、不回源；命中后才查用户权限集 |

---

## 附录 A：实现修订记录（2026-10-24）

> 原始需求条目保留不动；以下为交付阶段确认的差异与补充，详细论证见 `docs/impl/contracts.md` §9，验收证据见 `docs/acceptance.md`。

| 编号 | 原文档条目 | 实现修订 | 原因 / 依据 |
|---|---|---|---|
| A1 | §4.1 表清单「共 7 张」 | 新增第 8 张表 `sys_sdk_endpoint` | §5.7 要求 SDK 端点上报列表与关联权限码，需要持久化；契约 §9.2 |
| A2 | §5.6 / Q35 重新提权：旧行 `status=0` + 新行插入 | 同关系**更新原行**（时效/审批号/原因/状态） | `sys_user_role`/`sys_role_permission` 唯一键不允许同一关系存在两行；契约 §9.1 |
| A3 | §5.6 审批回调 `permissionCodes` | 仅支持 `grantType=role`；`permissionCodes` 仅用于校验审批内容与角色授权一致 | 无 `user_permission` 表，无法绕过角色直接授权；契约 §9.3 |
| A4 | §3.5 / §4 AppSecret、区域 Redis 密码「数据库加密存储」 | 环境变量注入，不新增密钥表 | 7 张表中无对应存储表；契约 §9.4 |
| A5 | §3.4 / §6.8 / §9.10 `api_pattern` 仅 Ant 风格 | 额外支持 Spring 模板 `{id}`（单段，与 `*` 等价）；`**` 仅允许末尾 | 端点上报 path 为 Spring 模板，`link` 需直接复用；契约 §3.4 / §7.7 |
| A6 | §6.4 `/rbac/me/systems` 数据来源未定义 | 新增开放接口 `GET /openapi/sdk/user-systems` | 管理端 `/api/user-systems` 需 JWT，SDK 不能调用；契约 §4.2 |
| A7 | §3.2 SDK「JDK 8 字节码」 | 字节码目标仍为 Java 8（major=52），但**构建工具链为 JDK 17+**（`--release 8`）；运行时 JDK 8+ | 官方 OpenJDK 8 无 macOS ARM 原生版本；GitHub Actions 已用 JDK 8 运行时验证；README §5 |
| A8 | §2 / §5 未明确权限中心自身是否接入权限体系 | v1 曾实现为权限中心作为 `rbac` 系统自接入；**v2.0 已被 A11 取代并删除** | 方案 A（用户确认）→ A11 系统隔离重构；契约 v2.0 |
| A9 | §3.2 SDK 仅面向 JDK 8 | 新增 **JDK 21 + Spring Boot 4.x SDK 变体** `telbank-rbac2-starter`：类名/包名/配置键/HTTP 协议与 JDK 8 版一致，同一业务系统二选一（同包名不可共存） | 供 JDK 21 + Boot 4 业务系统接入；契约 §0/§7.9 |
| A10 | §3.1/§4.1 数据库迁移使用 Flyway | **移除 Flyway**（依赖 + 手动 `FlywayConfig`）；数据库改为手动 SQL：`telbank-rbac-api/src/main/resources/db/manual/*.sql`，由 `scripts/init-db.sh` 建库并按顺序导入；`start-all.sh --temp-db` 自动导入；后端启动前库表须已存在 | 用户确认不需要 Flyway；契约 §2、README §4/§9 |
| A11 | §2/§5 权限管理模型：系统隔离、超管定义、用户-系统开通未明确 | **系统作为隔离边界**：系统管理员只管理本系统角色/权限，跨系统 403；平台超管改为**数据驱动**（平台系统 `platform` + `super-admin` + `platform:admin`，种子账号 `superadmin`），删除配置文件；**删除 `sys_user_system` 与用户-系统开通**，系统访问由有效角色推导；管理上下文 `X-System-Code=当前业务系统`；管理权限码改为系统内短码（`role:manage` 等）；**系统接入仅超管**且必须录入系统管理员，服务端自动创建默认 `admin` 角色 + 6 个短权限码并授权；菜单重构为「权限管理」组 + 顶级「系统接入」 | 用户确认的新设计（v2.0，取代 A8）；契约 v2.0 §1.5/§3/§10、README §1.1 |

> 补充验证：真实 Redis Pub/Sub 联调、JDK 8 运行时 CI、JDK 21 SDK 单测、**v2.0 系统隔离验收（API 81/81，含两个 SDK e2e 各 4/4）** 的记录见 `docs/acceptance.md`。

---

**文档结束。DeepSeek Harness 按此文档开发，验收按第 9 节逐条执行。**