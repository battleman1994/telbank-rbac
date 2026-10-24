import { request } from './request'
import type { AdminBootstrapVO, AdminSessionVO, MeVO, MySystemVO } from './types'

/** GET /api/me —— 当前登录用户信息（契约 §3.1，仅要求已认证） */
export function getMe(token?: string): Promise<MeVO> {
  return request<MeVO>('/api/me', {
    withSystemCode: false,
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/** GET /api/me/systems —— 当前用户可访问系统（平台超管返回全部启用系统） */
export function getMySystems(token?: string): Promise<MySystemVO[]> {
  return request<MySystemVO[]>('/api/me/systems', {
    withSystemCode: false,
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/**
 * GET /api/me/bootstrap —— 权限中心自身接入 RBAC 的管理上下文（仅要求已认证）：
 * 返回 managementSystemCode、管理权限码集合（平台超管为 ["*"]）与可访问系统列表。
 */
export function getBootstrap(token?: string): Promise<AdminBootstrapVO> {
  return request<AdminBootstrapVO>('/api/me/bootstrap', {
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/**
 * 管理端会话引导：严格按 /api/me → /api/me/systems → /api/me/bootstrap 顺序加载，
 * 登录页与刷新后的 AdminLayout 共用（契约 §3.1 / §10）。
 */
export async function loadAdminSession(token?: string): Promise<AdminSessionVO> {
  const me = await getMe(token)
  const systems = await getMySystems(token)
  const bootstrap = await getBootstrap(token)
  return { me, systems, bootstrap }
}
