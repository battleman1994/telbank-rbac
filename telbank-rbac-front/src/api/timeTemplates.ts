import { request } from './request'
import type {
  TimeTemplateCreatePayload,
  TimeTemplateListQuery,
  TimeTemplateUpdatePayload,
  TimeTemplateVO,
} from './types'

/**
 * GET /api/time-templates —— 全局时间模板列表（按 id DESC）。
 * status=1 只返回启用模板（角色绑定下拉使用）；需要 role:manage（平台超管 `*` 通过）。
 */
export function listTimeTemplates(
  query: TimeTemplateListQuery = {},
): Promise<TimeTemplateVO[]> {
  return request<TimeTemplateVO[]>('/api/time-templates', { query: { ...query } })
}

/** POST /api/time-templates —— 新建时间模板（平台超管） */
export function createTimeTemplate(payload: TimeTemplateCreatePayload): Promise<TimeTemplateVO> {
  return request<TimeTemplateVO>('/api/time-templates', { method: 'POST', body: payload })
}

/** PUT /api/time-templates/{id} —— 修改时间模板（平台超管） */
export function updateTimeTemplate(
  id: number,
  payload: TimeTemplateUpdatePayload,
): Promise<TimeTemplateVO> {
  return request<TimeTemplateVO>(`/api/time-templates/${id}`, { method: 'PUT', body: payload })
}

/** DELETE /api/time-templates/{id} —— 逻辑停用时间模板（平台超管） */
export function disableTimeTemplate(id: number): Promise<unknown> {
  return request<unknown>(`/api/time-templates/${id}`, { method: 'DELETE' })
}
