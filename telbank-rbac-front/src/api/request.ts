import type { ApiEnvelope, PageResult } from './types'

/**
 * 原生 fetch 封装（契约 §1 / §10）：
 * - 自动注入 `Authorization: Bearer <token>`（localStorage rbac.token）
 * - 系统级接口自动注入 `X-System-Code`（localStorage rbac.systemCode）
 * - 解析信封 `{code,message,data}`：HTTP 非 2xx 或 code !== 0 时抛 ApiError
 * - 失败时通过 antd message 统一提示（由 AppNotifier 注入）
 * - 401 时清空本地会话并触发未认证处理器
 */

export const TOKEN_KEY = 'rbac.token'
export const SYSTEM_CODE_KEY = 'rbac.systemCode'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '')

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: HttpMethod
  /** URL 查询参数；值为 undefined/null/'' 时忽略 */
  query?: Record<string, QueryValue>
  /** 请求体，自动 JSON 序列化 */
  body?: unknown
  /** 是否注入 X-System-Code，默认 true（管理端接口默认需要） */
  withSystemCode?: boolean
  /** 显式指定 token；不传则读取 localStorage */
  token?: string
  /** 是否弹 antd 错误提示，默认 true */
  notifyError?: boolean
  /** 401 时是否跳过全局未认证处理（登录校验场景使用） */
  skipUnauthorizedHandler?: boolean
}

/** 统一的 API 异常 */
export class ApiError extends Error {
  /** HTTP 状态码；网络错误或无法解析时为 0 */
  readonly status: number
  /** 业务错误码（信封中的 code）；未知时为 -1 */
  readonly code: number

  constructor(status: number, code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

type ErrorNotifier = (message: string) => void
type UnauthorizedHandler = () => void

let errorNotifier: ErrorNotifier = (message) => {
  // 未注入 antd message 时的兜底（例如单元测试环境）
  console.error('[api]', message)
}

let unauthorizedHandler: UnauthorizedHandler = () => {
  // 未注入全局处理器时的兜底
  window.localStorage.removeItem(TOKEN_KEY)
  window.localStorage.removeItem(SYSTEM_CODE_KEY)
}

/** 由 AppNotifier 注入 antd message.error */
export function setApiErrorNotifier(notifier: ErrorNotifier): void {
  errorNotifier = notifier
}

/** 由 AppNotifier 注入 401 跳转登录逻辑 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function readSystemCode(): string | null {
  try {
    return window.localStorage.getItem(SYSTEM_CODE_KEY)
  } catch {
    return null
  }
}

function buildQueryString(query?: Record<string, QueryValue>): string {
  if (!query) return ''
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    params.append(key, String(value))
  })
  const queryString = params.toString()
  return queryString ? `?${queryString}` : ''
}

async function parseResponseBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return undefined
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { code?: unknown }).code === 'number' &&
    'message' in (value as object)
  )
}

function extractMessage(value: unknown, fallback: string): string {
  if (isEnvelope(value) && value.message) return value.message
  if (typeof value === 'string' && value.trim() && value.trim().length < 500) return value.trim()
  return fallback
}

/**
 * 发起一次管理端 API 请求并返回信封中的 data。
 * 失败时抛出 ApiError，并默认通过 antd message 提示。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    query,
    body,
    withSystemCode = true,
    notifyError = true,
    skipUnauthorizedHandler = false,
  } = options

  const token = options.token ?? readToken()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (withSystemCode) {
    const systemCode = readSystemCode()
    if (systemCode) headers['X-System-Code'] = systemCode
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const url = `${API_BASE_URL}${path}${buildQueryString(query)}`
  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    const error = new ApiError(0, 0, `无法连接权限中心后端（${API_BASE_URL}），请确认服务已启动`)
    if (notifyError) errorNotifier(error.message)
    throw error
  }

  const parsed = await parseResponseBody(response)

  if (!response.ok) {
    const message = extractMessage(parsed, `请求失败（HTTP ${response.status}）`)
    const code = isEnvelope(parsed) ? parsed.code : response.status
    const error = new ApiError(response.status, code, message)
    if (response.status === 401 && !skipUnauthorizedHandler) {
      window.localStorage.removeItem(TOKEN_KEY)
      window.localStorage.removeItem(SYSTEM_CODE_KEY)
      unauthorizedHandler()
    }
    if (notifyError) errorNotifier(message)
    throw error
  }

  if (!isEnvelope(parsed)) {
    const error = new ApiError(response.status, -1, '响应格式不符合 {code,message,data} 约定')
    if (notifyError) errorNotifier(error.message)
    throw error
  }

  if (parsed.code !== 0) {
    const error = new ApiError(response.status, parsed.code, parsed.message || '业务处理失败')
    if (notifyError) errorNotifier(error.message)
    throw error
  }

  return parsed.data as T
}

/** 分页请求快捷方法 */
export function requestPage<T>(
  path: string,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<PageResult<T>> {
  return request<PageResult<T>>(path, options)
}

export { API_BASE_URL }
