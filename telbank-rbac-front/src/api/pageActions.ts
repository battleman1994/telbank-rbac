import { request, requestPage } from './request'
import type { PageActionListQuery, PageActionPayload, PageActionVO, PageResult } from './types'

/** GET /api/page-actions —— 页面操作元数据分页列表（契约 §3.8） */
export function listPageActions(params: PageActionListQuery): Promise<PageResult<PageActionVO>> {
  return requestPage<PageActionVO>('/api/page-actions', { query: { ...params } })
}

/** POST /api/page-actions —— 新建页面操作元数据 */
export function createPageAction(payload: PageActionPayload): Promise<PageActionVO> {
  return request<PageActionVO>('/api/page-actions', { method: 'POST', body: payload })
}

/** PUT /api/page-actions/{id} —— 更新页面操作元数据 */
export function updatePageAction(id: number, payload: PageActionPayload): Promise<PageActionVO> {
  return request<PageActionVO>(`/api/page-actions/${id}`, { method: 'PUT', body: payload })
}

/** DELETE /api/page-actions/{id} —— 停用 */
export function disablePageAction(id: number): Promise<unknown> {
  return request<unknown>(`/api/page-actions/${id}`, { method: 'DELETE' })
}
