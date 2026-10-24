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

/* ------------------------------------------------------------------ */
/* §3.2 系统管理                                                        */
/* ------------------------------------------------------------------ */

export interface SysSystemVO {
  id: number
  code: string
  name: string
  region: string
  status: number
  sort: number
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SystemCreatePayload {
  code: string
  name: string
  region: string
  status?: number
  sort?: number
  remark?: string
}

export interface SystemUpdatePayload {
  name: string
  region: string
  status?: number
  sort?: number
  remark?: string
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
/* §3.7 用户-系统开通                                                   */
/* ------------------------------------------------------------------ */

export interface UserSystemVO {
  id: number
  systemId: number
  systemCode: string
  externalUserId: string
  isDefault: number
  status: number
}

export interface UserSystemCreatePayload {
  systemId: number
  externalUserId: string
  isDefault?: boolean
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
/* 查询参数                                                             */
/* ------------------------------------------------------------------ */

export interface KeywordPageQuery extends PageQuery {
  keyword?: string
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

export interface UserSystemListQuery {
  externalUserId: string
}

export interface ApprovalGrantListQuery extends PageQuery {
  systemId?: number
  externalUserId?: string
}

export interface ExpiringGrantListQuery {
  systemId?: number
  withinDays?: number
}
