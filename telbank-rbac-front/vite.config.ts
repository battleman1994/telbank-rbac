import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 权限中心管理前端 Vite 配置
// 后端地址通过 VITE_API_BASE_URL 注入（默认 http://localhost:8080）
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 2000,
  },
})
