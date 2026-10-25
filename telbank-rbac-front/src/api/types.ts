/**
 * 权限中心管理端 API 类型定义。
 * 与 docs/impl/contracts.md §3 严格对齐：所有 /api/** 返回统一信封
 * `{ code, message, data }`，分页 data 为 `{ items, total, page, size }`，
 * 时间字段为 ISO-8601 UTC 字符串（如 2026-10-31T23:59:59Z）。
 */

/** 统一响应信封（契约 §1.2） */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** 分页响应载荷（契约 §1.3） */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page?: number
  size?: number
}

/* ------------------------------------------------------------------ */
/* §3.1 当前用户与系统                                                  */
/* ------------------------------------------------------------------ */

export interface MeVO {
  externalUserId: string
  platformAdmin: boolean
}

export interface MySystemVO {
  id: number
  code: string
  name: string
  region: string
  isDefault: boolean
}

/**
 * GET /api/me/bootstrap —— 当前系统上下文（需带 X-System-Code: 当前系统）。
 * 新系统隔离模型：currentSystemCode 为当前系统；permissions 为「当前系统内」的
 * 短码权限集合（role:manage / permission:manage / resource:manage / page:manage /
 * user:manage / audit:view）；平台超管 permissions 返回 ["*"]，hasPerm 恒为 true。
 */
export interface AdminBootstrapVO {
  externalUserId: string
  platformAdmin: boolean
  /** 当前系统上下文编码（与请求头 X-System-Code 一致；无可访问系统时为 null） */
  currentSystemCode: string | null
  /** 当前系统内有效权限码集合；平台超管为 ["*"] */
  permissions: string[]
  /** 当前账号可访问系统（由有效角色推导；平台超管为全部启用系统） */
  systems: MySystemVO[]
}

/** 管理端会话引导结果：/api/me → /api/me/systems → /api/me/bootstrap */
export interface AdminSessionVO {
  me: MeVO
  systems: MySystemVO[]
  bootstrap: AdminBootstrapVO
  /** 选定的当前系统编码（与 bootstrap.currentSystemCode 一致，可能为 null） */
  currentSystemCode: string | null
}

/* ------------------------------------------------------------------ */
/* §3.2 系统接入（平台超管专属）                                          */
/* ------------------------------------------------------------------ */

export interface SysSystemVO {
  id: number
  code: string
  name: string
  region: string
  status: number
  /** 系统管理员账号（接入时自动创建默认 admin 角色并授权） */
  adminExternalUserId?: string | null
  /** 系统管理员姓名（可选） */
  adminName?: string | null
  /** SDK 接入 AppKey（等宽展示，可复制） */
  appKey?: string | null
  /** 已脱敏的 AppSecret（完整密钥仅在创建/审核通过响应中返回一次） */
  appSecretMasked?: string | null
  sort?: number
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SystemCreatePayload {
  code: string
  name: string
  region: string
  /** 系统管理员账号（必填） */
  adminExternalUserId: string
  /** 管理员姓名（可选） */
  adminName?: string
  status?: number
}

export interface SystemUpdatePayload {
  name: string
  region: string
  adminExternalUserId?: string
  adminName?: string
  status?: number
}

/* ------------------------------------------------------------------ */
/* §3.2c 系统接入凭据（A18：当前系统 / 超管按 systemId 查看完整密钥）    */
/* ------------------------------------------------------------------ */

export interface SystemCredentialVO {
  systemId: number
  systemCode: string
  systemName: string
  region: string
  appKey: string
  /** 完整 AppSecret（敏感信息，仅授权页面/超管展示） */
  appSecret: string
}

/* ------------------------------------------------------------------ */
/* §3.2b 系统接入申请（A16：SDK 自动上报 + 平台超管审核）                */
/* ------------------------------------------------------------------ */

export type SystemApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface SystemApplicationVO {
  id: number
  systemCode: string
  name: string
  region: string
  /** 来源：SDK / MANUAL */
  source: string
  appKey?: string | null
  sdkVersion?: string | null
  remark?: string | null
  status: SystemApplicationStatus
  rejectReason?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  /** 审核通过后创建的业务系统 id */
  systemId?: number | null
  createdAt?: string | null
}

export interface SystemApplicationListQuery extends PageQuery {
  status?: SystemApplicationStatus
}

export interface SystemApplicationApprovePayload {
  adminExternalUserId: string
  adminName?: string
}

export interface SystemApplicationRejectPayload {
  reason: string
}

/** POST /api/systems 响应：系统 VO + 仅本次返回的完整 AppSecret（后续只返回掩码） */
export interface SystemCreateResultVO extends SysSystemVO {
  appSecret?: string | null
}

/** POST /api/system-applications/{id}/approve 响应：申请 VO + 新系统 AppKey/完整 AppSecret（仅本次返回） */
export interface SystemApproveResultVO extends SystemApplicationVO {
  appKey?: string | null
  appSecret?: string | null
}

/* ------------------------------------------------------------------ */
/* §3.3 角色管理                                                        */
/* ------------------------------------------------------------------ */

export interface RoleVO {
  id: number
  systemId: number
  code: string
  name: string
  status: number
  sort: number
  remark?: string | null
}

export interface RoleCreatePayload {
  systemId: number
  code: string
  name: string
  status?: number
  sort?: number
  remark?: string
}

export interface RoleUpdatePayload {
  name: string
  status?: number
  sort?: number
  remark?: string
}

/* ------------------------------------------------------------------ */
/* §3.4 权限资源管理（树形）                                             */
/* ------------------------------------------------------------------ */

/** 权限资源类型：1 目录/页面级、2 操作级、3 纯接口 */
export type PermissionType = 1 | 2 | 3

export interface PermissionNode {
  id: number
  systemId: number
  parentId: number
  type: PermissionType
  code: string
  name: string
  method?: string | null
  apiPattern?: string | null
  sort: number
  status: number
  isPublic: number
  meta?: unknown
  children?: PermissionNode[]
}

export interface PermissionSavePayload {
  systemId: number
  parentId?: number
  type: PermissionType
  code: string
  name: string
  method?: string | null
  apiPattern?: string | null
  sort?: number
  status?: number
  isPublic?: number
  meta?: unknown
}

/* ------------------------------------------------------------------ */
/* §3.5 角色-权限授权                                                   */
/* ------------------------------------------------------------------ */

export interface PermissionGrant {
  permissionId: number
  code: string
  validFrom: string | null
  validUntil: string | null
  status: number
}

export interface RolePermissionResult {
  roleId: number
  grants: PermissionGrant[]
}

export interface GrantPayload {
  permissionIds: number[]
  validFrom?: string | null
  validUntil?: string | null
  grantReason?: string
}

export interface RevokePayload {
  permissionIds: number[]
}

/* ------------------------------------------------------------------ */
/* §3.6 用户-角色分配                                                   */
/* ------------------------------------------------------------------ */

export interface UserRoleVO {
  id: number
  systemId: number
  externalUserId: string
  roleId: number
  roleCode: string
  roleName: string
  validFrom: string | null
  validUntil: string | null
  status: number
  grantReason: string | null
  sourceApprovalId: string | null
}

export interface UserRoleCreatePayload {
  systemId: number
  externalUserId: string
  roleId: number
  validFrom?: string | null
  validUntil?: string | null
  grantReason?: string
}

/* ------------------------------------------------------------------ */
/* §3.8 页面操作元数据（模式 B）                                        */
/* ------------------------------------------------------------------ */

export const PAGE_ACTION_KINDS = [
  'openDialog',
  'callApi',
  'download',
  'navigate',
  'confirmThenCallApi',
] as const

export type PageActionKind = (typeof PAGE_ACTION_KINDS)[number]

export interface PageActionVO {
  id: number
  systemId: number
  pageCode: string
  code: string
  text: string
  type: string
  action: PageActionKind | string
  config: unknown
  sort: number
  status: number
}

export interface PageActionPayload {
  systemId: number
  pageCode: string
  code: string
  text: string
  type?: string
  action: PageActionKind
  config?: unknown
  sort?: number
}

/* ------------------------------------------------------------------ */
/* §3.9 SDK 端点上报列表                                                */
/* ------------------------------------------------------------------ */

export interface SdkEndpointVO {
  id: number
  systemId: number
  method: string
  path: string
  candidateCode: string | null
  source: string
  permissionId: number | null
  permissionCode: string | null
  reportedAt: string | null
}

/* ------------------------------------------------------------------ */
/* §3.10 审批授权记录 / 即将到期                                         */
/* ------------------------------------------------------------------ */

export type ApprovalGrantKind = 'USER_ROLE' | 'ROLE_PERMISSION'

export interface ApprovalGrantVO {
  kind: ApprovalGrantKind
  systemId: number
  externalUserId?: string | null
  roleId?: number | null
  permissionId?: number | null
  sourceApprovalId: string | null
  grantReason: string | null
  validFrom: string | null
  validUntil: string | null
  status: number
  grantedAt: string | null
}

/* ------------------------------------------------------------------ */
/* §5.1 业务用户临时权限申请（申请人侧，自助申请）                      */
/* ------------------------------------------------------------------ */

/** 申请单状态：待审批 / 已通过 / 已驳回（审批在外部审批平台完成） */
export type AccessApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 可申请角色中的权限预览项 */
export interface AccessRolePermissionVO {
  id: number
  code: string
  name: string
  type: PermissionType | number
}

/** 可申请角色：owned=true 表示申请人当前已有效持有，前端禁止勾选 */
export interface AccessRoleOptionVO {
  roleId: number
  roleCode: string
  roleName: string
  owned: boolean
  permissions: AccessRolePermissionVO[]
}

/** GET /api/access-applications/options —— 当前系统可申请角色与审批链接模板 */
export interface AccessApplicationOptionsVO {
  systemId: number
  systemCode: string
  systemName: string
  approvalUrlTemplate: string
  roles: AccessRoleOptionVO[]
}

/** 申请单中的角色快照（申请时的 roleCode/roleName） */
export interface AccessApplicationRoleVO {
  roleId: number
  roleCode: string
  roleName: string
}

/**
 * 申请单视图：时间字段为 ISO-8601 UTC 字符串；
 * approvedValidFrom/approvedValidUntil 仅在审批通过后存在（统一授权窗口）。
 */
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

/** POST /api/access-applications —— roleIds 非空且不超过 20 个，reason 1–200 字 */
export interface AccessApplicationCreatePayload {
  roleIds: number[]
  reason: string
}

/* ------------------------------------------------------------------ */
/* 查询参数                                                             */
/* ------------------------------------------------------------------ */

export interface KeywordPageQuery extends PageQuery {
  keyword?: string
}

export interface AccessApplicationListQuery extends PageQuery {
  status?: AccessApplicationStatus
}

export interface RoleListQuery extends KeywordPageQuery {
  systemId: number
}

export interface PageActionListQuery extends PageQuery {
  systemId: number
  pageCode?: string
}

export interface SdkEndpointListQuery extends KeywordPageQuery {
  systemId: number
}

export interface UserRoleListQuery {
  systemId: number
  externalUserId: string
}

export interface ApprovalGrantListQuery extends PageQuery {
  systemId?: number
  externalUserId?: string
}

