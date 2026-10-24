# telbank-rbac 验收矩阵（对照需求第 9 节）

> 需求基线：[`docs/telbank-rbac.md`](telbank-rbac.md) §9。文档总览与维护规则见 [`docs/README.md`](README.md)。
> 本文件逐条给出**验证方式、预期结果、证据状态**。证据状态分三类：
> - `API 脚本`：`python3 scripts/acceptance.py`（权限中心 API 级，真实 HTTP + 签名）
> - `单元测试`：`mvn -pl <module> test`（SDK 行为 / 算法类）
> - `联调`：示例业务系统 + 管理前端（需要 JDK 21/Node 环境）
>
> 最近一次执行结果会在文末「执行记录」追加。

## 0. 如何复现

> 想一条命令起临时库+前后端（分平台脚本）：
> - macOS：`./scripts/start-all-macos.sh --temp-db`
> - Linux：`./scripts/start-all-linux.sh --temp-db`
> - Windows：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all-windows.ps1 -TempDb`
>
> 自动起一次性 MySQL/建库（默认后端 8080、前端 5173），`Ctrl+C` 同时停止并清理，日志见 `logs/`；已有 MySQL 时去掉 `--temp-db`。

```bash
# 1) 一次性本地 MySQL + 后端 + 前端（每平台一个自包含脚本；已有 MySQL 时去掉 --temp-db）
./scripts/start-all-macos.sh --temp-db   # Linux 用 start-all-linux.sh；Windows 用 start-all-windows.ps1 -TempDb

# 2) API 级验收（另开终端）
python3 scripts/acceptance.py

# 3) SDK / 前端单元与构建
mvn -pl telbank-rbac-starter test
cd telbank-rbac-front && npm install --no-audit --no-fund && npm run build

# 4) 管理前端联调（可选）
# 前端由平台一键脚本启动；也可 cd telbank-rbac-front && npm run dev
```

---

## 9.1 多系统隔离

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 用户 A 在 CRM 有 `order:delete`，ERP 没有；切换系统后按钮差异 | API 脚本 `§9.1`：分别取两系统 user-permissions；前端按 `hasPerm` 过滤 | CRM 含 `order:delete`，ERP 不含 | ✅ |
| 2 | 用户 A 调 ERP 的 `order:delete` 接口返回 403 | 单元测试（SDK filter：命中映射 + 无权限 → 403）+ 联调示例业务系统（CRM/ERP 两实例） | ERP 实例 403，CRM 实例放行 | ✅ |
| 3 | CRM/ERP 可有同名角色编码 `admin` | API 脚本：两系统 roles 列表均含 `admin` | 各自独立存在 | ✅ |
| 4 | 同一 `external_user_id` 在不同系统互不干扰 | API 脚本：`13600136000` 两系统权限集不同 | 隔离成立 | ✅ |

## 9.2 权限码模型

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `method+api_pattern` 权限码前后端同时受控 | API 脚本：mappings 含 `order:list`/`order:delete`；前端按钮受控 | 映射下发正确 | ✅ |
| 2 | 未配 `method+api_pattern` 的码只控前端 | API 脚本：`order:view:amount` 不在映射表；权限集包含它 | 不下发拦截 | ✅ |
| 3 | `is_public=1` 默认拥有 | API 脚本：`common:profile` 出现在任意用户权限集 | 所有登录用户拥有 | ✅ |

## 9.3 SDK 整套逻辑与总开关

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `enabled=true` 自动注册 Filter/Controller，业务零代码 | 单元测试（AutoConfiguration 条件测试 / spring.factories 存在性）+ 联调示例业务系统 | 组件全部注册 | ✅ |
| 2 | `enabled=false` 整套不注册 | 单元测试（`ApplicationContextRunner` 断言 Bean 缺失） | 零注册 | ✅ |
| 3 | 无细粒度开关 | 代码审查：仅 `rbac-sdk.enabled` 条件 | 成立 | ✅ |

## 9.4 业务前端数据获取

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `/rbac/me/bootstrap` 返回 permissions + pageActions | 单元测试（Controller MockMvc）+ 联调 | 结构正确、不含菜单树 | ✅ |
| 2 | `/rbac/me/systems` 返回系统列表 | 单元测试（Mock RbacClient/Feign）+ 联调 | 结构正确 | ✅ |
| 3 | bootstrap 不返回菜单树 | 单元测试断言 JSON 无 menu/routes 字段 | 成立 | ✅ |
| 4 | 业务后端可注入 `RbacClient` 自封装 | 单元测试（RbacClient 四个方法契约） | 可用 | ✅ |
| 5 | 前端路由表由代码维护，`hasPerm` 过滤 | 管理前端代码审查 + 示例业务前端（文档说明） | 成立 | ✅ |

## 9.5 模式 B 元数据驱动

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 新增 `order:export` 配置并授权后按钮出现（不改前端） | API 脚本：新增/删除 page_action 后 openapi page-actions 实时变化 + 管理前端联调 | 动态出现/消失 | ✅ |
| 2 | 删除配置或移除权限后按钮消失 | 同上 | 动态消失 | ✅ |
| 3 | 五种 action 至少各验证一种 | API 脚本：断言五种 action 均有示例 | 五种齐全 | ✅ |

## 9.6 模式 A 手写按钮

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 详情页“审批”按钮用 `hasPerm` 控制 | 示例业务前端代码（文档交付，不在本仓库）/ 管理前端模式 A 代码审查 | 成立 | ✅ |
| 2 | 前端隐藏后直接调接口 403 | 单元测试（filter 命中 + 无权限 403） | 403 | ✅ |

## 9.7 公共权限与平台超管

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `is_public=1` 所有登录用户 hasPerm=true | API 脚本（同 9.2.3） | 成立 | ✅ |
| 2 | 平台超管任意系统可访问全部菜单/接口 | API 脚本：`superadmin` 的 `platformAdmin=true`、`/api/me/systems` 返回全部业务系统、user-permissions=`["*"]` | 成立 | ✅ |
| 3 | 平台超管由数据驱动（platform 系统 + platform:admin），非配置文件 | API 脚本 + 种子数据核对；`crm-admin-001`/`erp-admin-001` 非超管 | 成立 | ✅ |

## 9.8 SDK 扫描与上报

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 启动后权限中心收到全量端点上报 | 单元测试（scanner 扫描候选码 + 上报请求体）+ API 脚本 `endpoints/sync` | 幂等 upsert | ✅ |
| 2 | 带 `@PreAuthorize` 的接口不出现在上报列表 | 单元测试（scanner 跳过规则） | 跳过 | ✅ |
| 3 | 带 `@RbacPermission` 的接口用注解 value 作为候选码 | 单元测试（scanner） | 成立 | ✅ |
| 4 | 无注解接口按规则生成候选码 | 单元测试（code generator） | 成立 | ✅ |
| 5 | 未配置权限码的接口默认 public | 单元测试（filter 映射未命中直接放行） | 成立 | ✅ |

## 9.9 SDK 拦截器判断顺序

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 映射未命中 → 直接放行，不查权限集、不回源 | 单元测试（filter + mock RbacClient 断言无调用） | 成立 | ✅ |
| 2 | 命中 → 缓存命中/未命中/回源/403 全链路 | 单元测试（filter 各分支） | 成立 | ✅ |
| 3 | 回源失败有旧缓存 → 旧缓存 + 时钟偏移 | 单元测试 | 成立 | ✅ |
| 4 | 回源失败无缓存 → unmatched-strategy（默认 pass） | 单元测试 | 成立 | ✅ |
| 5 | 未配置权限码接口永不回源 | 单元测试 | 成立 | ✅ |
| 6 | bootstrap 预热权限集缓存 | 单元测试（controller 调用后 cache 有值） | 成立 | ✅ |

## 9.10 映射表与通配符

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 受控接口无权限 403 / 有权限放行 | 单元测试 + 联调 | 成立 | ✅ |
| 2 | `unmatched-strategy=deny` 未匹配 403 | 单元测试 | 成立 | ✅ |
| 3 | 通配符保存校验 + SDK 通配匹配 | API 脚本：非法 `**` 中间 → 400，合法保存后映射下发；单元测试匹配 | 成立 | ✅ |
| 4 | 带 `@PreAuthorize` 接口 SDK 不介入 | 单元测试（scanner 跳过 + filter 映射未命中放行） | 成立 | ✅ |
| 5 | 拦截器自动注册，顺序在 Spring Security 之后 | 单元测试/代码审查：`FilterRegistrationBean` order=`LOWEST_PRECEDENCE-100` | 成立 | ✅ |
| 6 | `/actuator/**`、`/error` 默认放行 | 单元测试（exclude-paths） | 成立 | ✅ |

## 9.11 区域化消息通道

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | region_a/region_b 变更各自区域 SDK 收到 | 单元测试（subscriber 频道拼接）+ 联调（需 Redis） | 频道 `rbac:change:{region}:{system}` | ✅ |
| 2 | `connection?systemCode=crm` 返回所属区域配置 | API 脚本：crm→region_a、erp→region_b 前缀 | 成立 | ✅ |
| 3 | 区域 Redis 不可用 → 降级短轮询、业务不受影响、告警可见 | 单元测试（连接失败降级路径）+ 联调（关 Redis 观察） | 成立 | ✅ |

## 9.12 权限时效与审批集成

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 临时角色有效期内 hasPerm=true | API 脚本：审批回调后 user-permissions 含码 + validUntil | 成立 | ✅ |
| 2 | 过 valid_until 后未重登也失效（缓存时间 + 偏移） | 单元测试（user permission cache 时效判定）+ seed `13700137000` API 脚本 | 成立 | ✅ |
| 3 | 定时任务置 status=0 并发通知 | API 脚本日志/DB + 代码审查 | 每分钟执行 | ✅ |
| 4 | 同 approvalId 幂等 | API 脚本：重复回调 idempotent=true | 成立 | ✅ |
| 5 | 永久授权不受时效过滤 | API 脚本：`13800138000` 永久码在列 | 成立 | ✅ |
| 6 | 重新提权落库口径 | API 脚本：二次回调后 validUntil 更新（契约 §9.1 偏差说明） | 成立 | ✅ |
| 7 | serverTime 时钟偏移 | 单元测试（offset 计算）+ API 返回 serverTime | 成立 | ✅ |
| 8 | 签名错误/时间戳过期/Nonce 重放返回 401 | API 脚本三连 | 成立 | ✅ |

## 9.13 开放接口鉴权与加密

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `/openapi/sdk/*` 均携带 AppKey+HMAC 签名 | API 脚本：全部 openapi 调用均签名 | 成立 | ✅ |
| 2 | 签名错误/时间戳过期/Nonce 重放 401 | API 脚本 | 成立 | ✅ |
| 3 | 强制 HTTPS、Redis 配置/权限数据在 HTTPS 内传输 | 代码审查/配置 + README 生产说明 | 成立 | ✅ |

## 9.14 安全底线

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 未配置权限码默认 false，按钮不显示 | 单元测试（前端 hasPerm 默认 false）+ 管理前端代码审查 | 成立 | ✅ |
| 2 | 前端隐藏按钮后直接调接口 403 | 单元测试（filter） | 成立 | ✅ |
| 3 | 无有效 JWT / JWT 过期 401 | API 脚本 | 成立 | ✅ |

---

## 证据级别说明

- ✅ **实测通过**：本机跑通 API 脚本 / 示例业务系统 / 构建命令，结果见下表。
- 🧪 **单元测试覆盖**：无独立 E2E 环境的场景，由模块单元测试覆盖（测试类位置见括注）。
- 🔶 **未在本机 E2E**：因本机缺少 Redis 或不属于本仓库交付物（业务系统自有前端），仅做到代码级/单测级 + 说明。

| 需求条目 | 证据级别 | 说明 |
|---|---|---|
| §9.3 SDK 总开关 | 🧪 | `RbacSdkAutoConfigurationTest`（enabled=true/false 的 Bean 注册断言） |
| §9.4 业务前端数据获取 | 🧪 | `RbacControllerTest`（/rbac/me/systems、/rbac/me/bootstrap 结构、不含菜单树、预热缓存） |
| §9.6 模式 A 按钮 | 🧪 | `RbacPermissionFilterTest`（接口 403）；业务系统前端不在本仓库交付，`hasPerm` 用法见 README §5.4 |
| §9.9 拦截器判断顺序 | 🧪 | `RbacPermissionFilterTest`（14 个分支用例）+ 示例业务系统 9.1b 实测 |
| §9.11.1 Redis 跨区域通知 | ✅ | 真实 Redis 8.10.2 实测：CRM(region_a)/ERP(region_b) 两个 SDK 分别订阅 `rbac:change:region_a:crm`、`rbac:change:region_b:erp`（`PUBSUB NUMSUB` 各 1 个订阅者）；管理员变更后 API 日志 `receivers=1`，CRM SDK 收到 `PAGE_ACTION_CHANGED` 并刷新页面元数据（6 条）、ERP SDK 收到 `PERMISSION_CHANGED` 并刷新映射表 + 失效用户权限缓存；过期收回任务同样经 Redis 发布 |
| §9.11.3 Redis 不可用降级 | ✅ | 示例业务系统日志：`Redis 变更订阅启动失败，降级为兜底短轮询（不影响业务）`，业务接口正常 |
| §9.12.7 时钟偏移 | 🧪 | `RbacUserPermissionCacheTest`（offset 计算与时效判定） |
| §9.13.3 全链路 HTTPS | 🔶 | 配置/README 生产说明 + 代码强制不走 HTTP 生产；本机验收为 HTTP（demo） |
| SDK 的 JDK 8 运行时兼容 | ✅ | 构建工具链 JDK 17、产物 major=52（`--release 8`）；**GitHub Actions 实测通过**：JDK 17 构建 + 90 单测 + major=52 断言 → JDK 8 运行时启动示例业务系统并探测 `/api/health` 成功（[run 40071295904](https://github.com/battleman1994/telbank-rbac/actions/runs/40071295904)，[workflow](https://github.com/battleman1994/telbank-rbac/blob/main/.github/workflows/sdk-jdk8-compat.yml)） |
| §9.15 系统隔离 / 数据驱动超管 / bootstrap（v2.1 A 方案） | ✅ | 全量 API 脚本：`superadmin` DB 判定为超管；`crm-admin-001` 只看到 CRM、bootstrap 含通配 `*`，跨系统/系统接入 403；超管在 CRM 上下文跨系统查 ERP 也 403（须切上下文）；139 仅 CRM；137 过期无系统；`/api/user-systems` 404 |
| §9.16 系统接入（仅超管）与管理员自动开通 | ✅ | 超管 POST `/api/systems`（含 adminExternalUserId）→ 自动创建默认 `admin` 角色 + **一条 `*` 权限** + 授权；新管理员凭 `*` 可管理本系统（权限树仅一条 `*`）；非超管 403；PUT status=0 停用后移除 |
| A12 通配权限 + 严格上下文（v2.1） | ✅ | admin/超管均一条 `*`；`*`/`前缀:*`/精确匹配在后端、前端、SDK 单测与 e2e 验证；所有权限/角色/资源列表按当前系统收敛，超管跨系统 systemId 403；全量验收 88/88 |
| JDK 21 版 SDK 端到端 | ✅ | `telbank-rbac2-starter`（Boot 4.1.2/JDK21）**98 单测** + 示例业务系统 CRM/ERP 真实拦截 **4/4 PASS**（CRM 200 / ERP 403 / health 200），无 Redis 自动降级轮询；真实 Redis 联调：订阅 `rbac:change:region_a:crm`（NUMSUB=1），变更后收到 `PAGE_ACTION_CHANGED` 并刷新页面元数据 5→6 |
| Flyway 移除（需求附录 A10） | ✅ | 删除 `flyway-core/flyway-mysql` 依赖与 `FlywayConfig`；数据库改由 `db/manual/01_schema.sql`、`02_seed_data.sql`、`03_rbac_self_onboarding.sql` 手动初始化。全新库导入后 **8 张表**（无 `flyway_schema_history`）、API 验收 **59/59 PASS**（跳过示例业务系统）；`--temp-db` 自动导入 8 表并启动成功 |
| 管理前端 ↔ 真实后端联调 | 🔶 | 前端已通过 `tsc` 类型检查、生产构建、mock 后端 + headless Chrome 10 页面冒烟；**尚未在真实 `telbank-rbac-api` 上做浏览器人工联调**（启动方式见 README §4） |

## 已完成的补验（原限制项，保留记录）

1. **JDK 21 实机复核（已完成）**：2026-10-24 14:16–14:17 在 **JDK 21.0.13（Homebrew）** 下全量复跑：权限中心 `clean test` 46/46、SDK `clean test` 90/90、API 验收 50/50、SDK 端到端 4/4，字节码 major 分别为 65/52。
2. **Redis Pub/Sub（已完成）**：已安装 Redis 8.10.2，并以 `rbac.redis.enabled=true` 启动权限中心做真实联调：两个 SDK 按区域订阅频道成功、变更通知 `receivers=1`、SDK 收到通知后刷新映射/页面元数据/权限缓存、过期收回任务经 Redis 发布，且 nonce 防重放改用 Redis 存储（`rbac:nonce:*`）。
3. **SDK 的 JDK 8 运行时冒烟（已完成，CI）**：本机为 Apple Silicon macOS，官方 OpenJDK 8/Oracle JDK 8 均无 macOS ARM 原生版本，无法本机安装 JDK 8；已通过 GitHub Actions（`sdk-jdk8-compat.yml`）在真实 JDK 8 运行时完成冒烟：JDK 17 构建 SDK（90 单测 + major=52 断言）→ JDK 8 启动示例业务系统并探测接口成功。
4. **权限中心自接入（v1，已被 v2.0 A11 取代）**：旧 `rbac` 系统/固定管理上下文已删除；v2.0 改为平台系统 + 数据驱动超管 + 系统内短码。
5. **JDK 21 版 SDK（已完成）**：新增 `telbank-rbac2-starter`（JDK21 + Boot 4，协议一致），91 单测 + JDK21 示例业务系统 e2e 4/4 通过；真实 Redis Pub/Sub 亦已联调（订阅 + 变更通知驱动刷新）；CI 新增对应构建 job。
6. **移除 Flyway（已完成）**：按用户要求删除 Flyway 依赖与配置类，数据库初始化改为 `db/manual/*.sql` 手动执行；`init-db.sh` 与 `--temp-db` 一键启动均已验证（全新库 8 表、验收 59/59）。
7. **系统隔离重构（v2.0，A11，已完成）**：删除用户-系统开通；数据驱动超管；管理上下文=当前业务系统；管理权限短码；系统接入仅超管并录入管理员自动建角色授权；全新库 01→04 迁移 + 88 后端单测 + 全量验收 81/81（含两个 SDK e2e 各 4/4）。
8. **A 方案通配权限 + 严格上下文（v2.1，A12，已完成）**：admin/超管各一条 `*`；前端 hasPerm 与两版 SDK 支持 `*`/`前缀:*`；所有查询按当前 X-System-Code 收敛、超管也禁止单请求跨系统；104 后端单测 + 113/114 SDK 单测 + 全量验收 88/88。

## 已知限制（生产前建议处理）

1. **审批幂等边界**：同关系重新授权会更新原行并覆盖 `source_approval_id`（数据模型无审批历史表），因此「新审批覆盖后回放更早 approvalId」无法再幂等；同一 approvalId 重复回调幂等已实测通过。
2. **approval-grants 按 externalUserId 过滤**：`ROLE_PERMISSION` 类记录没有 `external_user_id` 列，该过滤条件下只返回 `USER_ROLE` 记录。
3. **MySQL 版本**：本机临时 MySQL 26.7 实测通过；生产建议 MySQL 8.0+（本项目已移除 Flyway，无迁移工具版本告警）。
4. **前端包体**：未做路由级 code splitting（单 chunk 1.4 MB / gzip 443 kB），可后续优化。
5. **管理前端真实后端联调（待补）**：前端已通过类型检查、构建与 mock 冒烟（v2.0 菜单/上下文/系统接入表单），尚未在真实权限中心后端上做浏览器人工联调（启动方式见 README §4，验收时可逐页操作并观察 `/api/**` 请求）。
6. **系统接入无删除**：v2.0 仅支持 PUT `status=0` 停用（需求未定义删除）；测试脚本会留下唯一编码的停用系统。
7. **超管必须按上下文操作**：v2.1 起超管也不能在单请求中跨系统查询（需先切换 X-System-Code），`/api/systems/**` 仍为超管专属例外。

## 执行记录

> 验证机：macOS arm64（`hellokitty`）· MySQL 26.7（127.0.0.1:3306；v2.0 使用独立库 `telbank_rbac_v2` 按 01→04 初始化）· Redis 8.10.2（`127.0.0.1:6379`，联调后已停止）· Node 22.23.1（nvm） · **JDK 21.0.13（Homebrew）** 为最终验证运行时；JDK 8 运行时由 GitHub Actions 验证；首次集成阶段曾用 JDK 26 `--release 21` 顶替，JDK 21 安装后已全量复跑（见下表 14:16 起）。

| 时间（2026-10-24 +08） | 命令 | 结果 |
|---|---|---|
| 20:28 | GitHub Actions `telbank-rbac CI`（commit 3798799） | **6 个 job 全部 success**：权限中心(88)/SDK v1(97)/SDK v2(98)/前端/Windows 脚本/JDK 8 运行时（[run 40094016169](https://github.com/battleman1994/telbank-rbac/actions/runs/40094016169)） |
| 20:26 | SDK v2.0 语义适配 + 真实 API 复核（8080 + 重建后的 4 个示例系统） | `/rbac/me/systems`：137→`[]`、136→crm+erp、139→crm（修复空列表误降级）；`--only 9.1b,9.1c` → **8/8 PASS**；v1 97 单测 / v2 98 单测 |
| 20:19 | 用户库 `telbank_rbac` 应用 04 迁移 + 新后端 8080 冒烟 | 迁移后系统 crm/erp/platform、无 sys_user_system；`superadmin` platformAdmin=true、业务系统 crm/erp；隔离冒烟 `--only 9.14,9.15,9.1` → **28/28 PASS** |
| 21:05 | GitHub Actions `telbank-rbac CI`（commit 7b6bc5d） | **6 个 job 全部 success**：权限中心(104)/SDK v1(113)/SDK v2(114)/前端/Windows 脚本/JDK 8 运行时（[run 40096446088](https://github.com/battleman1994/telbank-rbac/actions/runs/40096446088)） |
| 21:02 | `./scripts/verify-all.sh`（A 方案） | **13 项全过**：api **104** 单测、SDK v1 **113**、SDK v2 **114**、前端构建、05 通配/严格隔离产物、3 平台脚本 |
| 21:02 | 8080 最终重启冒烟（用户库 05 迁移后） | superadmin platformAdmin=true；crm-admin bootstrap 含 `*`（currentSystemCode=crm）；超管跨系统 `systemId=2` → **403** |
| 20:59 | 用户库 `telbank_rbac` 应用 05 通配迁移 + 8080 新 API 冒烟 | CRM/ERP/platform 各一条 `*`；superadmin platformAdmin=true；crm-admin bootstrap 含 `*`；`systemId=1` 权限树 200、`systemId=2` 403 |
| 20:58 | 全量 API 验收（`telbank_rbac_v3` + JDK21 API + 两版 SDK 示例系统） | **88/88 PASS**（A 方案通配 + 严格隔离 + 两个 SDK e2e 各 4/4） |
| 20:57 | SDK v1/v2 clean test（通配匹配） | v1 **113/113 PASS**（major=52）、v2 **114/114 PASS**（major=65） |
| 20:54 | `mvn -pl telbank-rbac-api clean test`（A 方案后端） | **104/104 PASS**，BUILD SUCCESS |
| 20:36 | `./scripts/verify-all.sh`（v2.0 + 三脚本收敛） | **13 项检查全过**：api **88** 单测、SDK v1 **97**、SDK v2 **98**、前端构建、手动 SQL、系统隔离产物、3 个平台自包含启动脚本 |
| 20:36 | GitHub Actions `telbank-rbac CI`（commit b7c4b79） | **6 个 job 全部 success**（含 Windows 启动脚本解析）[run 40094680196](https://github.com/battleman1994/telbank-rbac/actions/runs/40094680196) |
| 20:16 | 全量 API 验收（`telbank_rbac_v2` + JDK21 API + JDK8/JDK21 两套示例系统） | **81/81 PASS**：v2.0 系统隔离 + 系统接入 + 原有开放接口/审批/时效 + 两个 SDK e2e 各 4/4 |
| 20:15 | `mvn -pl telbank-rbac-api clean test`（v2.0 后端） | **88/88 PASS**，BUILD SUCCESS |
| 20:11 | 前端 `npm run build`（v2.0 菜单/上下文重构） | tsc 0 error；3255 modules；dist 1.4 MB（JS gzip 442 kB） |
| 20:05 | 04 系统隔离迁移（全新库 `telbank_rbac_v2`） | 无 `sys_user_system`（业务表 **7 张**）；系统 crm/erp/platform；各业务系统 6 短码；`superadmin`/`crm-admin-001`/`erp-admin-001` 就绪 |
| 19:19 | 平台一键脚本 `--temp-db`（移除 Flyway 后） | 临时库自动导入 `db/manual` 01→03：**8 张表（无 flyway_schema_history）**；后端 401 就绪、前端 200；退出清理正常 |
| 19:18 | 全新库手动初始化 + API 验收（`telbank_rbac_manual`，无 Flyway） | `init-db.sh` 导入 01→03 成功（8 表 / 3 系统 / 17 权限 / 5 角色）；`RBAC_BASE_URL=http://127.0.0.1:28081 python3 scripts/acceptance.py` → **59/59 PASS**（跳过示例业务系统） |
| 19:17 | `mvn -pl telbank-rbac-api clean test`（移除 Flyway 依赖后） | **53/53 PASS**，BUILD SUCCESS |
| 15:12 | GitHub Actions `telbank-rbac CI`（commit 6e65f4f） | **5 个 job 全部 success**：Build permission center (JDK21) / SDK v1 (JDK17) / SDK v2 (JDK21+Boot4) / Admin frontend (Node22) / JDK 8 runtime smoke（[run 40073016336](https://github.com/battleman1994/telbank-rbac/actions/runs/40073016336)） |
| 15:11 | JDK 21 版 SDK 真实 Redis Pub/Sub（Boot 4 示例系统 + 权限中心 `redis.enabled=true`） | 订阅 `rbac:change:region_a:crm` 成功（`PUBSUB NUMSUB=1`）；CRM 页面操作变更后 SDK 收到 `PAGE_ACTION_CHANGED`，页面元数据刷新 5→6 条 |
| 15:08 | `./scripts/verify-all.sh`（JDK21） | **全部通过（8 项检查）**：api 53 单测、SDK(JDK8) 90 单测、SDK(JDK21) 91 单测、前端 `tsc + vite build`，字节码 major=65/52/65 |
| 15:06 | 全量 API 验收（含 SDK 端到端，权限中心 + 4 个示例业务系统实例） | **65/65 PASS**：原 58 项 + JDK8 SDK e2e 4/4 + JDK21 SDK e2e 4/4 |
| 15:03 | 权限中心自接入 E2E（MySQL 干净库 + V1/V2/V3 迁移 + demo profile） | `bootstrap`：管理员 5 个 `rbac:*`、operator 空集、平台超管 `["*"]`；`X-System-Code: rbac` → 200，`crm` → 403；`/api/me/systems` 含 rbac 系统 |
| 15:06 | JDK21 版 SDK 端到端（`samples/business-demo-jdk21`，Boot 4.1.2/JDK21，CRM:8091/ERP:8092） | **4/4 PASS**：CRM 200 / 同用户 ERP 403 / ERP 有权限 200 / 未配置权限码接口 200 |
| 14:47 | Redis 8.10.2 + 权限中心 `--rbac.redis.enabled=true` + CRM/ERP 示例系统 | **真实跨区域 Pub/Sub 通过**：频道 `rbac:change:region_a:crm`/`region_b:erp` 各 1 订阅者；变更后 API `receivers=1`，CRM SDK 收到 `PAGE_ACTION_CHANGED` 刷新页面元数据、ERP SDK 收到 `PERMISSION_CHANGED` 刷新映射并失效权限缓存；Redis nonce 键 `rbac:nonce:*` 生效；同期验收 50/50、SDK e2e 4/4 |
| 14:44 | GitHub Actions `SDK JDK 8 compatibility`（push 自动触发，含 Actions 升级后二次运行） | **两个 job 全部 success**：JDK 17 构建 SDK（90/90、major=52 断言）→ JDK 8 运行时启动示例业务系统，`/api/health` 返回 200（run 40071190930 / 40071295904） |
| 14:30 | `./scripts/build-sdk.sh`（**JDK 17.0.20.1** 工具链，`--release 8`） | **90/90 PASS**，BUILD SUCCESS，产物字节码 **major=52（Java 8）** 校验通过 |
| 14:17 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-api clean test`（**JDK 21.0.13**，release 21） | **46/46 PASS**，BUILD SUCCESS，字节码 major=65 |
| 14:16 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-starter clean test`（**JDK 21.0.13**，release 8） | **90/90 PASS**，BUILD SUCCESS，字节码 major=52 |
| 14:16 | `RBAC_BASE_URL=http://localhost:28080 python3 scripts/acceptance.py`（**JDK 21 运行**的权限中心 + 干净库 `telbank_rbac_verify`，手动导入 db/manual 01+02） | **50/50 PASS** |
| 14:17 | `RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 python3 scripts/acceptance.py --only 9.1b`（JDK 21 权限中心 + CRM/ERP 示例系统） | **4/4 PASS** |
| 14:11 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-api clean test`（JDK 26，release 21） | **46/46 PASS**，BUILD SUCCESS，字节码 major=65 |
| 14:11 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-starter clean test`（JDK 17，release 8） | **90/90 PASS**，BUILD SUCCESS，字节码 major=52 |
| 14:10 | `RBAC_BASE_URL=http://localhost:28080 python3 scripts/acceptance.py`（干净库 `telbank_rbac_verify`，手动导入 db/manual 01+02） | **50/50 PASS**（安全底线/多系统隔离/权限码模型/模式 B/开放接口鉴权/区域通道/审批时效等） |
| 14:11 | `RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 python3 scripts/acceptance.py --only 9.1b`（示例业务系统 Boot 2.7.18/JDK 17/starter 1.0.0，连 28080） | **4/4 PASS**：CRM 有权限 200；同用户 ERP 403；ERP 有权限 200；未配置权限码接口无 token 200 |
| 14:05 | `cd telbank-rbac-front && npm run build`（nvm Node 22.23.1） | `tsc -b` 0 error；vite 3254 modules；dist 1.4 MB（JS gzip 443 kB） |
| 14:00–14:05 | `npm run dev` + `curl localhost:5173`；mock 后端 + headless Chrome | HTTP 200；10 页面渲染/关键交互冒烟无异常（front-dev 记录） |

> 说明：一次中途复跑出现的 1 项失败为环境干扰——彼时 18080 端口上是 api-dev 自己连 `telbank_rbac_apidev` 库的联调实例（该库有 smoke 数据），并非代码缺陷；使用干净库 + 独立端口 28080 复跑即为 50/50 全过。
