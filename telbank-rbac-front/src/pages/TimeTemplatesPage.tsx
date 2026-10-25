import { useState } from 'react'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import {
  Alert,
  App as AntApp,
  AutoComplete,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  TimePicker,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import {
  createTimeTemplate,
  disableTimeTemplate,
  listTimeTemplates,
  updateTimeTemplate,
} from '../api'
import type {
  TimeTemplateCreatePayload,
  TimeTemplateUpdatePayload,
  TimeTemplateVO,
} from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { describeTimeTemplate } from '../utils/format'
import { STATUS_OPTIONS } from '../utils/labels'

const { Text } = Typography

const DEFAULT_TIMEZONE = 'Asia/Shanghai'

/** 常用 IANA 时区（可自由输入其它时区） */
const COMMON_TIMEZONES = [
  'Asia/Shanghai',
  'Asia/Hong_Kong',
  'Asia/Tokyo',
  'Asia/Singapore',
  'UTC',
  'Europe/London',
  'America/New_York',
].map((value) => ({ value }))

type StatusFilter = 'ALL' | 1 | 0

const STATUS_FILTER_OPTIONS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
]

interface TimeTemplateFormValues {
  name: string
  timezone: string
  startDayOffset: number
  startTime: Dayjs
  endDayOffset: number
  endTime: Dayjs
  status: number
  remark?: string
}

/** HH:mm → dayjs 值（用于 TimePicker 回显，只关心时分） */
function timeToDayjs(value: string): Dayjs {
  const [hour, minute] = value.split(':').map((part) => Number(part))
  return dayjs().hour(Number.isFinite(hour) ? hour : 0).minute(Number.isFinite(minute) ? minute : 0).second(0)
}

/** dayjs 值 → HH:mm 字符串（提交给后端） */
function dayjsToTime(value: Dayjs): string {
  return value.format('HH:mm')
}

/** 将「天数偏移 + HH:mm」折算为分钟数，用于比较窗口先后 */
function toAbsoluteMinutes(offset: number, time: Dayjs): number {
  return offset * 24 * 60 + time.hour() * 60 + time.minute()
}

/** 天数偏移展示：0 当天、1 次日、其余第 N 天 */
function dayOffsetLabel(offset: number): string {
  if (offset === 0) return '当天'
  if (offset === 1) return '次日'
  return `第${offset}天`
}

/** 窗口起止描述（如 当天 19:00 / 次日 07:00） */
function windowPointLabel(offset: number, time: string): string {
  return `${dayOffsetLabel(offset)} ${time}`
}

/**
 * 时间模板（A21）：全局共享（不按系统隔离），仅平台超管维护。
 * 模板不保存绝对时间，只在「用户角色」分配/审批通过时按模板规则计算具体授权窗口。
 */
export default function TimeTemplatesPage() {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<TimeTemplateVO | null>(null)
  const [form] = Form.useForm<TimeTemplateFormValues>()

  const templatesQuery = useQuery({
    queryKey: ['time-templates', statusFilter],
    queryFn: () =>
      listTimeTemplates({ status: statusFilter === 'ALL' ? undefined : statusFilter }),
  })

  const saveMutation = useMutation({
    mutationFn: (values: TimeTemplateFormValues) => {
      const payload: TimeTemplateCreatePayload = {
        name: values.name.trim(),
        timezone: values.timezone.trim() || DEFAULT_TIMEZONE,
        startDayOffset: values.startDayOffset,
        startTime: dayjsToTime(values.startTime),
        endDayOffset: values.endDayOffset,
        endTime: dayjsToTime(values.endTime),
        status: values.status,
        remark: values.remark?.trim() || undefined,
      }
      const updatePayload: TimeTemplateUpdatePayload = { ...payload }
      return editing
        ? updateTimeTemplate(editing.id, updatePayload)
        : createTimeTemplate(payload)
    },
    onSuccess: () => {
      void message.success(editing ? '时间模板已更新' : '时间模板已创建')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['time-templates'] })
      // 角色页/申请页的模板下拉同样依赖模板数据
      void queryClient.invalidateQueries({ queryKey: ['roles'] })
      void queryClient.invalidateQueries({ queryKey: ['access-application-options'] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disableTimeTemplate(id),
    onSuccess: () => {
      void message.success('时间模板已停用')
      void queryClient.invalidateQueries({ queryKey: ['time-templates'] })
      void queryClient.invalidateQueries({ queryKey: ['roles'] })
    },
  })

  function openCreateModal() {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({
      timezone: DEFAULT_TIMEZONE,
      startDayOffset: 0,
      startTime: timeToDayjs('19:00'),
      endDayOffset: 1,
      endTime: timeToDayjs('07:00'),
      status: 1,
    })
    setModalOpen(true)
  }

  function openEditModal(record: TimeTemplateVO) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      name: record.name,
      timezone: record.timezone || DEFAULT_TIMEZONE,
      startDayOffset: record.startDayOffset,
      startTime: timeToDayjs(record.startTime),
      endDayOffset: record.endDayOffset,
      endTime: timeToDayjs(record.endTime),
      status: record.status,
      remark: record.remark ?? undefined,
    })
    setModalOpen(true)
  }

  const columns: TableProps<TimeTemplateVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '模板名称', dataIndex: 'name', width: 190 },
    { title: '时区', dataIndex: 'timezone', width: 150 },
    {
      title: '窗口（审批/分配时计算）',
      key: 'window',
      width: 240,
      render: (_, record) => (
        <Space size={4} wrap>
          <Text>{describeTimeTemplate(record)}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            （{windowPointLabel(record.startDayOffset, record.startTime)} →{' '}
            {windowPointLabel(record.endDayOffset, record.endTime)}）
          </Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (value: string | null) => value || '-' },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用时间模板"
            description={`确认停用模板「${record.name}」吗？停用后已绑定该模板的角色将无法再授权/审批。`}
            okText="确认停用"
            cancelText="取消"
            onConfirm={() => disableMutation.mutate(record.id)}
          >
            <Button type="link" size="small" danger disabled={record.status === 0}>
              停用
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <PageShell
      title="时间模板"
      description="全局时间模板（不按系统隔离）：定义相对授权时刻的开始/结束窗口；角色绑定模板后，用户角色分配与自助申请审批通过时按模板计算绝对时效。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void templatesQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建模板
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        title="时间语义上移：角色-权限为静态授权，时间窗口由「用户-角色」按模板计算"
        description="模板本身不保存绝对时间；开始/结束偏移相对锚点日期（0=当天、1=次日），锚点为分配/审批通过的时刻。是否跨夜、是否覆盖周末均由模板结果自然决定。"
      />

      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Select<StatusFilter>
              style={{ width: 160 }}
              value={statusFilter}
              options={STATUS_FILTER_OPTIONS}
              onChange={setStatusFilter}
            />
            <Text type="secondary">模板全局共享，所有系统管理员在角色管理页可读取启用模板。</Text>
          </Space>

          {templatesQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="时间模板列表加载失败"
              description={templatesQuery.error.message}
              action={
                <Button size="small" onClick={() => void templatesQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Table<TimeTemplateVO>
            rowKey="id"
            columns={columns}
            dataSource={templatesQuery.data ?? []}
            loading={templatesQuery.isLoading || templatesQuery.isFetching}
            pagination={false}
            locale={{ emptyText: '暂无时间模板，点击右上角「新建模板」创建' }}
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑时间模板：${editing.name}` : '新建时间模板'}
        open={modalOpen}
        forceRender
        width={720}
        okText="保存"
        cancelText="取消"
        confirmLoading={saveMutation.isPending}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
      >
        <Form<TimeTemplateFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          <Row gutter={16}>
            <Col span={14}>
              <Form.Item
                label="模板名称"
                name="name"
                rules={[
                  { required: true, whitespace: true, message: '请输入模板名称' },
                  { max: 100, message: '模板名称不能超过 100 字' },
                ]}
              >
                <Input placeholder="如 夜间窗口" maxLength={100} />
              </Form.Item>
            </Col>
            <Col span={10}>
              <Form.Item
                label="时区（IANA）"
                name="timezone"
                rules={[{ required: true, message: '请输入时区' }]}
                tooltip="如 Asia/Shanghai；默认 Asia/Shanghai"
              >
                <AutoComplete
                  options={COMMON_TIMEZONES}
                  placeholder="Asia/Shanghai"
                  filterOption={(input, option) =>
                    (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="开始偏移（天）"
                name="startDayOffset"
                rules={[{ required: true, message: '请输入开始天数偏移' }]}
                tooltip="相对锚点日期的天数，0 表示当天"
              >
                <InputNumber min={0} max={3650} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="开始时间（HH:mm）"
                name="startTime"
                rules={[{ required: true, message: '请选择开始时间' }]}
              >
                <TimePicker format="HH:mm" style={{ width: '100%' }} placeholder="HH:mm" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="结束偏移（天）"
                name="endDayOffset"
                rules={[{ required: true, message: '请输入结束天数偏移' }]}
                tooltip="相对锚点日期的天数，1 表示次日"
              >
                <InputNumber min={0} max={3650} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="结束时间（HH:mm）"
                name="endTime"
                dependencies={['startDayOffset', 'startTime']}
                rules={[
                  { required: true, message: '请选择结束时间' },
                  ({ getFieldValue }) => ({
                    validator(_, value: Dayjs | undefined) {
                      const start = getFieldValue('startTime') as Dayjs | undefined
                      const startOffset = getFieldValue('startDayOffset') as number | undefined
                      const endOffset = getFieldValue('endDayOffset') as number | undefined
                      if (!value || !start || startOffset === undefined || endOffset === undefined) {
                        return Promise.resolve()
                      }
                      if (toAbsoluteMinutes(endOffset, value) <= toAbsoluteMinutes(startOffset, start)) {
                        return Promise.reject(new Error('结束时间必须晚于开始时间'))
                      }
                      return Promise.resolve()
                    },
                  }),
                ]}
              >
                <TimePicker format="HH:mm" style={{ width: '100%' }} placeholder="HH:mm" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="状态"
                name="status"
                rules={[{ required: true, message: '请选择状态' }]}
              >
                <Select options={STATUS_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="备注" name="remark">
                <Input placeholder="选填" maxLength={255} />
              </Form.Item>
            </Col>
          </Row>

          <Alert
            type="warning"
            showIcon
            title="模板停用或未绑定时，相关授权将不可用"
            description="角色绑定启用中的模板后，用户角色分配/申请审批会按模板计算窗口；模板停用或缺失时分配/审批会返回 409「时间模板不存在或已停用」。"
          />
        </Form>
      </Modal>
    </PageShell>
  )
}

