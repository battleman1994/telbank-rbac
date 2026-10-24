import { request, requestPage } from './request'
import type {
  ApprovalGrantListQuery,
  ApprovalGrantVO,
  ExpiringGrantListQuery,
  PageResult,
} from './types'

/** GET /api/approval-grants —— 审批授权记录（契约 §3.10） */
export function listApprovalGrants(
  params: ApprovalGrantListQuery,
): Promise<PageResult<ApprovalGrantVO>> {
  return requestPage<ApprovalGrantVO>('/api/approval-grants', { query: { ...params } })
}

/** GET /api/expiring-grants —— 即将到期授权（按 valid_until 升序） */
export function listExpiringGrants(params: ExpiringGrantListQuery = {}): Promise<ApprovalGrantVO[]> {
  return request<ApprovalGrantVO[]>('/api/expiring-grants', { query: { ...params } })
}
