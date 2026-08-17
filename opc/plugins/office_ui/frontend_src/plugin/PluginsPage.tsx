import React, { useEffect, useMemo, useRef, useState } from 'react'
import './plugin.css'

export interface PluginsPageProps {
  plugins: any[]
  onRequestData: () => void
  onAdd: (source: string, enabled: boolean) => void
  onRemove: (pluginId: string) => void
  onEnable: (pluginId: string) => void
  onDisable: (pluginId: string) => void
  onConfigGet: (pluginId: string) => void
  onConfigSet: (pluginId: string, config: Record<string, unknown>) => void
  configTarget: string | null
  configData: Record<string, unknown>
  configSchema: any
  configError: string | null
  onConfigClose: () => void
  // Web-wide discovery ("search the whole network")
  onDiscover: (query: string, provider: string) => void
  onRefresh: () => void
  discoverResults: any[]
  discoverLoading: boolean
  discoverError: string | null
  // DSH preset lifecycle (faithful port of dsh-desktop .dshpreset handling)
  onPreview: (source: string) => void
  onExport: (pluginId: string) => void
  pluginPreviewData: any | null
  pluginPreviewLoading: boolean
  pluginPreviewError: string | null
  // Cordis-style layered override cascade (bundle/preset/profile/home/cli)
  onCascadeGet: () => void
  onCascadePatch: (tree: Record<string, unknown>, layer?: string) => void
  onCascadeReset: (layer?: string) => void
  cascadeData: any | null
  cascadeLoading: boolean
  cascadeError: string | null
}

function parseSource(value: string): { kind: string; label: string } {
  const v = value.trim()
  if (!v) return { kind: 'unknown', label: '空' }
  if (v.startsWith('git@') || v.startsWith('http://') || v.startsWith('https://') || v.endsWith('.git')) {
    return { kind: 'git', label: 'Git 仓库' }
  }
  if (v.startsWith('npm:') || v.startsWith('pypi:')) {
    return { kind: 'package', label: '包名' }
  }
  // Treat as a local directory path.
  return { kind: 'local', label: '本地路径' }
}

function ConfigField({
  name,
  spec,
  value,
  onChange,
  error,
}: {
  name: string
  spec: any
  value: unknown
  onChange: (v: unknown) => void
  error?: string | null
}) {
  const title = spec?.title ?? name
  const description = spec?.description ?? ''
  const type = spec?.type ?? 'string'

  let input: React.ReactNode
  if (type === 'boolean') {
    input = (
      <input
        type="checkbox"
        checked={Boolean(value)}
        onChange={(e) => onChange(e.target.checked)}
      />
    )
  } else if (type === 'integer' || type === 'number') {
    input = (
      <input
        type="number"
        className="plugin-input"
        value={value === undefined || value === null ? '' : String(value)}
        onChange={(e) => {
          const raw = e.target.value
          onChange(raw === '' ? (type === 'integer' ? 0 : 0) : Number(raw))
        }}
      />
    )
  } else {
    input = (
      <input
        type="text"
        className="plugin-input"
        value={value === undefined || value === null ? '' : String(value)}
        placeholder={spec?.default !== undefined ? String(spec.default) : ''}
        onChange={(e) => onChange(e.target.value)}
      />
    )
  }

  return (
    <div className="plugin-field">
      <label className="plugin-field-label">
        <span className="plugin-field-name">{title}</span>
        <span className="plugin-field-type">{type}</span>
      </label>
      {description && <div className="plugin-field-desc">{description}</div>}
      {input}
      {error && <div className="plugin-field-error">{error}</div>}
    </div>
  )
}

function ConfigEditor({
  target,
  schema,
  initialData,
  error,
  onSave,
  onClose,
}: {
  target: string
  schema: any
  initialData: Record<string, unknown>
  error: string | null
  onSave: (config: Record<string, unknown>) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<Record<string, unknown>>(initialData)
  const [rawMode, setRawMode] = useState(false)
  const [rawText, setRawText] = useState('')
  const [parseError, setParseError] = useState<string | null>(null)

  useEffect(() => {
    setDraft(initialData)
    setRawText(JSON.stringify(initialData, null, 2))
    setRawMode(false)
    setParseError(null)
  }, [target, initialData])

  const properties = schema?.properties ?? {}
  const propNames = Object.keys(properties)
  const required = Array.isArray(schema?.required) ? schema.required : []

  const fieldErrors = useMemo(() => {
    const errs: Record<string, string> = {}
    for (const name of required) {
      const v = draft[name]
      if (v === undefined || v === null || v === '') {
        errs[name] = '此项为必填'
      }
    }
    return errs
  }, [draft, required])

  const hasSchema = propNames.length > 0

  const handleSave = () => {
    if (rawMode) {
      try {
        const parsed = JSON.parse(rawText)
        if (typeof parsed !== 'object' || parsed === null) throw new Error('必须是一个 JSON 对象')
        onSave(parsed as Record<string, unknown>)
      } catch (e: any) {
        setParseError(e?.message ?? 'JSON 解析失败')
      }
      return
    }
    if (Object.keys(fieldErrors).length > 0) return
    onSave(draft)
  }

  return (
    <div className="plugin-modal-backdrop" onClick={onClose}>
      <div className="plugin-modal" onClick={(e) => e.stopPropagation()}>
        <div className="plugin-modal-head">
          <h3>配置插件：{target}</h3>
          <button className="plugin-icon-btn" onClick={onClose} title="关闭">✕</button>
        </div>

        {error && <div className="plugin-banner plugin-banner-error">{error}</div>}
        {parseError && <div className="plugin-banner plugin-banner-error">{parseError}</div>}

        <div className="plugin-modal-body">
          {!hasSchema && (
            <div className="plugin-banner plugin-banner-info">
              该插件未提供 config_schema，使用原始 JSON 编辑。
            </div>
          )}

          {hasSchema && !rawMode && (
            <div className="plugin-fields">
              {propNames.map((name) => (
                <ConfigField
                  key={name}
                  name={name}
                  spec={properties[name]}
                  value={draft[name]}
                  error={fieldErrors[name]}
                  onChange={(v) => setDraft((d) => ({ ...d, [name]: v }))}
                />
              ))}
            </div>
          )}

          {(rawMode || !hasSchema) && (
            <textarea
              className="plugin-raw"
              value={rawText}
              onChange={(e) => setRawText(e.target.value)}
              spellCheck={false}
            />
          )}
        </div>

        <div className="plugin-modal-foot">
          <button
            className="plugin-btn plugin-btn-ghost"
            onClick={() => {
              if (!rawMode && hasSchema) {
                setRawMode(true)
                setRawText(JSON.stringify(draft, null, 2))
              } else {
                setRawMode(false)
                setRawText(JSON.stringify(draft, null, 2))
              }
            }}
          >
            {rawMode ? '表单模式' : 'JSON 原始模式'}
          </button>
          <div className="plugin-foot-right">
            <button className="plugin-btn plugin-btn-ghost" onClick={onClose}>取消</button>
            <button
              className="plugin-btn plugin-btn-primary"
              disabled={!rawMode && Object.keys(fieldErrors).length > 0}
              onClick={handleSave}
            >
              保存
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function PresetPreviewModal({
  candidate,
  previewData,
  loading,
  error,
  onConfirm,
  onClose,
}: {
  candidate: any
  previewData: any | null
  loading: boolean
  error: string | null
  onConfirm: () => void
  onClose: () => void
}) {
  const warnings = previewData?.warnings ?? {}
  const errors = previewData?.errors ?? []
  const ok = Boolean(previewData?.ok)
  const meta = previewData?.manifest ?? {}

  return (
    <div className="plugin-modal-backdrop" onClick={onClose}>
      <div className="plugin-modal preset-preview-modal" onClick={(e) => e.stopPropagation()}>
        <div className="plugin-modal-head">
          <h3>安装 DSH 预设前校验</h3>
          <button className="plugin-icon-btn" onClick={onClose} title="关闭">✕</button>
        </div>

        <div className="plugin-modal-body">
          <div className="preset-preview-meta">
            <div><b>{candidate?.name ?? candidate?.id}</b> <span className="plugin-card-id">{candidate?.id}</span></div>
            {candidate?.source && <div className="plugin-disc-link">{candidate.source}</div>}
            {meta?.sourceDshVersion && (
              <div className="plugin-badge">DSH {meta.sourceDshVersion}</div>
            )}
          </div>

          {loading && <div className="plugin-banner plugin-banner-info">正在校验安装包安全性…</div>}
          {error && !loading && (
            <div className="plugin-banner plugin-banner-error">校验失败：{error}</div>
          )}

          {!loading && previewData && (
            <>
              {errors.length > 0 && (
                <div className="plugin-banner plugin-banner-error">
                  安装包存在以下问题，无法安装：
                  <ul className="preset-warn-list">
                    {errors.map((e: any, i: number) => (
                      <li key={i}>{e.message}</li>
                    ))}
                  </ul>
                </div>
              )}

              {(warnings.possible_secrets?.length > 0 || warnings.absolute_paths?.length > 0 || warnings.version_note) && (
                <div className="plugin-banner plugin-banner-warn">
                  <div>安全提示（仍可安装，请确认来源可信）：</div>
                  {warnings.possible_secrets?.length > 0 && (
                    <div className="preset-warn-row">· 可能包含密钥：{warnings.possible_secrets.join('、')}</div>
                  )}
                  {warnings.absolute_paths?.length > 0 && (
                    <div className="preset-warn-row">· 包含绝对路径引用：{warnings.absolute_paths.length} 处</div>
                  )}
                  {warnings.version_note && (
                    <div className="preset-warn-row">· {warnings.version_note}</div>
                  )}
                </div>
              )}

              {ok && errors.length === 0 && (
                <div className="plugin-banner plugin-banner-info">
                  校验通过。该预设将安装为 <code>agent</code> 插件，无需重启即可生效。
                </div>
              )}
            </>
          )}
        </div>

        <div className="plugin-modal-foot">
          <div className="plugin-foot-right">
            <button className="plugin-btn plugin-btn-ghost" onClick={onClose}>取消</button>
            {!loading && ok && errors.length === 0 && (
              <button className="plugin-btn plugin-btn-primary" onClick={onConfirm}>
                确认安装
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

export function PluginsPage(props: PluginsPageProps) {
  const {
    plugins,
    onRequestData,
    onAdd,
    onRemove,
    onEnable,
    onDisable,
    onConfigGet,
    onConfigSet,
    configTarget,
    configData,
    configSchema,
    configError,
    onConfigClose,
    onDiscover,
    onRefresh,
    discoverResults,
    discoverLoading,
    discoverError,
    onPreview,
    onExport,
    pluginPreviewData,
    pluginPreviewLoading,
    pluginPreviewError,
    onCascadeGet,
    onCascadePatch,
    onCascadeReset,
    cascadeData,
    cascadeLoading,
    cascadeError,
  } = props

  const [source, setSource] = useState('')
  const [enableOnAdd, setEnableOnAdd] = useState(true)
  const [busy, setBusy] = useState(false)

  // Web-wide discovery state
  const [discoverQuery, setDiscoverQuery] = useState('')
  const [discoverProvider, setDiscoverProvider] = useState<'github' | 'dsh'>('github')
  const [installingIds, setInstallingIds] = useState<Record<string, boolean>>({})
  // DSH preset install-confirm modal (holds the candidate being previewed).
  const [confirmPreset, setConfirmPreset] = useState<any | null>(null)
  // Cordis-style layered override cascade state + home-override editor state.
  const [cascadeOpen, setCascadeOpen] = useState(true)
  const [ovPlugin, setOvPlugin] = useState('')
  const [ovEnabled, setOvEnabled] = useState(false)
  const [ovConfigText, setOvConfigText] = useState('{}')
  const [ovError, setOvError] = useState<string | null>(null)

  const handleDiscover = () => {
    if (!discoverQuery.trim()) return
    onDiscover(discoverQuery.trim(), discoverProvider)
  }

  const handleDiscoverInstall = (cand: any) => {
    const id = cand?.id
    if (!id || !cand?.source) return
    // DSH presets go through the import-preview gate (security warnings /
    // errors) before install, mirroring dsh-desktop's two-step import.
    if (cand.provider === 'dsh') {
      setConfirmPreset(cand)
      onPreview(cand.source)
      return
    }
    doInstall(cand)
  }

  const doInstall = (cand: any) => {
    const id = cand?.id
    if (!id || !cand?.source) return
    setInstallingIds((prev) => ({ ...prev, [id]: true }))
    // Optimistic flip: show "✓ 已安装" instantly; the plugin_list broadcast
    // (onPluginList) will reconcile the real installed state shortly after.
    setDiscoverResults((prev) =>
      prev.map((c) => (c.id === id ? { ...c, installed: true } : c)),
    )
    onAdd(cand.source, true)
    // Reload runtime tools + list so the new plugin is live immediately.
    onRefresh()
    setTimeout(() => {
      setInstallingIds((prev) => ({ ...prev, [id]: false }))
      refresh()
    }, 600)
  }

  // Keep the latest callback without re-arming the poll interval each render.
  const onRequestDataRef = useRef(onRequestData)
  onRequestDataRef.current = onRequestData

  useEffect(() => {
    onRequestData()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Light poll: external changes (e.g. `dsh plugin add` run in a terminal)
  // are not pushed over WS, so re-sync while the page is open. Paused while
  // the config editor is open to avoid clobbering an in-progress edit.
  useEffect(() => {
    const t = setInterval(() => {
      if (!configTarget) onRequestDataRef.current()
    }, 2500)
    return () => clearInterval(t)
  }, [configTarget])

  // Fetch the layered override cascade when the page opens.
  const onCascadeGetRef = useRef(onCascadeGet)
  onCascadeGetRef.current = onCascadeGet
  useEffect(() => {
    onCascadeGetRef.current()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const sourceInfo = parseSource(source)

  const refresh = () => onRequestDataRef.current()

  const handleAdd = () => {
    if (!source.trim() || busy) return
    setBusy(true)
    onAdd(source.trim(), enableOnAdd)
    // Explicit refresh so the new plugin shows up instantly (don't wait for
    // the broadcast). Backend processes this request after the add completes.
    refresh()
    setTimeout(() => {
      setBusy(false)
      setSource('')
    }, 400)
  }

  const handleEnable = (id: string) => {
    onEnable(id)
    refresh()
  }

  const handleDisable = (id: string) => {
    onDisable(id)
    refresh()
  }

  const handleRemove = (id: string) => {
    if (!window.confirm(`确定卸载插件 "${id}"？该操作会删除其文件与配置。`)) return
    onRemove(id)
    refresh()
  }

  return (
    <div className="plugins-page">
      <div className="plugins-head">
        <div>
          <h2>插件管理</h2>
          <p className="plugins-sub">deepseek-harness 风格插件机制（dsh plugin add）。插件挂载到 profile（{`<opc_home>/config/plugins_config.yaml`}），可在此启用 / 停用 / 配置。</p>
        </div>
        <div className="plugins-count">
          <b>{plugins.length}</b> 个已安装
        </div>
      </div>

      <div className="plugins-add-bar">
        <div className="plugins-add-input">
          <input
            type="text"
            className="plugin-input plugin-input-wide"
            placeholder="Git 仓库 / 本地路径 / npm:包名 / pypi:包名"
            value={source}
            onChange={(e) => setSource(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleAdd() }}
          />
          <span className={`plugins-source-tag tag-${sourceInfo.kind}`}>{sourceInfo.label}</span>
        </div>
        <label className="plugins-enable-toggle">
          <input
            type="checkbox"
            checked={enableOnAdd}
            onChange={(e) => setEnableOnAdd(e.target.checked)}
          />
          安装后启用
        </label>
        <button
          className="plugin-btn plugin-btn-primary"
          disabled={!source.trim() || busy}
          onClick={handleAdd}
        >
          {busy ? '安装中…' : '添加插件'}
        </button>
      </div>

      {/* 搜索全网：发现技能 / DSH 插件 */}
      <div className="plugins-discover">
        <div className="plugins-discover-head">
          <h3>搜索全网</h3>
          <p className="plugins-sub">检索 GitHub 上的 SafeOPC 插件与 DSH Preset，一键安装并立即生效（无需重启）。</p>
        </div>
        <div className="plugins-discover-bar">
          <input
            type="text"
            className="plugin-input plugin-input-wide"
            placeholder="搜索技能 / 插件关键词，例如：web scraper, pdf, translator"
            value={discoverQuery}
            onChange={(e) => setDiscoverQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleDiscover() }}
          />
          <select
            className="plugin-select"
            value={discoverProvider}
            onChange={(e) => setDiscoverProvider(e.target.value as 'github' | 'dsh')}
          >
            <option value="github">SafeOPC 插件</option>
            <option value="dsh">DSH Preset</option>
          </select>
          <button
            className="plugin-btn plugin-btn-primary"
            disabled={!discoverQuery.trim() || discoverLoading}
            onClick={handleDiscover}
          >
            {discoverLoading ? '搜索中…' : '搜索'}
          </button>
        </div>

        {discoverError && (
          <div className="plugin-banner plugin-banner-error">{discoverError}</div>
        )}

        <div className="plugins-discover-grid">
          {discoverResults.length === 0 && !discoverLoading && !discoverError && (
            <div className="plugins-empty">输入关键词搜索可用的插件与预设。</div>
          )}
          {discoverResults.map((cand: any) => {
            const id = cand.id
            const installed = Boolean(cand.installed)
            const installing = Boolean(installingIds[id])
            return (
              <div key={id} className={`plugin-disc-card${installed ? ' is-installed' : ''}`}>
                <div className="plugin-disc-main">
                  <div className="plugin-disc-title">
                    <span className="plugin-disc-name">{cand.name ?? id}</span>
                    <span className={`plugin-badge plugin-badge-provider provider-${cand.provider}`}>{cand.provider}</span>
                    {cand.stars != null && <span className="plugin-disc-stars">★ {cand.stars}</span>}
                  </div>
                  {cand.description && <div className="plugin-disc-desc">{cand.description}</div>}
                  {cand.html_url && (
                    <a className="plugin-disc-link" href={cand.html_url} target="_blank" rel="noreferrer">{cand.html_url}</a>
                  )}
                </div>
                <div className="plugin-disc-actions">
                  {installed ? (
                    <span className="plugin-disc-installed">✓ 已安装 · 立即可用</span>
                  ) : (
                    <button
                      className="plugin-btn plugin-btn-primary"
                      disabled={installing || !cand.source}
                      onClick={() => handleDiscoverInstall(cand)}
                    >
                      {installing ? '安装中…' : '安装'}
                    </button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="plugins-list">
        {plugins.length === 0 && (
          <div className="plugins-empty">
            暂无插件。通过上面的输入框添加（或用命令行 <code>dsh plugin add &lt;source&gt;</code> / <code>opc plugin add &lt;source&gt;</code>）。
          </div>
        )}

        {plugins.map((p) => {
          const id = p.id
          const enabled = Boolean(p.enabled)
          const hasSchema =
            p.config_schema &&
            typeof p.config_schema === 'object' &&
            Object.keys(p.config_schema.properties ?? {}).length > 0
          return (
            <div key={id} className={`plugin-card${enabled ? ' is-enabled' : ' is-disabled'}`}>
              <div className="plugin-card-main">
                <div className="plugin-card-title">
                  <span className="plugin-card-name">{p.name ?? id}</span>
                  <span className="plugin-card-id">{id}</span>
                </div>
                <div className="plugin-card-meta">
                  {p.version && <span className="plugin-badge">v{p.version}</span>}
                  {p.kind && <span className="plugin-badge">{p.kind}</span>}
                  {p.source && <span className="plugin-badge plugin-badge-source" title={p.source}>{p.source}</span>}
                </div>
                {p.entry && <div className="plugin-card-entry">入口：<code>{p.entry}</code></div>}
                {p.description && <div className="plugin-card-desc">{p.description}</div>}
              </div>

              <div className="plugin-card-actions">
                <button
                  className={`plugin-toggle ${enabled ? 'on' : 'off'}`}
                  onClick={() => (enabled ? handleDisable(id) : handleEnable(id))}
                >
                  {enabled ? '已启用' : '已停用'}
                </button>
                {hasSchema && (
                  <button className="plugin-btn plugin-btn-ghost" onClick={() => onConfigGet(id)}>
                    配置
                  </button>
                )}
                {/* Export this agent preset as a .dshpreset (dsh-desktop parity). */}
                <button className="plugin-btn plugin-btn-ghost" onClick={() => onExport(id)} title="导出为 .dshpreset">
                  导出预设
                </button>
                <button className="plugin-btn plugin-btn-danger" onClick={() => handleRemove(id)}>
                  卸载
                </button>
              </div>
            </div>
          )
        })}
      </div>

      {/* Cordis-style layered override cascade (bundle/preset/profile/home/cli) */}
      <div className="plugins-cascade">
        <div className="plugins-discover-head">
          <h3>分层覆盖（Cordis cascade）</h3>
          <p className="plugins-sub">
            忠实移植 dsh-desktop 的 <code>cordis.patch.yml</code> 分层覆盖：配置像 CSS 级联一样从
            <code>bundle → preset → profile → home → cli</code> 逐层叠加，后层胜出。安装 DSH 预设会自动从其
            <code>preset/agent.cordis.yml</code> 组合出插件集（preset 层）；<code>home</code> 层是你可在线编辑的覆盖层。
          </p>
          <div className="plugins-cascade-actions">
            <button className="plugin-btn plugin-btn-ghost" onClick={() => onCascadeGet()}>
              {cascadeLoading ? '加载中…' : '刷新'}
            </button>
            <button className="plugin-btn plugin-btn-ghost" onClick={() => { onCascadeReset('home'); onCascadeGet() }}>
              重置 home 层
            </button>
            <button className="plugin-btn plugin-btn-primary" onClick={() => setCascadeOpen((v) => !v)}>
              {cascadeOpen ? '收起' : '展开'}
            </button>
          </div>
        </div>

        {cascadeError && <div className="plugin-banner plugin-banner-error">{cascadeError}</div>}

        {cascadeOpen && cascadeData && (
          <div className="cascade-body">
            {/* Layer stack */}
            <div className="cascade-layers">
              <div className="cascade-layers-title">层级（base → top）</div>
              {(cascadeData.layers ?? []).map((layer: any) => {
                const pls = (layer.tree?.plugins ?? []) as any[]
                const touched = pls.filter((p) => p && (p.enabled !== undefined || (p.config && Object.keys(p.config).length)))
                return (
                  <div key={layer.name} className="cascade-layer-row">
                    <span className={`cascade-layer-name layer-${layer.name}`}>{layer.name}</span>
                    <span className="cascade-layer-src" title={layer.source}>{layer.source}</span>
                    <span className="cascade-layer-count">{touched.length} 处覆盖</span>
                  </div>
                )
              })}
            </div>

            {/* Effective per-plugin resolution + trace */}
            <div className="cascade-effective">
              <div className="cascade-layers-title">生效结果（含级联轨迹）</div>
              <table className="cascade-table">
                <thead>
                  <tr>
                    <th>插件</th>
                    <th>生效启用</th>
                    <th>被覆盖</th>
                    <th>级联轨迹（base → top）</th>
                  </tr>
                </thead>
                <tbody>
                  {(cascadeData.plugins ?? []).map((eff: any) => (
                    <tr key={eff.id} className={eff.missing ? 'cascade-row-missing' : ''}>
                      <td>
                        <span className="plugin-card-name">{eff.name ?? eff.id}</span>
                        <span className="plugin-card-id">{eff.id}</span>
                        {eff.missing && <span className="plugin-badge plugin-badge-danger">未安装</span>}
                      </td>
                      <td>{eff.enabled ? '✓' : '—'}</td>
                      <td>{eff.overridden ? '是' : '否'}</td>
                      <td className="cascade-trace">
                        {(eff.trace ?? []).map((tr: any, i: number) => (
                          <span key={i} className={`cascade-trace-chip layer-${tr.layer}`}>
                            {tr.layer}
                            {tr.enabled !== undefined && (tr.enabled ? ':开' : ':关')}
                            {tr.config && Object.keys(tr.config).length > 0 && ':配置'}
                          </span>
                        ))}
                        {(eff.trace ?? []).length === 0 && <span className="cascade-trace-none">（无，使用基础值）</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Home-layer override editor */}
            <div className="cascade-editor">
              <div className="cascade-layers-title">编辑 home 覆盖层</div>
              <div className="cascade-editor-row">
                <select className="plugin-select" value={ovPlugin} onChange={(e) => setOvPlugin(e.target.value)}>
                  <option value="">选择已安装插件…</option>
                  {plugins.map((p: any) => (
                    <option key={p.id} value={p.id}>{p.name ?? p.id} ({p.id})</option>
                  ))}
                </select>
                <label className="plugins-enable-toggle">
                  <input type="checkbox" checked={ovEnabled} onChange={(e) => setOvEnabled(e.target.checked)} />
                  启用
                </label>
                <input
                  className="plugin-input plugin-input-wide"
                  placeholder='config JSON，如 {"timeout": 30}'
                  value={ovConfigText}
                  onChange={(e) => setOvConfigText(e.target.value)}
                />
                <button
                  className="plugin-btn plugin-btn-primary"
                  disabled={!ovPlugin}
                  onClick={() => {
                    let cfg: Record<string, unknown> = {}
                    if (ovConfigText.trim()) {
                      try { cfg = JSON.parse(ovConfigText) } catch (e: any) {
                        setOvError('config 不是合法 JSON: ' + (e?.message ?? ''))
                        return
                      }
                    }
                    setOvError(null)
                    onCascadePatch({ plugins: [{ id: ovPlugin, enabled: ovEnabled, config: cfg }] })
                    onCascadeGet()
                  }}
                >
                  应用覆盖
                </button>
              </div>
              {ovError && <div className="plugin-banner plugin-banner-error">{ovError}</div>}
              <div className="plugins-sub">覆盖写入 <code>{'<opc_home>/config/plugins_override.yaml'}</code>，立即同步到运行时（无需重启）。</div>
            </div>
          </div>
        )}
      </div>

      {configTarget && (
        <ConfigEditor
          target={configTarget}
          schema={configSchema}
          initialData={configData}
          error={configError}
          onSave={(cfg) => { onConfigSet(configTarget, cfg); refresh() }}
          onClose={onConfigClose}
        />
      )}

      {confirmPreset && (
        <PresetPreviewModal
          candidate={confirmPreset}
          previewData={pluginPreviewData}
          loading={pluginPreviewLoading}
          error={pluginPreviewError}
          onConfirm={() => {
            const cand = confirmPreset
            setConfirmPreset(null)
            doInstall(cand)
          }}
          onClose={() => setConfirmPreset(null)}
        />
      )}
    </div>
  )
}

export default PluginsPage
