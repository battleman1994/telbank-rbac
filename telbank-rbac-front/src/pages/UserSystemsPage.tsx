import { useState } from 'react'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { createUserSystem, disableUserSystem, listUserSystems } from '../api'
import type { UserSystemCreatePayload, UserSystemVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { DefaultTag, StatusTag } from '../components/StatusTags'
import { useAppStore } from '../stores/app'

const { Text } = Typography

interface UserSystemFormValues {
  externalUserId: string
  systemId: number
  isDefault: boolean
}

/**
 * 用户-系统开通（契约 §3.7）。
 * 用户可访问系统由权限中心维护：GET /api/user-systems?externalUserId=…
 */
export default function UserSystemsPage() {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const systems = useAppStore((state) => state.systems)
  const [searchInput, setSearchInput] = useState('')
  const [externalUserId, setExternalUserId] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<UserSystemFormValues>()

  const userSystemsQuery = useQuery({
    queryKey: ['user-systems', externalUserId],
    queryFn: () => listUserSystems({ externalUserId }),
    enabled: externalUserId.trim().length > 0,
  })

  const createMutation = useMutation({
    mutationFn: (values: UserSystemFormValues) => {
      const payload: UserSystemCreatePayload = {
        systemId: values.systemId,
        externalUserId: values.externalUserId.trim(),
        isDefault: Boolean(values.isDefault),
      }
      return createUserSystem(payload)
    },
    onSuccess: () => {
      void message.success('用户-系统开通成功')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['user-systems'] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disableUserSystem(id),
    onSuccess: () => {
      void message.success('已停用该用户-系统开通关系')
      void queryClient.invalidateQueries({ queryKey: ['user-systems'] })
    },
  })

  function handleSearch() {
    const value = searchInput.trim()
    if (!value) {
      void message.warning('请输入 external_user_id')
      return
    }
    setExternalUserId(value)
  }

  function openCreateModal() {
    form.resetFields()
    form.setFieldsValue({
      externalUserId: externalUserId || searchInput.trim(),
      systemId: systems[0]?.id,
      isDefault: false,
    })
    setModalOpen(true)
  }

  const columns: TableProps<UserSystemVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: 'external_user_id',
      dataIndex: 'externalUserId',
      width: 220,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    {
      title: '系统',
      key: 'system',
      width: 220,
      render: (_, record) => (
        <Space size={4}>
          <Tag color="geekblue">{record.systemCode}</Tag>
          <Text type="secondary">systemId：{record.systemId}</Text>
        </Space>
      ),
    },
    {
      title: '是否默认',
      dataIndex: 'isDefault',
      width: 110,
      render: (value: number) => <DefaultTag isDefault={value === 1} />,
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    {
      title: '操作',
      key: 'action',
      width: 110,
      fixed: 'right',
      render: (_, record) => (
        <Popconfirm
          title="停用用户-系统开通"
          description={`确认停用用户「${record.externalUserId}」对系统「${record.systemCode}」的开通关系吗？`}
          okText="确认停用"
          cancelText="取消"
          onConfirm={() => disableMutation.mutate(record.id)}
        >
          <Button type="link" size="small" danger disabled={record.status === 0}>
            停用
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <PageShell
      title="用户-系统开通"
      description="维护外部用户可访问的业务系统（sys_user_system）；用户登录后 /api/me/systems 返回其已开通系统。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void userSystemsQuery.refetch()} disabled={!externalUserId}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            开通系统
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="输入 external_user_id 查询已开通系统"
              style={{ width: 360 }}
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onPressEnter={handleSearch}
            />
            <Button type="primary" onClick={handleSearch}>
              查询
            </Button>
          </Space>

          {userSystemsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="用户系统列表加载失败"
              description={userSystemsQuery.error.message}
              action={
                <Button size="small" onClick={() => void userSystemsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          {!externalUserId ? (
            <Alert
              type="info"
              showIcon
              title="请输入 external_user_id 后查询"
              description="GET /api/user-systems?externalUserId=…"
            />
          ) : (
            <Table<UserSystemVO>
              rowKey="id"
              columns={columns}
              dataSource={userSystemsQuery.data ?? []}
              loading={userSystemsQuery.isLoading || userSystemsQuery.isFetching}
              scroll={{ x: 860 }}
              pagination={false}
              locale={{ emptyText: `用户 ${externalUserId} 暂未开通任何业务系统` }}
            />
          )}
        </Flex>
      </Card>

      <Modal
        title="开通用户-系统"
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={createMutation.isPending}
        okText="确认开通"
        cancelText="取消"
        width={520}
      >
        <Form<UserSystemFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => createMutation.mutate(values)}
        >
          <Form.Item
            label="external_user_id"
            name="externalUserId"
            rules={[{ required: true, message: '请输入 external_user_id' }]}
          >
            <Input placeholder="如 13800138000" maxLength={255} />
          </Form.Item>
          <Form.Item
            label="业务系统"
            name="systemId"
            rules={[{ required: true, message: '请选择业务系统' }]}
          >
            <Select
              placeholder="请选择业务系统"
              options={systems.map((system) => ({
                value: system.id,
                label: `${system.name}（${system.code}）`,
              }))}
              notFoundContent="当前账号无权访问任何系统"
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item
            label="设为默认系统"
            name="isDefault"
            valuePropName="checked"
            tooltip="用户登录后默认进入的系统；每个用户建议仅一个默认系统"
          >
            <Switch checkedChildren="默认" unCheckedChildren="非默认" />
          </Form.Item>
        </Form>
      </Modal>
      <Text type="secondary" style={{ fontSize: 12 }}>
        说明：本页开通关系仅影响 /api/me/systems 返回结果，不授予任何权限码；权限仍需通过角色授权获得。
      </Text>
    </PageShell>
  )
}
