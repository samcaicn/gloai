/**
 * AutoSkillScene — AutoSkill 自进化引擎场景页面
 */

import React, { useState, useEffect } from 'react'
import { Card, Button, Space, Typography, Tag, Alert, List, Progress, message } from 'antd'
import { RobotOutlined, PlayCircleOutlined, PauseCircleOutlined, ReloadOutlined, CheckCircleOutlined } from '@ant-design/icons'

const { Title, Text, Paragraph } = Typography

interface PipelineStep {
  name: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  progress: number
}

const AutoSkillScene: React.FC = () => {
  const [running, setRunning] = useState(false)
  const [steps, setSteps] = useState<PipelineStep[]>([
    { name: '参数泛化', status: 'pending', progress: 0 },
    { name: '模式挖掘', status: 'pending', progress: 0 },
    { name: '状态机执行', status: 'pending', progress: 0 },
    { name: '技能编译', status: 'pending', progress: 0 },
    { name: '评估验证', status: 'pending', progress: 0 },
  ])

  const handleStart = () => {
    setRunning(true)
    // 模拟流水线执行
    steps.forEach((_, index) => {
      setTimeout(() => {
        setSteps(prev => prev.map((s, i) => i === index ? { ...s, status: 'running' } : s))
        const interval = setInterval(() => {
          setSteps(prev => prev.map((s, i) => {
            if (i === index && s.progress < 100) {
              const newProgress = Math.min(s.progress + 10, 100)
              if (newProgress >= 100) {
                clearInterval(interval)
                return { ...s, progress: 100, status: 'completed' }
              }
              return { ...s, progress: newProgress }
            }
            return s
          }))
        }, 200)
      }, index * 2000)
    })
  }

  const handleStop = () => {
    setRunning(false)
    message.info('已停止')
  }

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <RobotOutlined style={{ marginRight: 8 }} />
          AutoSkill 自进化引擎
        </Title>
        <Text type="secondary">
          5步流水线：参数泛化 → 模式挖掘 → 状态机执行 → 技能编译 → 评估验证
        </Text>
      </div>

      <Card size="small" title="流水线控制" style={{ marginBottom: 16 }}>
        <Space>
          <Button type="primary" icon={running ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={running ? handleStop : handleStart}>
            {running ? '停止' : '启动流水线'}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => setSteps(prev => prev.map(s => ({ ...s, status: 'pending', progress: 0 })))}>
            重置
          </Button>
        </Space>
      </Card>

      <Card size="small" title="流水线步骤" style={{ marginBottom: 16 }}>
        <List
          dataSource={steps}
          renderItem={(item) => (
            <List.Item>
              <div style={{ width: '100%' }}>
                <Space>
                  <Tag color={item.status === 'completed' ? 'green' : item.status === 'running' ? 'blue' : item.status === 'failed' ? 'red' : 'default'}>
                    {item.status === 'completed' ? '完成' : item.status === 'running' ? '运行中' : item.status === 'failed' ? '失败' : '等待'}
                  </Tag>
                  <Text strong>{item.name}</Text>
                </Space>
                <Progress percent={item.progress} status={item.status === 'running' ? 'active' : item.status === 'completed' ? 'success' : 'normal'} />
              </div>
            </List.Item>
          )}
        />
      </Card>

      <Alert
        message="使用说明"
        description={
          <ul style={{ paddingLeft: 16, margin: 0 }}>
            <li>AutoSkill 通过5步流水线自动进化和优化技能</li>
            <li>参数泛化：分析技能参数模式</li>
            <li>模式挖掘：发现最优执行模式</li>
            <li>状态机执行：自动化测试执行</li>
            <li>技能编译：生成优化后的技能</li>
            <li>评估验证：验证技能质量</li>
          </ul>
        }
        type="info"
        showIcon
      />
    </div>
  )
}

export default AutoSkillScene
