/**
 * WatermarkScene — 视频去水印场景页面
 *
 * DSH 插件化的去水印 UI，通过 Cordis 上下文与宿主通信。
 * 支持 FFT-KCF 自动检测和 LaMA AI 两种方法。
 */

import React, { useState, useCallback, useEffect } from 'react'
import {
  Upload,
  Button,
  Card,
  Space,
  Progress,
  Alert,
  Typography,
  Divider,
  Tag,
  Modal,
  InputNumber,
  Radio,
  Spin,
  message,
} from 'antd'
import {
  UploadOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  DownloadOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import type { UploadFile } from 'antd/es/upload/interface'

const { Title, Text, Paragraph } = Typography

interface WatermarkInfo {
  current_method: string
  methods: Record<string, {
    name: string
    description: string
    model_size: string
    auto_download: boolean
  }>
  lama_downloaded: boolean
}

const WatermarkScene: React.FC = () => {
  const [file, setFile] = useState<UploadFile | null>(null)
  const [videoUrl, setVideoUrl] = useState<string | null>(null)
  const [outputUrl, setOutputUrl] = useState<string | null>(null)
  const [processing, setProcessing] = useState(false)
  const [progress, setProgress] = useState(0)
  const [method, setMethod] = useState<'fft_kcf' | 'lama'>('fft_kcf')
  const [watermarkPos, setWatermarkPos] = useState({ x: 10, y: 10, w: 200, h: 50 })
  const [autoDetect, setAutoDetect] = useState(true)
  const [info, setInfo] = useState<WatermarkInfo | null>(null)
  const [showPosModal, setShowPosModal] = useState(false)

  // 检测是否在 Tauri 环境中
  const isTauri = typeof window !== 'undefined' && !!(window as any).__TAURI_INTERNALS__

  // 加载方法信息
  const loadInfo = async () => {
    if (!isTauri) return
    try {
      const { invoke } = await import(/* webpackIgnore: true */ '@tauri-apps/api/core')
      const result = await invoke<WatermarkInfo>('watermark_get_info')
      setInfo(result)
    } catch (e) {
      console.error('Failed to load watermark info:', e)
    }
  }

  // 页面加载时获取信息
  useEffect(() => {
    loadInfo()
  }, [])

  const handleFileChange = (info: any) => {
    const { file } = info
    setFile(file)
    setOutputUrl(null)
    if (file.originFileObj) {
      const url = URL.createObjectURL(file.originFileObj)
      setVideoUrl(url)
    }
  }

  const handleRemoveWatermark = useCallback(async () => {
    if (!file?.originFileObj) {
      message.error('请先选择视频文件')
      return
    }

    if (!isTauri) {
      message.warning('请在 DSH 桌面客户端中使用此功能')
      // 演示模式：模拟处理
      setProcessing(true)
      setProgress(0)
      const interval = setInterval(() => {
        setProgress(p => {
          if (p >= 100) {
            clearInterval(interval)
            setProcessing(false)
            message.success('演示模式：处理完成（实际功能需在桌面客户端中使用）')
            return 100
          }
          return p + 10
        })
      }, 300)
      return
    }

    setProcessing(true)
    setProgress(0)
    setOutputUrl(null)

    try {
      const { invoke } = await import(/* webpackIgnore: true */ '@tauri-apps/api/core')
      const filePath = (file.originFileObj as any).path || file.name
      const outputPath = filePath.replace(/\.[^.]+$/, '_no_watermark.mp4')

      // 模拟进度
      const progressInterval = setInterval(() => {
        setProgress(p => Math.min(p + 5, 90))
      }, 500)

      const success = await invoke<boolean>('watermark_remove', {
        input: filePath,
        output: outputPath,
        method,
        x: autoDetect ? null : watermarkPos.x,
        y: autoDetect ? null : watermarkPos.y,
        w: autoDetect ? null : watermarkPos.w,
        h: autoDetect ? null : watermarkPos.h,
      })

      clearInterval(progressInterval)
      setProgress(100)

      if (success) {
        message.success('去水印完成！')
        setOutputUrl(outputPath)
      } else {
        message.error('去水印失败')
      }
    } catch (e: any) {
      console.error('Watermark removal failed:', e)
      message.error(`去水印失败: ${e}`)
    } finally {
      setProcessing(false)
    }
  }, [file, method, watermarkPos, autoDetect, isTauri])

  const handleSwitchMethod = async (newMethod: 'fft_kcf' | 'lama') => {
    if (newMethod === 'lama' && !info?.lama_downloaded && isTauri) {
      Modal.confirm({
        title: '下载 LaMA 模型',
        content: 'LaMA 模型约 1.5GB，是否现在下载？',
        onOk: async () => {
          try {
            const { invoke } = await import(/* webpackIgnore: true */ '@tauri-apps/api/core')
            message.loading('正在下载 LaMA 模型...', 0)
            await invoke('watermark_switch_method', { method: 'lama' })
            message.success('LaMA 模型下载完成')
            setMethod(newMethod)
            loadInfo()
          } catch (e) {
            message.error('下载失败')
          }
        },
      })
    } else {
      setMethod(newMethod)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      {/* 页面标题 */}
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <VideoCameraOutlined style={{ marginRight: 8 }} />
          视频去水印
        </Title>
        <Text type="secondary">
          自动检测并去除视频水印，支持 FFT-KCF 和 LaMA AI 两种方法
        </Text>
      </div>

      {!isTauri && (
        <Alert
          message="演示模式"
          description="当前在浏览器中运行，仅展示 UI 效果。实际去水印功能请在 DSH 桌面客户端中使用。"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 方法选择 */}
      <Card size="small" title="去水印方法" style={{ marginBottom: 16 }}>
        <Radio.Group value={method} onChange={e => handleSwitchMethod(e.target.value)}>
          <Space direction="vertical">
            <Radio value="fft_kcf">
              <Space>
                <Text strong>FFT-KCF</Text>
                <Tag color="blue">轻量级</Tag>
                <Text type="secondary">~200MB</Text>
              </Space>
              <br />
              <Text type="secondary" style={{ marginLeft: 24 }}>
                基于 FFT 频域分析 + KCF 跟踪，适合静态水印，自动下载模型
              </Text>
            </Radio>
            <Radio value="lama">
              <Space>
                <Text strong>LaMA AI</Text>
                <Tag color="green">高质量</Tag>
                <Text type="secondary">~1.5GB</Text>
                {!info?.lama_downloaded && <Tag color="orange">需下载</Tag>}
              </Space>
              <br />
              <Text type="secondary" style={{ marginLeft: 24 }}>
                AI 修复技术，适合复杂背景上的水印，切换时自动下载
              </Text>
            </Radio>
          </Space>
        </Radio.Group>
      </Card>

      {/* 文件上传 */}
      <Card size="small" title="选择视频" style={{ marginBottom: 16 }}>
        <Upload.Dragger
          accept="video/*"
          maxCount={1}
          fileList={file ? [file] : []}
          onChange={handleFileChange}
          beforeUpload={() => false}
          showUploadList={false}
        >
          <p style={{ fontSize: 32 }}>
            <UploadOutlined />
          </p>
          <p>点击或拖拽视频文件到此处</p>
          <p type="secondary">支持 MP4、AVI、MOV 等格式</p>
        </Upload.Dragger>

        {file && (
          <div style={{ marginTop: 12 }}>
            <Space>
              <Text>{file.name}</Text>
              <Button
                size="small"
                icon={<DeleteOutlined />}
                onClick={() => {
                  setFile(null)
                  setVideoUrl(null)
                  setOutputUrl(null)
                }}
              >
                移除
              </Button>
            </Space>
          </div>
        )}
      </Card>

      {/* 视频预览 */}
      {videoUrl && (
        <Card size="small" title="视频预览" style={{ marginBottom: 16 }}>
          <video
            src={videoUrl}
            controls
            style={{ width: '100%', maxHeight: 400, background: '#000' }}
          />
        </Card>
      )}

      {/* 水印位置 */}
      <Card size="small" title="水印位置" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Radio.Group value={autoDetect ? 'auto' : 'manual'} onChange={e => setAutoDetect(e.target.value === 'auto')}>
            <Space>
              <Radio value="auto">自动检测</Radio>
              <Radio value="manual">手动指定</Radio>
            </Space>
          </Radio.Group>

          {!autoDetect && (
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <span>X:</span>
                <InputNumber value={watermarkPos.x} onChange={v => setWatermarkPos({ ...watermarkPos, x: v || 0 })} />
                <span>Y:</span>
                <InputNumber value={watermarkPos.y} onChange={v => setWatermarkPos({ ...watermarkPos, y: v || 0 })} />
                <span>宽:</span>
                <InputNumber value={watermarkPos.w} onChange={v => setWatermarkPos({ ...watermarkPos, w: v || 0 })} />
                <span>高:</span>
                <InputNumber value={watermarkPos.h} onChange={v => setWatermarkPos({ ...watermarkPos, h: v || 0 })} />
              </Space>
            </div>
          )}
        </Space>
      </Card>

      {/* 处理按钮 */}
      <div style={{ textAlign: 'center', margin: '24px 0' }}>
        <Button
          type="primary"
          size="large"
          icon={processing ? <LoadingOutlined /> : <PlayCircleOutlined />}
          onClick={handleRemoveWatermark}
          disabled={!file || processing}
        >
          {processing ? '处理中...' : '开始去水印'}
        </Button>
      </div>

      {/* 进度条 */}
      {processing && (
        <div style={{ marginBottom: 16 }}>
          <Progress percent={progress} status="active" />
        </div>
      )}

      {/* 输出结果 */}
      {outputUrl && (
        <Card size="small" title="处理结果" style={{ marginBottom: 16 }}>
          <Alert
            message="去水印完成"
            description={`输出文件: ${outputUrl}`}
            type="success"
            showIcon
            icon={<CheckCircleOutlined />}
            style={{ marginBottom: 12 }}
          />
          <Space>
            <Button
              type="primary"
              icon={<DownloadOutlined />}
              href={outputUrl}
              download
            >
              下载结果
            </Button>
          </Space>
        </Card>
      )}

      {/* 使用说明 */}
      <Divider />
      <Alert
        message="使用说明"
        description={
          <ul style={{ paddingLeft: 16, margin: 0 }}>
            <li>FFT-KCF：适合固定位置的水印，处理速度快</li>
            <li>LaMA AI：适合复杂背景，水印边缘融合更自然</li>
            <li>自动检测基于梯度分析，可能不适用于所有视频</li>
            <li>建议先使用自动检测，效果不佳时手动指定位置</li>
          </ul>
        }
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
      />
    </div>
  )
}

export default WatermarkScene
