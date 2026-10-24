import { request } from './request'
import type { SystemCredentialVO } from './types'

/**
 * GET /api/system-credentials/current —— 当前系统（X-System-Code）的接入凭据。
 * 需要权限 `system:credential`（admin 角色经通配 `*` 通过；平台超管短路）。
 */
export function getCurrentSystemCredential(): Promise<SystemCredentialVO> {
  return request<SystemCredentialVO>('/api/system-credentials/current')
}

/**
 * GET /api/system-credentials/{systemId} —— 平台超管按系统 id 查看完整凭据
 * （仅用于系统接入页「查看密钥」，超管专属）。
 */
export function getSystemCredential(systemId: number): Promise<SystemCredentialVO> {
  return request<SystemCredentialVO>(`/api/system-credentials/${systemId}`)
}
