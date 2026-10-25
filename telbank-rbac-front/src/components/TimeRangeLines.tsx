import type { ReactNode } from 'react'

/** 时间区间统一展示：开始/结束各占一行，避免长区间在表格里挤成一行换行 */
export function TimeRangeLines({ start, end }: { start: ReactNode; end: ReactNode }) {
  return (
    <div className="time-range-lines">
      <div>
        <span className="time-range-lines__label">开始：</span>
        {start}
      </div>
      <div>
        <span className="time-range-lines__label">结束：</span>
        {end}
      </div>
    </div>
  )
}
