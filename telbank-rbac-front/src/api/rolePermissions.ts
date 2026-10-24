import { request } from './request'
import type { GrantPayload, RevokePayload, RolePermissionResult } from './types'

/** GET /api/roles/{roleId}/permissions —— 角色当前已授权列表（契约 §3.5） */
export function getRolePermissions(roleId: number): Promise<RolePermissionResult> {
  return request<RolePermissionResult>(`/api/roles/${roleId}/permissions`)
}

/** POST /api/roles/{roleId}/permissions/grant —— 授予权限（可带时效） */
export function grantRolePermissions(roleId: number, payload: GrantPayload): Promise<unknown> {
  return request<unknown>(`/api/roles/${roleId}/permissions/grant`, {
    method: 'POST',
    body: payload,
  })
}

/** POST /api/roles/{roleId}/permissions/revoke —— 撤销权限（status=0） */
export function revokeRolePermissions(roleId: number, payload: RevokePayload): Promise<unknown> {
  return request<unknown>(`/api/roles/${roleId}/permissions/revoke`, {
    method: 'POST',
    body: payload,
  })
}
