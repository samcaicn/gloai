#!/usr/bin/env node
/**
 * DSH 插件脚手架 — 一键生成标准插件结构
 *
 * 用法：
 *   node scripts/scaffold-plugin.mjs <plugin-name> --order 60 --icon DatabaseOutlined
 *
 * 示例：
 *   node scripts/scaffold-plugin.mjs my-tool --order 60 --icon ToolOutlined
 *
 * 生成：
 *   dsh-plugin-<name>/
 *     ├── package.json
 *     ├── cordis.patch.yml
 *     ├── tsconfig.client.json
 *     ├── src/
 *     │   ├── index.ts          (Host 端 apply 入口)
 *     │   └── client/
 *     │       ├── index.ts      (ModuleLoader 注册)
 *     │       └── <Name>Scene.tsx
 */

import { mkdir, writeFile, access } from 'node:fs/promises'
import { constants } from 'node:fs'
import { join, resolve } from 'node:path'

// ── 解析参数 ───────────────────────────────────────────────────────────────
const args = process.argv.slice(2)
if (args.length === 0 || args[0] === '--help') {
  console.log(`
DSH 插件脚手架

用法：
  node scripts/scaffold-plugin.mjs <plugin-name> [选项]

选项：
  --order <数字>     侧边栏顺序 (默认 50)
  --icon <图标名>    antd 图标名 (默认 FolderOutlined)
  --desc <描述>      插件描述

示例：
  node scripts/scaffold-plugin.mjs my-tool --order 60 --icon ToolOutlined
`)
  process.exit(0)
}

const pluginName = args[0]
const prefix = pluginName.startsWith('dsh-plugin-') ? '' : 'dsh-plugin-'
const fullName = prefix + pluginName
const shortName = pluginName.replace(/^dsh-plugin-/, '')

// 解析选项
let order = 50
let icon = 'FolderOutlined'
let description = `${shortName} plugin for DeepSeek Harness`

for (let i = 1; i < args.length; i++) {
  if (args[i] === '--order') order = parseInt(args[++i])
  if (args[i] === '--icon') icon = args[++i]
  if (args[i] === '--desc') description = args[++i]
}

// ── 模板生成 ───────────────────────────────────────────────────────────────

const packageJson = {
  name: fullName,
  version: '0.1.0',
  description,
  keywords: ['deepseek', 'deepseek-harness', 'dsh', 'dsh-plugin', shortName],
  license: 'MIT',
  type: 'module',
  engines: { node: '^22.19.0 || >=24.0.0' },
  main: './dist/index.js',
  types: './dist/index.d.ts',
  exports: {
    '.': { types: './dist/index.d.ts', default: './dist/index.js' },
    './plugin': { types: './dist/dsh-plugin.d.ts', default: './dist/dsh-plugin.js' },
    './client': { types: './dist/client.d.ts', default: './dist/client.js' },
    './cordis.patch.yml': './cordis.patch.yml',
    './package.json': './package.json',
  },
  dsh: {
    bundle: { patch: './cordis.patch.yml' },
    client: { platform: 'web', inject: ['tools', 'clientModules'] },
  },
  files: ['dist', 'cordis.patch.yml', 'README.md', 'LICENSE'],
  repository: { type: 'git', url: 'local' },
  scripts: {
    build: 'tsc -p tsconfig.json && tsc -p tsconfig.client.json',
    prepare: 'npm run build',
    typecheck: 'tsc -p tsconfig.json --noEmit',
  },
  dependencies: {},
  devDependencies: { typescript: '^5.9.3' },
}

const cordisPatch = `# ${fullName} — 自动生成
- insert:
    - id: ${fullName}
      name: ${shortName}
      config: {}
  ui:
    sceneBar:
      - id: ${fullName}
        name: ${shortName}
        icon: ${icon}
        order: ${order}
    scene:
      - id: ${fullName}
        component: ./client/index.js
`

const tsconfigClient = {
  exclude: ['node_modules', 'dist'],
  include: ['src/client/**'],
  compilerOptions: {
    module: 'ESNext',
    esInterop: true,
    target: 'ES2022',
    forceConsistentCasingInFileNames: true,
    strict: true,
    moduleResolution: 'bundler',
    declarationMap: true,
    outDir: './dist',
    declaration: true,
    skipLibCheck: true,
    lib: ['ES2022', 'DOM', 'DOM.Iterable'],
    sourceMap: true,
    resolveJsonModule: true,
    rootDir: './src/client',
  },
}

const hostIndex = `/**
 * ${fullName} — Cordis Host 端插件入口
 *
 * 注册服务和工具到 Cordis 上下文。
 * 遵循 DSH 插件规范：导出 name、inject、Config、apply。
 */

export const name = '${fullName}'
export const inject = []

export interface Config {
  // TODO: 定义配置
}

export const Config = {
  // TODO: 默认配置
}

export interface Context {
  // TODO: 定义服务接口
}

export function apply(ctx: any, config: Config): void {
  // TODO: 注册服务
  console.log(\`[\${name}] 插件已加载\`)
}

export default { name, inject, Config, apply }
`

const clientIndex = `/**
 * ${fullName} — Cordis 浏览器端插件
 *
 * 通过官方 ModuleLoader 注册 UI 组件。
 */

interface CordisContext {
  tools: ClientTools
  clientModules: ClientModules
}

interface ClientTools {
  schemas(): Array<{ name: string; description: string; parameters: Record<string, unknown> }>
  execute(input: { callId: string; name: string; arguments: unknown; signal: AbortSignal }): Promise<{ isError: boolean; content: Array<Record<string, unknown>> }>
  on?(event: string, handler: () => void): void
}

interface ClientModules {
  notifyToolsChanged(): void
}

interface ModuleLoaderEntry {
  id: string
  factory: (ctx: CordisContext) => void | Promise<void>
}

interface WindowWithModuleLoader extends Window {
  __ModuleLoader__?: {
    load(entry: ModuleLoaderEntry): void
  }
}

const PLUGIN_ID = '${fullName}-client'

function clientFactory(ctx: CordisContext): void {
  const { tools } = ctx
  if ('on' in tools && typeof tools.on === 'function') {
    ;(tools as ClientTools & { on(event: string, handler: () => void): void }).on('tools/change', () => ctx.clientModules?.notifyToolsChanged())
  }
  console.log(\`[\${PLUGIN_ID}] 客户端插件已加载，工具数: \${tools.schemas().length}\`)
}

function registerPlugin(): void {
  const win = window as WindowWithModuleLoader
  if (win.__ModuleLoader__) {
    win.__ModuleLoader__.load({ id: PLUGIN_ID, factory: clientFactory })
  } else {
    const checkInterval = window.setInterval(() => {
      if ((window as WindowWithModuleLoader).__ModuleLoader__) {
        window.clearInterval(checkInterval)
        ;(window as WindowWithModuleLoader).__ModuleLoader__?.load({ id: PLUGIN_ID, factory: clientFactory })
      }
    }, 100)
    window.setTimeout(() => window.clearInterval(checkInterval), 10000)
  }
}

registerPlugin()

export { clientFactory, PLUGIN_ID }
`

// 大驼峰命名
const componentName = shortName
  .split('-')
  .map(s => s.charAt(0).toUpperCase() + s.slice(1))
  .join('') + 'Scene'

const sceneComponent = `/**
 * ${componentName} — 场景页面
 */

import React from 'react'
import { Card, Typography } from 'antd'
import { ${icon} } from '@ant-design/icons'

const { Title, Text } = Typography

const ${componentName}: React.FC = () => {
  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>
          <${icon} style={{ marginRight: 8 }} />
          ${shortName}
        </Title>
        <Text type="secondary">
          ${description}
        </Text>
      </div>
      <Card title="TODO">
        <Text>在这里实现你的插件 UI</Text>
      </Card>
    </div>
  )
}

export default ${componentName}
`

// ── 写入文件 ───────────────────────────────────────────────────────────────

async function main() {
  const targetDir = resolve(process.cwd(), fullName)

  // 检查目录是否已存在
  try {
    await access(targetDir, constants.F_OK)
    console.error(`错误: 目录 ${fullName}/ 已存在`)
    process.exit(1)
  } catch {
    // 不存在，继续
  }

  console.log(`🚀 创建插件: ${fullName}`)
  console.log(`   侧边栏顺序: ${order}`)
  console.log(`   图标: ${icon}`)
  console.log()

  // 创建目录结构
  await mkdir(join(targetDir, 'src', 'client'), { recursive: true })

  // 写入文件
  await writeFile(join(targetDir, 'package.json'), JSON.stringify(packageJson, null, 2) + '\n')
  await writeFile(join(targetDir, 'cordis.patch.yml'), cordisPatch)
  await writeFile(join(targetDir, 'tsconfig.client.json'), JSON.stringify(tsconfigClient, null, 2) + '\n')
  await writeFile(join(targetDir, 'src', 'index.ts'), hostIndex)
  await writeFile(join(targetDir, 'src', 'client', 'index.ts'), clientIndex)
  await writeFile(join(targetDir, 'src', 'client', `${componentName}.tsx`), sceneComponent)

  console.log(`✅ 插件 ${fullName} 已创建！`)
  console.log()
  console.log('下一步：')
  console.log(`  1. cd ${fullName}`)
  console.log('  2. npm install')
  console.log('  3. 编辑 src/index.ts 实现 Host 端逻辑')
  console.log(`  4. 编辑 src/client/${componentName}.tsx 实现 UI`)
  console.log('  5. npm run build')
}

main().catch(err => {
  console.error('创建失败:', err)
  process.exit(1)
})
