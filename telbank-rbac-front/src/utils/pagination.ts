import type { TablePaginationConfig } from 'antd'

/** 构造 antd Table 统一分页配置，并处理每页条数变化时回到第一页 */
export function createPaginationConfig(
  page: number,
  size: number,
  total: number,
  onChange: (page: number, size: number) => void,
): TablePaginationConfig {
  return {
    current: page,
    pageSize: size,
    total,
    showSizeChanger: true,
    showQuickJumper: true,
    pageSizeOptions: ['10', '20', '50', '100'],
    showTotal: (count) => `共 ${count} 条`,
    onChange: (nextPage, nextSize) => {
      if (nextSize !== size) {
        onChange(1, nextSize)
        return
      }
      onChange(nextPage, nextSize)
    },
  }
}
