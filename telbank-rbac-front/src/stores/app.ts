import { create } from 'zustand'
import { SYSTEM_CODE_KEY, TOKEN_KEY } from '../api/request'
import type { MeVO, MySystemVO } from '../api/types'

/**
 * 客户端全局状态（契约 §10）：
 * - token：管理端 JWT，持久化到 localStorage["rbac.token"]
 * - currentUser：GET /api/me 返回的当前用户
 * - systems：GET /api/me/systems 返回的可访问系统
 * - currentSystemCode：当前系统编码，持久化到 localStorage["rbac.systemCode"]
 */
export interface AppState {
  token: string | null
  currentUser: MeVO | null
  systems: MySystemVO[]
  currentSystemCode: string | null
  platformAdmin: boolean
  setSession: (token: string) => void
  setCurrentUser: (user: MeVO | null) => void
  setSystems: (systems: MySystemVO[]) => void
  setCurrentSystemCode: (code: string | null) => void
  logout: () => void
}

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

export const useAppStore = create<AppState>()((set) => ({
  token: readStorage(TOKEN_KEY),
  currentUser: null,
  systems: [],
  currentSystemCode: readStorage(SYSTEM_CODE_KEY),
  platformAdmin: false,
  setSession: (token) => {
    window.localStorage.setItem(TOKEN_KEY, token)
    set({ token })
  },
  setCurrentUser: (user) => {
    set({ currentUser: user, platformAdmin: user?.platformAdmin ?? false })
  },
  setSystems: (systems) => {
    set({ systems })
  },
  setCurrentSystemCode: (code) => {
    if (code) {
      window.localStorage.setItem(SYSTEM_CODE_KEY, code)
    } else {
      window.localStorage.removeItem(SYSTEM_CODE_KEY)
    }
    set({ currentSystemCode: code })
  },
  logout: () => {
    window.localStorage.removeItem(TOKEN_KEY)
    window.localStorage.removeItem(SYSTEM_CODE_KEY)
    set({
      token: null,
      currentUser: null,
      systems: [],
      currentSystemCode: null,
      platformAdmin: false,
    })
  },
}))

/** 当前选中系统对象；未选择或不存在时返回 undefined */
export function useCurrentSystem(): MySystemVO | undefined {
  return useAppStore((state) => state.systems.find((item) => item.code === state.currentSystemCode))
}

/** 当前选中系统 id（各类按 systemId 隔离的查询使用） */
export function useCurrentSystemId(): number | undefined {
  return useCurrentSystem()?.id
}
