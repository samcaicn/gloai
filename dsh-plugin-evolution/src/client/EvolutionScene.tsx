/**
 * EvolutionScene — 进化追踪场景页面
 */

import React, { useState } from 'react'
import { Card, Button, Space, Typography, Tag, Alert, Statistic, Row, Col, List } from 'antd'
import { LineChartOutlined, ReloadOutlined, RiseOutlined, FallOutlined, MinusOutlined } from '@ant-design/icons'

const { Title, Text } = Typography

interface TrendRecord {
  date: string
  score: number
  change: number
}

const EvolutionScene: React.FC = () => {
  const [records] = useState<TrendRecord[]>([
    { date: '2026-08-25', score: 72, change: 0 },
    { date: '2026-08-26', score: 75, change: 3 },
    { date: '2026-08-27', score: 78, change: 3 },
    { date: '2026-08-28', score: 82, change: 4 },
    { date: '2026-08-29', score: 85, change: 3 },
  ])

  const latestScore = records[records.length - 1]?.score || 0
  const avgChange = records.reduce((sum, r) => sum + r.change, 0) / records.length

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <LineChartOutlined style={{ marginRight: 8 }} />
          进化追踪
        </Title>
        <Text type="secondary">
          滑动窗口趋势追踪，监控技能质量变化
        </Text>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="当前分数" value={latestScore} suffix="/ 100" valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="平均变化" value={avgChange.toFixed(1)} suffix="分/天" prefix={avgChange >= 0 ? <RiseOutlined /> : <FallOutlined />} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="追踪天数" value={records.length} suffix="天" />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="趋势记录" style={{ marginBottom: 16 }}>
        <List
          dataSource={records}
          renderItem={(item) => (
            <List.Item>
              <Space>
                <Text>{item.date}</Text>
                <Tag color="blue">{item.score}分</Tag>
                {item.change > 0 ? <Tag color="green">+{item.change}</Tag> : item.change < 0 ? <Tag color="red">{item.change}</Tag> : <Tag><MinusOutlined /></Tag>}
              </Space>
            </List.Item>
          )}
        />
      </Card>

      <Alert
        message="使用说明"
        description={
          <ul style={{ paddingLeft: 16, margin: 0 }}>
            <li>基于滑动窗口的趋势分析</li>
            <li>自动追踪技能质量变化</li>
            <li>支持长期数据积累和对比</li>
          </ul>
        }
        type="info"
        showIcon
      />
    </div>
  )
}

export default EvolutionScene
