import { request } from './request'
import type { MeVO, MySystemVO } from './types'

/** GET /api/me —— 当前登录用户信息（契约 §3.1） */
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
