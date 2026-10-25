import { request, requestPage } from './request'
import type {
  AccessApplicationCreatePayload,
  AccessApplicationListQuery,
  AccessApplicationOptionsVO,
  AccessApplicationVO,
  PageResult,
} from './types'

/**
 * GET /api/access-applications/options —— 当前系统可申请角色与审批链接模板。
 * 服务端按「当前系统（X-System-Code）+ 当前用户」过滤：启用、非 admin 角色；
 * owned=true 表示已有效持有（前端置灰不可勾选）。
 */
export function getAccessApplicationOptions(): Promise<AccessApplicationOptionsVO> {
  return request<AccessApplicationOptionsVO>('/api/access-applications/options')
}

/** POST /api/access-applications —— 提交临时权限申请（roleIds 非空、reason 1–200 字） */
export function createAccessApplication(
  payload: AccessApplicationCreatePayload,
): Promise<AccessApplicationVO> {
  return request<AccessApplicationVO>('/api/access-applications', {
    method: 'POST',
    body: payload,
  })
}

/** GET /api/access-applications —— 当前系统 + 当前用户的申请分页（按 id DESC） */
export function getAccessApplications(
  query: AccessApplicationListQuery = {},
): Promise<PageResult<AccessApplicationVO>> {
  return requestPage<AccessApplicationVO>('/api/access-applications', { query: { ...query } })
}

/** GET /api/access-applications/{applicationNo} —— 申请详情（他人申请统一 404） */
export function getAccessApplication(applicationNo: string): Promise<AccessApplicationVO> {
  return request<AccessApplicationVO>(`/api/access-applications/${applicationNo}`)
}
