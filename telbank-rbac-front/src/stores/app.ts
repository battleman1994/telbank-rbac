import { useMemo } from 'react'
import { create } from 'zustand'
import {
  DEFAULT_MANAGEMENT_SYSTEM_CODE,
  MANAGEMENT_SYSTEM_CODE_KEY,
  SYSTEM_CODE_KEY,
  TOKEN_KEY,
} from '../api/request'
import type { AdminBootstrapVO, MeVO, MySystemVO } from '../api/types'

/**
 * 客户端全局状态（契约 §10 + 权限中心自接入口径）：
 * - token：管理端 JWT，持久化到 localStorage["rbac.token"]
 * - currentUser：GET /api/me 返回的当前用户
 * - systems：GET /api/me/systems 返回的可访问系统（含管理上下文系统 rbac）
 * - currentSystemCode：业务系统选择，仅用于数据筛选（localStorage["rbac.systemCode"]）
 * - permissions：GET /api/me/bootstrap 返回的管理权限码集合（平台超管为 ["*"]）
 * - managementSystemCode：管理上下文系统编码，作为请求头 X-System-Code（默认 rbac）
 * - hasPerm(code)：平台超管恒 true；否则判断权限码集合
 */
export interface AppState {
  token: string | null
  currentUser: MeVO | null
  systems: MySystemVO[]
  currentSystemCode: string | null
  platformAdmin: boolean
  permissions: Set<string>
  managementSystemCode: string
  setSession: (token: string) => void
  setCurrentUser: (user: MeVO | null) => void
  setSystems: (systems: MySystemVO[]) => void
  setCurrentSystemCode: (code: string | null) => void
  /** 应用 /api/me/bootstrap 返回的管理上下文（权限码 + 管理系统编码） */
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
  currentSystemCode: readStorage(SYSTEM_CODE_KEY),
  platformAdmin: false,
  permissions: new Set<string>(),
  managementSystemCode: readStorage(MANAGEMENT_SYSTEM_CODE_KEY)?.trim() || DEFAULT_MANAGEMENT_SYSTEM_CODE,
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
    writeStorage(SYSTEM_CODE_KEY, code)
    set({ currentSystemCode: code })
  },
  setBootstrap: (bootstrap) => {
    const managementSystemCode =
      bootstrap.managementSystemCode?.trim() || DEFAULT_MANAGEMENT_SYSTEM_CODE
    writeStorage(MANAGEMENT_SYSTEM_CODE_KEY, managementSystemCode)
    set({
      permissions: new Set(bootstrap.permissions ?? []),
      managementSystemCode,
      platformAdmin: bootstrap.platformAdmin,
    })
  },
  hasPerm: (code) => {
    const { permissions, platformAdmin } = get()
    if (platformAdmin || permissions.has('*')) return true
    return permissions.has(code)
  },
  logout: () => {
    writeStorage(TOKEN_KEY, null)
    writeStorage(SYSTEM_CODE_KEY, null)
    writeStorage(MANAGEMENT_SYSTEM_CODE_KEY, null)
    set({
      token: null,
      currentUser: null,
      systems: [],
      currentSystemCode: null,
      platformAdmin: false,
      permissions: new Set<string>(),
      managementSystemCode: DEFAULT_MANAGEMENT_SYSTEM_CODE,
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
    () => (code: string) => platformAdmin || permissions.has('*') || permissions.has(code),
    [permissions, platformAdmin],
  )
}

/** 当前选中业务系统对象；未选择或不存在时返回 undefined */
export function useCurrentSystem(): MySystemVO | undefined {
  return useAppStore((state) => state.systems.find((item) => item.code === state.currentSystemCode))
}

/** 当前选中业务系统 id（各类按 systemId 隔离的数据查询使用） */
export function useCurrentSystemId(): number | undefined {
  return useCurrentSystem()?.id
}

/** 业务系统列表：排除管理上下文系统（默认 rbac），供系统切换器/数据筛选使用 */
export function useBusinessSystems(): MySystemVO[] {
  const systems = useAppStore((state) => state.systems)
  const managementSystemCode = useAppStore((state) => state.managementSystemCode)
  return useMemo(
    () => systems.filter((system) => system.code !== managementSystemCode),
    [systems, managementSystemCode],
  )
}
