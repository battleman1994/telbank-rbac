/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 权限中心后端地址，默认 http://localhost:8080 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
