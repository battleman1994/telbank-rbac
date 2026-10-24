import { request, requestPage } from './request'
import type { PageResult, SdkEndpointListQuery, SdkEndpointVO } from './types'

/** GET /api/sdk-endpoints —— SDK 端点上报分页列表（契约 §3.9） */
export function listSdkEndpoints(params: SdkEndpointListQuery): Promise<PageResult<SdkEndpointVO>> {
  return requestPage<SdkEndpointVO>('/api/sdk-endpoints', { query: { ...params } })
}

/**
 * PUT /api/sdk-endpoints/{id}/link —— 关联权限码：
 * 服务端把该端点的 method/path 写入权限码的 method/api_pattern，并标记端点已关联。
 */
export function linkSdkEndpoint(id: number, permissionId: number): Promise<unknown> {
  return request<unknown>(`/api/sdk-endpoints/${id}/link`, {
    method: 'PUT',
    body: { permissionId },
  })
}

/** PUT /api/sdk-endpoints/{id}/unlink —— 解除关联（不改权限码本身） */
export function unlinkSdkEndpoint(id: number): Promise<unknown> {
  return request<unknown>(`/api/sdk-endpoints/${id}/unlink`, { method: 'PUT' })
}
