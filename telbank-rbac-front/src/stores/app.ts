import { useMemo } from 'react'
import { create } from 'zustand'
import { CURRENT_SYSTEM_CODE_KEY, TOKEN_KEY, persistCurrentSystemCode } from '../api/request'
import type { AdminBootstrapVO, MeVO, MySystemVO } from '../api/types'

/**
 * 权限码通配匹配（A 方案）：
 * - permissions 含 `*` → 恒 true；
 * - permissions 含 `前缀:*`（如 `order:*`）→ code 以 `前缀:` 开头即 true；
 * - 否则精确匹配。
 */
export function matchesPermissionCode(permissions: Set<string>, code: string): boolean {
  if (permissions.has('*') || permissions.has(code)) return true
  for (const granted of permissions) {
    if (granted.endsWith(':*')) {
      const prefix = granted.slice(0, -1) // 保留末尾冒号，如 'order:'
      if (code.startsWith(prefix)) return true
    }
  }
  return false
}

/**
 * 客户端全局状态（系统隔离模型）：
 * - token：管理端 JWT，持久化到 localStorage["rbac.token"]
 * - currentUser：GET /api/me 返回的当前用户
 * - systems：GET /api/me/systems 返回的「由有效角色推导」的可访问系统；平台超管为全部启用系统
 * - currentSystemCode：当前系统上下文，持久化到 localStorage["rbac.currentSystemCode"]，
 *   作为所有 /api/** 请求的 X-System-Code
 * - permissions：GET /api/me/bootstrap 返回的「当前系统内」权限码集合（短码，
 *   如 role:manage；平台超管为 ["*"]）
 * - hasPerm(code)：平台超管恒 true；否则判断当前系统权限码集合
 */
export interface AppState {
  token: string | null
  currentUser: MeVO | null
  systems: MySystemVO[]
  currentSystemCode: string | null
  platformAdmin: boolean
  permissions: Set<string>
  setSession: (token: string) => void
  setCurrentUser: (user: MeVO | null) => void
  setSystems: (systems: MySystemVO[]) => void
  setCurrentSystemCode: (code: string | null) => void
  /** 应用 /api/me/bootstrap（currentSystemCode / permissions / platformAdmin） */
  setBootstrap: (bootstrap: AdminBootstrapVO) => void
  hasPerm: (code: string) => boolean
  logout: () => void
}

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string | null): void {
  try {
    if (value) {
      window.localStorage.setItem(key, value)
    } else {
      window.localStorage.removeItem(key)
    }
  } catch {
    // localStorage 不可用时忽略（会话级状态仍可用）
  }
}

export const useAppStore = create<AppState>()((set, get) => ({
  token: readStorage(TOKEN_KEY),
  currentUser: null,
  systems: [],
  currentSystemCode: readStorage(CURRENT_SYSTEM_CODE_KEY),
  platformAdmin: false,
  permissions: new Set<string>(),
  setSession: (token) => {
    writeStorage(TOKEN_KEY, token)
    set({ token })
  },
  setCurrentUser: (user) => {
    set({ currentUser: user, platformAdmin: user?.platformAdmin ?? false })
  },
  setSystems: (systems) => {
    set({ systems })
  },
  setCurrentSystemCode: (code) => {
    persistCurrentSystemCode(code)
    set({ currentSystemCode: code })
  },
  setBootstrap: (bootstrap) => {
    const currentSystemCode = bootstrap.currentSystemCode?.trim() || null
    persistCurrentSystemCode(currentSystemCode)
    set({
      currentSystemCode,
      permissions: new Set(bootstrap.permissions ?? []),
      platformAdmin: bootstrap.platformAdmin,
    })
  },
  hasPerm: (code) => {
    const { permissions, platformAdmin } = get()
    if (platformAdmin) return true
    return matchesPermissionCode(permissions, code)
  },
  logout: () => {
    writeStorage(TOKEN_KEY, null)
    writeStorage(CURRENT_SYSTEM_CODE_KEY, null)
    set({
      token: null,
      currentUser: null,
      systems: [],
      currentSystemCode: null,
      platformAdmin: false,
      permissions: new Set<string>(),
    })
  },
}))

/**
 * 订阅权限码的 React Hook：permissions/platformAdmin 变化时重新计算，
 * 避免直接依赖 store 上稳定引用的 hasPerm 函数导致不重渲染。
 */
export function useHasPerm(): (code: string) => boolean {
  const permissions = useAppStore((state) => state.permissions)
  const platformAdmin = useAppStore((state) => state.platformAdmin)
  return useMemo(
    () => (code: string) => platformAdmin || matchesPermissionCode(permissions, code),
    [permissions, platformAdmin],
  )
}

/** 当前系统上下文对象；未选择或不在列表时返回 undefined */
export function useCurrentSystem(): MySystemVO | undefined {
  return useAppStore((state) => state.systems.find((item) => item.code === state.currentSystemCode))
}

/** 当前系统 id（各类按 systemId 隔离的数据查询使用） */
export function useCurrentSystemId(): number | undefined {
  return useCurrentSystem()?.id
}
