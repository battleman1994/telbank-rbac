import { request, requestPage } from './request'
import type {
  PageResult,
  RoleCreatePayload,
  RoleListQuery,
  RoleUpdatePayload,
  RoleVO,
} from './types'

/** GET /api/roles —— 按系统隔离的角色分页列表（契约 §3.3） */
export function listRoles(params: RoleListQuery): Promise<PageResult<RoleVO>> {
  return requestPage<RoleVO>('/api/roles', { query: { ...params } })
}

/** POST /api/roles —— 新建角色，(systemId,code) 唯一 */
export function createRole(payload: RoleCreatePayload): Promise<RoleVO> {
  return request<RoleVO>('/api/roles', { method: 'POST', body: payload })
}

/** PUT /api/roles/{id} —— 修改角色 */
export function updateRole(id: number, payload: RoleUpdatePayload): Promise<RoleVO> {
  return request<RoleVO>(`/api/roles/${id}`, { method: 'PUT', body: payload })
}

/** DELETE /api/roles/{id} —— 停用角色 */
export function disableRole(id: number): Promise<unknown> {
  return request<unknown>(`/api/roles/${id}`, { method: 'DELETE' })
}
