/**
 * StorageScene — 数据存储场景页面
 */

import React, { useState } from 'react'
import { Card, Button, Space, Typography, Tag, Alert, Table, Progress, Row, Col, Statistic } from 'antd'
import { DatabaseOutlined, TableOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons'

const { Title, Text } = Typography

interface TableInfo {
  name: string
  rows: number
  size: string
  lastModified: string
}

const StorageScene: React.FC = () => {
  const [tables] = useState<TableInfo[]>([
    { name: 'memories', rows: 128, size: '2.4 MB', lastModified: '2026-08-29' },
    { name: 'skills', rows: 32, size: '512 KB', lastModified: '2026-08-28' },
    { name: 'evolution_logs', rows: 256, size: '4.8 MB', lastModified: '2026-08-29' },
    { name: 'autoskill_tasks', rows: 64, size: '1.2 MB', lastModified: '2026-08-27' },
  ])

  const totalRows = tables.reduce((sum, t) => sum + t.rows, 0)
  const totalSize = '8.9 MB'

  const columns = [
    { title: '表名', dataIndex: 'name', key: 'name', render: (text: string) => <Tag color="blue"><TableOutlined /> {text}</Tag> },
    { title: '行数', dataIndex: 'rows', key: 'rows' },
    { title: '大小', dataIndex: 'size', key: 'size' },
    { title: '最后修改', dataIndex: 'lastModified', key: 'lastModified' },
    {
      title: '操作',
      key: 'action',
      render: () => <Button size="small" danger icon={<DeleteOutlined />}>清理</Button>,
    },
  ]

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <DatabaseOutlined style={{ marginRight: 8 }} />
          数据存储
        </Title>
        <Text type="secondary">
          SQLite 本地存储引擎，管理记忆、技能、进化日志等数据
        </Text>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="数据表" value={tables.length} suffix="个" />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="总记录" value={totalRows} suffix="行" />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="占用空间" value={totalSize} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="存储概览" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {tables.map(t => (
            <div key={t.name}>
              <Space style={{ marginBottom: 4 }}>
                <Text strong>{t.name}</Text>
                <Text type="secondary">{t.size}</Text>
              </Space>
              <Progress
                percent={Math.min(100, Math.round(t.rows / 256 * 100))}
                size="small"
                showInfo={false}
                strokeColor={t.name === 'memories' ? '#1890ff' : t.name === 'skills' ? '#52c41a' : t.name === 'evolution_logs' ? '#faad14' : '#722ed1'}
              />
            </div>
          ))}
        </Space>
      </Card>

      <Card size="small" title="数据表管理" style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 12 }}>
          <Button type="primary" icon={<ReloadOutlined />}>刷新</Button>
          <Button icon={<DeleteOutlined />}>清理过期数据</Button>
        </Space>
        <Table
          dataSource={tables}
          columns={columns}
          rowKey="name"
          size="small"
          pagination={false}
        />
      </Card>

      <Alert
        message="存储说明"
        description={
          <ul style={{ paddingLeft: 16, margin: 0 }}>
            <li>引擎：SQLite（支持 DuckDB 切换）</li>
            <li>路径：~/.dsh/storage/</li>
            <li>数据自动备份，支持导出为 JSON</li>
          </ul>
        }
        type="info"
        showIcon
      />
    </div>
  )
}

export default StorageScene
