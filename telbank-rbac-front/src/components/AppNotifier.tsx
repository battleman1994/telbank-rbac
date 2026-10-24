import { useEffect } from 'react'
import { App } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { setApiErrorNotifier, setUnauthorizedHandler } from '../api/request'
import { useAppStore } from '../stores/app'
import { router } from '../router'

/**
 * 将 antd message 与全局 401 处理注入到 fetch 封装（request.ts）。
 * 必须位于 <App> 与 QueryClientProvider 之内。
 */
export function AppNotifier() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()

  useEffect(() => {
    setApiErrorNotifier((content) => {
      void message.error(content)
    })
  }, [message])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      useAppStore.getState().logout()
      queryClient.clear()
      if (window.location.pathname !== '/login') {
        void router.navigate('/login', { replace: true })
      }
    })
  }, [queryClient])

  return null
}
