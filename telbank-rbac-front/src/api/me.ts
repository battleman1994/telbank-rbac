import { persistCurrentSystemCode, readCurrentSystemCode, request } from './request'
import type { AdminBootstrapVO, AdminSessionVO, MeVO, MySystemVO } from './types'

/** GET /api/me —— 当前登录用户信息（仅要求已认证） */
export function getMe(token?: string): Promise<MeVO> {
  return request<MeVO>('/api/me', {
    withSystemCode: false,
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/**
 * GET /api/me/systems —— 当前用户可访问系统。
 * 新系统隔离模型：由有效角色（user_role status=1 且时效内、系统启用）推导去重；
 * 平台超管返回全部启用系统。
 */
export function getMySystems(token?: string): Promise<MySystemVO[]> {
  return request<MySystemVO[]>('/api/me/systems', {
    withSystemCode: false,
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/**
 * GET /api/me/bootstrap —— 当前系统上下文：
 * {externalUserId, platformAdmin, currentSystemCode, permissions[], systems[]}。
 * 必须带 X-System-Code: 当前系统（request() 默认从 localStorage 注入）。
 */
export function getBootstrap(token?: string): Promise<AdminBootstrapVO> {
  return request<AdminBootstrapVO>('/api/me/bootstrap', {
    token,
    skipUnauthorizedHandler: token !== undefined,
  })
}

/**
 * 选择当前系统上下文：
 * localStorage["rbac.currentSystemCode"] 若在可访问系统列表内则沿用，否则取第一条。
 */
function pickCurrentSystemCode(systems: MySystemVO[]): string | null {
  if (systems.length === 0) return null
  const storedCode = readCurrentSystemCode()
  if (storedCode && systems.some((system) => system.code === storedCode)) {
    return storedCode
  }
  return systems[0].code
}

/**
 * 管理端会话引导（登录与刷新共用）：
 * 1. GET /api/me → 2. GET /api/me/systems → 选 currentSystemCode 并持久化 →
 * 3. 带 X-System-Code 调 GET /api/me/bootstrap（返回当前系统内权限码）。
 */
export async function loadAdminSession(token?: string): Promise<AdminSessionVO> {
  const me = await getMe(token)
  const systems = await getMySystems(token)
  const selectedSystemCode = pickCurrentSystemCode(systems)
  persistCurrentSystemCode(selectedSystemCode)
  const bootstrap = await getBootstrap(token)
  const currentSystemCode = bootstrap.currentSystemCode?.trim() || selectedSystemCode
  persistCurrentSystemCode(currentSystemCode)
  return {
    me,
    systems,
    bootstrap: { ...bootstrap, currentSystemCode },
    currentSystemCode,
  }
}
