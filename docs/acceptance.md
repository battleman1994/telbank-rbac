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

## 9.17 业务用户临时权限申请（A21：按角色时间模板计算窗口）

> A21 起：申请/审批不再由外部审批平台回传统一窗口，窗口由**每个角色绑定的时间模板**在审批通过时计算（多角色可不同，申请级为包络）；角色必须「绑定启用模板」才能被自助申请。验证脚本 `python3 scripts/acceptance.py --only 9.17`。

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 业务用户无管理权限也能看到「我的申请」并进入 | 前端代码审查 + headless 联调（13600136000） | 菜单仅「我的申请」，首页重定向到 `/access-applications`，不再出现「无管理权限」整页空态 | ✅ headless 联调（业务用户，无管理权限） |
| 2 | 可申请角色只含当前系统非 admin、且已绑定启用模板的角色；附权限预览与模板摘要 | API 脚本 §9.17 `options` | 角色项含 `timeTemplateId/timeTemplateName/timeTemplateText`；无模板角色不出现 | ✅ |
| 3 | 创建申请：PENDING、单号/时间/审批链接；重复提交 409 | API 脚本 §9.17 | 申请单号 `RA-...`，`createdAt` 非空；同一用户同系统最多一条 PENDING | ✅ |
| 4 | 申请 admin 角色 / 无模板角色被拒绝 | API 脚本 §9.17 | 400 | ✅ |
| 5 | 审批通过：不再传 `validFrom/validUntil`，服务端按各角色模板写 `sys_user_role`，申请单窗口为包络 | API 脚本 §9.17 + bootstrap 复验 | 逐角色 `approvedValidFrom/Until` 有效；新权限出现；`grantedRoleIds` 与申请一致 | ✅ |
| 6 | 多角色不同模板 → 逐角色窗口不同，申请级窗口=最早开始/最晚结束 | API 脚本 §9.17 | 窗口差异成立、包络正确 | ✅ |
| 7 | 审批回调错误 AppKey / 重复审批 | API 脚本 §9.17 | 错误 401；重复 `idempotent=true` 且窗口不回滚 | ✅ |
| 8 | 驳回：REJECTED 且不授权；驳回后再通过 409 | API 脚本 §9.17 | 权限集不含申请角色权限；状态冲突 409 | ✅ |
| 9 | 跨系统查询本人申请单 | API 脚本 §9.17 | ERP 上下文查 CRM 申请单 404 | ✅ |
| 10 | 角色-权限静态永久（无逐项时效）；非 admin 手工授 `*` 仍拒绝 | API 脚本 §9.2/§9.17 + 后端单测 | 授权记录无 `validFrom/validUntil`；`grant` 传 `*` 400 | ✅ |
| 11 | 模拟外部审批脚本（不再传有效期） | `scripts/mock-access-approval.py list/show/approve/reject` | 无审批平台环境可完成通过/驳回闭环 | ✅ `list` 实读 4 张申请单；脚本参数自检通过；approve/reject HTTP 决策路径由 API 验收覆盖 |

## 9.18 角色时间模板（A21）

> 模板为全局配置，平台超管维护（系统管理员即使拥有 `*` 也只能读）；角色绑定模板后，分配/审批按模板计算绝对窗口。验证脚本 `python3 scripts/acceptance.py --only 9.18`。

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 系统管理员可读模板列表，预置「夜间窗口」「全天窗口」 | API 脚本 §9.18 | 200，列表含两个预置模板 | ✅ |
| 2 | 非平台超管创建/修改/停用模板 | API 脚本 §9.18 | 403（不受 `*` 影响） | ✅ |
| 3 | 平台超管模板 CRUD + 参数校验 | API 脚本 §9.18 | 非法时区/结束早于开始/重名 → 400；DELETE=逻辑停用且 `status=0` 可查 | ✅ |
| 4 | 角色创建/更新绑定模板、改绑、解绑 | API 脚本 §9.18 | `RoleVO.timeTemplateId/Name` 正确；解绑=永久角色 | ✅ |
| 5 | admin 角色绑定时间模板 | API 脚本 §9.18 | 400 | ✅ |
| 6 | 管理员直接分配用户角色 → 按模板写窗口；无模板角色 → 永久 | API 脚本 §9.18 | `UserRoleVO.validFrom/validUntil` 有效/为空 | ✅ |
| 7 | 可申请角色过滤：只含绑定启用模板的角色；申请无模板角色 400 | API 脚本 §9.18 | 成立 | ✅ |
| 8 | 模板停用后：新分配 409；已提交申请审批通过 409 | API 脚本 §9.18 | 409，不写入授权 | ✅ |

---

## 证据级别说明

- ✅ **实测通过**：本机跑通 API 脚本 / 示例业务系统 / 构建命令，结果见下表。
- 🧪 **单元测试覆盖**：无独立 E2E 环境的场景，由模块单元测试覆盖（测试类位置见括注）。
- 🔶 **未在本机 E2E**：因本机缺少 Redis 或不属于本仓库交付物（业务系统自有前端），仅做到代码级/单测级 + 说明。
- A21 已执行完毕；后续新增验收项仍以 `⏳` 表示待执行。

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
| A19 表格内容优先展示（v2.6） | ✅ | 表格 auto 布局；宽度足够不换行、不够换行完整展示，不使用省略号；时间列短格式；headless 1440×900 验证所有页面表格无横向滚动、字段完整展示 |
| A20 业务用户临时权限申请（v2.7） | ✅ | 新增 `sys_access_application` / `sys_access_application_role`（迁移 10）；自助申请 API 不要求管理权限；审批回调按申请单行锁、多角色事务、同窗口幂等；`13600136000` 申请→通过→权限出现→驳回→跨系统隔离闭环 22/22；前端「我的申请」入口/角色预览/审批外链；角色授权树过滤 `*`，后端拒绝非 admin 通配授权 |
| A21 角色时间模板（v3.0） | ✅ | 新增全局 `sys_time_template`（迁移 11，预置夜间/全天模板）、`sys_role.time_template_id`；审批/直接分配按模板计算绝对窗口（多角色可不同，申请级包络）；角色-权限改为永久（删逐项时效）；Python 验收 §9.17 35 项 + §9.18 24 项全过；后端 219 单测；verify-all 17 项；headless Chrome 验证时间模板页（仅平台超管）、角色权限页去逐项时效、用户角色/我的申请模板窗口展示 |
| A18 凭据查看 + admin 授权保护（v2.6） | ✅ | 「接入凭据」页（system:credential）系统管理员可看本系统完整 AppKey/AppSecret、默认隐藏；平台超管可查看任意系统；角色授权排除 admin，后端 grant/revoke 400；验收 7 项 + 单测 151 |
| A17 系统接入凭据（v2.5） | ✅ | `sys_system` 增加 appKey/appSecret（09 回填 crm/erp）；手动/审核创建均分配 appKey=code + 32 位 secret；列表返回 AppKey/掩码、创建/审批响应一次性返回完整 secret；开放接口 DB 凭据优先仍可正常验签 | 
| A16 系统接入双通道 + 审核（v2.5） | ✅ | 手动录入保留；SDK 上报 `POST /openapi/onboarding/systems`（onboarding AppKey/HMAC）→ PENDING 申请；超管 GET/approve/reject；approve 自动创建系统/`admin` 角色/`*`/管理员授权并返回凭据；单 system_code 最新状态、驳回可重报；真实 SDK 启动上报验证通过；全量验收 101/101 |
| A15 页面元素管理简化（v2.4） | ✅ | 页面元素只展示 页面编码/元素标识/元素名称/语义类型/状态/操作；新建/编辑表单仅 4 个字段；`07` 将历史 type 归一（button/link）；header/headless 验证无 action/config/sort 字段；API/SDK 字段保持兼容 |
| A14 前端菜单/布局优化（v2.3） | ✅ | 「到期提醒」菜单/路由/页面已移除（`/api/expiring-grants` 后端接口保留）；管理台布局占满 100% 视口、内容区独立滚动；headless Chrome 1440×900/1366×768 验证所有页面无外层滚动条、表格无横向滚动条 |
| A13 持久层 MyBatis-Plus（v2.2） | ✅ | JPA/Hibernate 依赖、实体注解、Repository、分页全部移除；112 单测（含 MapperContractTest）+ 真库 v4 全量验收 88/88；源码/依赖树无 JPA 残留 |
| A12 通配权限 + 严格上下文（v2.1） | ✅ | admin/超管均一条 `*`（`06` 清理后 admin 角色授权记录 = 1 条 `*`；公共权限 `common:profile` 除外）；`*`/`前缀:*`/精确匹配在后端、前端、SDK 单测与 e2e 验证；所有权限/角色/资源列表按当前系统收敛，超管跨系统 systemId 403；全量验收 88/88 |
| JDK 21 版 SDK 端到端 | ✅ | `telbank-rbac2-starter`（Boot 4.1.2/JDK21）**98 单测** + 示例业务系统 CRM/ERP 真实拦截 **4/4 PASS**（CRM 200 / ERP 403 / health 200），无 Redis 自动降级轮询；真实 Redis 联调：订阅 `rbac:change:region_a:crm`（NUMSUB=1），变更后收到 `PAGE_ACTION_CHANGED` 并刷新页面元数据 5→6 |
| Flyway 移除（需求附录 A10） | ✅ | 删除 `flyway-core/flyway-mysql` 依赖与 `FlywayConfig`；数据库改由 `db/manual/01_schema.sql`、`02_seed_data.sql`、`03_rbac_self_onboarding.sql` 手动初始化。全新库导入后 **8 张表**（无 `flyway_schema_history`）、API 验收 **59/59 PASS**（跳过示例业务系统）；`--temp-db` 自动导入 8 表并启动成功 |
| 管理前端 ↔ 真实后端联调 | ✅ | 前端已通过 `tsc` 类型检查与生产构建；headless Chrome 在真实 `telbank-rbac-api` 上完成多轮联调（A19/A20/A21），覆盖角色权限、时间模板、用户角色、我的申请等关键页面；未做人工视觉走查 |

## 已完成的补验（原限制项，保留记录）

1. **JDK 21 实机复核（已完成）**：2026-10-24 14:16–14:17 在 **JDK 21.0.13（Homebrew）** 下全量复跑：权限中心 `clean test` 46/46、SDK `clean test` 90/90、API 验收 50/50、SDK 端到端 4/4，字节码 major 分别为 65/52。
2. **Redis Pub/Sub（已完成）**：已安装 Redis 8.10.2，并以 `rbac.redis.enabled=true` 启动权限中心做真实联调：两个 SDK 按区域订阅频道成功、变更通知 `receivers=1`、SDK 收到通知后刷新映射/页面元数据/权限缓存、过期收回任务经 Redis 发布，且 nonce 防重放改用 Redis 存储（`rbac:nonce:*`）。
3. **SDK 的 JDK 8 运行时冒烟（已完成，CI）**：本机为 Apple Silicon macOS，官方 OpenJDK 8/Oracle JDK 8 均无 macOS ARM 原生版本，无法本机安装 JDK 8；已通过 GitHub Actions（`sdk-jdk8-compat.yml`）在真实 JDK 8 运行时完成冒烟：JDK 17 构建 SDK（90 单测 + major=52 断言）→ JDK 8 启动示例业务系统并探测接口成功。
4. **权限中心自接入（v1，已被 v2.0 A11 取代）**：旧 `rbac` 系统/固定管理上下文已删除；v2.0 改为平台系统 + 数据驱动超管 + 系统内短码。
5. **JDK 21 版 SDK（已完成）**：新增 `telbank-rbac2-starter`（JDK21 + Boot 4，协议一致），91 单测 + JDK21 示例业务系统 e2e 4/4 通过；真实 Redis Pub/Sub 亦已联调（订阅 + 变更通知驱动刷新）；CI 新增对应构建 job。
6. **移除 Flyway（已完成）**：按用户要求删除 Flyway 依赖与配置类，数据库初始化改为 `db/manual/*.sql` 手动执行；`init-db.sh` 与 `--temp-db` 一键启动均已验证（全新库 8 表、验收 59/59）。
7. **系统隔离重构（v2.0，A11，已完成）**：删除用户-系统开通；数据驱动超管；管理上下文=当前业务系统；管理权限短码；系统接入仅超管并录入管理员自动建角色授权；全新库 01→04 迁移 + 88 后端单测 + 全量验收 81/81（含两个 SDK e2e 各 4/4）。
9. **系统接入双通道 + 系统凭据（v2.5，A16/A17，已完成）**：手动录入 + SDK onboarding 上报（onboarding AppKey/HMAC）；单申请单最新状态；审核通过自动建系统并分配 appKey/appSecret；页面展示 AppKey/掩码与一次性完整 secret；138 后端单测 + 121/122 SDK 单测 + 全量验收 101/101；真实 SDK 上报验证通过。
8. **A 方案通配权限 + 严格上下文（v2.1，A12，已完成）**：admin/超管各一条 `*`；前端 hasPerm 与两版 SDK 支持 `*`/`前缀:*`；所有查询按当前 X-System-Code 收敛、超管也禁止单请求跨系统；104 后端单测 + 113/114 SDK 单测 + 全量验收 88/88。

## 已知限制（生产前建议处理）

1. **审批幂等边界**：同关系重新授权会更新原行并覆盖 `source_approval_id`（数据模型无审批历史表），因此「新审批覆盖后回放更早 approvalId」无法再幂等；同一 approvalId 重复回调幂等已实测通过。
2. **approval-grants 按 externalUserId 过滤**：`ROLE_PERMISSION` 类记录没有 `external_user_id` 列，该过滤条件下只返回 `USER_ROLE` 记录。
3. **MySQL 版本**：本机临时 MySQL 26.7 实测通过；生产建议 MySQL 8.0+（本项目已移除 Flyway，无迁移工具版本告警）。
4. **前端包体**：未做路由级 code splitting（单 chunk 1.4 MB / gzip 443 kB），可后续优化。
5. **管理前端真实后端联调**：已用 headless Chrome 在真实权限中心后端（API 28090 + 前端 5181/5173）完成多轮联调，覆盖 A19/A20/A21 页面与角色权限/时间模板/我的申请关键路径；未做人工视觉走查。
6. **系统接入无删除**：v2.0 仅支持 PUT `status=0` 停用（需求未定义删除）；测试脚本会留下唯一编码的停用系统。
7. **超管必须按上下文操作**：v2.1 起超管也不能在单请求中跨系统查询（需先切换 X-System-Code），`/api/systems/**` 仍为超管专属例外。
8. **审批平台无拉取申请单的开放 API**：A20 只提供权限中心 → 外部审批平台的决策回调；`scripts/mock-access-approval.py list/show` 直接读本地 demo 库模拟待办箱，生产需由外部审批平台自行保存申请单号/链接。
9. **审批窗口由角色时间模板决定（A21）**：申请/审批不再接收 `validFrom/validUntil`；审批通过时按每个角色绑定的模板计算绝对窗口（多角色可不同，申请单级取最早开始/最晚结束），角色停用/模板过期 → 409；已生效窗口不随模板后续修改或停用回滚。角色-权限不再有逐项时效（迁移 11 删除相关列）。

## 执行记录

> **A21 已执行**：全新临时库（01→11）上完成 §9.17（35 项）+ §9.18（24 项），全量验收 159/159 PASS；`verify-all.sh` 17 项全过。A21 页面已做 headless Chrome 联调（时间模板页、角色管理/角色权限/用户角色、业务用户「我的申请」）。

> 验证机：macOS arm64（`hellokitty`）· MySQL 26.7（127.0.0.1:3306；v2.0 使用独立库 `telbank_rbac_v2` 按 01→04 初始化）· Redis 8.10.2（`127.0.0.1:6379`，联调后已停止）· Node 22.23.1（nvm） · **JDK 21.0.13（Homebrew）** 为最终验证运行时；JDK 8 运行时由 GitHub Actions 验证；首次集成阶段曾用 JDK 26 `--release 21` 顶替，JDK 21 安装后已全量复跑（见下表 14:16 起）。

| 时间（2026-10-24 +08） | 命令 | 结果 |
|---|---|---|
| 2026-10-25 16:27 | `./scripts/verify-all.sh`（A21 后） | **17 项检查全过**：api **219** 单测、SDK v1 **121**、SDK v2 **122**、前端构建、MyBatis-Plus/无 JPA、08/09/10/11 迁移、凭据/admin 保护、A20 申请产物、A21 时间模板产物、3 平台脚本 |
| 2026-10-25 16:26 | 前端 `npm run build`（A21 页面与服务端窗口展示） | `tsc -b` 0 error；3261 modules；JS 1,469.08 kB / gzip 462.50 kB |
| 2026-10-25 16:20 | `python3 scripts/acceptance.py --base-url http://localhost:28090`（临时 MySQL 3308 + 全新库 01→11 + API 28090 + 前端 5181） | **159/159 PASS，0 FAIL**：含 A21 §9.17 **35/35**、§9.18 **24/24**；多角色模板窗口/包络、模板停用 409、角色权限静态化、admin 绑定模板 400 全过 |
| 2026-10-25 16:17 | headless Chrome（1440×900，真实 A21 后端） | 平台超管见「时间模板」页并可查看预置夜间/全天模板；crm-admin 角色权限页无逐项时效、用户角色页无日期选择；业务用户「我的申请」仅见该入口、表单展示角色模板摘要；角色管理表格内容完整无省略 |
| 2026-10-25 16:59 | 表格自适应 UI 审计（headless Chrome 1440×900 / 900×900，真实 A21 后端） | 覆盖角色管理、权限管理、页面元素、资源管理、授权记录、用户角色、系统接入（业务系统/接入申请）、时间模板、我的申请共 10 个表格；全部 `table-layout:auto`、`scrollWidth === clientWidth`、无横向裁切；1440 宽度按内容展开且不换行，900 宽度由内容压缩并换行完整展示；窄屏不启用 fixed 等宽列 |
| 2026-10-25 17:03 | 侧栏折叠控件 UI 验证（headless Chrome 1440×900） | 收起按钮位于标题/菜单分割线同一行右侧（`x=189/231`，12px 右边距），非默认底部 trigger；展开 232px、折叠 80px 切换正常，折叠后保留 logo 与展开按钮、隐藏标题；自定义双箭头图标 + Tooltip/aria-label 均生效 |
| 2026-10-25 17:40 | 授权记录时间列修复（headless Chrome 1440×900，真实 A21 后端） | 「授权时间」由截断到分钟改为完整 `YYYY-MM-DD HH:mm:ss`；授权时效开始/结束两行不拆行；8 个表头 47px 单行，`scrollWidth === clientWidth === 1110`；前端构建 3262 modules，JS 1,469.77 kB / gzip 462.64 kB，CSS 5.80 kB / gzip 1.80 kB |
| 2026-10-25 17:30 | 时间列到秒与折叠按钮收敛 UI 验证（headless Chrome 1440×900，真实 A21 后端） | 申请时间/授权有效期开始结束均显示 `YYYY-MM-DD HH:mm:ss`；生效行两行时间 69px、驳回行 49px，8 个表头均 47px 单行；`scrollWidth === clientWidth === 1110`；折叠按钮改为透明底、28px、`border:0`、`opacity:.7`、淡灰 `rgb(140,140,140)`，悬停才显示浅蓝底 |
| 2026-10-25 17:14 | 「我的申请」时间区间/生效状态列/紧凑行高 UI 验证（headless Chrome 1440×900，真实 A21 后端） | 「生效状态」独立成列（已通过行显示「生效中」）；授权有效期与详情抽屉改为「开始：… / 结束：…」两行；两行时间行 69px、单行 49px，表头单行 47px；整表 `scrollWidth === clientWidth`，无横向裁切；用户角色/授权记录同步使用两行时间展示 |
| 2026-10-25 17:30 | 前端 `npm run build`（时间到秒 + 紧凑行高 + 折叠按钮收敛） | `tsc -b` 0 error；**3262 modules**；JS 1,469.78 kB / gzip 462.65 kB；CSS 5.66 kB / gzip 1.77 kB |
| 2026-10-25 17:16 | 前端 `npm run build`（时间区间展示 + 紧凑表格 + 侧栏控件） | `tsc -b` 0 error；**3262 modules**；JS 1,469.90 kB / gzip 462.66 kB；CSS 5.74 kB / gzip 1.80 kB |
| 2026-10-25 13:02 | `./scripts/verify-all.sh`（A20 后） | **16 项检查全过**：api **192** 单测、SDK v1 **121**、SDK v2 **122**、前端构建、MyBatis-Plus/无 JPA、08/09/10 迁移、凭据/admin 保护、A20 申请产物、3 平台脚本 |
| 2026-10-25 12:59 | 前端 `npm run build`（A20 我的申请页/角色授权过滤） | `tsc -b` 0 error；3259 modules；JS 1,456.05 kB / gzip 459.13 kB |
| 2026-10-25 12:58 | headless Chrome（1440×900，API 28090 + 前端 5173） | 13600136000 仅见「我的申请」入口、无管理菜单，可打开新建弹窗；crm-admin-001 选择 operator 后权限预览 3 项；角色授权页 operator 授权树无 `*`、admin 不在角色下拉 |
| 2026-10-25 12:57 | `python3 scripts/acceptance.py --base-url http://localhost:28090`（临时 MySQL 3308 + 全新库 01→10 + API 28090 + 前端 5173） | **124/124 PASS，0 FAIL**：原 102 项 + A20 §9.17 **22/22**；136 申请→审批通过→bootstrap 权限出现→驳回→跨系统隔离闭环 |
| 2026-10-25 12:51 | `mvn -pl telbank-rbac-api clean test`（A20 后端） | **192/192 PASS，0 failure/0 error** |
| 20:28 | GitHub Actions `telbank-rbac CI`（commit 3798799） | **6 个 job 全部 success**：权限中心(88)/SDK v1(97)/SDK v2(98)/前端/Windows 脚本/JDK 8 运行时（[run 40094016169](https://github.com/battleman1994/telbank-rbac/actions/runs/40094016169)） |
| 20:26 | SDK v2.0 语义适配 + 真实 API 复核（8080 + 重建后的 4 个示例系统） | `/rbac/me/systems`：137→`[]`、136→crm+erp、139→crm（修复空列表误降级）；`--only 9.1b,9.1c` → **8/8 PASS**；v1 97 单测 / v2 98 单测 |
| 20:19 | 用户库 `telbank_rbac` 应用 04 迁移 + 新后端 8080 冒烟 | 迁移后系统 crm/erp/platform、无 sys_user_system；`superadmin` platformAdmin=true、业务系统 crm/erp；隔离冒烟 `--only 9.14,9.15,9.1` → **28/28 PASS** |
| 22:45 | 修复切换系统 toast 遮挡系统下拉框 | `.ant-message*` 设为 `pointer-events:none`；headless 验证切换 ERP 后系统下拉框顶部元素由 message 容器变回 input，页面下拉框可正常展开 |
| 22:36 | 资源管理「提示」列替代候选码列 | 候选码不再单独成列：未关联行提示「确认权限码后生效，候选码为：xxx」；已关联行提示「已确认权限码，已生效」；表格仍无横向滚动，`npm run build` 0 error |
| 23:50 | 角色授权交互重构 + 未生效计数强调（front build + headless） | `npm run build` 0 error；crm-admin-001 选择 operator 后「已勾选 3 项」，提交提示「授权已提交（2 组时效）」，提交前后 DB operator 授权记录一致；资源管理 Alert 数字 3 计算样式 21.6px/`rgb(212,107,8)` |
| 23:45 | `./scripts/verify-all.sh`（A18/A19 后） | **15 项全过**：api **151** 单测、SDK v1 **121**、SDK v2 **122**、前端构建、08/09 迁移、凭据/admin 保护产物、3 平台脚本 |
| 23:40 | 全量验收（v9 全新库 01→09 + 两版 SDK 示例系统） | **108/108 PASS**（新增 §9.16c 凭据/admin 7 项；SDK e2e 8/8；后端 151） |
| 23:40 | 系统凭据页 + admin 授权排除（front/API E2E） | crm-admin current 返回 crm 完整凭据、operator 403、缺头 403；superadmin by-id 跨系统 200；crm-admin by-id 403；admin grant/revoke 400；前端菜单/页面/密钥显示验证通过 |
| 23:30 | 全局表格字段不换行优化（headless Chrome 1440×900） | `.ant-table-cell` 统一 `white-space:nowrap + ellipsis`；短时间格式（YYYY-MM-DD HH:mm）；调整系统接入/资源管理/授权记录列宽；所有页面表格 `cw==sw==1110`、无 wrap、无横向滚动；长路径/长原因省略并保留 tooltip |
| 23:15 | GitHub Actions `telbank-rbac CI`（commit 440a525） | **6 个 job 全部 success**：权限中心(138)/SDK v1(121)/SDK v2(122)/前端/Windows 脚本/JDK 8 运行时（[run 40105622849](https://github.com/battleman1994/telbank-rbac/actions/runs/40105622849)） |
| 23:12 | `./scripts/verify-all.sh`（A16/A17 后） | **14 项全过**：api **138** 单测、SDK v1 **121**、SDK v2 **122**、前端构建、MyBatis-Plus/无 JPA、08/09 迁移、3 平台脚本 |
| 23:06 | 8080 切 A16/A17 后端（用户库 08/09） | 系统列表返回 appKey/掩码（crm/crm-****cret）；`/api/system-applications` 返回 finance 待审核；前端系统接入双 Tab 真实 API 验证通过 |
| 23:05 | 全量 API 验收（v7 全新库 01→09 + 两版 SDK 示例系统） | **101/101 PASS**（新增 §9.16b SDK 上报/审核 13 项；两个 SDK e2e 各 4/4） |
| 23:04 | 真实 SDK 启动上报 | v1 示例开启 onboarding → `POST /openapi/onboarding/systems` 验签成功，管理端出现 sdkdemo PENDING 申请 |
| 22:57 | `mvn -pl telbank-rbac-api clean test` / SDK clean test | 后端 **138/138**；SDK v1 **121/121**、v2 **122/122**；BUILD SUCCESS |
| 22:31 | 资源管理「生效状态」展示优化（headless Chrome） | 未关联端点显示「未生效」+「未关联」、当前页黄色提示、行轻微高亮、关联按钮为主按钮；已关联显示「已生效」；表格仍 1110px 无横向滚动；映射/接口行为未改 |
| 22:24 | 弹窗表单 UI 优化（headless Chrome 截图复核） | 角色/权限/页面元素/用户授权/系统接入弹窗统一宽度（600–720）、控件占满列宽；双列用 Row/Col 对齐；移除 textarea showCount 避免与 footer 重叠；SVG 截图人工复核通过 |
| 22:18 | 页面元素简化 + 07 类型归一（用户库/headless Chrome） | 表头 = 页面编码/元素标识/元素名称/元素类型/状态/操作；新建弹窗字段仅 4 个（无 action/config/sort）；类型显示“按钮/链接”；`npm run build` 0 error |
| 22:05 | 前端 A14 布局/菜单验证（headless Chrome 1440×900、1366×768） | 7 个页面 `doc.cw==doc.sw`、`doc.ch==doc.sh`，表格无横向溢出；菜单仅剩 7 个权限管理页面 + 系统接入；`npm run build` 0 error |
| 21:45 | `06_cleanup_admin_redundant_grants.sql`（用户库 + 全新库 v5） | admin 角色授权记录均为 **1 条 `*`**；crm-admin bootstrap 权限 = `['*','common:profile']`（后者为 `is_public=1` 公共权限）；验收冒烟 **45/45 PASS** |
| 21:24 | GitHub Actions `telbank-rbac CI`（commit e4e059e） | **6 个 job 全部 success**：权限中心(112)/SDK v1(113)/SDK v2(114)/前端/Windows 脚本/JDK 8 运行时（[run 40098002904](https://github.com/battleman1994/telbank-rbac/actions/runs/40098002904)） |
| 21:22 | `./scripts/verify-all.sh`（MyBatis-Plus 持久层） | **14 项全过**：api **112** 单测、SDK v1 113、SDK v2 114、前端构建、MP 持久层/无 JPA 检查等 |
| 21:20 | 真库 `telbank_rbac_v4`（01→05）+ 28086 API + 两版 SDK 示例系统 | `acceptance.py` **88/88 PASS**（含 SDK e2e 8/8），JPA/Hibernate 依赖/源码残留为 0 |
| 21:16 | `mvn -pl telbank-rbac-api clean test`（JPA → MyBatis-Plus） | **112/112 PASS**，BUILD SUCCESS |
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
