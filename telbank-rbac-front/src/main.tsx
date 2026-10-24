import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { AppErrorBoundary } from './components/AppErrorBoundary'
import { AppNotifier } from './components/AppNotifier'
import { router } from './router'
import { RouterProvider } from 'react-router'
import './styles/global.css'

dayjs.locale('zh-cn')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 15_000,
    },
    mutations: {
      retry: 0,
    },
  },
})

const themeConfig = {
  token: {
    colorPrimary: '#1668dc',
    borderRadius: 6,
    fontSize: 14,
  },
}

function Root() {
  return (
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <AntApp>
        <AppErrorBoundary>
          <QueryClientProvider client={queryClient}>
            <AppNotifier />
            <RouterProvider router={router} />
          </QueryClientProvider>
        </AppErrorBoundary>
      </AntApp>
    </ConfigProvider>
  )
}

const container = document.getElementById('root')
if (!container) {
  throw new Error('未找到 #root 挂载节点')
}

createRoot(container).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
