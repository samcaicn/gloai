/**
 * MemoryScene — 记忆系统场景页面
 */

import React, { useState } from 'react'
import { Card, Button, Space, Typography, Tag, Alert, List, Input, Statistic, Row, Col } from 'antd'
import { DatabaseOutlined, SearchOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons'

const { Title, Text } = Typography
const { Search } = Input

interface MemoryItem {
  id: string
  content: string
  category: 'hot' | 'warm' | 'cold'
  timestamp: string
}

const MemoryScene: React.FC = () => {
  const [memories, setMemories] = useState<MemoryItem[]>([
    { id: '1', content: '用户偏好深色主题', category: 'hot', timestamp: '2026-08-29' },
    { id: '2', content: '上次对话讨论了插件架构', category: 'warm', timestamp: '2026-08-28' },
    { id: '3', content: '项目使用 Tauri v2 框架', category: 'cold', timestamp: '2026-08-25' },
  ])

  const hotCount = memories.filter(m => m.category === 'hot').length
  const warmCount = memories.filter(m => m.category === 'warm').length
  const coldCount = memories.filter(m => m.category === 'cold').length

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <DatabaseOutlined style={{ marginRight: 8 }} />
          记忆系统
        </Title>
        <Text type="secondary">
          热/温/冷三层记忆衰减机制
        </Text>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="热记忆" value={hotCount} valueStyle={{ color: '#ff4d4f' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="温记忆" value={warmCount} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="冷记忆" value={coldCount} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="记忆管理" style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 12 }}>
          <Search placeholder="搜索记忆..." allowClear style={{ width: 300 }} />
          <Button type="primary" icon={<PlusOutlined />}>添加</Button>
        </Space>
        <List
          dataSource={memories}
          renderItem={(item) => (
            <List.Item
              actions={[<Button size="small" danger icon={<DeleteOutlined />} />]}
            >
              <List.Item.Meta
                title={item.content}
                description={
                  <Space>
                    <Tag color={item.category === 'hot' ? 'red' : item.category === 'warm' ? 'orange' : 'blue'}>
                      {item.category === 'hot' ? '热' : item.category === 'warm' ? '温' : '冷'}
                    </Tag>
                    <Text type="secondary">{item.timestamp}</Text>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      </Card>

      <Alert
        message="使用说明"
        description={
          <ul style={{ paddingLeft: 16, margin: 0 }}>
            <li>热记忆：高频访问，永不衰减</li>
            <li>温记忆：中频访问，缓慢衰减</li>
            <li>冷记忆：低频访问，快速衰减</li>
          </ul>
        }
        type="info"
        showIcon
      />
    </div>
  )
}

export default MemoryScene
