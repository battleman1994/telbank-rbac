# telbank-rbac-front 权限中心管理前端

`telbank-rbac` 权限管理平台的管理端 Web 界面，对接权限中心后端 `telbank-rbac-api` 的 `/api/**` 管理接口。

- 需求基线：`docs/telbank-rbac.md` §3.3 / §5.7
- 接口契约：`docs/impl/contracts.md` §3 / §10
- 完整文档地图：[`../docs/README.md`](../docs/README.md)

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
| `VITE_API_BASE_URL` | `http://localhost:8080` | 权限中心后端地址；开发环境直接跨域请求，后端需放行 `http://localhost:5173` 与 `http://127.0.0.1:5173`（契约 §1.8） |

可在 `telbank-rbac-front/.env.local` 中覆盖，例如：

```bash
VITE_API_BASE_URL=http://127.0.0.1:8080
```

## 与权限中心后端联调

本前端默认请求 `http://localhost:8080`。本地联调步骤（详见仓库根 README §4）：

```bash
# 1) 启动权限中心（JDK 21+，demo profile；HS256 演示 JWT）
cd .. && ./scripts/start-api.sh

# 2) 生成演示 JWT（另开终端）
TOKEN=$(./scripts/gen-demo-jwt.sh 13800138000)

# 3) 启动前端并在浏览器打开 http://localhost:5173，粘贴 TOKEN 登录
cd telbank-rbac-front && npm run dev
```

> 真实后端联调尚未纳入自动化验收（见 `docs/acceptance.md` 已知限制）；页面操作时可在浏览器 DevTools 观察 `/api/**` 请求头（`Authorization: Bearer` + `X-System-Code`）。

## 登录与系统上下文

1. 权限中心**不签发 JWT**（认证外部化）。管理端 `/login` 页面粘贴外部认证系统签发的 JWT，先调用 `GET /api/me` + `GET /api/me/systems` 校验，通过后写入 `localStorage["rbac.token"]`。
2. 本地联调：后端以 `demo` profile 启动（`rbac.security.jwt.mode=hs256`）时，可用仓库根目录 `scripts/gen-demo-jwt.sh` 生成的 HS256 token 粘贴登录；生产使用外部认证系统 JWKS 校验。
3. 登录后默认选中 `isDefault=true` 的系统，当前系统编码写入 `localStorage["rbac.systemCode"]`；所有管理接口请求自动注入：
   - `Authorization: Bearer <token>`
   - `X-System-Code: <currentSystemCode>`
4. 顶部系统切换器切换系统后，写入 `localStorage["rbac.systemCode"]` 并让所有 TanStack Query 缓存失效重取；各页面查询 key 均按 `systemId` 隔离。
5. 退出登录清空 `rbac.token` / `rbac.systemCode` 与全部查询缓存。接口返回 401 时自动清理会话并跳转 `/login`。

## 页面清单（10 个功能页面）

| 路由 | 页面 | 主要接口（契约 §3） |
|---|---|---|
| `/systems` | 系统管理 | `GET/POST /api/systems`、`PUT/DELETE /api/systems/{id}` |
| `/roles` | 角色管理（按系统） | `GET/POST /api/roles`、`PUT/DELETE /api/roles/{id}` |
| `/permissions` | 权限资源管理（树形） | `GET/POST /api/permissions`、`PUT/DELETE /api/permissions/{id}` |
| `/role-permissions` | 角色-权限授权（树勾选 + 时效） | `GET /api/roles/{roleId}/permissions`、`POST .../grant`、`POST .../revoke` |
| `/user-roles` | 用户-角色分配（含时效） | `GET/POST /api/user-roles`、`DELETE /api/user-roles/{id}` |
| `/user-systems` | 用户-系统开通 | `GET/POST /api/user-systems`、`DELETE /api/user-systems/{id}` |
| `/page-actions` | 页面操作元数据（模式 B） | `GET/POST /api/page-actions`、`PUT/DELETE /api/page-actions/{id}` |
| `/sdk-endpoints` | SDK 端点上报 | `GET /api/sdk-endpoints`、`PUT .../link`、`PUT .../unlink` |
| `/approval-grants` | 审批授权记录（只读） | `GET /api/approval-grants` |
| `/expiring-grants` | 即将到期授权（只读） | `GET /api/expiring-grants` |

交互约定：

- 列表页均支持分页 / 搜索 / 刷新；写操作均有新建或编辑 Modal 表单 + 删除（停用）二次确认。
- 权限资源树支持 `type`、`is_public`、`code`、`method`、`api_pattern`、`meta(JSON)` 编辑；`api_pattern` 前端按 Ant 风格提前校验（`*` 匹配单段、`**` 只能出现在末尾），且 `method` 与 `api_pattern` 必须同时为空或同时非空（与契约 §3.4 一致，最终以后端 400 校验为准）。
- 角色-权限授权页按系统选择角色 → 权限树勾选 → 设置 `valid_from/valid_until`（不填为永久）→ 提交 grant/revoke。
- SDK 端点上报页支持「关联已有权限码」与「新建权限码并关联」两种方式；关联后服务端把端点 `method/path` 写入权限码的 `method/api_pattern`。
- 审批授权记录、即将到期授权为只读查询页，提供筛选、分页、详情 Modal。

## 状态管理

- **TanStack Query** 管理所有服务端状态：权限树、角色、授权、映射、审批记录等。查询 key 均显式包含 `systemId`，例如：
  - `['permissions', systemId]`
  - `['roles', systemId, { keyword, page, size }]`
  - `['role-permissions', systemId, roleId]`
  - `['expiring-grants', systemId, withinDays]`
  - 切换系统后 `queryClient.invalidateQueries()` 全量失效重取。
- **Zustand**（`src/stores/app.ts`）管理客户端上下文：`token`、`currentUser`、`systems`、`currentSystemCode`、`platformAdmin`。

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
    ├── router.tsx               # 登录页 + 受保护布局 + 10 个页面路由
    ├── api/
    │   ├── request.ts           # 原生 fetch 封装：JWT / X-System-Code / 信封解析 / 401 处理
    │   ├── types.ts             # 契约 §3 全部 VO / 请求 / 响应类型
    │   ├── me.ts systems.ts roles.ts permissions.ts
    │   ├── rolePermissions.ts userRoles.ts userSystems.ts
    │   ├── pageActions.ts sdkEndpoints.ts grants.ts
    │   └── index.ts
    ├── stores/app.ts            # Zustand 全局状态（localStorage 持久化 token/systemCode）
    ├── layouts/AdminLayout.tsx  # 侧边栏 + 顶部系统切换器 + 登录态引导 + 退出登录
    ├── components/              # PageShell / SystemGate / StatusTags / AppNotifier / 错误边界
    ├── pages/                   # LoginPage + 10 个管理页面 + NotFoundPage
    ├── utils/                   # 时间格式、Ant 路径校验、分页配置、文案常量
    └── styles/global.css
```

## 错误与空态处理

- 统一响应信封 `{code,message,data}`：HTTP 非 2xx 或 `code !== 0` 抛出 `ApiError`，并通过 antd `message.error` 提示后端 `message`。
- 每个页面在查询失败时展示 `Alert`（含错误信息与「重试」按钮），不会白屏；列表查询为空时展示表格空态文案。
- 顶层 `AppErrorBoundary` 捕获渲染异常，展示可刷新的错误页。
- 未登录访问受保护路由自动跳转 `/login`；JWT 失效（401）自动清理会话并回到登录页。
- 后端未启动时，页面提示「无法连接权限中心后端」，构建与开发服务器不依赖后端。

## 构建产物（本工程最近一次构建）

```text
> telbank-rbac-front@1.0.0 build
> tsc -b && vite build

vite v8.3.5 building client environment for production...
transforming...
✓ 3254 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                     0.60 kB │ gzip:   0.40 kB
dist/assets/index-*.css             1.80 kB │ gzip:   0.84 kB
dist/assets/index-*.js          1,406.55 kB │ gzip: 443.48 kB
✓ built in ~1s
```

`npm run build` 总耗时约 6~15 秒（含 `tsc -b` 类型检查，视机器负载），`dist/` 总大小约 1.4 MB（JS gzip 约 443 kB）。

## 已知限制 / 后续

- 未做路由级 code splitting：生产包单 chunk 1.4 MB（gzip 443 kB），后续可用 `React.lazy` + `manualChunks` 优化首屏。
- 真实权限中心后端的浏览器人工联调待补（当前验证为类型检查 / 构建 / mock 冒烟）。
- 审批授权记录、即将到期授权按契约仅提供查询（只读）。

## 本地验证记录

- `npm run build`：`tsc -b` 类型检查 0 error，Vite 构建成功。
- `npm run dev`：`vite` 启动于 5173，`curl http://localhost:5173/` 返回 HTTP 200。
- 使用 mock 后端 + headless Chrome（CDP）做页面级冒烟：10 个页面全部渲染无异常、无控制台错误；验证了新建/编辑弹窗、角色选择 + 权限树勾选、external_user_id 查询、系统切换（请求头 `X-System-Code` 随系统变为 `erp`、查询 `systemId=2` 隔离生效）、退出登录回到 `/login`。
