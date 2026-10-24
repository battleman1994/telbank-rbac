/**
 * Ant 风格 API 路径校验（与后端契约 §3.4 口径一致，仅做前端提前校验）：
 * - 必须以 / 开头，不能含空白字符；
 * - `*` 匹配单段，必须独占一个路径段（如 /api/*）；
 * - `**` 只能出现在路径末尾，且必须独占最后一段（如 /api/**）；
 * - `{id}` 一类占位符必须成对且非空。
 */

export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'] as const

const ALLOWED_CHARS = /^[A-Za-z0-9_\-./{}*]+$/

/** 校验 Ant 风格路径，合法返回 null，非法返回中文错误信息 */
export function validateAntPattern(pattern?: string | null): string | null {
  const value = (pattern ?? '').trim()
  if (!value) return 'API 路径不能为空'
  if (!value.startsWith('/')) return 'API 路径必须以 / 开头'
  if (/\s/.test(value)) return 'API 路径不能包含空白字符'
  if (!ALLOWED_CHARS.test(value)) {
    return 'API 路径只能包含字母、数字、下划线、连字符、点、斜杠、花括号与通配符'
  }
  if (value.includes('//')) return 'API 路径不能包含连续的 /'

  const segments = value.split('/').slice(1)
  let doubleStarIndex = -1
  for (let index = 0; index < segments.length; index += 1) {
    const segment = segments[index]
    if (segment.includes('*') && segment !== '*' && segment !== '**') {
      return '通配符必须独占一个路径段（* 匹配单段，** 只能出现在末尾）'
    }
    if (segment === '**') {
      if (doubleStarIndex !== -1) return '** 只能出现一次'
      doubleStarIndex = index
    }
    const openBraceCount = (segment.match(/{/g) ?? []).length
    const closeBraceCount = (segment.match(/}/g) ?? []).length
    if (openBraceCount !== closeBraceCount) {
      return `路径段 "${segment}" 中的 {} 占位符未成对出现`
    }
    if (segment.includes('{}')) return '{} 占位符不能为空'
  }

  if (doubleStarIndex !== -1 && doubleStarIndex !== segments.length - 1) {
    return '** 只能出现在路径末尾'
  }
  return null
}

/** method 与 apiPattern 必须同时为空或同时非空（契约 §3.4） */
export function validateMethodAndPattern(
  method?: string | null,
  apiPattern?: string | null,
): string | null {
  const hasMethod = Boolean(method?.trim())
  const hasPattern = Boolean(apiPattern?.trim())
  if (hasMethod && !hasPattern) return '填写 HTTP 方法时必须同时填写 API 路径'
  if (!hasMethod && hasPattern) return '填写 API 路径时必须同时选择 HTTP 方法'
  if (hasPattern) return validateAntPattern(apiPattern)
  return null
}
