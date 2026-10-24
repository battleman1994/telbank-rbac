import { requestPage } from './request'
import type {
  ApprovalGrantListQuery,
  ApprovalGrantVO,
  PageResult,
} from './types'

/** GET /api/approval-grants —— 审批授权记录（契约 §3.10） */
export function listApprovalGrants(
  params: ApprovalGrantListQuery,
): Promise<PageResult<ApprovalGrantVO>> {
  return requestPage<ApprovalGrantVO>('/api/approval-grants', { query: { ...params } })
}
