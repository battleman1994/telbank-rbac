import dayjs from 'dayjs'
import type { TimeTemplateVO } from '../api/types'

/** 统一时间展示格式 */
export const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** 格式化为本地时间字符串；空值返回占位符 */
export function formatDateTime(value?: string | null, placeholder = '-'): string {
  if (!value) return placeholder
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format(DATE_TIME_FORMAT) : String(value)
}

/** 授权时效展示：空 validFrom 表示立即生效，空 validUntil 表示永久有效 */
export function formatValidity(validFrom?: string | null, validUntil?: string | null): string {
  const from = validFrom ? formatDateTime(validFrom) : '立即生效'
  const until = validUntil ? formatDateTime(validUntil) : '永久有效'
  return `${from} ~ ${until}`
}

/**
 * 时间模板窗口摘要：如「当天 19:00 ~ 次日 07:00」。
 * 仅描述模板规则本身，实际绝对窗口在授权写入时按当前时刻计算。
 */
export function describeTimeTemplate(
  template: Pick<TimeTemplateVO, 'startDayOffset' | 'startTime' | 'endDayOffset' | 'endTime'>,
): string {
  const startDay = template.startDayOffset === 0 ? '当天' : `第${template.startDayOffset}天`
  const endDay =
    template.endDayOffset === 0
      ? '当天'
      : template.endDayOffset === 1
        ? '次日'
        : `第${template.endDayOffset}天`
  return `${startDay} ${template.startTime} ~ ${endDay} ${template.endTime}`
}

/** dayjs 值转 ISO-8601 UTC 字符串（提交给后端） */
export function toIsoString(value?: dayjs.Dayjs | null): string | null {
  if (!value || !value.isValid()) return null
  return value.toISOString()
}

/** ISO 字符串转 dayjs 值（DatePicker 回显） */
export function toDayjs(value?: string | null): dayjs.Dayjs | null {
  if (!value) return null
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed : null
}

/** 计算距到期还有多少天（向上取整，最小 1） */
export function daysUntil(value?: string | null): number | null {
  if (!value) return null
  const target = dayjs(value)
  if (!target.isValid()) return null
  const diff = target.diff(dayjs(), 'day', true)
  return Math.max(1, Math.ceil(diff))
}

/** JSON 值转格式化文本（null/undefined 返回占位符） */
export function formatJson(value: unknown, placeholder = '-'): string {
  if (value === null || value === undefined || value === '') return placeholder
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
