import { request } from './request'
import type { UserSystemCreatePayload, UserSystemListQuery, UserSystemVO } from './types'

/** GET /api/user-systems —— 查询某用户已开通的系统（契约 §3.7） */
export function listUserSystems(params: UserSystemListQuery): Promise<UserSystemVO[]> {
  return request<UserSystemVO[]>('/api/user-systems', { query: { ...params } })
}

/** POST /api/user-systems —— 开通用户-系统 */
export function createUserSystem(payload: UserSystemCreatePayload): Promise<UserSystemVO> {
  return request<UserSystemVO>('/api/user-systems', { method: 'POST', body: payload })
}

/** DELETE /api/user-systems/{id} —— 停用开通关系 */
export function disableUserSystem(id: number): Promise<unknown> {
  return request<unknown>(`/api/user-systems/${id}`, { method: 'DELETE' })
}
