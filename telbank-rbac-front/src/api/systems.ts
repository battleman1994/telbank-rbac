import { request, requestPage } from './request'
import type {
  KeywordPageQuery,
  PageResult,
  SysSystemVO,
  SystemCreatePayload,
  SystemUpdatePayload,
} from './types'

/** GET /api/systems —— 系统分页列表（契约 §3.2） */
export function listSystems(params: KeywordPageQuery): Promise<PageResult<SysSystemVO>> {
  return requestPage<SysSystemVO>('/api/systems', { query: { ...params } })
}

/** POST /api/systems —— 新建系统 */
export function createSystem(payload: SystemCreatePayload): Promise<SysSystemVO> {
  return request<SysSystemVO>('/api/systems', { method: 'POST', body: payload })
}

/** PUT /api/systems/{id} —— 修改系统（code 不可改） */
export function updateSystem(id: number, payload: SystemUpdatePayload): Promise<SysSystemVO> {
  return request<SysSystemVO>(`/api/systems/${id}`, { method: 'PUT', body: payload })
}

/** DELETE /api/systems/{id} —— 停用系统（status=0，不物理删除） */
export function disableSystem(id: number): Promise<unknown> {
  return request<unknown>(`/api/systems/${id}`, { method: 'DELETE' })
}
