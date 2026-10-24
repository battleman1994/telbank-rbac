import { request } from './request'
import type { UserRoleCreatePayload, UserRoleListQuery, UserRoleVO } from './types'

/** GET /api/user-roles —— 查询某用户的角色分配（含时效，契约 §3.6） */
export function listUserRoles(params: UserRoleListQuery): Promise<UserRoleVO[]> {
  return request<UserRoleVO[]>('/api/user-roles', { query: { ...params } })
}

/** POST /api/user-roles —— 新增用户-角色分配（含时效） */
export function createUserRole(payload: UserRoleCreatePayload): Promise<UserRoleVO> {
  return request<UserRoleVO>('/api/user-roles', { method: 'POST', body: payload })
}

/** DELETE /api/user-roles/{id} —— 撤销分配（status=0） */
export function revokeUserRole(id: number): Promise<unknown> {
  return request<unknown>(`/api/user-roles/${id}`, { method: 'DELETE' })
}
