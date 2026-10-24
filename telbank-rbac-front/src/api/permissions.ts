import { request } from './request'
import type { PermissionNode, PermissionSavePayload } from './types'

/** GET /api/permissions —— 权限资源树（契约 §3.4） */
export function listPermissionTree(systemId: number): Promise<PermissionNode[]> {
  return request<PermissionNode[]>('/api/permissions', { query: { systemId } })
}

/** GET /api/permissions/{id} —— 单节点 */
export function getPermission(id: number): Promise<PermissionNode> {
  return request<PermissionNode>(`/api/permissions/${id}`)
}

/** POST /api/permissions —— 新建权限资源，(systemId,code) 唯一 */
export function createPermission(payload: PermissionSavePayload): Promise<PermissionNode> {
  return request<PermissionNode>('/api/permissions', { method: 'POST', body: payload })
}

/** PUT /api/permissions/{id} —— 修改权限资源（code 可改但需系统内唯一） */
export function updatePermission(
  id: number,
  payload: PermissionSavePayload,
): Promise<PermissionNode> {
  return request<PermissionNode>(`/api/permissions/${id}`, { method: 'PUT', body: payload })
}

/** DELETE /api/permissions/{id} —— 停用（有子节点时后端返回 400） */
export function disablePermission(id: number): Promise<unknown> {
  return request<unknown>(`/api/permissions/${id}`, { method: 'DELETE' })
}
