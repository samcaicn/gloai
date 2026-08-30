/**
 * SkillScene — 技能系统场景页面
 */

import React, { useState } from 'react'
import { Card, Button, Space, Typography, Tag, Alert, List, Input, Modal, message } from 'antd'
import { AppstoreOutlined, PlusOutlined, DeleteOutlined, EditOutlined, CheckCircleOutlined } from '@ant-design/icons'

const { Title, Text } = Typography
const { Search } = Input

interface SkillItem {
  id: string
  name: string
  description: string
  status: 'active' | 'inactive' | 'error'
  version: string
}

const SkillScene: React.FC = () => {
  const [skills, setSkills] = useState<SkillItem[]>([
    { id: '1', name: 'watermark-remover', description: '视频去水印', status: 'active', version: '0.1.0' },
    { id: '2', name: 'langgraph', description: 'LangGraph 多 Agent 调度', status: 'active', version: '0.1.0' },
    { id: '3', name: 'code-review', description: '代码审查', status: 'inactive', version: '0.1.0' },
  ])

  const handleDelete = (id: string) => {
    setSkills(prev => prev.filter(s => s.id !== id))
    message.success('技能已删除')
  }

  const handleToggle = (id: string) => {
    setSkills(prev => prev.map(s => s.id === id ? { ...s, status: s.status === 'active' ? 'inactive' : 'active' } : s))
  }

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <AppstoreOutlined style={{ marginRight: 8 }} />
          技能系统
        </Title>
        <Text type="secondary">
          技能注册、编译、评估和管理
        </Text>
      </div>

      <Card size="small" title="技能管理" style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 12 }}>
          <Search placeholder="搜索技能..." allowClear style={{ width: 300 }} />
          <Button type="primary" icon={<PlusOutlined />}>安装技能</Button>
        </Space>
        <List
          dataSource={skills}
          renderItem={(item) => (
            <List.Item
              actions={[
                <Button size="small" icon={<EditOutlined />} onClick={() => message.info(`编辑 ${item.name}`)} />,
                <Button size="small" danger={item.status === 'active'} onClick={() => handleToggle(item.id)}>
                  {item.status === 'active' ? '禁用' : '启用'}
                </Button>,
                <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(item.id)} />,
              ]}
            >
              <List.Item.Meta
                title={
                  <Space>
                    <Text strong>{item.name}</Text>
                    <Tag color="blue">v{item.version}</Tag>
                  </Space>
                }
                description={
                  <Space>
                    <Text>{item.description}</Text>
                    <Tag color={item.status === 'active' ? 'green' : item.status === 'error' ? 'red' : 'default'}>
                      {item.status === 'active' ? '启用' : item.status === 'error' ? '错误' : '禁用'}
                    </Tag>
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
            <li>技能是 DSH 的功能扩展单元</li>
            <li>支持安装、卸载、启用、禁用操作</li>
            <li>技能通过 cordis.patch.yml 注册</li>
          </ul>
        }
        type="info"
        showIcon
      />
    </div>
  )
}

export default SkillScene
