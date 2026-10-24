/** 表单下拉选项与常量（与契约字段口径一致） */

export const STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
]

export const PERMISSION_TYPE_OPTIONS = [
  { value: 1, label: '目录/页面级' },
  { value: 2, label: '操作级' },
  { value: 3, label: '纯接口' },
]

export const HTTP_METHOD_OPTIONS = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' },
  { value: 'PATCH', label: 'PATCH' },
]

export const PAGE_ACTION_OPTIONS = [
  { value: 'openDialog', label: 'openDialog（打开弹窗）' },
  { value: 'callApi', label: 'callApi（调用接口）' },
  { value: 'download', label: 'download（下载）' },
  { value: 'navigate', label: 'navigate（跳转）' },
  { value: 'confirmThenCallApi', label: 'confirmThenCallApi（二次确认后调接口）' },
]

export const YES_NO_OPTIONS = [
  { value: 1, label: '是' },
  { value: 0, label: '否' },
]
