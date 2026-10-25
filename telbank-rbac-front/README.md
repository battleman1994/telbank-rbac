# telbank-rbac-front 权限中心管理前端

`telbank-rbac` 权限管理平台的管理端 Web 界面，对接权限中心后端 `telbank-rbac-api` 的 `/api/**` 管理接口。

- 需求基线：`docs/telbank-rbac.md` §3.3 / §5.7
- 接口契约：`docs/impl/contracts.md` §3 / §10
- 完整文档地图：[`../docs/README.md`](../docs/README.md)
- **系统隔离模型**：按「当前系统」隔离数据与权限，`X-System-Code` 恒为当前业务系统；平台超管由后端数据驱动（`platform:admin` 权限），前端只认 `/api/me` / `/api/me/bootstrap` 返回的 `platformAdmin`，不读环境变量、不硬编码账号。

## 技术栈（锁定版本）

| 组件 | 版本 |
|---|---|
| Node.js | 22.22.0+（本工程验证环境 nvm Node 22.23.1） |
| React / React DOM | 19.2.9（`~19.2.0`） |
| Vite | 8.3.5 |
| @vitejs/plugin-react | 6.1.2 |
| TypeScript | 5.9.3 |
| Ant Design | 6.6.7（仅 antd 主题） |
| React Router | 8.5.0 |
| TanStack Query | 5.104.5 |
| Zustand | 5.0.16 |
| HTTP | 原生 fetch（未使用 Axios） |

## 环境要求与启动

```bash
# Node 通过 nvm 使用（React Router 8 要求 Node 22.22+）
export NVM_DIR="$HOME/.nvm"; . "$NVM_DIR/nvm.sh"; nvm use 22

cd telbank-rbac-front
npm install            # 首次安装
npm run dev            # 开发服务器 http://localhost:5173
```

构建（包含 `tsc -b` 类型检查）：

```bash
npm run build          # tsc -b && vite build，产物输出到 dist/
npm run typecheck      # 仅类型检查
npm run preview        # 预览 dist 构建产物（4173 端口）
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | 权限中心后端地址；开发环境直接跨域请求，后端需放行 `http://localhost:5173` 与 `http://127.0.0.1:5173` |

可在 `telbank-rbac-front/.env.local` 中覆盖，例如：

```bash
VITE_API_BASE_URL=http://127.0.0.1:8080
```

> 不再需要 `VITE_MANAGEMENT_SYSTEM_CODE`：管理端没有独立「管理上下文系统」，`X-System-Code` 就是当前业务系统。

## 与权限中心后端联调

本前端默认请求 `http://localhost:8080`。本地联调步骤（详见仓库根 README）：

```bash
# 1) 启动权限中心（JDK 21+，demo profile；HS256 演示 JWT）
cd .. && ./scripts/start-all-macos.sh   # Linux 用 start-all-linux.sh

# 2) 生成演示 JWT（另开终端，演示账号如 superadmin / crm-admin-001 / erp-admin-001）
TOKEN=$(./scripts/gen-demo-jwt.sh superadmin)

# 3) 启动前端并在浏览器打开 http://localhost:5173，粘贴 TOKEN 登录
cd telbank-rbac-front && npm run dev
```

演示账号（由种子数据/后端迁移决定，前端不做特殊判断）：

| 账号 | 角色 | 说明 |
|---|---|---|
| `superadmin` | 平台超管（`platform:admin`） | 可见顶级「系统接入」；可在所有业务系统间切换，`hasPerm` 恒 true |
| `crm-admin-001` | CRM 系统管理员 | 仅可见 CRM 系统与「权限管理」菜单，无「系统接入」 |
| `erp-admin-001` | ERP 系统管理员 | 仅可见 ERP 系统与「权限管理」菜单，无「系统接入」 |
| 普通运营账号 | 无管理权限 | 无管理菜单，仅「我的申请」入口（`alwaysVisible`）；直接访问管理页面显示 403 无权限 |

## 系统隔离模型与登录上下文

1. **认证外部化**：权限中心不签发 JWT，管理员在 `/login` 粘贴外部认证系统签发的 JWT，登录态存 `localStorage["rbac.token"]`。
2. **系统由角色推导**：`GET /api/me/systems` 返回当前账号可访问的业务系统（由有效 `user_role` 推导去重；平台超管为全部启用系统；不含平台系统）。
3. **当前系统上下文**：登录/刷新时按 `/api/me` → `/api/me/systems` → 选 `currentSystemCode`（`localStorage["rbac.currentSystemCode"]` 若在列表则沿用，否则第一条）→ 带 `X-System-Code` 调 `/api/me/bootstrap`。
4. **bootstrap 契约**：

   ```json
   {
     "externalUserId": "crm-admin-001",
     "platformAdmin": false,
     "currentSystemCode": "crm",
     "permissions": ["role:manage", "permission:manage", "resource:manage", "page:manage", "user:manage", "audit:view"],
     "systems": [
       { "id": 1, "code": "crm", "name": "CRM系统", "region": "region_a", "isDefault": true }
     ]
   }
   ```

   - `platformAdmin`：后端按 `platform:admin` 权限/数据判定，前端唯一依据；超管 `permissions` 为 `["*"]`。
   - `permissions`：**当前系统内**的短码权限集合（`role:manage` / `permission:manage` / `resource:manage` / `page:manage` / `user:manage` / `audit:view`）。
5. **请求头**：所有 `/api/**` 请求注入 `Authorization: Bearer <token>` 与 `X-System-Code: <currentSystemCode>`；`/api/me`、`/api/me/systems` 仅认证。
6. **切换系统**：只在顶部切换器操作，更新 store 与 `localStorage` → 重新拉 bootstrap（带新系统头）→ 失效数据查询；不整页刷新。
   - 平台超管：可在所有业务系统间切换，并显示顶级「系统接入」。
   - 普通管理员：只看到自己的系统；系统数 > 1 时显示切换器，= 1 时显示「当前系统：CRM系统」标签。
   - 平台系统不出现在切换器（后端返回的 systems 已排除）。
7. **退出登录**：清空 `rbac.token` / `rbac.currentSystemCode` 与全部查询缓存；401 同样自动清理并跳转 `/login`。

## 菜单结构（权限管理 IA）

| 菜单 | 路由 | 所需权限码（当前系统内短码） | 页面说明 |
|---|---|---|---|
| 系统接入（顶级，仅平台超管） | `/systems` | 无 | 系统接入管理：编码、名称、区域、系统管理员账号/姓名、状态 |
| 时间模板（顶级，仅平台超管） | `/time-templates` | 无（PlatformAdminGuard） | 全局时间模板 CRUD（名称/时区/开始日偏移与时刻/结束日偏移与时刻/状态），删除=逻辑停用 |
| 我的申请（顶级，无需权限码） | `/access-applications` | 无（alwaysVisible） | 业务用户自助提交临时角色权限申请、查看本人申请单与审批链接 |
| 接入凭据（权限管理组内） | `/system-credential` | `system:credential` | 查看当前系统 AppKey / AppSecret（默认隐藏，可显示/复制）；admin 角色经通配 `*` 通过 |
| 角色管理 | `/roles` | `role:manage` | 当前系统角色 CRUD（含绑定时间模板） |
| 权限管理 | `/permissions` | `permission:manage` | 权限码树（type/is_public/method/api_pattern） |
| 角色权限 | `/role-permissions` | `role:manage` | 左右工作区：左侧选角色/勾权限/提交（角色-权限永久），右侧已授权列表与撤销 |
| 用户角色 | `/user-roles` | `user:manage` | 用户-角色分配：窗口由角色绑定的时间模板计算，无模板角色直接分配为永久 |
| 资源管理 | `/sdk-endpoints` | `resource:manage` | SDK 端点上报与权限码关联；未关联端点显式标记「未生效」 |
| 页面元素 | `/page-actions` | `page:manage` | 页面元素标识：pageCode/code/text/type（语义类型） |
| 授权记录 | `/approval-grants` | `audit:view` | 审批授权记录查询 |

- 路由守卫：`src/router.tsx` 中每个页面包裹 `PermissionGuard`（短码）；不满足渲染 403 无权限页（可跳转到第一个有权限菜单），不白屏。
- 侧栏折叠：标题行右侧（标题/菜单分割线同一行）提供自定义圆角按钮，双箭头图标 + Tooltip；使用 `Sider trigger={null}` 关闭默认底部 trigger。折叠后保留 logo 图标和展开按钮，标题文本隐藏。
- `/systems`、`/time-templates` 使用 `PlatformAdminGuard`（`bootstrap.platformAdmin === true` 才可访问），非超管直接访问显示无权限页；「时间模板」为全局配置，不随系统上下文变化。
- **角色权限（A21）**：角色-权限永久有效；角色的生效窗口由角色绑定的「时间模板」决定，在角色管理页配置、在用户角色/审批分配时由服务端计算。
- **通配权限（A 方案）**：`permissions` 支持 `*` 与 `前缀:*` 两种通配。权限集合含 `*` 时 `hasPerm` 恒 true（admin / superadmin 角色只授一条 `*`，因此全部菜单可见）；含 `前缀:*`（如 `role:*`）时，请求码以 `前缀:` 开头即 true；否则精确匹配。**角色权限页的授权树/可授权列表会递归过滤 `code='*'` 节点**（通配由系统默认规则管理，不参与逐项授权），已存在的通配授权仍在右侧「已授权列表」只读展示，并在选择该角色时显示「已拥有通配权限」提示。
- 无任何管理权限：布局菜单仍保留「我的申请」（`alwaysVisible`），不显示「无管理权限」空态；连「我的申请」之外的页面均无权限时，路由守卫渲染 403 无权限页。
- **已移除「用户-系统开通」**：删除路由 `/user-systems`、页面、API 封装、菜单项与类型；系统访问权限完全由「用户-角色（有效角色）」推导。

## 系统接入页（平台超管专属，A16）

页面为两个 Tab：

### Tab1「业务系统」

- 列表列：系统编码 / 系统名称 / 区域 / 管理员账号 / 管理员姓名 / **AppKey** / **AppSecret（掩码）** / 状态 / 操作（查看密钥 / 编辑 / 停用）。
- AppKey 以等宽字体展示并支持一键复制；AppSecret 只展示后端返回的脱敏值（`appSecretMasked`）。
- 「查看密钥」弹窗打开时调用 `GET /api/system-credentials/{id}`（平台超管专属）获取完整 AppKey/AppSecret 并支持复制，弹窗内提示敏感信息妥善保管；「重置密钥」暂未开放（按钮置灰提示）。
- 新建 / 编辑表单必填：系统编码（编辑不可改）、系统名称、所属区域、**系统管理员账号（adminExternalUserId）**；可选：管理员姓名（adminName）；另有状态（启用/停用）。
- 后端在接入时自动为管理员账号创建该系统默认 `admin` 角色并授权（A 方案为通配权限 `*`）；前端只负责提交 `adminExternalUserId` / `adminName`。
- **手动新建成功后弹出结果弹窗**：展示系统 AppKey + 完整 AppSecret，并提示「完整密钥仅显示一次，请立即复制保存」；关闭后列表只展示掩码。
- 请求模型（契约字段）：
  - `GET /api/systems?keyword=&page=&size=`：`items[] = {id,code,name,region,status,adminExternalUserId?,adminName?,appKey?,appSecretMasked?,...}`
  - `POST /api/systems`：`{code,name,region,adminExternalUserId,adminName?,status?}` → 响应额外返回一次 `appSecret`
  - `PUT /api/systems/{id}`：`{name,region,adminExternalUserId?,adminName?,status?}`
  - `DELETE /api/systems/{id}`：停用（status=0）

### Tab2「接入申请」（SDK 自动上报审核）

- SDK 启动后用 onboarding AppKey 上报 `POST /openapi/onboarding/systems`，权限中心生成待审核申请（`sys_system_application`，`system_code` 唯一，**同一系统只保留最新状态**，不展示历史）。
- Tab 名称带**待审核数量 Badge**（`GET /api/system-applications?status=PENDING&size=1` 的 total）；顶部状态筛选：待审核 / 已通过 / 已驳回 / 全部 + 刷新。
- 列表列：系统编码 / 系统名称 / 区域 / 来源 / SDK 版本 / 状态 / 申请时间 / 备注 / 审核信息 / 操作；表格无横向滚动，空态与错误重试齐全。
- 待审核行操作：
  - **审核**：弹窗展示申请信息（含上报 AppKey），表单必填「系统管理员账号」+ 可选「管理员姓名」→ `POST /api/system-applications/{id}/approve`；审核通过后弹结果弹窗展示新系统 AppKey + 完整 AppSecret（仅一次），并刷新申请列表、业务系统列表与 `admin-session`。
  - **驳回**：弹窗必填驳回原因 → `POST /api/system-applications/{id}/reject`；成功后刷新列表。
- 已通过显示「已开通（systemId=xx）」；已驳回在「审核信息」列展示驳回原因。
- API：`listSystemApplications({status,page,size})`、`approveSystemApplication(id,{adminExternalUserId,adminName?})`、`rejectSystemApplication(id,{reason})`；query key 为 `['system-applications', status, page, size]`（Badge 使用 `['system-applications','PENDING',1,1]`，审核/驳回后按前缀统一失效）。

## 接入凭据页（A18）

- 路由 `/system-credential`，菜单在「权限管理」组内（末位，避免系统管理员落地页变成凭据页），权限码 `system:credential`（admin 角色由通配 `*` 通过；普通角色无该码，菜单/路由均不可见）；平台超管同样可见。
- 数据接口：`GET /api/system-credentials/current` → `{systemId,systemCode,systemName,region,appKey,appSecret}`（当前 `X-System-Code` 系统，严格系统隔离）。
- 页面展示：当前系统名称/编码/区域/系统 ID；AppKey 等宽可复制，AppSecret **默认隐藏**（掩码展示），点击「显示」后展示完整值并可复制、可再次隐藏；切换系统后自动重新隐藏；页面 Alert 提示凭据属敏感信息请妥善保管。
- 加载中骨架、失败 Alert + 重试、当前系统上下文缺失提示；路由守卫无权限渲染 403（不白屏）。

**系统接入页完整密钥**：平台超管在「业务系统」Tab 点击「查看密钥」时，按系统 id 调用 `GET /api/system-credentials/{id}` 获取完整 AppSecret（该接口仅平台超管可访问，404/403 均有错误提示与重试）。

**admin 角色授权保护（A18）**：

- 角色授权页角色下拉**过滤 `code='admin'` 的角色**（admin 默认拥有全部权限，无需授权）；若历史状态选中 admin 会自动清空。
- 页面顶部 Alert：「系统管理员角色（admin）默认拥有全部权限，无需授权；如需调整请编辑角色信息」。
- 角色管理页对 `admin` 角色展示「内置」金色 Tag；后端对 admin 角色的 grant/revoke 返回 400 作为双保险。

## 我的申请（自助权限申请，业务用户入口）

- 路由 `/access-applications`，顶级菜单「我的申请」标记 `alwaysVisible`，**不需要任何权限码**，路由放在 `PermissionGuard` 之外；任何登录用户都可见/可进入。
- 布局 `AdminLayout` 不再因「无管理权限」整页拦截：只要存在可见菜单就正常渲染 `Outlet`；仅当连 alwaysVisible 菜单都不存在时才展示无权限空态。
- 首页重定向 `HomeRedirect`：有管理页时优先第一个有权限的管理页；无管理页时回退到 `/access-applications`（普通运营账号登录后直接进入「我的申请」）。
- 页面结构：
  - 顶部只读展示当前系统（`currentSystem.name/code`），状态筛选（全部 / 待审批 / 已通过 / 已驳回）+ 刷新 + 新建申请。
  - 列表列：申请单号 / 状态 / 申请角色 / 授权窗口（申请级为各角色窗口包络）/ 申请原因 / 申请时间 / 操作；待审批单号可点击跳转 `approvalUrl`（外部审批平台，新窗口），详情抽屉复用列表数据并拉取单条详情（展开逐角色模板名与窗口）。
  - 空态、加载态、错误 `Alert` + 重试齐全；列表 `table-layout:auto` 无横向滚动。
- 新建申请弹窗：
  - 当前系统只读；**角色多选**只展示当前系统非 `admin` 角色，已拥有的角色置灰并标注「已拥有」；
  - 选择角色后实时展示「角色权限预览」（角色名/角色编码/权限码，按角色分组）与角色的**时间模板摘要**（如「审批后当天 19:00 至次日 07:00」；无模板角色不可申请，不在选项中）；
  - 申请原因必填（≤200 字），前端校验并提交 `POST /api/access-applications`（当前系统通过 `X-System-Code` 头传递，不放在 body）；成功后 toast 展示申请单号、列表回第 1 页并刷新；失败保留已选角色与原因可重试。
- 接口（契约 §5.1，`src/api/accessApplications.ts`）：
  - `GET /api/access-applications/options` → `{systemId,systemCode,systemName,approvalUrlTemplate?,roles[{roleId,roleCode,roleName,owned,timeTemplateId?,timeTemplateName?,timeTemplateText?,permissions[{id,code,name,type}]}]}`
  - `GET /api/access-applications?status=&page=&size=` → `{items[],total,page,size}`，`items[]` 字段：`id,applicationNo,systemId,systemCode,status(PENDING/APPROVED/REJECTED),roleCount,roles[{roleId,roleCode,roleName,timeTemplateName?}],reason,approvedValidFrom?,approvedValidUntil?,decisionComment?,reviewedBy?,reviewedAt?,createdAt,approvalUrl?`；详情接口的 `roles[]` 额外含 `timeTemplateName,approvedValidFrom,approvedValidUntil`（逐角色窗口）
  - `POST /api/access-applications`：`{roleIds[],reason}`
  - `GET /api/access-applications/{applicationNo}`：申请详情
  - 状态映射：`PENDING` 待审批 / `APPROVED` 已通过 / `REJECTED` 已驳回。
- 前端不提供审批操作（审批在外部审批平台完成，展示审批链接跳转）；审批通过后由后端**按各角色绑定的时间模板分别计算窗口**写入 `sys_user_role`，多角色取包络，过期自动失效。

## 角色权限页（A21 简化）

- **左右工作区**：左侧「授权操作」完成角色选择、权限树勾选、授权原因与提交；右侧「已授权列表」展示该角色全部授权（状态、单条撤销）。避免把宽表格拉满后字段空旷、操作区与列表一左一右割裂。
- **无逐项时效（A21）**：角色-权限为**永久授权**，页面不再提供 `RangePicker` / 时效分组；勾选后一次提交即生效，取消勾选只移出本次提交，不会自动撤销。
- **角色时间模板**：角色的生效窗口由其绑定的「时间模板」决定（在角色管理页配置），不在角色权限页设置；申请/分配时由服务端按模板计算绝对窗口。
- **右栏闭环**：已授权项按状态展示「启用/停用」，单条「撤销」即时生效；已过期/停用项不可撤销。admin 角色不出现在下拉，后端 400 双保险。
- **空间控制**：权限树限高滚动，选中清单限高滚动；授权原因与「提交授权/撤销勾选」同排，保证主操作在首屏可见。

## 用户角色页（A21：模板窗口）

- 路由 `/user-roles`，权限码 `user:manage`；用户-角色分配**不再填写日期**，窗口由所选角色绑定的时间模板在服务端计算并写入 `sys_user_role`，页面只读展示生效/失效时间。
- 无模板（永久）角色：仅管理员可直接分配，窗口为空即永久有效；无模板角色不会出现在「我的申请」选项中。
- 提交 `POST /api/user-roles {systemId, externalUserId, roleId, grantReason?}`；后端校验角色属于当前系统、非 admin、模板启用（模板停用返回 409）。
- 列表展示 externalUserId / 角色 / 生效窗口 / 状态，支持撤销；表格 `table-layout:auto` 无横向滚动。

## 时间模板页（平台超管，A21）

- 顶级菜单「时间模板」，路由 `/time-templates`，`PlatformAdminGuard` 守卫；**全局配置**（不属于任何业务系统，不随系统上下文变化）。
- 列表列：模板名 / 时区 / 开始（第 N 天 HH:mm）/ 结束（第 N 天 HH:mm）/ 状态 / 备注 / 操作；删除为逻辑停用（`status=0`），不物理删除。
- 表单字段：名称（≤100，全局唯一）、时区（IANA，默认 `Asia/Shanghai`）、开始日偏移（0–3650）+ 开始时刻、结束日偏移 + 结束时刻、状态、备注；校验结束本地时刻必须晚于开始（支持跨天窗口，如 19:00 → 次日 07:00）。
- 新建/编辑/删除仅平台超管可操作（后端 403 兜底）；停用模板不影响已写入的历史用户-角色窗口，但不可再绑定到角色或用于新审批/分配。
- **角色管理页**：增加「时间模板」列与下拉选择（无模板=永久角色）；admin 角色绑定模板由后端返回 400（admin 默认拥有全部权限、不受窗口限制）。

## 只查当前系统数据（前端审计）

系统隔离模型下，前端所有权限相关查询都只使用「当前系统」上下文，禁止跨系统/全系统查询：

| 查询 | systemId 来源 | 页面 |
|---|---|---|
| 权限树 `GET /api/permissions?systemId=` | 当前 `currentSystemId` | 权限管理、角色权限、资源管理（关联权限弹窗） |
| 角色列表 `GET /api/roles?systemId=` | 当前 `currentSystemId` | 角色管理、角色权限、用户角色 |
| 角色权限 `GET/POST /api/roles/{roleId}/permissions[/grant|/revoke]` | 当前系统的角色 id | 角色权限 |
| 用户角色 `GET/POST /api/user-roles` | 查询参数 / 请求体 `systemId` | 用户角色 |
| 时间模板 `GET/POST/PUT/DELETE /api/time-templates` | 全局（平台超管），不按 `systemId` | 时间模板、角色表单模板下拉 |
| 页面元素 `GET/POST /api/page-actions` | 查询参数 / 请求体 `systemId` | 页面元素 |
| 资源管理 `GET /api/sdk-endpoints`、`link/unlink` | 查询参数 `systemId` + 当前系统端点 | 资源管理 |
| 授权记录 `GET /api/approval-grants` | 查询参数 `systemId` | 授权记录（`/api/expiring-grants` 后端接口保留，前端不再展示） |

- `/api/me`、`/api/me/systems` 仅要求认证，不带 `X-System-Code`；其余 `/api/**` 均带 `X-System-Code: <currentSystemCode>`。
- 切换系统（含平台超管）：更新 `currentSystemCode` + `localStorage` → 失效并重拉 `admin-session`（bootstrap 带新系统头）→ 所有数据查询按新 `systemId` 重取；角色权限页清空上一系统所选角色、资源管理页关闭关联弹窗，且授权关系查询仅在「所选角色属于当前系统角色列表」时才发起，杜绝切换瞬间用旧 roleId 跨系统查询。
- mock 冒烟日志审计：CRM 阶段所有业务查询均为 `systemId=1` + `X-System-Code=crm`，无 `systemId=2`；superadmin 切到 ERP 后仅出现 `systemId=2` + `erp` 请求，权限树/角色/授权记录均为 ERP 数据，无 CRM 残留。

## 弹窗表单规范

- 统一使用 `Form layout="vertical"`；同一行字段用 `Row gutter={16} + Col`，不使用 `Space` 拼 flex；
- 弹窗宽度按字段量统一：普通 2 列 600–680、权限/资源关联 720；
- `.ant-modal` 内 `Select/TreeSelect/DatePicker/InputNumber` 统一 `width:100%`，避免控件过短；
- 字段间距 18px，textarea 不使用 `showCount`，避免与 footer 重叠。

## 表格展示

- 表格 `table-layout:auto`：宽度足够不换行，不够时换行完整展示，不使用省略号；时间列到分钟；表格 100% 宽度、无横向滚动。
- 列宽由内容决定：全局覆盖 `columns.width` 生成的 col 宽度（`col { width:auto; min-width:0 }`），不允许固定列宽把内容挤成竖排。
- 行高紧凑：单元格上下内边距统一 12px；`>=1200px` 表头单行不换行，窄屏允许表头换行。
- 时间区间统一「开始：… / 结束：…」两行展示，覆盖我的申请列表/详情抽屉、用户角色、授权记录；「我的申请」的「生效状态」独立成列，不混在授权有效期里。
- 窄屏兜底：`<=1200px` 单元格左右内边距降为 8px，`<=1100px` 降为 6px，并允许 `Tag` 与操作按钮组换行；不使用 `fixed` 等宽布局。
- 适配范围：角色管理、权限管理、资源管理、页面元素、授权记录、用户角色、我的申请、系统接入（两个 Tab）、时间模板；headless Chrome 在 1440×900 与 900×900 验证 `scrollWidth === clientWidth`，空间足够时不换行。

## 状态管理

- **TanStack Query** 管理服务端状态：
  - `['admin-session']`：`/api/me` → `/api/me/systems` →（选当前系统）→ `/api/me/bootstrap` 串联结果；切换系统时失效重取
  - `['permissions', systemId]`、`['roles', systemId, {...}]`、`['role-permissions', systemId, roleId]`、`['user-roles', systemId, externalUserId]`、`['page-actions', systemId, {...}]`、`['sdk-endpoints', systemId, {...}]`、`['approval-grants', systemId, {...}]`、`['time-templates']`、`['access-applications', systemId, {status,page,size}]`、`['access-application-options', systemId]`、`['access-application', systemId, applicationNo]`
  - 切换系统时**只 refetch `admin-session`**，不全局 invalidate 旧 systemId 查询；页面 queryKey 含 systemId，系统上下文变化后自动按新 key 重取（避免「旧 systemId + 新系统头」的跨系统 403）
- **Zustand**（`src/stores/app.ts`）：`token`、`currentUser`、`systems`、`currentSystemCode`、`platformAdmin`、`permissions: Set<string>`、`hasPerm(code)`（平台超管恒 true）、`setBootstrap`、`logout`
  - 持久化：`rbac.token`（JWT）、`rbac.currentSystemCode`（当前业务系统上下文）

## 目录结构

```text
telbank-rbac-front/
├── index.html
├── vite.config.ts
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
├── .env.example
├── public/favicon.svg
└── src/
    ├── main.tsx                 # Provider 装配：ConfigProvider + antd App + QueryClient + Router
    ├── router.tsx               # 登录页 + 受保护布局 + 权限管理页 + 系统接入/时间模板（超管守卫）+ 我的申请（无需权限）
    ├── config/menu.ts           # 菜单/路由/权限短码映射（role:manage 等）+ 系统接入/时间模板（仅超管）+ 我的申请（alwaysVisible）
    ├── api/
    │   ├── request.ts           # 原生 fetch：JWT / X-System-Code=当前系统 / 信封解析 / 401
    │   ├── types.ts             # 契约类型（含 bootstrap / 系统接入管理员字段 / 时间模板 / 权限申请）
    │   ├── me.ts                # getMe / getMySystems / getBootstrap / loadAdminSession
    │   ├── systems.ts roles.ts permissions.ts rolePermissions.ts
    │   ├── userRoles.ts pageActions.ts sdkEndpoints.ts grants.ts
    │   ├── systemCredentials.ts systemApplications.ts accessApplications.ts
    │   ├── timeTemplates.ts     # 时间模板 CRUD（平台超管）
    │   └── index.ts
    ├── stores/app.ts            # Zustand：token/systems/currentSystemCode/permissions/hasPerm
    ├── layouts/AdminLayout.tsx  # 权限菜单 + 系统切换器 + 会话引导 + 我的申请入口
    ├── components/              # PageShell/SystemGate/StatusTags/AppNotifier/错误边界/PermissionGuard/NoPermission
    ├── pages/                   # LoginPage + 管理页面（含 TimeTemplatePage 时间模板）+ AccessApplicationsPage + NotFoundPage
    ├── utils/                   # 时间格式、Ant 路径校验、分页配置、文案常量
    └── styles/global.css
```

## 错误与空态处理

- 统一响应信封 `{code,message,data}`：HTTP 非 2xx 或 `code !== 0` 抛出 `ApiError`，并通过 antd `message.error` 提示后端 `message`。
- 每个页面在查询失败时展示 `Alert`（含错误信息与「重试」按钮），不会白屏；列表查询为空时展示表格空态文案。
- 顶层 `AppErrorBoundary` 捕获渲染异常，展示可刷新的错误页。
- 未登录访问受保护路由自动跳转 `/login`；JWT 失效（401）自动清理会话并回到登录页。
- 权限不足：路由级 `PermissionGuard` 展示 403 无权限页（含「前往可用页面」按钮）；`/systems` 非超管访问展示「系统接入仅平台超管可访问」；无任何可见菜单（连「我的申请」也没有）时布局展示无权限空态。
- 后端未启动时，页面提示「无法连接权限中心后端」，构建与开发服务器不依赖后端。

## 构建产物（本工程最近一次构建）

```text
> telbank-rbac-front@1.0.0 build
> tsc -b && vite build

vite v8.3.5 building client environment for production...
✓ 3259 modules transformed.
dist/index.html                     0.60 kB │ gzip:   0.40 kB
dist/assets/index-udQR-fIr.css      4.00 kB │ gzip:   1.43 kB
dist/assets/index-DQn7UXyr.js   1,456.05 kB │ gzip: 459.13 kB
✓ built in 323ms
```

`npm run build` 总耗时约 3.8 秒（含 `tsc -b` 类型检查），`dist/` 总大小约 1.4 MB（JS gzip 约 459 kB）。

## 已知限制 / 后续

- 角色权限（A21）已取消逐项时效：一次提交即永久授权；时效统一由「用户-角色」窗口承担（角色绑定时间模板），角色权限页不再按时间分组提交。
- 时间模板窗口由服务端按模板时区计算，前端只读展示；跨天窗口（如 19:00→次日 07:00）无需前端特殊处理。
- 后端新契约（系统隔离、`platform:admin` 数据驱动超管、系统接入管理员字段）由 api-dev 并行改造；联调阶段若字段名有差异需要对齐。
- 未做路由级 code splitting：生产包单 chunk 约 1.46 MB（gzip 约 459 kB），后续可用 `React.lazy` + `manualChunks` 优化首屏。
- 授权记录按契约仅提供查询（只读）；「到期提醒」菜单/路由已移除（后端 `/api/expiring-grants` 接口保留）。
- 资源管理：未关联权限码的端点显示「未生效/未关联」并有当前页提示，行背景高亮；关联后才进入 SDK mappings 生效。
- 角色权限提交按「相同时效」分组调用 grant：A21 起已取消逐项时效，此历史说明不再适用（保留供对照）。
- 全局 `.ant-message*` 设置 `pointer-events:none`，避免切换系统 toast 覆盖顶部系统下拉框、导致下拉框点不开。
- 表格按 A19 内容优先：`table-layout:auto`，宽度足够不换行，不够时换行完整展示；时间列用 `YYYY-MM-DD HH:mm`。

## 本地验证记录

- `npm run build`：`tsc -b` 类型检查 0 error，Vite 构建成功。
- `npm run dev`（5175 端口冒烟）：`curl http://localhost:5175/` 返回 HTTP 200。
- mock 后端 + headless Chrome（CDP）冒烟（系统隔离模型）：
  - `crm-admin-001`：仅见「权限管理」组 8 个菜单，无「系统接入」；顶部「当前系统：CRM系统」标签（单系统）；直接访问 `/systems` 显示「系统接入仅平台超管可访问」403；请求头 `X-System-Code=crm`、数据查询 `systemId=1`。
  - `erp-admin-001`：仅见 ERP（`X-System-Code=erp`、`systemId=2`），无「系统接入」。
  - `superadmin`（数据驱动超管）：见顶级「系统接入」+「权限管理」组；切换器可切 CRM/ERP，切换后 `localStorage["rbac.currentSystemCode"]` 与请求头同步变化、bootstrap 重新拉取；系统接入列表含管理员账号/姓名列，新建表单含必填「系统管理员账号」与可选「管理员姓名」，提交载荷含 `adminExternalUserId/adminName`。
  - 非超管但拥有两个系统的管理员：可见切换器、无「系统接入」，直接访问 `/systems` 403。
  - 普通运营账号：无管理菜单，首页「无管理权限」空态；直接访问 `/roles` 显示无权限。
  - 退出登录后清空 `rbac.token` / `rbac.currentSystemCode` 并回到 `/login`。
- 通配权限与严格系统隔离冒烟（mock 后端 + headless Chrome，零 console 错误）：
  - `permissions=["*"]` 的 CRM admin：8 个「权限管理」菜单全部可见（无系统接入）；权限树显示 `*（全部权限）` 行；角色授权页选择 admin 后通配权限行已勾选、已授权列表展示「*（全部权限）」并提示通配授权。
  - `permissions=["role:*"]` 前缀通配账号：仅「角色管理 / 角色授权」可见；直接访问 `/permissions` 显示缺少 `permission:manage` 的 403。
  - 平台超管 `superadmin`：从 CRM 切到 ERP 后，权限树仅 ERP 权限（含 ERP 通配行）、角色列表为 ERP 角色、授权记录 roleId 为 ERP 角色；请求日志 100% 为 `systemId=2` + `X-System-Code=erp`，无 CRM 数据残留。
  - 请求审计：CRM 阶段所有权限相关查询均为 `systemId=1` + `X-System-Code=crm`；全程无跨系统 `systemId`、无空 systemId 的全系统查询（`/api/me`、`/api/me/systems` 为仅认证接口，按契约不带系统头）。
- 系统接入页 A16 冒烟（mock 后端 + headless Chrome，零 console 错误）：
  - Tabs「业务系统 / 接入申请」正常切换，接入申请 Tab Badge 显示待审核数量（2 → 审核后 1）。
  - 业务系统列表展示 AppKey（等宽可复制）与 AppSecret 掩码；「查看密钥」弹窗展示 AppKey + 掩码，「重置密钥」置灰不可用。
  - 手动新建系统后弹出结果弹窗，展示 `appKey` + 完整 `appSecret`（仅一次提示），关闭后列表新增该系统并只显示掩码。
  - 审核弹窗必填校验（空提交提示「请输入系统管理员账号」）、通过后结果弹窗展示新系统 AppKey/完整 AppSecret，列表状态刷新为「已开通（systemId=4）」、Badge 数量递减。
  - 驳回弹窗必填原因校验（空提交提示「请输入驳回原因」），驳回后「已驳回」筛选展示驳回原因。
  - 两个 Tab 的表格 `scrollWidth === clientWidth`（无横向滚动）。
- 接入凭据 + admin 授权保护冒烟（mock 后端 + headless Chrome，零 console 错误）：
  - `crm-admin-001`（permissions=["*"]）：顶级「接入凭据」可见、「系统接入」不可见；`/system-credential` 展示 CRM系统/crm/region_a，AppSecret 默认掩码，点「显示」后展示 `crm-dev-secret`、点「隐藏」恢复掩码，复制按钮可用。
  - `superadmin`：系统接入页「查看密钥」调用 `/api/system-credentials/1`，弹窗展示完整 `crm-app` / `crm-dev-secret` + 敏感提示 + 复制按钮；业务系统表格无横向滚动。
  - 角色授权页：顶部 admin 提示 Alert 存在；角色下拉仅有「运营（operator）」，无 admin 选项。
  - 角色授权交互重构：选择「CRM运营（operator）」后「已勾选 3 项」，本次清单显示 3 条独立时效；点击「提交授权」提示「授权已提交（2 组时效）」（永久 1 条 + 临时 2 条分组），提交前后数据库 operator 授权记录完全一致；右栏展示 4 条（3 条生效）并支持单条撤销。
  - 资源管理未生效提示：当前页 3 个未生效端点时 Alert 文案中的数字 3 为强调样式（21.6px / `#d46b08`），不再是普通正文。
  - 普通运营账号：无任何管理菜单，直接访问 `/system-credential` 显示无权限空态。
  - 接口日志：`/api/system-credentials/current` 由当前系统上下文发起；`/api/system-credentials/{id}` 仅 superadmin 调用（其他账号 403）。
- 我的申请 + 通配过滤冒烟（mock 后端 + headless Chrome，零 console 错误；dev server 用 5176 端口，不占用用户 5173/8080）：
  - 普通运营账号（无管理权限）：登录后 `/` 重定向到 `/access-applications`；左侧仅「我的申请」菜单，不再出现「无管理权限」整页空态。
  - 列表展示申请单号/状态/申请角色/授权有效期/申请原因/申请时间/操作；待审批单号链接到 `approvalUrl`（`http://approval.local/app/APP-...`）。
  - 新建申请：空提交校验「请至少选择一个角色」「请填写申请原因」；选择「订单运营（operator）」后预览 `order:list/order:delete`；提交成功 toast「申请已提交：APP-20261025-0004」并回列表刷新；已拥有角色置灰标注「已拥有」。
  - 详情抽屉展示单号/状态/所属系统/申请角色/原因/提交时间/有效期/审批信息，与列表一致。
  - 角色授权页（`permissions=["*"]` 管理员）：选择「订单运营（operator）」后，授权树内**不含**「全部权限（通配）」节点（仅订单管理 2 个子节点），顶部展示通配提示 Alert，右栏已授权列表仍只读展示通配记录，已勾选数仅统计非通配权限（1 项）。
  - 表格 `scrollWidth === clientWidth`（我的申请列表无横向滚动）。
- **A21（角色时间模板）已联调**：headless Chrome 在真实 A21 后端（API 28090 + 前端 5181，1440×900）验证：平台超管可见 `/time-templates` 并查看夜间/全天预置模板；crm-admin 角色权限页无逐项时效，用户角色页无日期选择；业务用户「我的申请」仅见该入口，表单展示角色模板摘要；角色管理表格内容完整无省略。前端 `npm run build` 3261 modules / JS 1,469.08 kB（gzip 462.50 kB）。
