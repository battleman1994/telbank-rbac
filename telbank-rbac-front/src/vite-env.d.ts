/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 权限中心后端地址，默认 http://localhost:8080 */
  readonly VITE_API_BASE_URL?: string
  /** 管理上下文系统编码（请求头 X-System-Code），默认 rbac */
  readonly VITE_MANAGEMENT_SYSTEM_CODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
