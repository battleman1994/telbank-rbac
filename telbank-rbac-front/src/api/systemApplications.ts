import { request, requestPage } from './request'
import type {
  PageResult,
  SystemApplicationApprovePayload,
  SystemApplicationListQuery,
  SystemApplicationRejectPayload,
  SystemApplicationVO,
  SystemApproveResultVO,
} from './types'

/**
 * GET /api/system-applications —— 系统接入申请分页列表（平台超管专属，A16）：
 * status=PENDING/APPROVED/REJECTED，缺省返回全部状态。
 */
export function listSystemApplications(
  params: SystemApplicationListQuery,
): Promise<PageResult<SystemApplicationVO>> {
  return requestPage<SystemApplicationVO>('/api/system-applications', { query: { ...params } })
}

/** POST /api/system-applications/{id}/approve —— 审核通过：创建业务系统并回填 systemId */
export function approveSystemApplication(
  id: number,
  payload: SystemApplicationApprovePayload,
): Promise<SystemApproveResultVO> {
  return request<SystemApproveResultVO>(`/api/system-applications/${id}/approve`, {
    method: 'POST',
    body: payload,
  })
}

/** POST /api/system-applications/{id}/reject —— 驳回申请（原因必填） */
export function rejectSystemApplication(
  id: number,
  payload: SystemApplicationRejectPayload,
): Promise<SystemApplicationVO> {
  return request<SystemApplicationVO>(`/api/system-applications/${id}/reject`, {
    method: 'POST',
    body: payload,
  })
}
