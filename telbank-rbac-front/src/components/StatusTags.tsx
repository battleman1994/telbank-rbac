import { Tag } from 'antd'
import type { ApprovalGrantKind, PageActionKind, PermissionType } from '../api/types'

/** 启用/停用状态标签 */
export function StatusTag({ status }: { status: number }) {
  return status === 1 ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>
}

/** 权限资源类型标签：1 目录/页面级、2 操作级、3 纯接口 */
export function PermissionTypeTag({ type }: { type: PermissionType | number }) {
  switch (type) {
    case 1:
      return <Tag color="blue">目录/页面级</Tag>
    case 2:
      return <Tag color="purple">操作级</Tag>
    case 3:
      return <Tag color="cyan">纯接口</Tag>
    default:
      return <Tag>{type}</Tag>
  }
}

/** is_public 标签 */
export function PublicTag({ isPublic }: { isPublic: number | boolean }) {
  return isPublic ? <Tag color="green">公开</Tag> : <Tag>受限</Tag>
}

/** 审批授权类型：用户-角色 / 角色-权限 */
export function ApprovalKindTag({ kind }: { kind: ApprovalGrantKind }) {
  return kind === 'USER_ROLE' ? (
    <Tag color="geekblue">用户-角色</Tag>
  ) : (
    <Tag color="magenta">角色-权限</Tag>
  )
}

const PAGE_ACTION_LABELS: Record<PageActionKind, string> = {
  openDialog: '打开弹窗',
  callApi: '调用接口',
  download: '下载',
  navigate: '跳转',
  confirmThenCallApi: '二次确认后调接口',
}

/** 页面操作 action 标签 */
export function PageActionTag({ action }: { action: PageActionKind | string }) {
  const label = PAGE_ACTION_LABELS[action as PageActionKind] ?? action
  return <Tag color="geekblue">{label}</Tag>
}

/** 是否默认系统标签 */
export function DefaultTag({ isDefault }: { isDefault: number | boolean }) {
  return isDefault ? <Tag color="gold">默认</Tag> : <Tag>非默认</Tag>
}
